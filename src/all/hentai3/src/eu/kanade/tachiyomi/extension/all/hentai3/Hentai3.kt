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
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

@Source
abstract class Hentai3 :
    KeiSource(),
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

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addNetworkInterceptor(::authorizationInterceptor)
    }

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
    }

    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = when {
            searchLang.isBlank() -> "$baseUrl/search?q=pages%3A>0&sort=popular-7d&page=$page"
            page == 1 -> "$baseUrl/language/$searchLang?sort=popular-7d"
            else -> "$baseUrl/language/$searchLang/$page?sort=popular-7d"
        }

        return parseMangasPage(client.get(url).asJsoup())
    }

    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select(popularMangaSelector).map(::popularMangaFromElement)
        val hasNextPage = document.selectFirst(popularMangaNextPageSelector) != null

        return MangasPage(mangas, hasNextPage)
    }

    private val popularMangaSelector = "a[href*=/d/]"
    private val popularMangaNextPageSelector = "a[rel=next]"

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("div.title")!!.ownText().replace("\"", "").let {
            if (displayFullTitle) it.trim() else it.shortenTitle()
        }
        setUrlWithoutDomain(element.absUrl("href"))
        thumbnail_url = element.selectFirst(".cover img")!!.let { img ->
            if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.absUrl("src")
        }
    }

    // Related
    override val supportsRelatedMangas get() = true

    private fun relatedMangaListSelector(): String = popularMangaSelector + if (flagLang.isNotEmpty()) ":has(.flag-$flagLang)" else ""

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = client.get(getMangaUrl(manga)).asJsoup()
        .select(relatedMangaListSelector()).map(::popularMangaFromElement)

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/${if (searchLang.isNotEmpty()) "language/$searchLang/$page" else "search?q=pages%3A>0&page=$page"}"
        return parseMangasPage(client.get(url).asJsoup())
    }

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = when {
        query.startsWith(PREFIX_ID_SEARCH) -> MangasPage(listOf(searchMangaById(query.removePrefix(PREFIX_ID_SEARCH))), false)
        query.toIntOrNull() != null -> MangasPage(listOf(searchMangaById(query)), false)
        else -> {
            val response = client.get(searchMangaRequestUrl(page, query, filters))
            val document = response.asJsoup()

            if (response.request.url.toString().contains("/login") &&
                document.select("input[value=Login to my account]").isNotEmpty()
            ) {
                throw Exception("Log in via WebView to view favorites")
            }

            parseMangasPage(document)
        }
    }

    private suspend fun searchMangaById(id: String): SManga {
        val document = client.get("$baseUrl/d/$id").asJsoup()
        return parseMangaDetails(document).apply { url = "/d/$id" }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.size < 2) {
            return null
        }

        return searchMangaById(url.pathSegments[1])
    }

    private fun searchMangaRequestUrl(page: Int, query: String, filters: FilterList): HttpUrl {
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

        return searchURL.toHttpUrl().newBuilder().apply {
            addQueryParameter("q", queries.ifEmpty { "pages:>0" })
            addQueryParameter("page", offsetPage.toString())
            filterList.firstInstanceOrNull<SelectFilter>()?.let { f ->
                addQueryParameter("sort", f.getValue())
            }
        }.build()
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

    // Details

    private fun parseMangaDetails(document: Document): SManga {
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

    private fun parseChapterList(document: Document, chapterUrl: String): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Chapter"
            setUrlWithoutDomain(chapterUrl)
            date_upload = getTime(document)
        },
    )

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaUrl = getMangaUrl(manga)
        val document = client.get(mangaUrl).asJsoup()

        val updatedManga = parseMangaDetails(document).apply { url = manga.url }
        val updatedChapters = parseChapterList(document, mangaUrl)

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val images = client.get(getChapterUrl(chapter)).asJsoup().select("#thumbnail-gallery .single-thumb a > img")
        return images.mapIndexed { index, image ->
            val imageUrl = image.attr("abs:data-src")
            Page(index, imageUrl = imageUrl.replace("t.", "."))
        }
    }

    override fun getFilterList(data: JsonElement?) = getFilters()

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
    }
}
