package eu.kanade.tachiyomi.extension.all.hentai3

import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException

@Source
abstract class Hentai3 :
    HttpSource(),
    ConfigurableSource {

    private val searchLang: String
        get() = when (lang) {
            "all" -> ""
            "en" -> "english"
            "ja" -> "japanese"
            "ko" -> "korean"
            "zh" -> "chinese"
            "mo" -> "mongolian"
            "es" -> "spanish"
            "pt" -> "portuguese"
            "id" -> "indonesian"
            "jv" -> "javanese"
            "tl" -> "tagalog"
            "vi" -> "vietnamese"
            "th" -> "thai"
            "my" -> "burmese"
            "tr" -> "turkish"
            "ru" -> "russian"
            "uk" -> "ukrainian"
            "pl" -> "polish"
            "fi" -> "finnish"
            "de" -> "german"
            "it" -> "italian"
            "fr" -> "french"
            "nl" -> "dutch"
            "cs" -> "czech"
            "hu" -> "hungarian"
            "bg" -> "bulgarian"
            "is" -> "icelandic"
            "la" -> "latin"
            "ar" -> "arabic"
            "ceb" -> "cebuano"
            else -> ""
        }

    private val flagLang: String
        get() = when (lang) {
            "all" -> ""
            "en" -> "en"
            "ja" -> "jpn"
            "ko" -> "kor"
            "zh" -> "zho"
            "mo" -> "mon"
            "es" -> "spa"
            "pt" -> "por"
            "id" -> "ind"
            "jv" -> "jav"
            "vi" -> "vie"
            "th" -> "tha"
            "tr" -> "tur"
            "ru" -> "rus"
            "uk" -> "ukr"
            "fi" -> "fin"
            "de" -> "deu"
            "it" -> "ita"
            "fr" -> "fra"
            "nl" -> "nld"
            "cs" -> "ces"
            "hu" -> "hun"
            "is" -> "isl"
            "la" -> "lat"
            "ar" -> "ara"
            "ceb" -> "ceb"
            else -> ""
        }

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
        network.client.newBuilder()
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
        if (response.code == 302 && response.header("Location")?.contains("/login") == true) {
            response.close()
            throw IOException("Log in via WebView to view favorites")
        }
        return response
    }

    private val prefs: SharedPreferences by lazy { getPreferences() }

    private var displayFullTitle: Boolean = prefs.getBoolean("full_title", false)

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

    // Popular
    override fun popularMangaRequest(page: Int): Request = GET(
        when {
            searchLang.isBlank() -> "$baseUrl/search?q=pages%3A>0&sort=popular-7d&page=$page"
            page == 1 -> "$baseUrl/language/$searchLang?sort=popular-7d"
            else -> "$baseUrl/language/$searchLang/$page?sort=popular-7d"
        },
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()

        val mangas = doc.select(popularMangaSelector()).map(::popularMangaFromElement)
        val hasNextPage = doc.selectFirst(popularMangaNextPageSelector()) != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun popularMangaSelector() = "a[href*=/d/]"
    private fun popularMangaNextPageSelector() = "a[rel=next]"

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("div.title")!!.ownText().replace("\"", "").let {
            if (displayFullTitle) it.trim() else it.shortenTitle()
        }
        setUrlWithoutDomain(element.absUrl("href"))
        thumbnail_url = element.selectFirst(".cover img")!!.let { img ->
            if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.absUrl("src")
        }
    }

    private fun relatedMangaListSelector(): String = popularMangaSelector() + if (flagLang.isNotEmpty()) ":has(.flag-$flagLang)" else ""

    override fun relatedMangaListParse(response: Response): List<SManga> = response.asJsoup()
        .select(relatedMangaListSelector()).map(::popularMangaFromElement)

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/${if (searchLang.isNotEmpty()) "language/$searchLang/$page" else "search?q=pages%3A>0&page=$page"}", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.size < 2) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments[1]
            return fetchSearchManga(page, "$PREFIX_ID_SEARCH$id", filters)
        }

        return when {
            query.startsWith(PREFIX_ID_SEARCH) -> {
                val id = query.removePrefix(PREFIX_ID_SEARCH)
                client.newCall(searchMangaByIdRequest(id))
                    .asObservableSuccess()
                    .map { response -> searchMangaByIdParse(response, id) }
            }
            query.toIntOrNull() != null -> {
                client.newCall(searchMangaByIdRequest(query))
                    .asObservableSuccess()
                    .map { response -> searchMangaByIdParse(response, query) }
            }
            else -> super.fetchSearchManga(page, query, filters)
        }
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
            filterList.firstInstanceOrNull<SelectFilter>()?.let { f ->
                addQueryParameter("sort", f.getValue())
            }
        }

        return GET(url.build(), headers)
    }

    private fun combineQuery(filters: FilterList): List<String> {
        val advSearch = filters.filterIsInstance<TextFilter>().flatMap { filter ->
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

        return popularMangaParse(response)
    }

    // Details

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val fullTitle = document.select("#main-info > h1").text()
            .replace("\"", "").trim()

        return SManga.create().apply {
            val authors = getGroups(document) ?: ""
            val artists = getArtists(document) ?: ""
            initialized = true

            title = if (displayFullTitle) {
                fullTitle
            } else {
                document.select("#main-info > h1 > span").text()
                    .replace("\"", "").trim()
                    .ifBlank { fullTitle.shortenTitle() }
            }
            author = authors.ifEmpty { artists }
            artist = artists.ifEmpty { authors }
            val code = getCodes(document)
            // Some people want these additional details in description
            description = "Full English and Japanese titles:\n"
                .plus("$fullTitle\n\n")
                .plus(code ?: "")
                .plus("Pages: ${getNumPages(document)}\n")
                .plus(getTagDescription(document))
            genre = getTags(document)
            thumbnail_url = document.select("#main-cover img").attr("data-src")
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            status = SManga.COMPLETED
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                setUrlWithoutDomain(response.request.url.toString())
                date_upload = getTime(doc)
            },
        )
    }

    // Pages

    override fun pageListParse(response: Response): List<Page> {
        val images = response.asJsoup().select("#thumbnail-gallery .single-thumb a > img")
        return images.mapIndexed { index, image ->
            val imageUrl = image.attr("abs:data-src")
            Page(index, imageUrl = imageUrl.replace("t.", "."))
        }
    }

    override fun getFilterList() = getFilters()
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
    }
}
