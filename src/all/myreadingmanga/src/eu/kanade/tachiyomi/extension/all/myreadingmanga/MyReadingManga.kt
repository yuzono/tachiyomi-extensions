package eu.kanade.tachiyomi.extension.all.myreadingmanga

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

open class MyReadingManga(override val lang: String, private val siteLang: String, private val latestLang: String) : ParsedHttpSource(), ConfigurableSource {

    /*
     *  ========== Basic Info ==========
     */
    override val name = "MyReadingManga"
    final override val baseUrl = "https://myreadingmanga.info"
    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.7258.159 Mobile Safari/537.36")
            .add("X-Requested-With", randomString((1..20).random()))

    private val preferences: SharedPreferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    private val credentials: Credential get() = Credential(
        username = preferences.getString(USERNAME_PREF, "") ?: "",
        password = preferences.getString(PASSWORD_PREF, "") ?: "",
    )
    private data class Credential(val username: String, val password: String)
    private var isLoggedIn: Boolean = false

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .addInterceptor(::loginInterceptor)
        .build()

    override val supportsLatest = true

    // Login Interceptor
    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return chain.proceed(request)
        }

        if (isLoggedIn) {
            return chain.proceed(request)
        }

        try {
            val loginForm = FormBody.Builder()
                .add("log", credentials.username)
                .add("pwd", credentials.password)
                .add("wp-submit", "Log In")
                .add("redirect_to", "$baseUrl/")
                .add("testcookie", "1")
                .build()

            val loginRequest = POST("$baseUrl/wp-login.php", headers, loginForm)
            val loginResponse = network.cloudflareClient.newCall(loginRequest).execute()

            if (loginResponse.isSuccessful) {
                isLoggedIn = true
                return chain.proceed(request)
            } else {
                Toast.makeText(Injekt.get<Application>(), "MyReadingManga login failed. Please check your credentials.", Toast.LENGTH_LONG).show()
            }
            return chain.proceed(request)
        } catch (_: Exception) {
            return chain.proceed(request)
        }
    }

    // Preference Screen
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val application = Injekt.get<Application>()
        val usernamePref = EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "Username"
            summary = "Enter your username"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(application, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }
        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Password"
            summary = "Enter your password"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(application, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(usernamePref)
        screen.addPreference(passwordPref)
    }

    /*
     *  ========== Popular - Random ==========
     */
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/page/$page/?s=&ep_sort=rand&ep_filter_lang=$siteLang", headers) // Random Manga as returned by search
    }

    override fun popularMangaNextPageSelector() = null
    override fun popularMangaSelector() = ".wpp-list li:not(:has(img[src*=vlcsnap]))"
    override fun popularMangaFromElement(element: Element) = buildManga(element.select(".wpp-post-title").first()!!, element.select(".wpp-thumbnail").first())
    override fun popularMangaParse(response: Response): MangasPage {
        cacheAssistant()
        return super.popularMangaParse(response)
    }

    /*
     * ========== Latest ==========
     */
    @SuppressLint("DefaultLocale")
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/lang/${latestLang.lowercase()}" + if (page > 1) "/page/$page/" else "", headers) // Home Page - Latest Manga
    }

    override fun latestUpdatesNextPageSelector() = "li.pagination-next"
    override fun latestUpdatesSelector() = "article:not(.category-video)"
    override fun latestUpdatesFromElement(element: Element) = buildManga(element.select("a.entry-title-link").first()!!, element.select("a.entry-image-link img").first())
    override fun latestUpdatesParse(response: Response): MangasPage {
        cacheAssistant()
        return super.latestUpdatesParse(response)
    }

    /*
     * ========== Search ==========
     */
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        // whether enforce language is true will change the index of the loop below
        val indexModifier = filterList.filterIsInstance<EnforceLanguageFilter>().first().indexModifier()

        val uri = Uri.parse("$baseUrl/page/$page/").buildUpon()
            .appendQueryParameter("s", query)
        filterList.forEachIndexed { i, filter ->
            if (filter is UriFilter) {
                filter.addToUri(uri)
            }
            if (filter is SearchSortTypeList) {
                uri.appendQueryParameter("ep_sort", listOf("date", "date_asc", "rand", "")[filter.state])
            }
        }

        return GET(uri.toString(), headers)
    }

    override fun searchMangaNextPageSelector() = "li.pagination-next"
    override fun searchMangaSelector() = "article"
    private var mangaParsedSoFar = 0
    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        if (document.location().contains("/page/1")) mangaParsedSoFar = 0
        val mangas = document.select(searchMangaSelector()).map { searchMangaFromElement(it) }
            .also { mangaParsedSoFar += it.count() }
        val totalResults = Regex("""([\d,]+)""").find(document.select(".ep-search-count").text())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
        return MangasPage(mangas, mangaParsedSoFar < totalResults)
    }

    override fun searchMangaFromElement(element: Element) = buildManga(element.select("a[rel]").first()!!, element.select("a.entry-image-link img").first())

    /*
     * ========== Building manga from element ==========
     */
    private fun buildManga(titleElement: Element, thumbnailElement: Element?): SManga {
        val manga = SManga.create().apply {
            setUrlWithoutDomain(titleElement.attr("href"))
            title = cleanTitle(titleElement.text())
        }
        if (thumbnailElement != null) manga.thumbnail_url = getThumbnail(getImage(thumbnailElement))
        return manga
    }

    private val extensionRegex = Regex("""\.(jpg|png|jpeg|webp)""")

    private fun getImage(element: Element): String? {
        val url = when {
            element.attr("data-src").contains(extensionRegex) -> element.attr("abs:data-src")
            element.attr("data-cfsrc").contains(extensionRegex) -> element.attr("abs:data-cfsrc")
            element.attr("src").contains(extensionRegex) -> element.attr("abs:src")
            else -> element.attr("abs:data-lazy-src")
        }

        return if (URLUtil.isValidUrl(url)) url else null
    }

    // removes resizing
    private fun getThumbnail(thumbnailUrl: String?): String? {
        thumbnailUrl ?: return null
        val url = thumbnailUrl.substringBeforeLast("-") + "." + thumbnailUrl.substringAfterLast(".")
        return if (URLUtil.isValidUrl(url)) url else null
    }
    private val titleRegex = Regex("""\s*\[[^]]*]\s*""")
    private fun cleanTitle(title: String): String {
        var cleanedTitle = title
        cleanedTitle = cleanedTitle.replace(titleRegex, " ").trim()
        if (cleanedTitle.endsWith(")") && cleanedTitle.lastIndexOf('(') != -1) {
            cleanedTitle = cleanedTitle.substringBeforeLast("(").trimEnd()
        }
        return cleanedTitle.replace(Regex("\\s+"), " ").trim()
    }

    // Manga Details
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val needCover = manga.thumbnail_url?.let { url -> client.newCall(GET(url, headers)).execute().use { !it.isSuccessful } } ?: true

        val response = client.newCall(mangaDetailsRequest(manga)).await()
        return mangaDetailsParse(response.asJsoup(), needCover).apply { initialized = true }
    }

    private fun mangaDetailsParse(document: Document, needCover: Boolean = true): SManga {
        return SManga.create().apply {
            title = cleanTitle(document.select("h1").text())
            author = document.select(".entry-terms a[href*=artist]").firstOrNull()?.text()
            artist = author
            genre = document.select(".entry-header p a[href*=genre], [href*=tag], span.entry-categories a").joinToString { it.text() }
            val basicDescription = document.select("h1").text()
            // too troublesome to achieve 100% accuracy assigning scanlator group during chapterListParse
            val scanlatedBy = document.select(".entry-terms:has(a[href*=group])").firstOrNull()
                ?.select("a[href*=group]")?.joinToString(prefix = "Scanlated by: ") { it.text() }
            val extendedDescription = document.select(".entry-content p:not(p:containsOwn(|)):not(.chapter-class + p)").joinToString("\n") { it.text() }
            description = listOfNotNull(basicDescription, scanlatedBy, extendedDescription).joinToString("\n").trim()
            status = when (document.select("a[href*=status]").first()?.text()) {
                "Completed" -> SManga.COMPLETED
                "Ongoing" -> SManga.ONGOING
                "Licensed" -> SManga.LICENSED
                "Dropped" -> SManga.CANCELLED
                "Discontinued" -> SManga.CANCELLED
                "Hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            if (needCover) {
                thumbnail_url = client.newCall(GET("$baseUrl/?s=${document.location()}", headers))
                    .execute().use {
                        it.asJsoup().select("div.ep-search-content div.entry-content img").firstOrNull()
                    }?.let {
                        getThumbnail(getImage(it))
                    }
            }
        }
    }

    override fun mangaDetailsParse(document: Document) = throw UnsupportedOperationException()

    /*
     * ========== Building chapters from element ==========
     */
    override fun chapterListSelector() = "a[class=page-numbers]"

    @SuppressLint("DefaultLocale")
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        val date = parseDate(document.select(".entry-time").text())
        // create first chapter since its on main manga page
        chapters.add(createChapter("1", document.baseUri(), date, "Ch. 1"))
        // see if there are multiple chapters or not
        val lastChapterNumber = document.select(chapterListSelector()).last()?.text()?.toIntOrNull()
        if (lastChapterNumber != null) {
            // There are entries with more chapters but those never show up,
            // so we take the last one and loop it to get all hidden ones.
            // Example: 1 2 3 4 .. 7 8 9 Next
            for (i in 2..lastChapterNumber) {
                chapters.add(createChapter(i.toString(), document.baseUri(), date, "Part $i"))
            }
        }
        return chapters.reversed()
    }

    private fun parseDate(date: String): Long {
        return SimpleDateFormat("MMM dd, yyyy", Locale.US).parse(date)?.time ?: 0
    }

    private fun createChapter(pageNumber: String, mangaUrl: String, date: Long, chname: String): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain("$mangaUrl/$pageNumber")
        chapter.name = chname
        chapter.date_upload = date
        return chapter
    }

    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    /*
     * ========== Building pages from element ==========
     */
    override fun pageListParse(document: Document): List<Page> {
        return (document.select("div.entry-content img") + document.select("div.separator img[data-src]"))
            .mapNotNull { getImage(it) }
            .distinct()
            .mapIndexed { i, url -> Page(i, "", url) }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    /*
     * ========== Parse filters from pages ==========
     *
     * In a recent (2025) update, MRM updated their search interface. As such, there is no longer
     * pages listing every tags, every author, etc. (except for Langs and Genres). The search page
     * display the top 25 results for each filter category. Since these lists aren't exhaustive, we
     * call them "Popular"
     *
     * TODO : MRM have a meta sitemap (https://myreadingmanga.info/sitemap_index.xml) that links to
     * tag/genre/pairing/etc xml sitemaps. Filters could be populated from those instead of HTML pages
     */
    private var filtersCached = false
    private var mainPage = ""
    private var searchPage = ""

    // Grabs page containing filters and puts it into cache
    private fun filterAssist(url: String) {
        filterMap[url] = client.newCall(GET(url, headers)).execute().use {
            it.body.string()
        }
    }

    private fun cacheAssistant() {
        if (!filtersCached) {
            mainPage = filterAssist(baseUrl)
            searchPage = filterAssist("$baseUrl/?s=")
            filtersCached = true
        }
    }

    // Parses cached page for filters
    private fun returnFilter(url: String, css: String): Array<Pair<String, String>> {
        val document = if (filterMap.isEmpty()) {
            filtersCached = false
            null
        } else {
            filtersCached = true
            Jsoup.parse(mainPage)
        }
        return document?.select(css)?.map { Pair(it.text(), it.attr("href")?.split("/")?.dropLast(1)?.last() ?: "") }?.toTypedArray()
            ?: arrayOf(Pair("Press 'Reset' to load filters", ""))
    }

    // URLs for the pages we need to cache
    private val cachedPagesUrls = hashMapOf(
        Pair("genres", baseUrl),
        Pair("tags", "$baseUrl/tags/"),
        Pair("categories", "$baseUrl/cats/"),
        Pair("pairings", "$baseUrl/pairing/"),
        Pair("groups", "$baseUrl/group/"),
    )

    // Generates the filter lists for app
    override fun getFilterList(): FilterList {
        return FilterList(
            EnforceLanguageFilter(siteLang),
            SearchSortTypeList(),
            GenreFilter(returnFilter(cachedPagesUrls["genres"]!!, ".tagcloud a[href*=/genre/]")),
            TagFilter(returnFilter(cachedPagesUrls["tags"]!!, ".tag-groups-alphabetical-index a")),
            CatFilter(returnFilter(cachedPagesUrls["categories"]!!, ".tag-groups-alphabetical-index a")),
            PairingFilter(returnFilter(cachedPagesUrls["pairings"]!!, ".tag-groups-alphabetical-index a")),
            ScanGroupFilter(returnFilter(cachedPagesUrls["groups"]!!, ".tag-groups-alphabetical-index a")),
        )
    }

    private class EnforceLanguageFilter(val siteLang: String) : Filter.CheckBox("Enforce language", true), UriFilter {
        fun indexModifier() = if (state) 0 else 1
        override fun addToUri(uri: Uri.Builder) {
            if (state) uri.appendQueryParameter("ep_filter_lang", siteLang)
        }
    }

    private class GenreFilter(genres: Array<Pair<String, String>>) : UriSelectFilter("Genre", "ep_filter_genre", arrayOf(Pair("Any", ""), *genres))
    private class TagFilter(popTags: Array<Pair<String, String>>) : UriSelectFilter("Popular Tags", "ep_filter_post_tag", arrayOf(Pair("Any", ""), *popTags))
    private class CatFilter(catIds: Array<Pair<String, String>>) : UriSelectFilter("Categories", "ep_filter_category", arrayOf(Pair("Any", ""), *catIds))
    private class PairingFilter(pairs: Array<Pair<String, String>>) : UriSelectFilter("Pairing", "ep_filter_pairing", arrayOf(Pair("Any", ""), *pairs))
    private class ScanGroupFilter(groups: Array<Pair<String, String>>) : UriSelectFilter("Scanlation Group", "ep_filter_group", arrayOf(Pair("Any", ""), *groups))
    private class SearchSortTypeList : Filter.Select<String>("Sort by", arrayOf("Newest", "Oldest", "Random", "More relevant"))

    private class MrmFilter(name: String, val value: String) : Filter.CheckBox(name)
    private open class UriSelectFilter(
        displayName: String,
        val uriParam: String,
        val vals: Array<Pair<String, String>>,
        val firstIsUnspecified: Boolean = true,
        defaultValue: Int = 0,
    ) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            if (state != 0 || !firstIsUnspecified) {
                uri.appendQueryParameter(uriParam, vals[state].second)
            }
        }
    }

    /**
     * Represents a filter that is able to modify a URI.
     */
    private interface UriFilter {
        fun addToUri(uri: Uri.Builder)
    }

    companion object {
        private const val USERNAME_PREF = "MYREADINGMANGA_USERNAME"
        private const val PASSWORD_PREF = "MYREADINGMANGA_PASSWORD"
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}
