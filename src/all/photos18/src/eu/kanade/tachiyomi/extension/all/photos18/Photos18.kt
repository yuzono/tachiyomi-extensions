package eu.kanade.tachiyomi.extension.all.photos18

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLDecoder

class Photos18 :
    HttpSource(),
    ConfigurableSource {
    override val name = "Photos18"
    override val lang = "all"
    override val supportsLatest = true

    override val baseUrl = "https://www.photos18.com"

    private val baseUrlWithLang get() = if (useTrad) baseUrl else "$baseUrl/zh-hans"
    private fun String.stripLang() = removePrefix("/zh-hans")

    override val client = network.cloudflareClient.newBuilder().followRedirects(false).build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int) = GET("$baseUrlWithLang?sort=likes&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("#videos div.card").map { card ->
            val cardBody = card.select(".card-body")
            val link = cardBody.select("a")
            val category = cardBody.select("div label.badge").text()
            SManga.create().apply {
                // stripLang() so both lang (traditional or simplified) will have same entries catalog
                url = link.attr("href").stripLang()
                title = link.text()
                thumbnail_url = baseUrl + card.select("img").attr("src")
                genre = translate(category)
                description = category
                status = SManga.COMPLETED
            }
        }
        val isLastPage = document.selectFirst("nav .pagination .next").run {
            this == null || hasClass("disabled")
        }
        return MangasPage(mangas, !isLastPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrlWithLang/sort/created?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrlWithLang${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val category = document.select("nav li.breadcrumb-item:nth-child(2) a")

        // Update entry's details in case language is switched
        return SManga.create().apply {
            title = document.select("title").text().trim()
            thumbnail_url = document.select("div#content div.imgHolder")
                .select("img").attr("src")
            status = SManga.COMPLETED
            if (category.toString().contains("href=\"(/zh-hans)?/cat/".toRegex())) {
                genre = translate(category.text())
                description = category.text()
            }
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Gallery"
            chapter_number = 0f
        }
        return listOf(chapter)
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val images = document.select("div#content a img")
        return images.mapIndexed { index, image ->
            Page(index, imageUrl = image.attr("src"))
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList {
        launchIO { fetchCategories() }
        return FilterList(
            SortFilter(),
            if (categoryList.isEmpty()) {
                Filter.Header("Tap 'Reset' to load categories")
            } else {
                CategoryFilter(categoryList)
            },
            if (keywordList.isEmpty()) {
                Filter.Header("Tap 'Reset' to load keywords")
            } else {
                KeywordFilter(keywordList)
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

    class Category(val name: String, val value: String)

    private var categoryList: List<Category> = emptyList()

    private class CategoryFilter(categories: List<Category>) :
        QueryFilter(
            "Category",
            categories.map { it.name }.toTypedArray(),
            "category_id",
            categories.map { it.value }.toTypedArray(),
        )

    /**
     * Inner variable to control how much tries the categories request was called.
     */
    private var fetchCategoriesAttempts: Int = 0

    /**
     * Fetch the categories from the source to be used in the filters.
     */
    private fun fetchCategories() {
        if (fetchCategoriesAttempts < 3 && categoryList.isEmpty()) {
            try {
                client.newCall(categoriesRequest()).execute()
                    .let {
                        val document = it.asJsoup()
                        categoryList = parseCategories(document)
                        keywordList = parseKeywords(document)
                    }
            } catch (_: Exception) {
            } finally {
                fetchCategoriesAttempts++
            }
        }
    }

    /**
     * The request to the search page (or another one) that have the categories list.
     */
    private fun categoriesRequest(): Request = GET("$baseUrlWithLang/node/keywords", headers)

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

    class Keyword(val name: String, val value: String)

    private var keywordList: List<Keyword> = emptyList()

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

    private val scope = CoroutineScope(Dispatchers.IO)

    private fun launchIO(block: () -> Unit) = scope.launch { block() }

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
