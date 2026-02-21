package eu.kanade.tachiyomi.extension.all.buondua

import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.randomua.UserAgentType
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class BuonDua :
    ParsedHttpSource(),
    ConfigurableSource {
    override val baseUrl = "https://buondua.com"
    override val lang = "all"
    override val name = "Buon Dua"
    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 10, 1, TimeUnit.SECONDS)
        .setRandomUserAgent(UserAgentType.MOBILE)
        .build()

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    // Latest
    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = element.select("img").attr("abs:src")
        manga.title = element.select(".item-content .item-link").text()
        manga.setUrlWithoutDomain(element.select(".item-content .item-link").attr("abs:href"))
        return manga
    }

    override fun latestUpdatesNextPageSelector() = ".pagination-next:not([disabled])"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?start=${20 * (page - 1)}")

    override fun latestUpdatesSelector() = ".blog > div"

    // Popular
    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/hot?start=${20 * (page - 1)}")

    override fun popularMangaSelector() = latestUpdatesSelector()

    // Search
    override fun searchMangaFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tagFilter = filters.filterIsInstance<TagFilter>().firstOrNull() ?: TagFilter()
        return when {
            query.isNotEmpty() -> GET("$baseUrl/?search=$query&start=${20 * (page - 1)}")
            tagFilter.state.isNotEmpty() -> GET("$baseUrl/tag/${tagFilter.state}&start=${20 * (page - 1)}")
            else -> popularMangaRequest(page)
        }
    }

    override fun searchMangaSelector() = latestUpdatesSelector()

    // Details
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.select(".article-header").text()
            .replace(titlePageRegex, "").trim()

        val articleInfo = document.select(".article-info > strong").text()
            .replace("Buondua", "").trim()

        val password = document.select("code").text()
        val downloadAvailable = document.select(".article-links a[href]")
        val downloadLinks = downloadAvailable.joinToString("\n") { element ->
            val serviceText = element.text()
            val link = element.attr("href")
            "[$serviceText]($link)"
        }

        manga.description = StringBuilder().apply {
            if (articleInfo.isNotBlank()) {
                append(articleInfo).append("\n")
            }
            if (downloadLinks.isNotBlank()) {
                append("\n").append(downloadLinks).append("\n")
            }
            if (password.isNotBlank()) {
                append("\n").append(password)
            }
        }.toString()

        val genres = document.select(".article-tags").first()?.let {
            it.select(".tags > .tag").map { tag ->
                tag.text().substringAfter("#")
            }
        }
        manga.genre = genres?.joinToString()?.takeIf { it.isNotBlank() }
        return manga
    }
    private val titlePageRegex = """ - \( Page \d+ / \d+ \)""".toRegex()

    override fun chapterListSelector() = throw UnsupportedOperationException()
    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val doc = client.newCall(chapterListRequest(manga)).await().asJsoup()
        val dateUploadStr = doc.selectFirst(".article-info > small")?.text()

        val dateUpload = DATE_FORMAT.tryParse(dateUploadStr)
        if (preferences.splitPages) {
            val maxPage = doc.getLastPageNum
            return (maxPage downTo 1).map { page ->
                SChapter.create().apply {
                    setUrlWithoutDomain(
                        "${baseUrl}${manga.url}".toHttpUrl().newBuilder()
                            .setQueryParameter("page", page.toString())
                            .build()
                            .toString(),
                    )
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

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.newCall(pageListRequest(chapter)).await().asJsoup()
        return if (preferences.splitPages) {
            pageListParse(document)
        } else {
            pageListMerge(document)
        }
    }

    private val pageListSelector = ".article-fulltext img"

    override fun pageListParse(document: Document): List<Page> = document.select(pageListSelector)
        .mapIndexed { i, imgEl -> Page(i, imageUrl = imgEl.absUrl("src")) }

    private suspend fun pageListMerge(document: Document): List<Page> {
        val basePageUrl = document.location()
        val maxPage = document.getLastPageNum

        return coroutineScope {
            (1..maxPage).map { page ->
                async(Dispatchers.IO) {
                    val doc = when (page) {
                        1 -> document
                        else -> {
                            val pageUrl = basePageUrl.toHttpUrl().newBuilder()
                                .setQueryParameter("page", page.toString())
                                .build()
                                .toString()
                            client.newCall(GET(pageUrl)).await().asJsoup()
                        }
                    }
                    doc.select(pageListSelector).map { imgEl ->
                        imgEl.absUrl("src")
                    }
                }
            }.awaitAll().flatten()
        }.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    private val Document.getLastPageNum: Int
        get() = select("nav.pagination:first-of-type a.pagination-next").last()
            ?.let {
                runCatching {
                    it.absUrl("href")
                        .toHttpUrl()
                        .queryParameter("page")?.toInt()
                }.getOrNull()
            } ?: 1

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // Filters
    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("NOTE: Ignored if using text search!"),
        Filter.Separator(),
        TagFilter(),
    )

    class TagFilter : Filter.Text("Tag ID")

    // Settings
    private val SharedPreferences.splitPages
        get() = getBoolean(PREF_SPLIT_PAGES, DEFAULT_SPLIT_PAGES)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SPLIT_PAGES
            title = "Split into multiple pages"
            summaryOff = "Single gallery"
            summaryOn = "Multiple pages"
            setDefaultValue(DEFAULT_SPLIT_PAGES)
        }.also(screen::addPreference)
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("HH:mm dd-MM-yyyy", Locale.US)

        private const val PREF_SPLIT_PAGES = "pref_split_pages"
        private const val DEFAULT_SPLIT_PAGES = true
    }
}
