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
        element.selectFirst("a")!!.run {
            setUrlWithoutDomain(absUrl("href"))
            title = attr("title").ifBlank { element.selectFirst(".img-overlay > p")!!.text() }
        }
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
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun channelGridApiUrl(channel: String, sort: String) = "$baseUrl/gridapi/?content=$channel&sort=$sort"

    /** The /gridapi/ URL a listing page sets for its infinite scroll, or null when the listing has one page. */
    private fun Document.gridApiUrl(): String? = selectFirst("script:containsData(apiUrl)")
        ?.let { API_URL_REGEX.find(it.data())?.groupValues?.get(1) }
        ?.takeIf(String::isNotEmpty)

    /** Fetches page [page] of the listing at [path] (relative to [baseUrl]) through the /gridapi/ URL on its first page. */
    protected suspend fun getListingPage(path: String, page: Int): MangasPage {
        val mangaFromElement = listingMangaFromElement(path)
        val document = client.get("$baseUrl/$path").asJsoup()
        val apiUrl = document.gridApiUrl()
            ?: return MangasPage(document.select(popularMangaSelector()).map(mangaFromElement), false)
        return getGridPage(apiUrl, page, mangaFromElement)
    }

    /** Maps the tiles of the listing at [path]: model listings show models, the rest show galleries. */
    protected open fun listingMangaFromElement(path: String): (Element) -> SManga = if (path.startsWith("model")) ::modelMangaFromElement else ::popularMangaFromElement

    override suspend fun getPopularManga(page: Int): MangasPage = getGridPage(channelGridApiUrl("updates", "popular"), page)

    // Not /archive/: Cloudflare serves it days stale on most sites, and only placeholders on some
    override suspend fun getLatestUpdates(page: Int): MangasPage = getGridPage(channelGridApiUrl("updates", "newest"), page)

    /**
     * The /gridapi/ content browsed for popular/trending/newest:
     * - models
     * - updates (galleries)
     *
     * @param searchType is value of [searchTypeOptions]
     */
    protected open fun getBrowseChannelUri(searchType: String): String = when (searchType) {
        "model" -> "models"
        else -> "updates"
    }

    protected open val searchTypeOptions = listOf(
        Pair("Galleries", "post"),
        Pair("Models", "model"),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchType = filters.firstInstanceOrNull<SearchTypeFilter>()?.selected ?: searchTypeOptions.first().second
        if (query.isNotEmpty()) {
            val url = "$baseUrl/search/$searchType/".toHttpUrl().newBuilder()
                .addPathSegment(query.trim())
                .addPathSegments("mpage/$page/")
                .build()
            return parseSearchMangaList(client.get(url).asJsoup())
        }

        val sort = (filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()).selected
        // Filter listings only sort by popular or newest
        val order = sort.takeIf { it == "newest" } ?: "popular"
        val tags = (filters.firstInstanceOrNull<TagsFilter>() ?: TagsFilter()).state.filter { it.state }
        val modelTags = (filters.firstInstanceOrNull<ModelTagsFilter>() ?: ModelTagsFilter()).state.filter { it.state }
        val modelAgeFilter = filters.firstInstanceOrNull<AgesFilter>() ?: AgesFilter()
        val modelCountries = (filters.firstInstanceOrNull<ModelCountriesFilter>() ?: ModelCountriesFilter()).state.filter { it.state }

        val path = when {
            tags.size == 1 -> "tag/${tags.first().uriPart}/sort/$sort/"

            tags.isNotEmpty() ->
                "sort/filter/ord/$order/content/0/quality/0/tags/${tags.joinToString("+") { it.uriPart }}/mpage/1/"

            // Some model-tag won't support trending
            modelTags.size == 1 && modelAgeFilter.state == 0 && modelCountries.isEmpty() ->
                "model-tag/${modelTags.first().uriPart}/sort/$sort/"

            modelTags.isNotEmpty() || modelAgeFilter.state != 0 || modelCountries.isNotEmpty() -> {
                val countries = modelCountries.joinToString("+") { it.uriPart }.ifEmpty { "0" }
                val tagParts = modelTags.joinToString("+") { it.uriPart }.ifEmpty { "0" }
                "models/sort/filter/ord/$order/age/${modelAgeFilter.selected}/country/$countries/tags/$tagParts/mpage/1/"
            }

            else -> {
                val channel = getBrowseChannelUri(searchType)
                return getGridPage(channelGridApiUrl(channel, sort), page, listingMangaFromElement(channel))
            }
        }
        return getListingPage(path, page)
    }

    protected open fun parseSearchMangaList(document: Document): MangasPage {
        val mangaFromElement = when {
            // Text search for models (/search/model/) returns model entries
            document.location().toHttpUrl().pathSegments.any { it == "model" || it == "models" || it == "model-tag" } -> ::modelMangaFromElement

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

    // The models filter is only on the models filter listing, not on /models/
    protected open suspend fun fetchModelTagsAndCountries(): Pair<List<Tag>, List<Country>> {
        val document = client.get("$baseUrl/models/sort/filter/ord/popular/age/0/country/0/tags/0/mpage/1/").asJsoup()
        val modelTags = document.select("#filter-b section[data-slot=tags] label")
            .map {
                Tag(
                    "M: " + it.select(".txt").text(),
                    it.select("input").attr("value"),
                )
            }
        val modelCountries = document.select("#filter-b section[data-slot=country] label")
            .map {
                Country(
                    it.select(".txt").text(),
                    it.select("input").attr("value"),
                )
            }
        return modelTags to modelCountries
    }

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val tagsDeferred = async { fetchTags() }
        val modelDeferred = async { fetchModelTagsAndCountries() }

        val tags = tagsDeferred.await()
        val (modelTags, modelCountries) = modelDeferred.await()

        MasonryFilterData(tags, modelTags, modelCountries).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<MasonryFilterData>()

        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Below filters are ignored when doing text search except Sort\nText search only support Galleries & Models"),
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
            filters += ModelTagsFilter(filterData.modelTags)
            filters += ModelCountriesFilter(filterData.modelCountries)
        }
        filters += AgesFilter()
        filters += Filter.Separator()
        filters += Filter.Header("Open WebView to watch video (chapter with only 1 photo)")

        return FilterList(filters)
    }

    /* Manga details & chapters */

    // TODO: Related & deep-links; fix next page or implement new support; more sites/links

    /** Resolves a pasted gallery (/<slug>/) or model (/model/<slug>/) link of this site. */
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter(String::isNotEmpty)
        val isGallery = segments.size == 1
        val isModel = segments.size == 2 && segments.first() == "model"
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.") || !(isGallery || isModel)) return null

        val manga = SManga.create().apply { this.url = "/${segments.joinToString("/")}/" }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
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
        // Details and chapters both come from the same gallery page - fetch it once regardless of flags.
        val document = client.get(getMangaUrl(manga)).asJsoup()
        SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = parseChapterList(document, manga),
        )
    }

    protected open suspend fun fetchModelChapterList(manga: SManga): List<SChapter> {
        val url = getMangaUrl(manga).toHttpUrl().newBuilder().apply {
            /* Must use sort/latest to get correct title (instead of description),
              also will list chapters in timely manner */
            addPathSegments("sort/latest")
        }.build()
        val document = client.get(url).asJsoup()
        // The page only renders the latest galleries, the rest load from its /gridapi/ infinite scroll
        val apiUrl = document.gridApiUrl() ?: return parseChapterList(document, manga)
        val chapters = mutableListOf<SChapter>()
        var page = 1
        do {
            val tiles = fetchGridTiles(apiUrl, page++)
            tiles.mapTo(chapters, ::modelChapterFromElement)
        } while (tiles.isNotEmpty())
        return chapters
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
                }
                    ?.select("ul.list-inline li")
                    ?.eachText()?.joinToString()
                description = "$stats\n" +
                    select("p.read-more, div.module-more > p, div.module-more ul li")
                        .eachText().joinToString("\n")
            }
            genre = (
                listOf(artist) + document.select("article.module-model + p a[href*=/model-tag/]")
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

    protected open fun pageListParse(document: Document): List<Page> = document.select(".list-gallery a[href^=https://cdn.], $videoSelector")
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
    }
}
