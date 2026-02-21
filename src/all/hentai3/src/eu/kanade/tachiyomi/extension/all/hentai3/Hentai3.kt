package eu.kanade.tachiyomi.extension.all.hentai3

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getArtists
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getCodes
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getGroups
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getNumPages
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getTagDescription
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getTags
import eu.kanade.tachiyomi.extension.all.hentai3.Hentai3Utils.getTime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.randomua.addRandomUAPreferenceToScreen
import keiyoushi.lib.randomua.getPrefCustomUA
import keiyoushi.lib.randomua.getPrefUAType
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

open class Hentai3(
    override val lang: String = "all",
    private val searchLang: String = "",
    private val flagLang: String = "",
) : ParsedHttpSource(),
    ConfigurableSource {

    override val name = "3Hentai"

    override val baseUrl = "https://3hentai.net"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .setRandomUserAgent(
                userAgentType = preferences.getPrefUAType(),
                customUA = preferences.getPrefCustomUA(),
                filterInclude = listOf("chrome"),
            )
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("referer", "$baseUrl/")
        .set("origin", baseUrl)

    private val preferences by getPreferencesLazy()

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)

        addRandomUAPreferenceToScreen(screen)
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
        thumbnail_url = element.selectFirst(".cover img")!!.let { img ->
            if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
        }
    }

    override fun popularMangaSelector() = "#main-content .listing-container .doujin"

    override fun popularMangaNextPageSelector() = "#main-content nav .pagination .page-item .page-link[rel=next]"

    override fun relatedMangaListSelector(): String = popularMangaSelector() + if (flagLang.isNotEmpty()) ":has(.flag-$flagLang)" else ""

    /* Latest */

    override fun latestUpdatesRequest(page: Int) = GET(if (searchLang.isBlank()) "$baseUrl/search?q=pages%3A>0&pages=$page" else "$baseUrl/language/$searchLang/$page", headers)

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
                        .execute()
                        .let { response -> searchMangaByIdParse(response, id) }
                }
                query.toIntOrNull() != null -> {
                    client.newCall(searchMangaByIdRequest(query))
                        .execute()
                        .let { response -> searchMangaByIdParse(response, query) }
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
                if (searchLang.isNotEmpty()) "lang:$searchLang" else null,
            ) + combineQuery(filterList).filterNotNull()
            )
            .joinToString(" ") { it.trim() }
            .trim()

        val favoriteFilter = filterList.findInstance<FavoriteFilter>()
        val offsetPage =
            filterList.findInstance<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        val searchURL = if (favoriteFilter?.state == true) {
            "$baseUrl/user/panel/favorites"
        } else {
            "$baseUrl/search"
        }

        val url = searchURL.toHttpUrl().newBuilder().apply {
            addQueryParameter("q", if (queries.isNotEmpty()) queries else "pages:>0")
            addQueryParameter("page", offsetPage.toString())
            filterList.findInstance<SortFilter>()?.let { f ->
                addQueryParameter("sort", f.toUriPart())
            }
        }

        return GET(url.build(), headers)
    }

    private fun combineQuery(filters: FilterList): List<String?> {
        val advSearch = filters.filterIsInstance<AdvSearchEntryFilter>().flatMap { filter ->
            val splits = filter.state.split(",").map(String::trim).filterNot(String::isBlank)
            splits.map {
                AdvSearchEntry(
                    type = filter.type,
                    text = it.removePrefix("-"),
                    exclude = it.startsWith("-"),
                    specific = filter.specific,
                )
            }
        }

        return advSearch.mapNotNull { tag ->
            if (tag.text.isNotBlank()) {
                buildString {
                    if (tag.exclude) append("-")
                    append(tag.type, ":'")
                    append(tag.text)
                    append(if (tag.specific.isNotBlank()) " (${tag.specific})'" else "'")
                }
            } else {
                null
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

    override fun mangaDetailsParse(document: Document): SManga {
        val fullTitle = document.select("#main-info > h1").text().replace("\"", "").trim()
        val artists = getArtists(document)
        val authors = getGroups(document)

        return SManga.create().apply {
            title = if (displayFullTitle) fullTitle else fullTitle.shortenTitle()
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
        private const val TITLE_PREF = "Display manga title as:"
    }
}
