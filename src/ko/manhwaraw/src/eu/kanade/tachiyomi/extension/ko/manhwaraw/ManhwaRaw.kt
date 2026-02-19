package eu.kanade.tachiyomi.extension.ko.manhwaraw

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ManhwaRaw : Madara("ManhwaRaw", "https://manhwaraw.com", "ko") {

    override val mangaSubString = "manhwa-raw"

    // The website does not flag the content.
    override val filterNonMangaItems = false

    override fun searchMangaSelector() = ".list-movie .movie-item > a"

    override fun popularMangaNextPageSelector() = ".wp-pagenavi a.next"

    override fun mangaDetailsParse(document: Document): SManga = super.mangaDetailsParse(document)
        .also {
            with(document) {
                select(mangaDetailsSelectorAuthor)
                    .map { it.extractURI() }
                    .filter { it.first.notUpdating() }
                    .let { authorList += it.toSet() }
                select(mangaDetailsSelectorArtist)
                    .map { it.extractURI() }
                    .filter { it.first.notUpdating() }
                    .let { artistList += it.toSet() }
            }
        }

    private fun Element.extractURI(): Pair<String, String> = Pair(
        ownText(),
        attr("href")
            .removeSuffix("/")
            .substringAfterLast('/'),
    )

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            manga.setUrlWithoutDomain(attr("abs:href"))
            manga.title = attr("title")
            selectFirst("img")?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
        }

        return manga
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val orderByFilter = filters.filterIsInstance<OrderByFilter>().firstOrNull()
        val genreFilter = filters.filterIsInstance<GenreList>().firstOrNull()
            ?: GenreList("", emptyList())
        val authorFilter = filters.filterIsInstance<AuthorList>().firstOrNull()
            ?: AuthorList("", emptyList())
        val artistFilter = filters.filterIsInstance<ArtistList>().firstOrNull()
            ?: ArtistList("", emptyList())
        if (query.isNotBlank() ||
            (
                genreFilter.state == 0 &&
                    authorFilter.state == 0 &&
                    artistFilter.state == 0
                )
        ) {
            return super.getSearchManga(page, query, filters)
        } else {
            val url = baseUrl.toHttpUrl().newBuilder()
            when {
                genreFilter.state > 0 -> {
                    url.addPathSegment("manhwa-raw-genre")
                    url.addPathSegment(genreFilter.toUriPart())
                }
                authorFilter.state > 0 -> {
                    url.addPathSegment("manhwa-raw-author")
                    url.addPathSegment(authorFilter.toUriPart())
                }
                artistFilter.state > 0 -> {
                    url.addPathSegment("manhwa-raw-artist")
                    url.addPathSegment(artistFilter.toUriPart())
                }
                else -> {
                    url.addPathSegment(mangaSubString)
                }
            }
            url.addPathSegments(searchPage(page))

            orderByFilter?.let {
                url.addQueryParameter("m_orderby", it.toUriPart())
            }

            val request = GET(url.build(), headers)
            return client.newCall(request).execute()
                .use { popularMangaParse(it) }
        }
    }

    override fun getFilterList(): FilterList {
        val filters = mutableListOf(
            OrderByFilter(
                title = intl["order_by_filter_title"],
                options = orderByFilterOptions.toList(),
                state = 0,
            ),
            Filter.Separator(),
            Filter.Header("Browsing"),
            GenreList(
                title = intl["genre_filter_title"],
                options = listOf(Genre("<${intl["genre_filter_title"]}>", "")) +
                    genresList,
            ),
            AuthorList(
                title = intl["author_filter_title"],
                options = listOf(Pair("<${intl["author_filter_title"]}>", "")) +
                    authorList,
            ),
            ArtistList(
                title = intl["artist_filter_title"],
                options = listOf(Pair("<${intl["artist_filter_title"]}>", "")) +
                    artistList,
            ),
        )

        return FilterList(filters)
    }

    private class GenreList(title: String, options: List<Genre>, state: Int = 0) : UriPartFilter(title, options.map { it.name to it.id }.toTypedArray(), state)

    private class AuthorList(title: String, options: List<Pair<String, String>>, state: Int = 0) : UriPartFilter(title, options.toTypedArray(), state)

    private class ArtistList(title: String, options: List<Pair<String, String>>, state: Int = 0) : UriPartFilter(title, options.toTypedArray(), state)

    private var authorList: Set<Pair<String, String>> = emptySet()
    private var artistList: Set<Pair<String, String>> = emptySet()

    override val orderByFilterOptions: Map<String, String> = mapOf(
        intl["order_by_filter_new"] to "new-manga",
        intl["order_by_filter_latest"] to "latest",
        intl["order_by_filter_az"] to "alphabet",
        intl["order_by_filter_rating"] to "rating",
        intl["order_by_filter_trending"] to "trending",
        intl["order_by_filter_views"] to "views",
    )

    // console.log([...document.querySelectorAll(".wp-manga-section .manga-genres-class-name .genres li a")].map((el) => `Genre("${el.innerText}", "${el.getAttribute('href')}"),`).join('\n'))
    override var genresList = listOf(
        Genre("Action", "action"),
        Genre("Adult", "adult"),
        Genre("Adventure", "adventure"),
        Genre("Anime", "anime"),
        Genre("Boy's Love", "boy-love"),
        Genre("Campus", "campus"),
        Genre("Comedy", "comedy"),
        Genre("Comic", "comic"),
        Genre("Crime", "crime"),
        Genre("Dance", "dance"),
        Genre("Doujinshi", "doujinshi"),
        Genre("Drama", "drama"),
        Genre("Ecchi", "ecchi"),
        Genre("Fantasy", "fantasy"),
        Genre("Girls's Love", "girls-love"),
        Genre("Harem", "harem"),
        Genre("Hentai", "hentai"),
        Genre("Historical", "historical"),
        Genre("Horror", "horror"),
        Genre("Isekai", "isekai"),
        Genre("Josei", "josei"),
        Genre("Manga", "manga"),
        Genre("Manhua", "manhua"),
        Genre("Manhwa", "manhwa"),
        Genre("manhwa raw", "manhwa-raw"),
        Genre("Martial Arts", "martial-arts"),
        Genre("Mature", "mature"),
        Genre("Mecha", "mecha"),
        Genre("Military", "military"),
        Genre("Mystery", "mystery"),
        Genre("Raw", "raw"),
        Genre("Reincarnation", "reincarnation"),
        Genre("Revenge", "revenge"),
        Genre("Romance", "romance"),
        Genre("School Life", "school-life"),
        Genre("Sci-fi", "sci-fi"),
        Genre("Secret Relationship", "secret-relationship"),
        Genre("Seinen", "seinen"),
        Genre("Shoujo", "shoujo"),
        Genre("Shoujo Ai", "shoujo-ai"),
        Genre("Shounen", "shounen"),
        Genre("Slice of Life", "slice-of-life"),
        Genre("Smut", "smut"),
        Genre("Sports", "sports"),
        Genre("Supernatural", "supernatural"),
        Genre("Thriller", "thriller"),
        Genre("Tragedy", "tragedy"),
        Genre("Webtoon", "webtoon"),
        Genre("Yaoi", "yaoi"),
        Genre("Yuri", "yuri"),
    )

    override fun oldXhrChaptersRequest(mangaId: String): Request {
        val form = FormBody.Builder()
            .add("action", "ajax_chap")
            .add("post_id", mangaId)
            .build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, form)
    }
}
