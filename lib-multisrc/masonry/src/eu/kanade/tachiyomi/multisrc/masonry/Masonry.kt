package eu.kanade.tachiyomi.multisrc.masonry

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
    protected open val useAlternativeLatestRequest = false

    /**
     * /updates/sort/popular/ doesn't support pages on all sites so we use filter instead
     * Some time, it has a bit different content comparing to filter so we still query it
     */
    protected open fun popularMangaUrl(page: Int): String = when (page) {
        1 -> baseUrl
        2 -> "$baseUrl/updates/sort/popular/"
        else -> "$baseUrl/updates/sort/filter/ord/popular/content/0/quality/0/tags/0/mpage/${page - 2}/"
    }

    /**
     * Archive is sorted as post's ID
     *
     * Newest is sorted as post's date
     *
     * /updates/sort/newest/ is similar to
     *   => /updates/sort/newest/mpage/1/ (a bit out of sync)
     *   => /archive/
     *   => /archive/page/1/
     *   => /updates/sort/filter/ord/newest/content/0/quality/0/tags/0/
     *   => /updates/sort/filter/ord/newest/content/0/quality/0/tags/0/mpage/1/
     *
     * /updates/sort/newest/mpage/2/ is similar to
     *   => /archive/page/2/
     *   => /updates/sort/filter/ord/newest/content/0/quality/0/tags/0/mpage/2/
     *
     * Some sites don't support page for /updates/sort/newest/
     *  - JoyMii
     *  - XArt (doesn't have any content at all)
     *  - FemJoy
     *  - FTVHunter
     * This URL is often not showing consistent contents
     */
    protected open fun latestUpdatesUrl(page: Int): String = if (useAlternativeLatestRequest) {
        "$baseUrl/updates/sort/newest/mpage/$page/"
    } else {
        "$baseUrl/archive/page/$page/"
    }

    protected open val galleryListSelector = ".list-gallery:not(.static)"
    protected open val gallerySelector = "figure"
    protected open val videoTitleSelector = ".icon-play, a[href*='/video/']"
    protected open val videoSelector = "video[poster^=https://cdn.]"
    protected open fun popularMangaSelector() = "$galleryListSelector $gallerySelector"

    // Add fake selector for updates/sort/popular because it only has 1 page
    protected open fun popularMangaNextPageSelector() = ".pagination-a li.next, main#content .link-btn a.overlay-b[href='/sort/popular/']"

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

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(popularMangaUrl(page)).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(latestUpdatesUrl(page)).asJsoup()
        return parseMangaList(document)
    }

    /**
     * The Uri used to browse for popular/trending/newest galleries:
     * - <domain>/models/
     * - <domain>/updates/ (redirected to <domain>/)
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
        val url = if (query.isNotEmpty()) {
            "$baseUrl/search/$searchType/".toHttpUrl().newBuilder()
                .addPathSegment(query.trim())
                .addPathSegments("mpage/$page/")
                .build()
        } else {
            val sortFilter = filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()
            val tagsFilter = filters.firstInstanceOrNull<TagsFilter>() ?: TagsFilter()
            val modelTagsFilter = filters.firstInstanceOrNull<ModelTagsFilter>() ?: ModelTagsFilter()
            val modelAgeFilter = filters.firstInstanceOrNull<AgesFilter>() ?: AgesFilter()
            val modelCountriesFilter =
                filters.firstInstanceOrNull<ModelCountriesFilter>() ?: ModelCountriesFilter()

            baseUrl.toHttpUrl().newBuilder().apply {
                when {
                    tagsFilter.state.any { it.state } -> {
                        val tags = tagsFilter.state.filter { it.state }
                        if (tags.size == 1) {
                            // Use /tag/ for single tag
                            addPathSegment("tag")
                            addPathSegment(tags.joinToString("+") { it.uriPart })
                            sortFilter.getUriPartIfNeeded("tag").also {
                                if (it.isBlank()) {
                                    addPathSegments("page/$page/")
                                } else {
                                    addPathSegments("sort/$it")
                                    addPathSegments("mpage/$page/")
                                }
                            }
                        } else {
                            // Use: /updates/sort/filter/ord/<popular|newest>/content/<pix/vid>/quality/0/tags/<tag>+<tag>/mpage/<#>/
                            addPathSegments("updates/sort/filter/ord")
                            addPathSegment(sortFilter.selected.takeIf { it == "newest" } ?: "popular")
                            addPathSegments("content/0/quality/0")
                            addPathSegment("tags")
                            addPathSegment(tags.joinToString("+") { it.uriPart })
                            addPathSegments("mpage/$page/")
                        }
                    }

                    modelTagsFilter.state.any { it.state } ||
                        modelAgeFilter.state != 0 ||
                        modelCountriesFilter.state.any { it.state } -> {
                        val modelTags = modelTagsFilter.state.filter { it.state }

                        if (modelTags.size == 1 && modelAgeFilter.state == 0 &&
                            modelCountriesFilter.state.none { it.state }
                        ) {
                            // Use: /model-tag/, only support single tag
                            // Some model-tag won't support trending
                            addPathSegment("model-tag")
                            addPathSegment(modelTags.first { it.state }.uriPart)
                            sortFilter.getUriPartIfNeeded("model-tag").also {
                                if (it.isBlank()) {
                                    addPathSegments("page/$page/")
                                } else {
                                    addPathSegments("sort/$it")
                                    addPathSegments("mpage/$page/")
                                }
                            }
                        } else {
                            // Use: /models/sort/filter/ord/<popular|newest>/age/<#>/country/<name>+<name>/tags/<tag>+<tag>/mpage/<#>/
                            val modelCountries = modelCountriesFilter.state.filter { it.state }
                            addPathSegments("models/sort/filter/ord")
                            addPathSegment(sortFilter.selected.takeIf { it == "newest" } ?: "popular")
                            addPathSegment("age")
                            addPathSegment(modelAgeFilter.selected)
                            addPathSegment("country")
                            if (modelCountries.isEmpty()) {
                                addPathSegment("0")
                            } else {
                                addPathSegment(modelCountries.joinToString("+") { it.uriPart })
                            }
                            addPathSegment("tags")
                            addPathSegment(modelTags.joinToString("+") { it.uriPart })
                            addPathSegments("mpage/$page/")
                        }
                    }

                    else -> {
                        val channel = getBrowseChannelUri(searchType)
                        if (sortFilter.selected == "trending" || channel != "updates") {
                            // Trending: use /updates/sort/ since it won't be available with site's search
                            addPathSegment(channel)
                            sortFilter.getUriPartIfNeeded(channel).also {
                                // Only EliteBabes & MetArt supports Pages for updates/sort/trending
                                if (it.isBlank()) {
                                    addPathSegments("page/$page/")
                                } else {
                                    addPathSegments("sort/$it")
                                    addPathSegments("mpage/$page/")
                                }
                            }
                        } else {
                            when (sortFilter.selected) {
                                "newest" -> {
                                    // Using a more effective request comparing to the /updates/sort/newest/ (some sites doesn't support)
                                    if (useAlternativeLatestRequest) {
                                        addPathSegments("updates/sort/newest/mpage/$page")
                                    } else {
                                        addPathSegments("archive/page/$page/")
                                    }
                                }

                                "popular" -> {
                                    // Using a more effective request comparing to the /updates/sort/popular/ (doesn't support page)
                                    when (page) {
                                        1 -> addPathSegment("")
                                        2 -> addPathSegments("updates/sort/popular")
                                        else -> addPathSegments("updates/sort/filter/ord/popular/content/0/quality/0/tags/0/mpage/${page - 2}")
                                    }
                                }
                            }
                        }
                    }
                }
            }.build()
        }

        val document = client.get(url).asJsoup()
        return parseSearchMangaList(document)
    }

    protected open fun parseSearchMangaList(document: Document): MangasPage {
        val mangaFromElement = when {
            /* Support all three:
             - models browsing /models/ to make each model a title with multiple chapters of her galleries
             - model search /search/model/ to show each gallery as a separated title (just like normal browsing)
             - and model-tag
              They all return model-entries */
            document.location().toHttpUrl().pathSegments.any { it == "model" || it == "models" || it == "model-tag" } -> ::modelMangaFromElement

            else -> ::popularMangaFromElement
        }

        return parseMangaList(document, mangaFromElement)
    }

    /* Filters */

    override val supportsFilterFetching = true

    protected open suspend fun fetchTags(): List<Tag> = client.get("$baseUrl/updates/").asJsoup()
        .select("#filter-a span[data-placeholder='Tags'] span:has(> input)")
        .map {
            Tag(
                it.select("label").text(),
                it.select("input").attr("value"),
            )
        }

    protected open suspend fun fetchModelTagsAndCountries(): Pair<List<Tag>, List<Country>> {
        val document = client.get("$baseUrl/models/").asJsoup()
        val modelTags = document.select("#filter-b span[data-placeholder='Tags'] span:has(> input)")
            .map {
                Tag(
                    "M: " + it.select("label").text(),
                    it.select("input").attr("value"),
                )
            }
        val modelCountries = document.select("#filter-b span[data-placeholder='Country'] span:has(> input)")
            .map {
                Country(
                    it.select("label").text(),
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
            filters += AgesFilter()
            filters += Filter.Separator()
            filters += Filter.Header("Open WebView to watch video (chapter with only 1 photo)")
        }

        return FilterList(filters)
    }

    /* Manga details & chapters */

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
        return parseChapterList(document, manga)
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
}
