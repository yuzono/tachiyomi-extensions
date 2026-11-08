package eu.kanade.tachiyomi.multisrc.masonry

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * Features:
 * - Better popular/latest browsing, support Popular with more pages.
 * - Support browse/search for models & model's collection (/models/)
 * - Support model's tags filter (/model-tag/)
 * - Support advanced models filter
 * - Support multiple tags filter
 * - Support Collections (/collections/), Boards (/pinboard/) & text search for Pins
 * - Support video via WebView
 */
abstract class Masonry : KeiSource() {
    protected open val galleryListSelector = ".list-gallery:not(.static)"
    protected open val gallerySelector = "figure"
    protected open val videoTitleSelector = ".icon-play, a[href*='/video/']"
    protected open val videoSelector = "video[poster^=https://cdn.]"
    protected open fun popularMangaSelector() = "$galleryListSelector $gallerySelector"
    protected open fun popularMangaNextPageSelector() = ".pagination-a li.next"

    protected open fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a")!!
        // Video tiles on some sites link to a sister video site (xerotica.com), their overlay links this site's gallery
        val galleryLink = link.takeIf { it.absUrl("href").startsWith(baseUrl) }
            ?: element.selectFirst(".img-overlay > p > a")
            ?: link
        setUrlWithoutDomain(galleryLink.absUrl("href"))
        title = link.attr("title").ifBlank { element.selectFirst(".img-overlay > p")!!.text() }
        thumbnail_url = element.selectFirst("a img")?.imgAttr()
    }

    protected open fun modelMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.selectFirst(".img-overlay > p > a")!!.run {
            val model = text()
            artist = model
            author = name
            title = "$model @$name"
            setUrlWithoutDomain(absUrl("href"))
        }
        element.selectFirst("a > img")?.run {
            thumbnail_url = imgAttr()
        }
        status = SManga.ONGOING
        update_strategy = UpdateStrategy.ALWAYS_UPDATE
    }

    protected open fun collectionMangaFromElement(element: Element): SManga = popularMangaFromElement(element).apply {
        status = SManga.ONGOING
        update_strategy = UpdateStrategy.ALWAYS_UPDATE
    }

    // Pins no longer have their own page (/pin/<id>/ is 404), so list the gallery each pin comes from
    protected open fun pinMangaFromElement(element: Element): SManga? {
        val sourceGallery = element.selectFirst(".list-icon a:has(.icon-image)") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(sourceGallery.absUrl("href"))
            element.selectFirst("figure > a > img")!!.run {
                title = attr("alt")
                thumbnail_url = imgAttr()
            }
            artist = element.select(".list-icon a:has(.icon-woman)").eachText().joinToString()
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    protected open fun parseMangaList(document: Document, mangaFromElement: (Element) -> SManga = ::popularMangaFromElement): MangasPage {
        val mangas = document.select(popularMangaSelector()).map(mangaFromElement)
        val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    /**
     * Listings only render their first page and load the rest from /gridapi/, the site's infinite scroll,
     * which returns bare tiles and nothing past the last page. Text search still uses page links.
     */
    private suspend fun fetchGridTiles(apiUrl: String, page: Int): Elements = client.get("$apiUrl&mpage=$page", Fetch.XHR).asJsoup().select(gallerySelector)

    private suspend fun getGridPage(apiUrl: String, page: Int, mangaFromElement: (Element) -> SManga = ::popularMangaFromElement): MangasPage {
        val mangas = fetchGridTiles(apiUrl, page).map(mangaFromElement)
        // The app shows an empty page as a "No results found" error, so stop at the short last page instead of fetching the empty one after it
        return MangasPage(mangas, mangas.size >= GRID_PAGE_SIZE)
    }

    private fun channelGridApiUrl(channel: String, sort: String) = "$baseUrl/gridapi/?content=$channel&sort=$sort"

    /** The /gridapi/ URL a listing page sets for its infinite scroll, or null when the listing has one page. */
    private fun Document.gridApiUrl(): String? = selectFirst("script:containsData(apiUrl)")
        ?.let { API_URL_REGEX.find(it.data())?.groupValues?.get(1) }
        ?.takeIf(String::isNotEmpty)

    /** The /gridapi/ URL of the last listing browsed, keyed by its path, so its next pages don't fetch its first page again. */
    @Volatile
    private var lastListingApiUrl: Pair<String, String>? = null

    /** Fetches page [page] of the listing at [path] (relative to [baseUrl]) through the /gridapi/ URL on its first page. */
    protected suspend fun getListingPage(path: String, page: Int): MangasPage {
        val mangaFromElement = listingMangaFromElement(path)
        val apiUrl = lastListingApiUrl?.takeIf { page > 1 && it.first == path }?.second ?: run {
            val document = client.get("$baseUrl/$path").asJsoup()
            document.gridApiUrl()
                ?: return MangasPage(document.select(popularMangaSelector()).map(mangaFromElement), false)
        }
        lastListingApiUrl = path to apiUrl
        return getGridPage(apiUrl, page, mangaFromElement)
    }

    /** Maps the tiles of the listing at [path]: model and collection listings show those, the rest show galleries. */
    protected open fun listingMangaFromElement(path: String): (Element) -> SManga = when {
        path.startsWith("model") -> ::modelMangaFromElement
        path.startsWith("collections") -> ::collectionMangaFromElement
        else -> ::popularMangaFromElement
    }

    override suspend fun getPopularManga(page: Int): MangasPage = getGridPage(channelGridApiUrl("updates", "popular"), page)

    // Not /archive/: Cloudflare serves it days stale on most sites, and only placeholders on some
    override suspend fun getLatestUpdates(page: Int): MangasPage = getGridPage(channelGridApiUrl("updates", "newest"), page)

    /**
     * The /gridapi/ content browsed for popular/trending/newest:
     * - models
     * - collections
     * - pins (no listing anymore, pins only come from text search)
     * - updates (galleries)
     *
     * @param searchType is value of [searchTypeOptions]
     */
    protected open fun getBrowseChannelUri(searchType: String): String = when (searchType) {
        "model" -> "models"
        "collection" -> "collections"
        "list_item" -> "pins"
        else -> "updates"
    }

    protected open val searchTypeOptions = listOf(
        Pair("Galleries", "post"),
        Pair("Models", "model"),
        Pair("Collections", "collection"),
        Pair("Pins", "list_item"),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val boardFilter = filters.firstInstanceOrNull<BoardFilter>() ?: BoardFilter()
        val sort = (filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()).selected
        if (boardFilter.selected.isNotEmpty()) {
            return getSearchPage(page) { p ->
                val pagePath = if (p > 1) "mpage/$p/" else ""
                "$baseUrl/pinboard/${boardFilter.selected}/sort/$sort/$pagePath".toHttpUrl()
            }
        }

        val searchType = filters.firstInstanceOrNull<SearchTypeFilter>()?.selected ?: searchTypeOptions.first().second
        if (query.isNotBlank()) {
            return getSearchPage(page) { p ->
                "$baseUrl/search/$searchType/".toHttpUrl().newBuilder()
                    .addPathSegment(query.trim())
                    .addPathSegments("mpage/$p/")
                    .build()
            }
        }

        // Filter listings only sort by popular or newest
        val order = sort.takeIf { it == "newest" } ?: "popular"
        val tags = (filters.firstInstanceOrNull<TagsFilter>() ?: TagsFilter()).state.filter { it.state }
        val modelFilters = filters.filterIsInstance<ModelFilter>()
        fun checkedModelValues(slot: String) = modelFilters.filter { it.slot == slot }
            .flatMap { group -> group.state.filter { it.state }.map { it.uriPart } }
        val modelTags = checkedModelValues("tags")
        val modelAgeFilter = filters.firstInstanceOrNull<AgesFilter>() ?: AgesFilter()
        val modelCountries = checkedModelValues("country")

        val path = when {
            tags.size == 1 -> "tag/${tags.first().uriPart}/sort/$sort/"

            tags.isNotEmpty() ->
                "sort/filter/ord/$order/content/0/quality/0/tags/${tags.joinToString("+") { it.uriPart }}/mpage/1/"

            // Some model-tag won't support trending
            modelTags.size == 1 && modelAgeFilter.state == 0 && modelCountries.isEmpty() ->
                "model-tag/${modelTags.first()}/sort/$sort/"

            modelTags.isNotEmpty() || modelAgeFilter.state != 0 || modelCountries.isNotEmpty() -> {
                val countries = modelCountries.joinToString("+").ifEmpty { "0" }
                val tagParts = modelTags.joinToString("+").ifEmpty { "0" }
                "models/sort/filter/ord/$order/age/${modelAgeFilter.selected}/country/$countries/tags/$tagParts/mpage/1/"
            }

            else -> {
                // The site's video listing (/sort/videos/) is a sort of the galleries
                if (searchType == "video") return getGridPage(channelGridApiUrl("updates", "videos"), page)
                val channel = getBrowseChannelUri(searchType)
                // /gridapi/ lists no pins, say why instead of "No results found"
                if (channel == "pins") throw Exception("Pins need a text search")
                return getGridPage(channelGridApiUrl(channel, sort), page, listingMangaFromElement(channel))
            }
        }
        return getListingPage(path, page)
    }

    /**
     * Fetches page [page] of a board or text search, moving on while a page parses to nothing but has a next page:
     * a page whose pins are all videos or have no gallery would otherwise stop the app's paging, as it shows an
     * empty page as an error. The app then asks for the page shown in its place, and drops the repeated entries.
     */
    private suspend fun getSearchPage(page: Int, url: (Int) -> HttpUrl): MangasPage {
        var current = page
        while (true) {
            val result = parseSearchMangaList(client.get(url(current)).asJsoup())
            if (result.mangas.isNotEmpty() || !result.hasNextPage) return result
            current++
        }
    }

    protected open fun parseSearchMangaList(document: Document): MangasPage {
        val segments = document.location().toHttpUrl().pathSegments
        // Boards are /pinboard/<slug>/, text search is /search/<type>/<query>/: the slug or query can be any word
        val listing = if (segments.first() == "search") segments[1] else segments.first()
        // Boards and pin search both list pins
        if (listing == "pinboard" || listing == "list_item") {
            val mangas = document.select("$galleryListSelector > li:not(:has($videoTitleSelector))")
                .mapNotNull(::pinMangaFromElement)
                .distinctBy { it.url }
            val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
            return MangasPage(mangas, hasNextPage)
        }

        val mangaFromElement = when (listing) {
            "model" -> ::modelMangaFromElement
            "collection" -> ::collectionMangaFromElement
            else -> ::popularMangaFromElement
        }

        return parseMangaList(document, mangaFromElement)
    }

    /* Filters */

    override val supportsFilterFetching = true

    protected open suspend fun fetchTags(): List<Tag> = client.get("$baseUrl/updates/").asJsoup()
        .select("#filter-a section[data-slot=tags] label")
        .map {
            Tag(
                it.select(".txt").text(),
                it.select("input").attr("value"),
            )
        }

    // The models filter is only on the models filter listing, not on /models/. Age has its own fixed filter.
    protected open suspend fun fetchModelFilters(): List<ModelFilterGroup> = client.get("$baseUrl/models/sort/filter/ord/popular/age/0/country/0/tags/0/mpage/1/").asJsoup()
        .select("#filter-b section[data-slot=tags], #filter-b section[data-slot=country]")
        .map { section ->
            ModelFilterGroup(
                name = section.attr("data-label"),
                slot = section.attr("data-slot"),
                options = section.select("label").map {
                    Tag(
                        // Model tags share names with gallery tags (Asian, Blonde...)
                        "M: " + it.select(".txt").text(),
                        it.select("input").attr("value"),
                    )
                },
            )
        }

    // Boards are user-made pinboards, thousands of them, so offer the trending, newest and top ones shown on /pinboards/
    protected open suspend fun fetchBoards(): List<Tag> = client.get("$baseUrl/pinboards/").asJsoup()
        .select(".list-gallery figure > a[href*=/pinboard/]")
        .map {
            Tag(
                it.select("img").attr("alt"),
                it.attr("href").removeSuffix("/").substringAfterLast("/"),
            )
        }
        .distinctBy { it.uriPart }

    protected suspend fun fetchMasonryFilterData(): MasonryFilterData = coroutineScope {
        val tagsDeferred = async { fetchTags() }
        val modelFiltersDeferred = async { fetchModelFilters() }
        val boardsDeferred = async { fetchBoards() }

        MasonryFilterData(tagsDeferred.await(), modelFiltersDeferred.await(), boardsDeferred.await())
    }

    override suspend fun fetchFilterData(): JsonElement = fetchMasonryFilterData().toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<MasonryFilterData>()

        val filters = mutableListOf<Filter<*>>()
        filterData?.boards?.takeIf { it.isNotEmpty() }?.let {
            filters += Filter.Header("Boards supports Sort but ignore Browse/Search, Tags & Models filter")
            filters += BoardFilter(it)
            filters += Filter.Separator()
        }
        filters += listOf(
            Filter.Header("Pins need a text search"),
            SearchTypeFilter(searchTypeOptions),
            Filter.Separator(),
            Filter.Header("Some source might not support Trending"),
            SortFilter(),
            Filter.Separator(),
        )

        if (filterData != null) {
            filters += TagsFilter(filterData.tags)
            filters += Filter.Separator()
            filters += Filter.Header("Model filters are ignored when Tags filter is selected.\nTrending is supported if only 1 Model's tag is selected.")
            filterData.modelFilters.mapTo(filters, ::ModelFilter)
        }
        filters += AgesFilter()
        filters += Filter.Separator()
        filters += Filter.Header("Open WebView to watch video (chapter with only 1 photo)")

        return FilterList(filters)
    }

    /* Manga details & chapters */

    // TODO: Related galleries; more sites/links

    /** Resolves a pasted gallery (/<slug>/), model (/model/<slug>/) or collection (/collections/<slug>/) link of this site. */
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter(String::isNotEmpty)
        val isGallery = segments.size == 1
        val isModelOrCollection = segments.size == 2 && segments.first() in setOf("model", "collections")
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.") || !(isGallery || isModelOrCollection)) return null

        val manga = SManga.create().apply { this.url = "/${segments.joinToString("/")}/" }
        if (isModelOrCollection) return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga

        // Listings such as /updates/, /models/ or a channel share the gallery link shape, only a gallery has photos or a video
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        return document.takeIf { it.selectFirst(pageListSelector()) != null }?.let { doc ->
            parseMangaDetails(doc, manga).apply {
                // Not in parseMangaDetails: the page lacks the cover listings show, so every refresh would swap it for the first photo
                thumbnail_url = doc.selectFirst(".list-gallery a[href^=https://cdn.] img")?.imgAttr()
                    ?: doc.selectFirst(videoSelector)?.absUrl("poster")
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = if (manga.url.contains("/model/")) {
        coroutineScope {
            val detailsDeferred = if (fetchDetails) async { fetchModelMangaDetails(manga) } else null
            val chaptersDeferred = if (fetchChapters) async { fetchModelChapterList(manga) } else null

            SMangaUpdate(
                manga = detailsDeferred?.await() ?: manga,
                chapters = chaptersDeferred?.await() ?: chapters,
            )
        }
    } else {
        // Details and chapters both come from the same gallery or collection page - fetch it once regardless of flags.
        val document = client.get(getMangaUrl(manga)).asJsoup()
        if (manga.url.startsWith("/collections/")) {
            SMangaUpdate(
                manga = collectionMangaDetailsParse(document, manga),
                // Unlike a gallery's, a collection's chapters take a /gridapi/ request per page
                chapters = if (fetchChapters) fetchGridChapterList(document, manga) else chapters,
            )
        } else {
            SMangaUpdate(
                manga = parseMangaDetails(document, manga),
                chapters = parseChapterList(document, manga),
            )
        }
    }

    protected open suspend fun fetchModelChapterList(manga: SManga): List<SChapter> {
        val url = getMangaUrl(manga).toHttpUrl().newBuilder().apply {
            /* Must use sort/latest to get correct title (instead of description),
              also will list chapters in timely manner */
            addPathSegments("sort/latest")
        }.build()
        return fetchGridChapterList(client.get(url).asJsoup(), manga)
    }

    /** Model and collection pages only render their latest galleries, the rest load from their /gridapi/ infinite scroll. */
    private suspend fun fetchGridChapterList(document: Document, manga: SManga): List<SChapter> {
        val apiUrl = document.gridApiUrl() ?: return parseChapterList(document, manga)
        val chapters = mutableListOf<SChapter>()
        var page = 1
        do {
            val tiles = fetchGridTiles(apiUrl, page++)
            tiles.mapTo(chapters, ::modelChapterFromElement)
        } while (tiles.isNotEmpty())
        return chapters
    }

    protected open fun collectionMangaDetailsParse(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        val header = document.selectFirst(".header-model")!!
        title = header.selectFirst("h1")!!.text()
        thumbnail_url = header.selectFirst("figure img")?.imgAttr()
        description = header.select("ul.list-inline li").eachText().joinToString()
        author = document.select("article.module-model a[href*=/author/]").text()
        status = SManga.ONGOING
    }

    protected open fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        document.selectFirst("header#top h1")?.run {
            title = text()
        }
        document.selectFirst("p.link-btn")?.run {
            artist = select("a[href*=/model/]").eachText().joinToString()
            author = selectFirst("a")?.text()
            genre = (
                listOfNotNull(author?.takeIf(String::isNotBlank), artist?.takeIf(String::isNotBlank)) +
                    select("a[href*=/tag/]").eachText()
                ).distinct().joinToString()
        }
        description = document.selectFirst("#content > p")?.text()
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    protected open suspend fun fetchModelMangaDetails(manga: SManga): SManga {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SManga.create().apply {
            url = manga.url
            document.selectFirst("article.module-model")?.run {
                val stats = selectFirst(".header-model")?.also { elm: Element ->
                    val model = elm.selectFirst("h1")?.text() ?: "Model"
                    artist = model
                    title = "$model @$name"
                    // The same portrait as the model's tile in listings
                    thumbnail_url = elm.selectFirst("figure img")?.imgAttr()
                }
                    ?.select("ul.list-inline li")
                    ?.eachText()?.joinToString()
                description = listOfNotNull(
                    stats,
                    select("p.read-more, div.module-more > p, div.module-more ul li").eachText().joinToString("\n"),
                ).joinToString("\n")
            }
            genre = (
                listOfNotNull(artist) + document.select("article.module-model + p a[href*=/model-tag/]")
                    .eachText().map { "M: $it" }
                ).joinToString()
            status = SManga.ONGOING
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    protected open fun parseChapterList(document: Document, manga: SManga): List<SChapter> = document.selectFirst(galleryListSelector)?.run {
        // select separately so if a model doesn't have any content then it will return an empty list
        select(gallerySelector).map(::modelChapterFromElement)
    } ?: document.selectFirst(videoSelector)?.let {
        listOf(
            SChapter.create().apply {
                name = "Video"
                url = manga.url
            },
        )
    } ?: listOf(
        SChapter.create().apply {
            name = "Gallery"
            url = manga.url
        },
    )

    /**
     * This is mainly used for model as a manga with each of her galleries as a chapter
     */
    protected open fun modelChapterFromElement(element: Element): SChapter {
        val isVideo = element.selectFirst(videoTitleSelector) != null
        return SChapter.create().apply {
            // Use img-overlay to get correct set's name without duplicate model's name
            with(element.selectFirst(".img-overlay > p > a")!!) {
                setUrlWithoutDomain(absUrl("href"))
                name = if (isVideo) "Video: ${text()}" else text()
            }
        }
    }

    /* Pages */

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return pageListParse(document)
    }

    protected open fun pageListSelector() = ".list-gallery a[href^=https://cdn.], $videoSelector"

    protected open fun pageListParse(document: Document): List<Page> = document.select(pageListSelector())
        .mapIndexed { idx, img ->
            Page(
                idx,
                imageUrl = with(img) {
                    when {
                        hasAttr("href") -> absUrl("href")
                        hasAttr("poster") -> absUrl("poster")
                        else -> imgAttr()
                    }
                },
            )
        }

    protected fun Element.imgAttr(): String? = when {
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        hasAttr("data-cfsrc") -> attr("abs:data-cfsrc")
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        else -> attr("abs:src")
    }

    companion object {
        private val API_URL_REGEX = Regex("""const apiUrl = '([^']*)'""")

        // Tiles per full /gridapi/ page, the same on every site and listing
        private const val GRID_PAGE_SIZE = 20
    }
}
