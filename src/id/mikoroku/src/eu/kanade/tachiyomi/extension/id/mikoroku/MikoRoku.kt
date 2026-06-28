package eu.kanade.tachiyomi.extension.id.mikoroku

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * MikoRoku (https://www.mikoroku.top).
 *
 * The reader front-end at mikoroku.com is now a Firebase-backed SPA, but all
 * manga/chapter data still lives on two Blogger blogs that expose Atom JSON
 * feeds. This source queries those feeds directly:
 *
 *  - CATALOG (mikoroku.top)     -> manga listings, details, search
 *  - CHAPTERS (mikodrive.my.id) -> chapter posts (pages live in the post content)
 *
 * Chapter posts are labelled with the exact manga title, so a chapter feed is
 * just the chapter blog filtered by that label:
 *   `.../feeds/posts/default/-/<manga title>?alt=json`
 */
class MikoRoku : HttpSource() {

    override val name = "MikoRoku"
    override val baseUrl = "https://www.mikoroku.top"
    override val lang = "id"
    override val supportsLatest = true

    /** Blogger blog that holds the chapter posts. */
    private val chapterBaseUrl = "https://www.mikodrive.my.id"

    private val json: Json by injectLazy()

    /** Single manga page size when browsing/searching Blogger feeds. */
    private val pageSize = 20

    private val dateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
    }

    // ============================================================================================
    //  Helpers
    // ============================================================================================

    /**
     * Builds a catalog feed URL.
     *
     * @param segments optional Blogger label path segments appended after `/-/`
     *  (e.g. `["Manga"]`, `["Action", "Manga"]`).
     * @param page 1-based page index, mapped to Blogger's `start-index`.
     * @param query optional `q=` full-text query.
     */
    private fun catalogFeedUrl(
        page: Int,
        query: String? = null,
        segments: List<String> = emptyList(),
        orderByPublished: Boolean = false,
    ): String {
        val url = "$baseUrl/feeds/posts/default".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", pageSize.toString())
            .addQueryParameter("start-index", ((page - 1) * pageSize + 1).toString())

        if (segments.isNotEmpty()) {
            url.addPathSegment("-")
            segments.forEach(url::addPathSegment)
        }
        query?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("q", it) }
        if (orderByPublished) url.addQueryParameter("orderby", "published")

        return url.build().toString()
    }

    private fun parseFeed(response: Response): FeedDto = json.decodeFromString(response.body.string())

    /** Labels that mark a catalog entry as a series (vs. chapter/help posts). */
    private val seriesLabels = setOf("Manga", "Manhua", "Manhwa")

    private fun isSeriesEntry(entry: EntryDto): Boolean {
        val cats = entry.categories
        return cats.any { it in seriesLabels } &&
            // skip the chapter / "Chapter" labelled posts that occasionally leak through
            cats.none { it.equals("Chapter", true) }
    }

    private fun parseDate(iso: String): Long = dateFormat.tryParse(iso.trim())

    // ============================================================================================
    //  Popular / Latest
    // ============================================================================================

    override fun popularMangaRequest(page: Int): Request = // No real "popular" ranking on Blogger; reuse latest as popular does.
        GET(latestFeedUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(latestFeedUrl(page), headers)

    private fun latestFeedUrl(page: Int): String = catalogFeedUrl(page = page, segments = listOf("Manga"), orderByPublished = true)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    private fun parseMangaList(response: Response): MangasPage {
        val feed = parseFeed(response)
        val entries = feed.feed?.entry.orEmpty().filter(::isSeriesEntry)
        val mangas = entries.map { it.toSManga() }
        // Blogger returns exactly `max-results` when more pages exist.
        val hasNext = entries.size == pageSize
        return MangasPage(mangas, hasNext)
    }

    // ============================================================================================
    //  Search
    // ============================================================================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val activeGenres = mutableListOf<String>()
        var status: String? = null
        var type: String? = null

        filters.forEach { filter ->
            when (filter) {
                is GenreFilterGroup -> {
                    filter.state.filter { it.state }.forEach { activeGenres.add(it.value) }
                }
                is StatusFilter -> if (filter.state in filter.values.indices) {
                    status = filter.values[filter.state].takeIf { it.isNotEmpty() }
                }
                is TypeFilter -> if (filter.state in filter.values.indices) {
                    type = filter.values[filter.state].takeIf { it.isNotEmpty() }
                }
                else -> {}
            }
        }

        val segments = buildList {
            status?.let { add(it) }
            type?.let { add(it) }
            activeGenres.forEach { add(it) }
            // always scope to Manga-ish labels so chapter/help posts are excluded
            if (none { it in seriesLabels }) add("Manga")
        }

        val url = catalogFeedUrl(
            page = page,
            query = query.takeIf { it.isNotBlank() },
            segments = segments,
            orderByPublished = true,
        )
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val base = parseMangaList(response)
        val query = response.request.url.queryParameter("q").orEmpty()
        if (query.isBlank()) return base

        // Blogger `q=` is a full-text search and can match unrelated titles via
        // content; keep only entries whose title actually contains the query.
        val filtered = base.mangas.filter { it.title.contains(query, ignoreCase = true) }
        return MangasPage(filtered, base.hasNextPage && filtered.size == pageSize)
    }

    // ============================================================================================
    //  Manga details
    // ============================================================================================

    /**
     * The stored manga URL is a catalog (mikoroku.top) post URL, but we don't use
     * it directly. Instead we re-query the catalog feed by the manga title and
     * pick the entry whose post URL matches the one we stored. This gives us the
     * clean `#extra-info` block (Author/Artist/...) from the feed `content`,
     * which the rendered post page splits across separate `.y6x11p` tags.
     */
    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$baseUrl/feeds/posts/default".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "1")
            .addQueryParameter("q", manga.title)
            .build()
            .toString()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val feed = parseFeed(response)
        val entry = feed.feed?.entry?.firstOrNull()
            ?: throw Exception("Manga not found")

        val entryTitle = entry.title?.t.orEmpty()
        val cats = entry.categories
        val doc = Jsoup.parse(entry.content?.t.orEmpty())

        return SManga.create().apply {
            title = entryTitle
            thumbnail_url = entry.thumbnail?.url?.let(::upgradeThumb)
            description = doc.selectFirst("#synopsis")?.text()?.trim()
            genre = cats
                .filter { it !in reservedLabels && it.lowercase() !in reservedLabelsLower }
                .filter { it != entryTitle }
                // drop numeric rating labels (e.g. "8.6")
                .filter { it.toDoubleOrNull() == null }
                .distinct()
                .joinToString(", ")
            status = parseStatus(cats)
            parseInfoRows(doc, this)
        }
    }

    private fun parseInfoRows(doc: Document, manga: SManga) {
        doc.select("#extra-info .y6x11p").forEach { row ->
            val label = row.ownText().trim().trimEnd(':', ' ', '\u00A0').lowercase()
            val value = row.selectFirst(".dt")?.text()?.trim().orEmpty()
            if (value.isBlank() || value == "-") return@forEach
            when {
                label.startsWith("author") || label.startsWith("penulis") || label.startsWith("pengarang") ->
                    manga.author = value
                label.startsWith("artist") || label.startsWith("ilustrator") || label.startsWith("illustrator") ->
                    manga.artist = value
                label.startsWith("alt") || label.contains("judul lain") || label.contains("alternative") ->
                    manga.description = buildString {
                        manga.description?.let { append(it).append("\n\n") }
                        append("Alternative title(s): ").append(value)
                    }
            }
        }
    }

    private fun upgradeThumb(url: String): String = url.replace(Regex("""\/s\d+-c[\/]"""), "/s500/")
        .replace(Regex("""\/s\d+[\/](?!.*\/s\d)"""), "/s500/")

    // ============================================================================================
    //  Chapters
    // ============================================================================================

    /**
     * Chapter posts live on the chapter blog and are labelled with the manga
     * title. We fetch the full label feed and paginate until we have everything.
     *
     * The manga title is stashed in the request URL fragment so that
     * [chapterListParse] can rebuild subsequent pages without re-parsing the
     * (percent-decoded) label out of the path.
     */
    override fun chapterListRequest(manga: SManga): Request {
        // Carry the raw title via the URL fragment so chapterListParse can rebuild
        // subsequent pages without re-parsing the (percent-decoded) label.
        val url = chapterFeedUrl(manga.title, startIndex = null)
            .newBuilder()
            .fragment(manga.title)
            .build()
        return GET(url, headers)
    }

    private fun chapterFeedUrl(title: String, startIndex: Int?): HttpUrl {
        val builder = "$chapterBaseUrl/feeds/posts/default/-/${title.toBloggerPath()}"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", MAX_CHAPTER_RESULTS.toString())
        startIndex?.let { builder.addQueryParameter("start-index", it.toString()) }
        return builder.build()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val first = parseFeed(response)
        val total = first.feed?.totalResults?.t?.toIntOrNull() ?: 0
        val title = response.request.url.fragment
        val all = first.feed?.entry.orEmpty().toMutableList()

        // Fetch remaining pages.
        if (title != null && total > all.size) {
            var start = all.size + 1
            while (all.size < total) {
                val res = client.newCall(GET(chapterFeedUrl(title, start), headers)).execute()
                val page = parseFeed(res).feed?.entry.orEmpty()
                if (page.isEmpty()) break
                all.addAll(page)
                start += page.size
            }
        }

        return all
            .filter { it.categories.any { c -> c.equals("Chapter", true) } }
            .map { entry ->
                val date = parseDate(entry.published?.t.orEmpty()).takeIf { it > 0 } ?: 0L
                entry.toSChapter(date)
            }
            // Blogger label feeds are roughly newest-first; enforce a descending
            // chapter-number order for a stable chapter list.
            .sortedByDescending { it.chapter_number }
    }

    // ============================================================================================
    //  Pages
    // ============================================================================================

    /**
     * The chapter URL points at a chapter-blog post (mikodrive.my.id). Its HTML
     * body holds the page images inside `div.separator img`.
     */
    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        // Primary container used by the chapter blog.
        val images = document.select("div.separator img[src]").ifEmpty {
            document.select("#post-body div.separator img[src]")
        }
        return images.mapIndexed { index, img ->
            Page(index, imageUrl = img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val referer = page.url.takeIf { it.isNotBlank() } ?: baseUrl
        val builder = headers.newBuilder().set("Referer", "$referer/")
        return GET(page.imageUrl!!, builder.build())
    }

    // ============================================================================================
    //  Status parsing
    // ============================================================================================

    private fun parseStatus(categories: List<String>): Int {
        val lower = categories.map { it.lowercase() }
        return when {
            lower.any { it in ongoingTerms } -> SManga.ONGOING
            lower.any { it in completedTerms } -> SManga.COMPLETED
            lower.any { it in hiatusTerms } -> SManga.ON_HIATUS
            lower.any { it in cancelledTerms } -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // ============================================================================================
    //  Filters
    // ============================================================================================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Note: genre filter digabung dengan label. Filter bisa membatasi hasil."),
        Filter.Separator(),
        StatusFilter(),
        TypeFilter(),
        GenreFilterGroup(),
    )

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("", "Ongoing", "Completed", "Hiatus", "Dropped"))

    private class TypeFilter : Filter.Select<String>("Type", arrayOf("", "Manga", "Manhua", "Manhwa"))

    private class GenreFilterGroup :
        Filter.Group<GenreChip>(
            "Genre",
            GENRES.map { GenreChip(it) },
        )

    private class GenreChip(name: String) : Filter.CheckBox(name, false) {
        val value = name
    }

    // ============================================================================================
    //  Constants
    // ============================================================================================

    private val reservedLabels = setOf("Up", "Series", "Chapter")
    private val reservedLabelsLower = reservedLabels.map { it.lowercase() }.toSet() +
        setOf(
            "ongoing", "completed", "hiatus", "dropped", "cancelled", "canceled",
            "manga", "manhua", "manhwa", "novel", "up",
        )

    private val ongoingTerms = setOf("ongoing")
    private val completedTerms = setOf("completed")
    private val hiatusTerms = setOf("hiatus")
    private val cancelledTerms = setOf("dropped", "cancelled", "canceled")

    companion object {
        private const val MAX_CHAPTER_RESULTS = 150

        /** Genre list offered by the site (mirrors the SPA's own filter set). */
        private val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Dark Fantasy", "Drama", "Fantasy",
            "Historical", "Horror", "Isekai", "Magic", "Mecha", "Military",
            "Mystery", "Psychological", "Romance", "School Life", "Sci-Fi",
            "Seinen", "Shounen", "Slice of Life", "Supernatural", "Survival",
            "Tragedy",
        )
    }
}

/** Encodes a manga title into a safe Blogger label path segment. */
private fun String.toBloggerPath(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
