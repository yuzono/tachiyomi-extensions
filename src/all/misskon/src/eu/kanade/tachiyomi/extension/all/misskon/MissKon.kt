package eu.kanade.tachiyomi.extension.all.misskon

import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
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
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MissKon :
    KeiSource(),
    ConfigurableSource {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(10, 1.seconds) { it.host == baseUrlHost }

    private val preferences by getPreferencesLazy()

    private fun mangaFromElement(element: Element): SManga {
        val titleEL = element.selectFirst(".post-box-title")!!
        return SManga.create().apply {
            title = titleEL.text()
            thumbnail_url = element.selectFirst(".post-thumbnail img")?.imgAttr()
            setUrlWithoutDomain(titleEL.selectFirst("a")!!.absUrl("href"))
            val meta = element.selectFirst("p.post-meta")
            description = "View: ${meta?.select("span.post-views")?.text() ?: "---"}"
            genre = meta?.parseTags()
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    // region popular
    override suspend fun getPopularManga(page: Int): MangasPage {
        val topDays = (preferences.topDays?.toInt() ?: 0) + 1
        val topDaysFilter = TopDaysFilter(
            "",
            arrayOf(
                getTopDaysList()[0],
                getTopDaysList()[topDays],
            ),
        ).apply { state = 1 }
        return getSearchMangaList(page, "", FilterList(topDaysFilter))
    }
    // endregion

    // region latest
    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListPage(client.get("$baseUrl/page/$page"))
    // endregion

    // region Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val tagFilter = filters.firstInstanceOrNull<TagsFilter>()
        val topDaysFilter = filters.firstInstanceOrNull<TopDaysFilter>()
        val url = baseUrl.toHttpUrl().newBuilder()
        when {
            query.isNotBlank() -> {
                if (listOf("photo", "photos", "video", "videos").contains(query.trim())) {
                    return parseMangaListPage(client.get("$baseUrl/search"))
                }
                if (page > 1) {
                    url.addPathSegment("page")
                    url.addPathSegment(page.toString())
                }
                url.addQueryParameter("s", query.trim())
            }

            topDaysFilter != null && topDaysFilter.state > 0 -> {
                url.addPathSegment(topDaysFilter.toUriPart())
            }

            tagFilter != null && tagFilter.state > 0 -> {
                url.addPathSegment("tag")
                url.addPathSegment(tagFilter.toUriPart())

                url.addPathSegment("page")
                url.addPathSegment(page.toString())
            }

            else -> return getLatestUpdates(page)
        }
        return parseMangaListPage(client.get(url.build()))
    }

    private fun parseMangaListPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("article.item-list").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst(".current + a.page") != null
        return MangasPage(mangas, hasNextPage)
    }
    // endregion

    // region Details
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = parseMangaDetails(document)
        val updatedChapters = parseChapterList(document, manga.url)
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.select(".post-title span").text()
        val view = document.select("p.post-meta span.post-views").text()
        val info = document.select("div.info div.box-inner-block")

        val password = info.select("input").attr("value")
        val downloadAvailable = document.select("div.post-inner > div.entry > p > a[href]:has(i.fa-download)")
        val downloadLinks = downloadAvailable.joinToString("\n") { element ->
            val serviceText = element.text()
            val link = element.attr("href")
            "[$serviceText]($link)"
        }

        description = "${
            info.html()
                .replace("<input.*?>".toRegex(), password)
                .replace("<.+?>".toRegex(), "")
        }\n\n" +
            "$view\n\n" +
            downloadLinks
        genre = document.parseTags()
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> {
        val dateUploadStr = document.selectFirst(".entry img")?.imgAttr()
            ?.let { url ->
                FULL_DATE_REGEX.find(url)?.groupValues?.get(1)
                    ?: YEAR_MONTH_REGEX.find(url)?.groupValues?.get(1)?.let { "$it/01" }
            }

        val dateUpload = FULL_DATE_FORMAT.tryParse(dateUploadStr)

        return if (preferences.splitPages) {
            val maxPage = document.select("div.page-link:first-of-type a.post-page-numbers").last()?.text()?.toIntOrNull() ?: 1
            (maxPage downTo 1).map { page ->
                SChapter.create().apply {
                    setUrlWithoutDomain("$mangaUrl/$page")
                    name = "Page $page"
                    chapter_number = page.toFloat()
                    date_upload = dateUpload
                }
            }
        } else {
            listOf(
                SChapter.create().apply {
                    chapter_number = 0F
                    setUrlWithoutDomain(mangaUrl)
                    name = "Gallery"
                    date_upload = dateUpload
                },
            )
        }
    }

    /* Related titles */
    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return document.select(".content > .yarpp-related a.yarpp-thumbnail").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }
    }
    // endregion

    // region Pages
    private val imageListSelector = "div.post-inner > div.entry > p > img"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return if (preferences.splitPages) {
            pageListParse(document)
        } else {
            pageListMerge(document)
        }
    }

    private fun pageListParse(document: Document): List<Page> = document
        .select(imageListSelector)
        .mapIndexed { i, img -> Page(i, imageUrl = img.imgAttr()) }

    private suspend fun pageListMerge(document: Document): List<Page> {
        val pages = document
            .select("div.page-link:first-of-type a")
            .mapNotNull { it.absUrl("href") }

        val chapterPage = parseImageList(document).toMutableList()

        coroutineScope {
            chapterPage += pages.map { url ->
                async(Dispatchers.IO) {
                    parseImageList(client.get(url).asJsoup())
                }
            }.awaitAll().flatten()
        }

        return chapterPage.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    private fun parseImageList(document: Document): List<String> = document.select(imageListSelector)
        .map { it.imgAttr() }
    // endregion

    /* Filters */
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("NOTE: Unable to further search in the category!"),
        Filter.Separator(),
        TopDaysFilter("Top days", getTopDaysList()),
        TagsFilter("Browse Tag", tagList.toList()),
    )

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/sets/").asJsoup()
        .select(".entry .tag-counterz a[href*=/tag/]")
        .map {
            Pair(
                it.select("strong").text(),
                it.attr("href")
                    .removeSuffix("/")
                    .substringAfterLast('/')
                    .let(Uri::decode),
            )
        }
        .let { newTags ->
            updateTags(newTags.toSet(), true)
            // we save our own pref, put empty here just so we can leverage the 3 days refresh limit
            emptyList<String>()
        }
        .toJsonElement()

    @Volatile
    private var tagList: Set<Pair<String, String>> = DefaultTagList + loadTagListFromPreferences()

    @Synchronized
    private fun updateTags(newTags: Set<Pair<String, String>>, reset: Boolean = false) {
        val updated = if (reset) {
            DefaultTagList + newTags + tagList
        } else {
            tagList + newTags
        }
        if (tagList != updated) {
            val additionalTags = updated - DefaultTagList
            preferences.edit().putString(
                TAG_LIST_PREF,
                additionalTags.joinToString("%") { "${it.first}|${it.second}" },
            ).apply()
            tagList = updated
        }
    }

    private fun loadTagListFromPreferences(): Set<Pair<String, String>> = preferences.getString(TAG_LIST_PREF, "")
        ?.let {
            it.split('%').mapNotNull { tag ->
                tag.split('|')
                    .let { splits ->
                        if (splits.size == 2) Pair(splits[0], splits[1]) else null
                    }
            }
        }?.toSet()
        ?: emptySet()

    private fun Element.parseTags(selector: String = ".post-tag a, .post-cats a"): String = select(selector)
        .also { elements ->
            val newTags = elements.map { tag ->
                val uri = tag.attr("href")
                    .removeSuffix("/")
                    .substringAfterLast('/')
                    .let(Uri::decode)
                tag.text() to uri
            }
            updateTags(newTags.toSet())
        }
        .joinToString { it.text() }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-original") -> absUrl("data-original")
        hasAttr("data-src") -> absUrl("data-src")
        hasAttr("data-bg") -> absUrl("data-bg")
        hasAttr("data-srcset") -> absUrl("data-srcset")
        else -> absUrl("src")
    }

    private val SharedPreferences.topDays
        get() = getString(PREF_TOP_DAYS, DEFAULT_TOP_DAYS)

    private val SharedPreferences.splitPages
        get() = getBoolean(PREF_SPLIT_PAGES, DEFAULT_SPLIT_PAGES)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TOP_DAYS
            title = "Default Top-Days used for Popular"
            summary = "%s"
            entries = topDaysList().map { it.name }.toTypedArray()
            entryValues = topDaysList().indices.map { it.toString() }.toTypedArray()
            setDefaultValue(DEFAULT_TOP_DAYS)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SPLIT_PAGES
            title = "Split into multiple pages"
            summaryOff = "Single gallery"
            summaryOn = "Multiple pages"
            setDefaultValue(DEFAULT_SPLIT_PAGES)
        }.also(screen::addPreference)
    }

    companion object {
        private val FULL_DATE_REGEX = Regex("""/(\d{4}/\d{2}/\d{2})/""")
        private val YEAR_MONTH_REGEX = Regex("""/(\d{4}/\d{2})/""")
        private val FULL_DATE_FORMAT = SimpleDateFormat("yyyy/MM/dd", Locale.US)

        private const val PREF_TOP_DAYS = "pref_top_days"
        private const val DEFAULT_TOP_DAYS = "0" // 3-days

        private const val PREF_SPLIT_PAGES = "pref_split_pages"
        private const val DEFAULT_SPLIT_PAGES = false

        private const val TAG_LIST_PREF = "TAG_LIST"
    }
}
