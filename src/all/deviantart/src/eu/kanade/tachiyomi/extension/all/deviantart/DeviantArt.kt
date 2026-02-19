package eu.kanade.tachiyomi.extension.all.deviantart

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale

class DeviantArt :
    HttpSource(),
    ConfigurableSource {
    override val name = "DeviantArt"
    override val baseUrl = "https://www.deviantart.com"
    override val lang = "all"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0")
    }

    private val backendBaseUrl = "https://backend.deviantart.com"
    private fun backendBuilder() = backendBaseUrl.toHttpUrl().newBuilder()

    private val json = Json { ignoreUnknownKeys = true }

    private val dateFormat by lazy {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
    }

    private val dateFormatZ by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    }

    private fun queryBuilder(page: Int): HttpUrl.Builder = backendBuilder()
        .addPathSegment("rss.xml")
        .addQueryParameter("type", "deviation")
        .apply {
            if (page > 1) {
                addQueryParameter("offset", ((page - 1) * 60).toString())
            }
        }

    override fun popularMangaRequest(page: Int): Request {
        val url = queryBuilder(page)
            .apply {
                val queryString = listOfNotNull(
                    "boost:popular",
                    catalogCategory.takeIf { it.isNotBlank() }?.let { "in:$it" },
                    catalogSearchQuery.takeIf { it.isNotBlank() },
                ).joinToString("+")
                addEncodedQueryParameter("q", queryString)
            }
            .build()

        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoupXml()
        val mangaList = parseToMangaList(document).toMutableList()
        val nextUrl = document.selectFirst("[rel=next]")?.absUrl("href")

        return MangasPage(mangaList, nextUrl != null)
    }

    private fun parseToMangaList(document: Document): List<SManga> {
        val items = document.select("item")
        return items.map {
            val creator = it.selectFirst("media|credit")?.text()
            val artName = it.selectFirst("title")!!.text()
            val link = it.selectFirst("link")!!.text()
            val id = link.removeSuffix("/")
                .substringAfterLast('/')
                .substringAfterLast('-')

            SManga.create().apply {
                setUrlWithoutDomain(link)
                title = if (preferences.includeArtIdInChapterName) {
                    "$artName [$id]"
                } else {
                    artName
                }
                author = creator
                thumbnail_url = it.selectFirst("media|content")?.getImgAttr()
                author?.let { genre = "gallery:$author, by:$author" }
                description = "Click tag to show artist's gallery"
            }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val matchGroups = Regex("""gallery:([\w-]+)(?:/(\d+))?""").matchEntire(query)?.groupValues
        if (matchGroups != null) {
            val username = matchGroups[1]
            val folderId = matchGroups[2].ifEmpty { "all" }
            return GET("$baseUrl/$username/gallery/$folderId", headers)
        } else {
            return GET(
                queryBuilder(page)
                    .apply {
                        val queryString = listOfNotNull(
                            filters.filterIsInstance<SortFilter>().firstOrNull()?.selected,
                            filters.filterIsInstance<CategoryFilter>().firstOrNull()?.state
                                ?.takeIf { it.isNotBlank() }?.let { "in:$it" },
                            query.takeIf { it.isNotBlank() },
                        ).joinToString("+")
                        addEncodedQueryParameter("q", queryString)
                    }
                    .build(),
                headers,
            )
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        if (url.contains("rss.xml")) {
            return popularMangaParse(response)
        } else {
            val manga = mangaDetailsParse(response)
            return MangasPage(listOf(manga), false)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = queryBuilder(page)
            .apply {
                val queryString = listOfNotNull(
                    "sort:time",
                    catalogCategory.takeIf { it.isNotBlank() }?.let { "in:$it" },
                    catalogSearchQuery.takeIf { it.isNotBlank() },
                ).joinToString("+")
                addEncodedQueryParameter("q", queryString)
            }
            .build()

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val link = response.request.url.toString()
        if (link.contains("/gallery/")) {
            val gallery = document.selectFirst("#sub-folder-gallery")

            // If manga is sub-gallery then use sub-gallery name, else use gallery name
            val galleryName = gallery?.selectFirst("._2vMZg + ._2vMZg")?.text()?.substringBeforeLast(" ")
                ?: gallery?.selectFirst("[aria-haspopup=listbox] > div")!!.ownText()
            val artistInTitle = (preferences.artistInTitle == ArtistInTitle.ALWAYS.name) ||
                ((preferences.artistInTitle == ArtistInTitle.ONLY_ALL_GALLERIES.name) && (galleryName == "All"))

            return SManga.create().apply {
                setUrlWithoutDomain(link)
                author = document.title().substringBefore(" ")
                title = when {
                    artistInTitle -> "$author - $galleryName"
                    else -> galleryName
                }
                description = gallery.selectFirst(".legacy-journal")?.wholeText()
                    ?.let { "$it\n\nClick tag to show artist's gallery" }
                    ?: "Click tag to show artist's gallery"
                thumbnail_url = gallery.selectFirst("img[property=contentUrl]")?.getImgAttr()
                    ?: document.selectFirst("#background-container + div a[data-icon]")?.attr("abs:data-icon")
                author?.let { genre = "gallery:$author, by:$author" }
                status = SManga.ONGOING
            }
        } else {
            return SManga.create().apply {
                // Update higher resolution thumbnail
                thumbnail_url = document.selectFirst("img[property=contentUrl]")?.getImgAttr()
                status = SManga.COMPLETED
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        if (manga.url.contains("/gallery/")) {
            val pathSegments = getMangaUrl(manga).toHttpUrl().pathSegments
            val username = pathSegments[0]
            val query = when (val folderId = pathSegments[2]) {
                "all" -> "gallery:$username"
                else -> "gallery:$username/$folderId"
            }

            val url = backendBuilder()
                .addPathSegment("rss.xml")
                .addQueryParameter("q", query)
                .build()

            return GET(url, headers)
        } else {
            return GET(getMangaUrl(manga), headers)
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoupXml()
        val url = response.request.url.toString()
        if (url.contains("rss.xml")) {
            val chapterList = parseToChapterList(document).toMutableList()
            var nextUrl = document.selectFirst("[rel=next]")?.absUrl("href")

            while (nextUrl != null) {
                val newRequest = GET(nextUrl, headers)
                val newResponse = client.newCall(newRequest).execute()
                val newDocument = newResponse.asJsoupXml()
                val newChapterList = parseToChapterList(newDocument)
                chapterList.addAll(newChapterList)

                nextUrl = newDocument.selectFirst("[rel=next]")?.absUrl("href")
            }

            return chapterList.also(::orderChapterList).toList()
        } else {
            val time = document.selectFirst("span:contains(Published) time")
                ?.attr("datetime")
            return listOf(
                SChapter.create().apply {
                    setUrlWithoutDomain(url)
                    name = "Art"
                    time?.let { date_upload = dateFormatZ.tryParse(it) }
                },
            )
        }
    }

    override fun relatedMangaListParse(response: Response): List<SManga> {
        val url = response.request.url.toString()
        val document = response.asJsoup()
        if (url.contains("/gallery/")) {
            /* Return other galleries of this user */
            val creator = document.selectFirst("#background-container + div a[data-username] > span")?.ownText()
            val galleries = document.select("#background-container + div #content section._1olSA")
            return galleries.mapNotNull { gallery ->
                val link = gallery.selectFirst("a")
                    ?.absUrl("href") ?: return@mapNotNull null
                val matchResult = Regex("""(.+gallery/[\w-]+)(?:/[\w-]+)?""").matchEntire(link)?.groupValues
                    ?: return@mapNotNull null
                if (matchResult.size < 2) return@mapNotNull null
                val galleryLink = matchResult[1]
                val galleryName = gallery.selectFirst("[title]")?.ownText() ?: return@mapNotNull null
                val artistInTitle = preferences.artistInTitle == ArtistInTitle.ALWAYS.name ||
                    (preferences.artistInTitle == ArtistInTitle.ONLY_ALL_GALLERIES.name && galleryName == "All")

                SManga.create().apply {
                    setUrlWithoutDomain(galleryLink)
                    title = when (artistInTitle) {
                        true -> "$creator - $galleryName"
                        false -> galleryName
                    }
                    thumbnail_url = gallery.selectFirst("img")?.getImgAttr()
                    author = creator
                    author?.let { genre = "by:$author" }
                    description = "Click tag to show artist's gallery"
                }
            }
        } else {
            val script = document.selectFirst("script:containsData(__RCACHE__)")?.data() ?: return emptyList()
            val matchResult = Regex("""window\.__RCACHE__ = JSON\.parse\("(.+?)"\);""")
                .find(script)?.groupValues ?: return emptyList()
            val jsonString = matchResult[1]
                .replace("\\\"", "\"")
                .replace("\\\'", "\'")
                .replace("\\\\", "\\")

            try {
                val jsonArray = json.parseToJsonElement(jsonString)
                    .jsonObject["relatedContent"]?.let { it.jsonObject["relatedContent"] }
                jsonArray?.let {
                    it.jsonArray.mapNotNull { jsonElement ->
                        try {
                            val contentType = jsonElement.jsonObject["contentType"]?.jsonPrimitive?.content
                                ?: return@mapNotNull null

                            when (contentType) {
                                "gallery", "boosted", "recommended",
                                "monetization_promotion_tier_thirdparty",
                                "monetization_promotion_dreamup_thirdparty",
                                "monetization_promotion_pcp_firstparty",
                                -> {
                                    val deviations = json.decodeFromString<List<Deviation>>(
                                        jsonElement.jsonObject["deviations"]?.jsonArray.toString(),
                                    )
                                    deviations
                                }
                                "users" -> {
                                    val users = json.decodeFromString<List<User>>(
                                        jsonElement.jsonObject["users"]?.jsonArray.toString(),
                                    )
                                    val deviations = users.map { user -> user.deviations }.flatten()
                                    deviations
                                }
                                "collections" -> {
                                    val collections = json.decodeFromString<List<Collection>>(
                                        jsonElement.jsonObject["collections"]?.jsonArray.toString(),
                                    )
                                    val deviations = collections.map { collection -> collection.deviations }.flatten()
                                    deviations
                                }
                                else -> {
                                    Log.e("DeviantArt", "contentType: $contentType | ${jsonElement.jsonObject}")
                                    return@mapNotNull null
                                }
                            }
                        } catch (e: SerializationException) {
                            Log.e("DeviantArt", "Error parsing JSON", e)
                            return@mapNotNull null
                        }
                    }.flatten()
                        .map { deviation ->
                            SManga.create().apply {
                                setUrlWithoutDomain(deviation.url)
                                title = deviation.title
                                thumbnail_url = deviation.media.getCoverUrl()
                                author = deviation.author.username
                                author?.let { genre = "gallery:$author, by:$author" }
                                description = "Click tag to show artist's gallery"
                            }
                        }
                }?.let { return it }
            } catch (e: SerializationException) {
                Log.e("DeviantArt", "Error parsing JSON", e)
            }

            return emptyList()
        }
    }

    private fun parseToChapterList(document: Document): List<SChapter> = document.select("item").map {
        val artName = it.selectFirst("title")!!.text()
        val link = it.selectFirst("link")!!.text()
        val id = link.removeSuffix("/")
            .substringAfterLast('/')
            .substringAfterLast('-')

        SChapter.create().apply {
            setUrlWithoutDomain(link)
            name = if (preferences.includeArtIdInChapterName) {
                "$artName [$id]"
            } else {
                artName
            }
            date_upload = dateFormat.tryParse(it.selectFirst("pubDate")?.text())
            // scanlator = it.selectFirst("media|credit")?.text()
        }
    }

    private fun orderChapterList(chapterList: MutableList<SChapter>) {
        // In Mihon's updates tab, chapters are ordered by source instead
        // of chapter number, so to avoid updates being shown in reverse,
        // disregard source order and order chronologically instead
        if (chapterList.first().date_upload < chapterList.last().date_upload) {
            chapterList.reverse()
        }
        chapterList.forEachIndexed { i, chapter ->
            chapter.chapter_number = chapterList.size - i.toFloat()
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val buttons = document.selectFirst("[draggable=false]")?.children()
        return if (buttons == null) {
            val imageUrl = document.selectFirst("img[fetchpriority=high]")?.absUrl("src")
            listOf(Page(0, imageUrl = imageUrl))
        } else {
            buttons.mapIndexed { i, button ->
                // Remove everything past "/v1/" to get original instead of thumbnail
                // But need to preserve the query parameter where the token is
                val imageUrl = button.selectFirst("img")?.absUrl("src")
                    ?.replaceFirst(Regex("""/v1(/.*)?(?=\?)"""), "")
                Page(i, imageUrl = imageUrl)
            }
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun Response.asJsoupXml(): Document = Jsoup.parse(body.string(), request.url.toString(), Parser.xmlParser())

    private fun Element.getImgAttr(): String? = when {
        hasAttr("url") -> attr("abs:url")
        hasAttr("src") -> attr("abs:src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> null
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val artistInTitlePref = ListPreference(screen.context).apply {
            key = ArtistInTitle.PREF_KEY
            title = "Artist name in manga title"
            entries = ArtistInTitle.entries.map { it.text }.toTypedArray()
            entryValues = ArtistInTitle.entries.map { it.name }.toTypedArray()
            summary = "Current: %s\n\n" +
                "Changing this preference will not automatically apply to manga in Library " +
                "and History, so refresh all DeviantArt manga and/or clear database in Settings " +
                "> Advanced after doing so."
            setDefaultValue(ArtistInTitle.defaultValue.name)
        }

        screen.addPreference(artistInTitlePref)

        SwitchPreferenceCompat(screen.context).apply {
            key = CHAPTER_NAME_PREF
            title = "Include Art ID in chapter name (to avoid duplicate chapter name)"
            summaryOff = "Title only"
            summaryOn = "Title [ID]"
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CATALOG_SEARCH_QUERY_PREF
            title = CATALOG_FORMAT_MSG
            summary = CATALOG_SEARCH_QUERY + (preferences.getString(CATALOG_SEARCH_QUERY_PREF, "") ?: "")
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CATALOG_CATEGORY_PREF
            title = CATALOG_CATEGORY
            summary = preferences.getString(CATALOG_CATEGORY_PREF, "") ?: ""
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private val catalogSearchQuery
        get() = preferences.getString(CATALOG_SEARCH_QUERY_PREF, "") ?: ""

    private val catalogCategory
        get() = preferences.getString(CATALOG_CATEGORY_PREF, "") ?: ""

    private enum class ArtistInTitle(val text: String) {
        NEVER("Never"),
        ALWAYS("Always"),
        ONLY_ALL_GALLERIES("Only in \"All\" galleries"),
        ;

        companion object {
            const val PREF_KEY = "artistInTitlePref"
            val defaultValue = ALWAYS
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        Filter.Header(FILTER_CATEGORY),
        CategoryFilter("Category"),
        Filter.Separator(),
        Filter.Header(SEARCH_FORMAT_MSG),
    )

    class SortFilter : SelectFilter("Sort By", sort) {
        companion object {
            private val sort = listOf(
                Pair("Popular", "boost:popular"),
                Pair("Newest", "sort:time"),
            )
        }
    }

    abstract class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
    ) : Filter.Select<String>(
        name,
        options.map { it.first }.toTypedArray(),
        0,
    ) {
        val selected get() = options[state].second.takeUnless { it.isEmpty() }
    }

    private class CategoryFilter(name: String) : Filter.Text(name)

    private val SharedPreferences.artistInTitle
        get() = getString(ArtistInTitle.PREF_KEY, ArtistInTitle.defaultValue.name)

    private val SharedPreferences.includeArtIdInChapterName
        get() = getBoolean(CHAPTER_NAME_PREF, true)

    companion object {
        private const val SEARCH_FORMAT_MSG = "Browse user's gallery by searching for `gallery:{username}` or `gallery:{username}/{folderId}`\n" +
            "Or search for his separated arts with `by:{username}"
        private const val FILTER_CATEGORY = "Category (<name> or <name1>/<name2>/...)"
        private const val CATALOG_FORMAT_MSG = "Show Popular/Latest with:"
        private const val CATALOG_SEARCH_QUERY = "search query of: "
        private const val CATALOG_SEARCH_QUERY_PREF = "catalog_search_query_pref"
        private const val CATALOG_CATEGORY = "in category (<name> or <name1>/<name2>/...)"
        private const val CATALOG_CATEGORY_PREF = "catalog_category_pref"

        private const val CHAPTER_NAME_PREF = "includeArtIdAsChapterNamePref"
    }
}
