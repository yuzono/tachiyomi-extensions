package eu.kanade.tachiyomi.extension.all.hentai3

import android.webkit.CookieManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getArtists
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getCodes
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getGroups
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getNumPages
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getTagDescription
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getTags
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getTime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

open class Hentai3(
    override val lang: String = "all",
    private val searchLang: String = "",
    private val flagLang: String = "",
) : ParsedHttpSource(),
    ConfigurableSource {

    override val name = "3Hentai"

    override val baseUrl = "https://3hentai.net"

    override val supportsLatest = true

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }

    val cookies
        get() = webViewCookieManager.getCookie(baseUrl)
            ?.split("; ")
            ?.filter {
                val name = it.substringBefore("=")
                name.length >= 40 ||
                    name in listOf(
                        "XSRF-TOKEN",
                        "hornysess2",
                        "show_modal_warn_adult",
                    )
            }
            ?: emptyList()

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .addNetworkInterceptor(::authorizationInterceptor)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    fun authorizationInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .removeHeader("Cookie")
            .addHeader("Cookie", cookies.joinToString("; "))
            .build()
        val response = chain.proceed(request)
        if (response.code == 302) {
            response.close()
            throw IOException("Log in via WebView to view favorites")
        }
        return response
    }

    private val preferences by getPreferencesLazy()

    private var displayFullTitle: Boolean = preferences.getBoolean("full_title", false)

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = "full_title"
            title = "Display full title"
            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = newValue as Boolean
                true
            }
        }.also(screen::addPreference)

        screen.addRandomUAPreference()
    }

    /* Popular */

    override fun popularMangaRequest(page: Int) = GET(
        when {
            searchLang.isBlank() -> "$baseUrl/search?q=pages%3A>0&sort=popular-7d&page=$page"
            page == 1 -> "$baseUrl/language/$searchLang?sort=popular-7d"
            else -> "$baseUrl/language/$searchLang/$page?sort=popular-7d"
        },
        headers,
    )

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("a > .title")!!.text().replace("\"", "").let {
            if (displayFullTitle) it.trim() else it.shortenTitle()
        }
        thumbnail_url = element.selectFirst(".cover img")!!.let { img: Element ->
            if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
        }
    }

    override fun popularMangaSelector() = "#main-content .listing-container .doujin"

    override fun popularMangaNextPageSelector() = "#main-content nav .pagination .page-item .page-link[rel=next]"

    override fun relatedMangaListSelector(): String = popularMangaSelector() + if (flagLang.isNotEmpty()) ":has(.flag-$flagLang)" else ""

    /* Latest */

    override fun latestUpdatesRequest(page: Int) = GET(if (searchLang.isBlank()) "$baseUrl/search?q=pages%3A>0&page=$page" else "$baseUrl/language/$searchLang/$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    /* Search */

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = coroutineScope {
        async {
            when {
                query.startsWith(PREFIX_ID_SEARCH) -> {
                    val id = query.removePrefix(PREFIX_ID_SEARCH)
                    client.newCall(searchMangaByIdRequest(id))
                        .await()
                        .use { response -> searchMangaByIdParse(response, id) }
                }
                query.toIntOrNull() != null -> {
                    client.newCall(searchMangaByIdRequest(query))
                        .await()
                        .use { response -> searchMangaByIdParse(response, query) }
                }
                else -> super.getSearchManga(page, query, filters)
            }
        }.await()
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/d/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/d/$id"
        return MangasPage(listOf(details), false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val queries = (
            listOfNotNull(
                query.replace("♀", "female").replace("♂", "male"),
                if (searchLang.isNotEmpty()) "language:$searchLang" else null,
            ) +
                combineQuery(filterList)
            )
            .joinToString(" ") { it.trim() }
            .trim()

        val favoriteFilter = filterList.firstInstanceOrNull<FavoriteFilter>()
        val offsetPage =
            filterList.firstInstanceOrNull<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        val searchURL = if (favoriteFilter?.state == true) {
            "$baseUrl/user/panel/favorites"
        } else {
            "$baseUrl/search"
        }

        val url = searchURL.toHttpUrl().newBuilder().apply {
            addQueryParameter("q", queries.ifEmpty { "pages:>0" })
            addQueryParameter("page", offsetPage.toString())
            filterList.firstInstanceOrNull<SortFilter>()?.let { f ->
                addQueryParameter("sort", f.toUriPart())
            }
        }

        return GET(url.build(), headers)
    }

    private fun combineQuery(filters: FilterList): List<String> {
        val advSearch = filters.filterIsInstance<AdvSearchEntryFilter>().flatMap { filter ->
            val splits = filter.state.split(",")
                .map(String::trim)
                .filter(String::isNotBlank)
            splits.map { rawTag ->
                val tag = rawTag.lowercase()
                AdvSearchEntry(
                    type = filter.type,
                    text = tag.removePrefix("-"),
                    exclude = tag.startsWith("-"),
                    specific = filter.specific,
                )
            }
        }

        return advSearch
            .filter { tag -> tag.text.isNotBlank() }
            .map { tag ->
                buildString {
                    if (tag.exclude) append("-")
                    append(tag.type, ":'")
                    append(tag.text)
                    if (tag.specific.isNotBlank()) {
                        append(" (${tag.specific})")
                    }
                    append("'")
                }
            }
    }

    data class AdvSearchEntry(val type: String, val text: String, val exclude: Boolean, val specific: String)

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.toString().contains("/login")) {
            val document = response.asJsoup()
            if (document.select("input[value=Login to my account]").isNotEmpty()) {
                throw Exception("Log in via WebView to view favorites")
            }
        }

        return super.searchMangaParse(response)
    }

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    /* Details */

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(document: Document): SManga {
        val fullTitle = document.select("#main-info > h1").text()
            .replace("\"", "").trim()
        val artists = getArtists(document)
        val authors = getGroups(document)

        return SManga.create().apply {
            title = if (displayFullTitle) {
                fullTitle
            } else {
                document.select("#main-info > h1 > span").text()
                    .replace("\"", "").trim()
            }
            thumbnail_url = document.select("#main-cover img").attr("data-src")
            status = SManga.COMPLETED
            artist = artists?.ifEmpty { authors }
            author = authors?.ifEmpty { artists }
            val code = getCodes(document)
            // Some people want these additional details in description
            description = "Full English and Japanese titles:\n"
                .plus("$fullTitle\n\n")
                .plus(code ?: "")
                .plus("Pages: ${getNumPages(document)}\n")
                .plus(getTagDescription(document))
            genre = getTags(document)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }
    }

    /* Chapters */

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                scanlator = getGroups(document)
                date_upload = getTime(document)
                setUrlWithoutDomain(response.request.url.encodedPath)
            },
        )
    }

    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()
    override fun chapterListSelector() = throw UnsupportedOperationException()

    /* Pages */

    override fun pageListParse(document: Document): List<Page> = document.select("#thumbnail-gallery .single-thumb a > img").mapIndexed { idx, img ->
        Page(idx, imageUrl = img.attr("abs:data-src").replace("t.", "."))
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()
    override fun getFilterList() = getFilters()

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
    }
}
