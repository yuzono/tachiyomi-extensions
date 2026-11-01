package eu.kanade.tachiyomi.extension.all.photos18

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLDecoder

@Source
abstract class Photos18 :
    KeiSource(),
    ConfigurableSource {

    private val baseUrlWithLang get() = if (useTrad) baseUrl else "$baseUrl/zh-hans"
    private fun String.stripLang() = replace("/zh-hans/", "/")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        followRedirects(false)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(client.get("$baseUrlWithLang?sort=likes&page=$page", Fetch.DOCUMENT))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(client.get("$baseUrlWithLang/sort/created?page=$page", Fetch.DOCUMENT))

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("#videos div.card").map { card ->
            val cardBody = card.select(".card-body")
            val link = cardBody.select("a")
            val category = cardBody.select("div label.badge").text()
            SManga.create().apply {
                // stripLang() so both lang (traditional or simplified) will have same entries catalog
                setUrlWithoutDomain(link.attr("href").stripLang())
                title = link.text()
                thumbnail_url = baseUrl + card.select("img").attr("src")
                genre = translate(category)
                description = category
                status = SManga.COMPLETED
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
                // initialized = true
            }
        }
        val isLastPage = document.selectFirst("nav .pagination .next").run {
            this == null || hasClass("disabled")
        }
        return MangasPage(mangas, !isLastPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrlWithLang.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            url.addQueryParameter("q", query.trim())
        }
        url.addQueryParameter("page", page.toString())

        filters.forEach {
            when (it) {
                is KeywordFilter -> if (query.isBlank()) it.addQueryTo(url)
                is QueryFilter -> it.addQueryTo(url)
                else -> {}
            }
        }

        return parseMangaList(client.get(url.build(), Fetch.DOCUMENT))
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val chapter = SChapter.create().apply {
            setUrlWithoutDomain(manga.url)
            name = "Gallery"
            chapter_number = 0f
        }
        return SMangaUpdate(
            manga = if (fetchDetails) parseMangaDetails(client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()) else manga,
            chapters = listOf(chapter),
        )
    }

    override fun getMangaUrl(manga: SManga) = baseUrlWithLang + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) return null
        val segments = url.pathSegments.dropWhile { it == "zh-hans" }
        val id = segments.getOrNull(1)?.takeIf { segments[0] == "v" && it.isNotEmpty() } ?: return null
        val mangaUrl = "/v/$id"
        return parseMangaDetails(client.get(baseUrlWithLang + mangaUrl, Fetch.DOCUMENT).asJsoup()).apply {
            this.url = mangaUrl
            initialized = true
        }
    }

    private fun parseMangaDetails(document: Document): SManga {
        val category = document.select("nav li.breadcrumb-item:nth-child(2) a")

        // Update entry's details in case language is switched
        return SManga.create().apply {
            title = document.select("title").text().trim()
            thumbnail_url = document.select("div#content div.imgHolder")
                .select("img").attr("src")
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            if (category.toString().contains("href=\"(/zh-hans)?/cat/".toRegex())) {
                genre = translate(category.text())
                description = category.text()
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        val images = document.select("div#content a img")
        return images.mapIndexed { index, image ->
            Page(index, imageUrl = image.attr("src"))
        }
    }

    override val supportsRelatedMangas = true
    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val response = client.get(getMangaUrl(manga), Fetch.DOCUMENT)
        return parseMangaList(response).mangas
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrlWithLang/node/keywords", Fetch.DOCUMENT).asJsoup()
        return FilterData(
            categories = parseCategories(document),
            keywords = parseKeywords(document),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>()

        return FilterList(
            SortFilter(),
            if (filterData?.categories.isNullOrEmpty()) {
                Filter.Header("Tap 'Reset' to load categories")
            } else {
                CategoryFilter(filterData.categories)
            },
            if (filterData?.keywords.isNullOrEmpty()) {
                Filter.Header("Tap 'Reset' to load keywords")
            } else {
                KeywordFilter(filterData.keywords)
            },
        )
    }

    private open class QueryFilter(
        name: String,
        values: Array<String>,
        private val queryName: String,
        private val queryValues: Array<String>,
        state: Int = 0,
    ) : Filter.Select<String>(name, values, state) {
        fun addQueryTo(builder: HttpUrl.Builder) = builder.addQueryParameter(queryName, queryValues[state])
    }

    private class SortFilter :
        QueryFilter(
            "Sort by",
            arrayOf("Latest", "Popular", "Trend", "Recommended", "Best"),
            "sort",
            arrayOf("created", "hits", "views", "score", "likes"),
            state = 0,
        )

    @Serializable
    class Category(val name: String, val value: String)

    private class CategoryFilter(categories: List<Category>) :
        QueryFilter(
            "Category",
            categories.map { it.name }.toTypedArray(),
            "category_id",
            categories.map { it.value }.toTypedArray(),
        )

    /**
     * Get the categories from the search page document.
     *
     * @param document The search page document
     */
    private fun parseCategories(document: Document): List<Category> {
        val items = document.select("#w2 a")
        return buildList(items.size + 1) {
            add(Category("All", ""))
            items.mapTo(this) {
                val value = it.text().substringBefore(" (").trim()
                val queryValue = it.attr("href").substringAfterLast('/')
                Category(translate(value), queryValue)
            }
        }
    }

    @Serializable
    class Keyword(val name: String, val value: String)

    private class KeywordFilter(keywords: List<Keyword>) :
        QueryFilter(
            "Keyword",
            keywords.map { it.name }.toTypedArray(),
            "q",
            keywords.map { it.value }.toTypedArray(),
        )

    /**
     * Get the keywords from the search page document.
     *
     * @param document The search page document
     */
    private fun parseKeywords(document: Document): List<Keyword> {
        val items = document.select("div.content form#keywordForm ~ a.tag")
        return buildList(items.size + 1) {
            add(Keyword("None", ""))
            items.mapTo(this) {
                val value = it.text()
                val queryValue = URLDecoder.decode(
                    it.attr("href").substringAfterLast('/'),
                    "UTF-8",
                )
                Keyword(translate(value), queryValue)
            }
        }
    }

    @Serializable
    class FilterData(val categories: List<Category>, val keywords: List<Keyword>)

    private val preferences by getPreferencesLazy()

    private val useTrad get() = preferences.getBoolean("ZH_HANT", false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = "ZH_HANT"
            title = "Use Traditional Chinese"
            setDefaultValue(false)
        }.let(screen::addPreference)
    }
}
