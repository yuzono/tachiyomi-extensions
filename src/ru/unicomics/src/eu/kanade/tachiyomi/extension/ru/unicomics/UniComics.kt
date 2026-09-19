package eu.kanade.tachiyomi.extension.ru.unicomics

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import rx.schedulers.Schedulers
import kotlin.time.Duration.Companion.seconds

@Source
abstract class UniComics : HttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(10.seconds)
        .readTimeout(30.seconds)
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comics/series/page/$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".comics-grid .comic-card").mapNotNull { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = document.selectFirst("select.mobilePageSelector option[selected] ~ option") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun popularMangaFromElement(element: Element): SManga? {
        val titleLink = element.selectFirst(".comic-title-link") ?: element.selectFirst("a") ?: return null
        val url = titleLink.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val ruTitle = element.selectFirst(".comic-title-ru")?.text()
        val enTitle = element.selectFirst(".comic-title-en")?.text()
        val title = ruTitle.takeUnless { it.isNullOrEmpty() }
            ?: enTitle.takeUnless { it.isNullOrEmpty() }
            ?: titleLink.text().takeIf { it.isNotEmpty() } ?: return null

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(url)
            thumbnail_url = element.selectFirst(".comic-image-link img")?.absUrl("src")
                ?: element.selectFirst(".comic-thumb img, .cover-series img")?.absUrl("src")
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/comics/online/page/$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".comics-grid .comic-card").mapNotNull { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = document.selectFirst("select.mobilePageSelector option[selected] ~ option") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            // The site has no real search backend (/map ignores the search param),
            // so filtering and pagination happen client-side in searchMangaParse.
            // Round-trip query and page through the request URL for the parser.
            val url = "$baseUrl/map".toHttpUrl().newBuilder()
                .addQueryParameter("search", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is GetEventsList -> {
                    if (filter.state > 0) {
                        return GET("$baseUrl$PATH_EVENTS", headers)
                    }
                }
                is Publishers -> {
                    if (filter.state > 0) {
                        val publisherName = getPublishersList()[filter.state].url
                        return GET("$baseUrl$PATH_PUBLISHERS/$publisherName/page/$page", headers)
                    }
                }
                else -> {}
            }
        }
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        if (response.request.url.encodedPath.contains(PATH_EVENTS)) {
            val mangas = document.select(".events-grid .event-card, .list_events").mapNotNull { element ->
                val a = element.selectFirst("a") ?: return@mapNotNull null
                val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val title = element.selectFirst(".comic-title-ru, .event-title")?.text()?.takeIf { it.isNotEmpty() }
                    ?: a.text().takeIf { it.isNotEmpty() } ?: return@mapNotNull null

                SManga.create().apply {
                    setUrlWithoutDomain(url)
                    this.title = title
                    thumbnail_url = element.selectFirst("img")?.absUrl("src")
                }
            }
            return MangasPage(mangas, false)
        }

        if (response.request.url.encodedPath.contains("/map")) {
            val queryLower = response.request.url.queryParameter("search")?.lowercase().orEmpty()
            val queryTokens = QUERY_TOKEN_REGEX.findAll(queryLower).map { it.value }.toList()
            if (queryTokens.isEmpty()) return MangasPage(emptyList(), false)

            // Filter before dedup: /map lists every series twice (RU and EN titles
            // sharing one slug), so a query matching only one variant must survive.
            val filtered = document.select("a[href^=/comics/series/]").mapNotNull { a ->
                val href = a.attr("href")
                if (!href.startsWith("/comics/series/")) return@mapNotNull null
                val title = a.text().trim()
                if (title.isEmpty()) return@mapNotNull null
                val titleLower = title.lowercase()
                val hrefLower = href.lowercase()
                if (queryTokens.all { token -> titleLower.contains(token) || hrefLower.contains(token) }.not()) {
                    return@mapNotNull null
                }

                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                }
            }.distinctBy { it.url }

            val page = response.request.url.queryParameter("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val fromIndex = (page - 1) * SEARCH_PAGE_SIZE
            if (fromIndex >= filtered.size) return MangasPage(emptyList(), false)
            val toIndex = minOf(fromIndex + SEARCH_PAGE_SIZE, filtered.size)
            return MangasPage(filtered.subList(fromIndex, toIndex), toIndex < filtered.size)
        }

        val mangas = document.select(".comics-grid .comic-card").mapNotNull { element ->
            popularMangaFromElement(element)
        }.distinctBy { it.url }
        val hasNextPage = document.selectFirst("select.mobilePageSelector option[selected] ~ option") != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val titleid = url.pathSegments[2]
            return fetchSearchManga(page, "$PREFIX_SLUG_SEARCH$titleid", filters)
        }
        if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_SLUG_SEARCH)
            return client.newCall(GET("$baseUrl$PATH_URL$realQuery", headers))
                .asObservableSuccess()
                .map { response ->
                    val details = mangaDetailsParse(response)
                    details.url = PATH_URL + realQuery
                    MangasPage(listOf(details), false)
                }
        }
        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .flatMap { response ->
                val mangasPage = searchMangaParse(response)
                if (mangasPage.mangas.none { it.thumbnail_url.isNullOrEmpty() }) {
                    return@flatMap Observable.just(mangasPage)
                }
                // /map entries carry no covers, fetch them with bounded parallelism.
                // Results that already have thumbnails (filter/browse searches) skip this.
                Observable.from(mangasPage.mangas.mapIndexed { index, manga -> index to manga })
                    .flatMap({ (index, manga) ->
                        Observable.fromCallable {
                            if (manga.thumbnail_url.isNullOrEmpty()) {
                                try {
                                    client.newCall(GET("$baseUrl${manga.url}", headers)).execute().use { detailResponse ->
                                        manga.thumbnail_url = mangaDetailsParse(detailResponse).thumbnail_url
                                    }
                                } catch (e: Exception) {
                                    // keep the result, just without a cover
                                }
                            }
                            index to manga
                        }.subscribeOn(Schedulers.io())
                    }, COVER_FETCH_CONCURRENCY)
                    .toList()
                    .map { enriched ->
                        MangasPage(enriched.sortedBy { it.first }.map { it.second }, mangasPage.hasNextPage)
                    }
            }
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        val isIssue = document.selectFirst(".issue-info-grid") != null
        if (isIssue) {
            title = document.selectFirst(".issue-info h1")?.text() ?: ""
            thumbnail_url = document.selectFirst(".issue-cover img")?.absUrl("src")

            val labels = document.select(".issue-info-grid .issue-info-label")
            val values = document.select(".issue-info-grid .issue-info-value")
            val infoMap = labels.mapIndexed { i, el ->
                el.text().removeSuffix(":") to (values.getOrNull(i)?.text() ?: "")
            }.toMap()
            author = infoMap["Издательство"]
        } else {
            val titleRu = document.selectFirst(".series-main h1")?.text()
            val titleEn = document.selectFirst(".series-main h2")?.text()
            title = titleRu ?: titleEn ?: ""
            thumbnail_url = document.selectFirst(".cover-series img, .cover-series-mobile img")?.absUrl("src")

            val labels = document.select(".series-info-grid .label")
            val values = document.select(".series-info-grid .value")
            val infoMap = labels.mapIndexed { i, el ->
                el.text().removeSuffix(":") to (values.getOrNull(i)?.text() ?: "")
            }.toMap()
            author = infoMap["Издательство"]

            description = buildString {
                if (!titleEn.isNullOrEmpty()) append(titleEn).append("\n\n")
                document.selectFirst(".series-description")?.text()?.let { append(it) }
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapters = mutableListOf<SChapter>()
        var url = baseUrl + manga.url

        while (url.isNotBlank()) {
            val request = GET(url, headers)
            val response = client.newCall(request).execute()
            val document = response.asJsoup()

            val isIssue = document.selectFirst(".issue-info-grid") != null
            if (isIssue) {
                val chapter = SChapter.create().apply {
                    name = document.selectFirst(".issue-info h1")?.text() ?: "Глава"
                    val match = CHAPTER_NUMBER_REGEX.find(name)
                    if (match != null) {
                        chapter_number = match.groupValues[1].toFloatOrNull() ?: 1f
                    }

                    val readBtn = document.selectFirst(".btn-read-online-issues")
                    if (readBtn != null) {
                        setUrlWithoutDomain(readBtn.absUrl("href"))
                    } else {
                        setUrlWithoutDomain(manga.url)
                    }
                }
                chapters.add(chapter)
                break
            }

            document.select(".comics-grid .comic-card").forEach { element ->
                chapters.add(chapterFromElement(element))
            }

            val nextOption = document.selectFirst("select.mobilePageSelector option[selected] ~ option")
            url = nextOption?.absUrl("value") ?: ""
        }

        chapters.reversed()
    }

    private fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val readUrl = element.selectFirst(".buttons-grid a:contains(Читать)")?.absUrl("href")?.ifEmpty { null }
            ?: element.selectFirst(".comic-title-link")?.absUrl("href") ?: ""
        chapter.setUrlWithoutDomain(readUrl)

        val ruTitle = element.selectFirst(".comic-title-ru")?.text() ?: ""
        val enTitle = element.selectFirst(".comic-title-en")?.text() ?: ""
        chapter.name = ruTitle.ifEmpty { enTitle }

        val match = CHAPTER_NUMBER_REGEX.find(chapter.name)
        if (match != null) {
            chapter.chapter_number = match.groupValues[1].toFloatOrNull() ?: -1f
        }

        return chapter
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val options = document.select("select.mobilePageSelector option")
        if (options.isNotEmpty()) {
            return options.mapIndexed { i, option ->
                Page(i, url = option.absUrl("value"))
            }
        }

        val html = document.html()
        val match = PAGINATOR_REGEX.find(html)
        if (match != null) {
            val totalPages = match.groupValues[1].toIntOrNull() ?: 1
            val basePath = match.groupValues[2]
            return (1..totalPages).mapIndexed { i, pageNum ->
                Page(i, url = "$baseUrl$basePath$pageNum")
            }
        }

        return listOf(Page(0, url = document.location()))
    }

    override fun imageUrlParse(response: Response): String {
        val document = response.asJsoup()
        return document.selectFirst(".image_online, #b_image, #image")?.absUrl("src") ?: ""
    }

    override fun getFilterList() = FilterList(
        Publishers(publishersName),
        GetEventsList(),
    )

    companion object {
        const val PREFIX_SLUG_SEARCH = "slug:"
        private const val PATH_URL = "/comics/series/"
        private const val PATH_PUBLISHERS = "/comics/publishers"
        private const val PATH_EVENTS = "/comics/events"
        private const val SEARCH_PAGE_SIZE = 30
        private const val COVER_FETCH_CONCURRENCY = 5
        private val QUERY_TOKEN_REGEX = "[\\p{L}\\p{N}]+".toRegex()

        private val ISSUE_REGEX = "-\\d+/?$".toRegex()
        private val CHAPTER_NUMBER_REGEX = "№\\s*(\\d+(?:\\.\\d+)?)".toRegex()
        private val PAGINATOR_REGEX = "new Paginator\\(['\"].*?['\"],\\s*(\\d+),\\s*\\d+,\\s*\\d+,\\s*['\"](.*?)['\"]\\)".toRegex()
    }
}
