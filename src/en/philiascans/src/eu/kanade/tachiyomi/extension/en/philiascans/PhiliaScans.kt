package eu.kanade.tachiyomi.extension.en.philiascans

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class PhiliaScans :
    Madara(
        "Philia Scans",
        "https://philiascans.org",
        "en",
        SimpleDateFormat("dd/MMM", Locale.US),
    ) {
    override val versionId: Int = 2
    override val useNewChapterEndpoint = true

    private class SeriesTypeCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class SeriesTypeFilter(title: String, options: List<Pair<String, String>>) :
        Filter.Group<SeriesTypeCheckBox>(
            title,
            options.map { SeriesTypeCheckBox(it.first, it.second) },
        )

    override fun popularMangaSelector(): String = ".unit"
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun searchMangaSelector(): String = popularMangaSelector()

    override val popularMangaUrlSelector: String = "a.c-title"
    override val popularMangaUrlSelectorImg: String =
        "a.poster .poster-image-wrapper > img:not(.flag-icon)"
    override val searchMangaUrlSelector: String = "a.c-title"

    override val mangaDetailsSelectorTitle: String = "div.serie-info > h1"
    override val mangaDetailsSelectorStatus: String =
        "div.serie-info .manga-stats > div:nth-child(2) > div > span.manga"
    override val mangaDetailsSelectorDescription: String = "div.serie-info .description-content"

    override val mangaDetailsSelectorAuthor: String =
        "div.serie-info .manga-stats > div:has(span.stat-label:contains(Author)) span.stat-value"

    override val mangaDetailsSelectorArtist: String =
        "div.serie-info .manga-stats > div:has(span.stat-label:contains(Artist)) span.stat-value"

    override val mangaDetailsSelectorGenre: String = "div.serie-info .genre-list > span > a"

    override fun popularMangaRequest(page: Int): Request {
        val url =
            (if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/")
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("post_type", "wp-manga")
                .addQueryParameter("s", "")
                .addQueryParameter("sort", "most_viewed")
                .build()
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url =
            if (page == 1) {
                "$baseUrl/recently-updated/"
            } else {
                "$baseUrl/recently-updated/?page=$page"
            }
        return GET(url, headers)
    }

    // Override next page selectors for different pagination types
    private val paginationSelector = "li.page-item:not(.disabled) a[rel='next']"
    override fun popularMangaNextPageSelector(): String? = paginationSelector
    override fun latestUpdatesNextPageSelector(): String? = paginationSelector
    override fun searchMangaNextPageSelector(): String? = paginationSelector

    // Override chapter list selector to find free chapters
    override fun chapterListSelector(): String = "#free-list > li > a"

    // Override page list selector to find manga pages
    override val pageListParseSelector =
        ".reading-content img, .wp-manga-chapter-version-content img"

    // Override chapter parsing to remove "Free" prefix
    override fun chapterFromElement(element: Element): SChapter {
        val chapter = super.chapterFromElement(element)
        // Remove anything prefixing "Chapter" in chapter names
        chapter.name = chapter.name.replace(Regex("^.*?(?=Chapter)"), "")
        return chapter
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)
        val raw = document.select(mangaDetailsSelectorStatus).last()?.text()?.trim()?.lowercase()
        if (raw?.contains("releasing") == true) {
            manga.status = SManga.ONGOING
        }
        return manga
    }

    override val statusFilterOptions: Map<String, String> =
        mapOf(
            "Completed" to "end",
            "Ongoing" to "on-going",
            "Canceled" to "canceled",
            "On Hold" to "on-hold",
        )

    override val orderByFilterOptions: Map<String, String> =
        mapOf(
            "Most Viewed" to "most_viewed",
            "Alphabetical" to "alphabet",
            "Latest" to "new",
        )

    val typeFilterOptions: Map<String, String> =
        mapOf(
            "Manga" to "manga",
            "Manhua" to "manhua",
            "Manhwa" to "manhwa",
        )

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters =
            mutableListOf(
                AuthorFilter(intl["author_filter_title"]),
                ArtistFilter(intl["artist_filter_title"]),
                YearFilter(intl["year_filter_title"]),
                SeriesTypeFilter(intl["type_filter_title"], typeFilterOptions.toList()),
                StatusFilter(
                    intl["status_filter_title"],
                    statusFilterOptions.map { Tag(it.key, it.value) },
                ),
                OrderByFilter(
                    intl["order_by_filter_title"],
                    orderByFilterOptions.toList(),
                    state = 0,
                ),
            )

        if (genresList.isNotEmpty()) {
            filters +=
                listOf(
                    Filter.Separator(),
                    Filter.Header(intl["genre_filter_header"]),
                    GenreConditionFilter(
                        intl["genre_condition_filter_title"],
                        genreConditionFilterOptions.toList(),
                    ),
                    GenreList(intl["genre_filter_title"], genresList),
                )
        } else if (fetchGenres) {
            filters +=
                listOf(
                    Filter.Separator(),
                    Filter.Header(intl["genre_missing_warning"]),
                )
        }

        return FilterList(filters)
    }

    // Ensure search uses the site's query params, supports genre AND, and paginates with /page/N/
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val base = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        val urlBuilder = base.toHttpUrl().newBuilder()

        urlBuilder.addQueryParameter("post_type", "wp-manga")
        urlBuilder.addQueryParameter("s", query)

        // Apply filters matching site's params
        filters.forEach { filter ->
            when (filter) {
                is GenreConditionFilter -> {
                    // AND mode
                    if (filter.toUriPart() == "1") {
                        urlBuilder.addQueryParameter("genre_mode", "and")
                    }
                }
                is GenreList -> {
                    filter.state.filter { it.state }.forEach { g ->
                        urlBuilder.addQueryParameter("genre[]", g.id)
                    }
                }
                is OrderByFilter -> {
                    when (filter.toUriPart()) {
                        "views" -> urlBuilder.addQueryParameter("sort", "most_viewed")
                        "alphabet" -> urlBuilder.addQueryParameter("sort", "alphabet")
                        "latest" -> urlBuilder.addQueryParameter("sort", "new")
                    }
                }
                is StatusFilter -> {
                    filter.state.forEach {
                        if (it.state) {
                            urlBuilder.addQueryParameter("status[]", it.id)
                        }
                    }
                }
                is AuthorFilter -> {
                    if (filter.state.isNotBlank()) {
                        urlBuilder.addQueryParameter("author", filter.state)
                    }
                }
                is ArtistFilter -> {
                    if (filter.state.isNotBlank()) {
                        urlBuilder.addQueryParameter("artist", filter.state)
                    }
                }
                is YearFilter -> {
                    if (filter.state.isNotBlank()) {
                        // Support multiple years: extract all 4-digit sequences and send as
                        // release[]
                        val years =
                            Regex("\\b\\d{4}\\b")
                                .findAll(filter.state)
                                .map { it.value }
                                .toList()
                        if (years.isEmpty()) {
                            urlBuilder.addQueryParameter("release[]", filter.state.trim())
                        } else {
                            years.forEach { y -> urlBuilder.addQueryParameter("release[]", y) }
                        }
                    }
                }
                is SeriesTypeFilter -> {
                    filter.state.filter { it.state }.forEach { g ->
                        urlBuilder.addQueryParameter("type[]", g.value)
                    }
                }
                else -> {
                    /* ignore unsupported filters */
                }
            }
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun parseGenres(document: Document): List<Genre> {
        return document.select("ul.genres li").mapNotNull { li ->
            val id = li.selectFirst("input[name='genre[]'][value]")?.attr("value")?.trim()
            val name = li.selectFirst("label")?.text()?.trim()
            if (!id.isNullOrBlank() && !name.isNullOrBlank()) Genre(name, id) else null
        }
    }

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        with(element) {
            selectFirst(searchMangaUrlSelector)!!.let {
                manga.setUrlWithoutDomain(it.attr("abs:href"))
                manga.title = it.ownText()
            }
            // Use the same image selector as popular to avoid picking the flag icon
            selectFirst(popularMangaUrlSelectorImg)?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
        }
        return manga
    }
}
