package eu.kanade.tachiyomi.extension.all.misskon

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MissKon :
    ParsedHttpSource(),
    ConfigurableSource {
    override val name = "MissKon (MrCong)"
    override val lang = "all"
    override val supportsLatest = true
    override val versionId = 2

    override val baseUrl = "https://misskon.com"

    override val client = network.cloudflareClient.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 10, 1, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

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

    override fun latestUpdatesSelector() = "div#main-content div.post-listing article.item-list"

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        val post = element.select("h2.post-box-title a").first()!!
        setUrlWithoutDomain(post.absUrl("href"))
        title = post.text()
        thumbnail_url = element.selectFirst("div.post-thumbnail img")?.imgAttr()
        val meta = element.selectFirst("p.post-meta")
        description = "View: ${meta?.select("span.post-views")?.text() ?: "---"}"
        genre = meta?.parseTags()
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    override fun popularMangaRequest(page: Int): Request {
        val topDays = (preferences.topDays?.toInt() ?: 0) + 1
        val topDaysFilter = TopDaysFilter(
            "",
            arrayOf(
                getTopDaysList()[0],
                getTopDaysList()[topDays],
            ),
        ).apply { state = 1 }
        return searchMangaRequest(page, "", FilterList(topDaysFilter))
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tagFilter = filters.filterIsInstance<TagsFilter>().firstOrNull()
        val topDaysFilter = filters.filterIsInstance<TopDaysFilter>().firstOrNull()
        val url = baseUrl.toHttpUrl().newBuilder()
        when {
            query.isNotBlank() -> {
                if (listOf("photo", "photos", "video", "videos").contains(query.trim())) {
                    return GET("$baseUrl/search")
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
            else -> return latestUpdatesRequest(page)
        }
        return GET(url.build(), headers)
    }

    override fun latestUpdatesNextPageSelector() = "div#main-content div.pagination span.current + a.page"

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun popularMangaSelector() = latestUpdatesSelector()

    override fun searchMangaSelector() = latestUpdatesSelector()

    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    /* Details */
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
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

        description = "View: $view\n" +
            "${info.html()
                .replace("<input.*?>".toRegex(), password)
                .replace("<.+?>".toRegex(), "")}\n" +
            downloadLinks
        genre = document.parseTags()
        status = SManga.COMPLETED
    }

    override fun chapterListSelector() = throw UnsupportedOperationException()
    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val doc = client.newCall(chapterListRequest(manga)).await().asJsoup()
        val dateUploadStr = doc.selectFirst(".entry img")?.imgAttr()
            ?.let { url ->
                FULL_DATE_REGEX.find(url)?.groupValues?.get(1)
                    ?: YEAR_MONTH_REGEX.find(url)?.groupValues?.get(1)?.let { "$it/01" }
            }

        val dateUpload = FULL_DATE_FORMAT.tryParse(dateUploadStr)
        if (preferences.splitPages) {
            val maxPage = doc.select("div.page-link:first-of-type a.post-page-numbers").last()?.text()?.toIntOrNull() ?: 1
            return (maxPage downTo 1).map { page ->
                SChapter.create().apply {
                    setUrlWithoutDomain("${manga.url}/$page")
                    name = "Page $page"
                    chapter_number = page.toFloat()
                    date_upload = dateUpload
                }
            }
        } else {
            return listOf(
                SChapter.create().apply {
                    chapter_number = 0F
                    setUrlWithoutDomain(manga.url)
                    name = "Gallery"
                    date_upload = dateUpload
                },
            )
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.newCall(pageListRequest(chapter)).await().asJsoup()
        return if (preferences.splitPages) {
            pageListParse(document)
        } else {
            pageListMerge(document)
        }
    }

    private val imageListSelector = "div.post-inner > div.entry > p > img"
    private fun parseImageList(document: Document): List<String> = document.select(imageListSelector)
        .map { it.imgAttr() }

    override fun pageListParse(document: Document): List<Page> = document.select(imageListSelector)
        .mapIndexed { i, img -> Page(i, imageUrl = img.imgAttr()) }

    private suspend fun pageListMerge(document: Document): List<Page> {
        val pages = document
            .select("div.page-link:first-of-type a")
            .mapNotNull {
                it.absUrl("href")
            }

        val chapterPage = parseImageList(document).toMutableList()

        coroutineScope {
            chapterPage += pages.map { url ->
                async(Dispatchers.IO) {
                    val request = GET(url, headers)
                    parseImageList(client.newCall(request).await().asJsoup())
                }
            }.awaitAll().flatten()
        }

        return chapterPage.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList {
        getTags()
        return FilterList(
            TopDaysFilter("Top days", getTopDaysList()),
            if (tagList.isEmpty()) {
                Filter.Header("Hit refresh to load Tags")
            } else {
                TagsFilter("Browse Tag", tagList.toList())
            },
        )
    }

    /* Filters */
    private val scope = CoroutineScope(Dispatchers.IO)
    private fun launchIO(block: () -> Unit) = scope.launch { block() }
    private var tagsFetched = false
    private var tagsFetchAttempt = 0

    private fun getTags() {
        launchIO {
            if (!tagsFetched && tagsFetchAttempt < 3) {
                try {
                    client.newCall(GET("$baseUrl/sets/", headers)).execute()
                        .asJsoup()
                        .select(".entry .tag-counterz a[href*=/tag/]")
                        .mapNotNull {
                            Pair(
                                it.select("strong").text(),
                                it.attr("href")
                                    .removeSuffix("/")
                                    .substringAfterLast('/'),
                            )
                        }
                        .onEach {
                            tagList = tagList.plus(it)
                        }
                        .also {
                            tagsFetched = true
                        }
                } catch (_: Exception) {
                } finally {
                    tagsFetchAttempt++
                }
            }
        }
    }

    private var tagList: Set<Pair<String, String>> = loadTagListFromPreferences()
        set(value) {
            preferences.edit().putString(
                TAG_LIST_PREF,
                value.joinToString("%") { "${it.first}|${it.second}" },
            ).apply()
            field = value
        }

    private fun loadTagListFromPreferences(): Set<Pair<String, String>> = preferences.getString(TAG_LIST_PREF, "")
        ?.let {
            it.split('%').mapNotNull { tag ->
                tag.split('|')
                    .let { splits ->
                        if (splits.size == 2) Pair(splits[0], splits[1]) else null
                    }
            }
        }
        ?.toSet()
        // Load default tags
        .let { if (it.isNullOrEmpty()) TagList else it }

    private fun Element.parseTags(selector: String = ".post-tag a, .post-cats a"): String = select(selector)
        .onEach {
            val uri = it.attr("href")
                .removeSuffix("/")
                .substringAfterLast('/')
            tagList = tagList.plus(it.text() to uri)
        }
        .joinToString { it.text() }

    /* Related titles */
    override fun relatedMangaListParse(response: Response): List<SManga> {
        val document = response.asJsoup()
        return document.select(".content > .yarpp-related a.yarpp-thumbnail").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-original") -> absUrl("data-original")
        hasAttr("data-src") -> absUrl("data-src")
        hasAttr("data-bg") -> absUrl("data-bg")
        hasAttr("data-srcset") -> absUrl("data-srcset")
        hasAttr("data-srcset") -> absUrl("data-srcset")
        else -> absUrl("src")
    }

    companion object {
        private const val PREF_TOP_DAYS = "pref_top_days"
        private const val DEFAULT_TOP_DAYS = "1" // 7-days

        private const val PREF_SPLIT_PAGES = "pref_split_pages"
        private const val DEFAULT_SPLIT_PAGES = false

        private val FULL_DATE_REGEX = Regex("""/(\d{4}/\d{2}/\d{2})/""")
        private val YEAR_MONTH_REGEX = Regex("""/(\d{4}/\d{2})/""")

        private val FULL_DATE_FORMAT = SimpleDateFormat("yyyy/MM/dd", Locale.US)

        private const val TAG_LIST_PREF = "TAG_LIST"
    }
}
