package eu.kanade.tachiyomi.multisrc.masonry

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
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
abstract class Masonry(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : ParsedHttpSource() {
    protected open val useAlternativeLatestRequest = false

    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    /**
     * /updates/sort/popular/ doesn't support pages on all sites so we use filter instead
     * Some time, it has a bit different content comparing to filter so we still query it
     */
    override fun popularMangaRequest(page: Int): Request {
        val url = when (page) {
            1 -> baseUrl
            2 -> "$baseUrl/updates/sort/popular/"
            else -> "$baseUrl/updates/sort/filter/ord/popular/content/0/quality/0/tags/0/mpage/${page - 2}/"
        }

        return GET(url, headers)
    }

    protected open val galleryListSelector = ".list-gallery:not(.static)"
    protected open val gallerySelector = "figure"
    protected open val videoTitleSelector = ".icon-play, a[href*='/video/']"
    protected open val videoSelector = "video[poster^=https://cdn.]"
    override fun popularMangaSelector() = "$galleryListSelector $gallerySelector"

    // Add fake selector for updates/sort/popular because it only has 1 page
    override fun popularMangaNextPageSelector() = ".pagination-a li.next, main#content .link-btn a.overlay-a[href='/updates/sort/popular/']"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        element.selectFirst(".img-overlay > p > a")!!.run {
            setUrlWithoutDomain(absUrl("href"))
            title = text()
        }
        thumbnail_url = element.selectFirst("a img")?.imgAttr()
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
     */
    override fun latestUpdatesRequest(page: Int) = if (useAlternativeLatestRequest) {
        alternativeLatestRequest(page)
    } else {
        defaultLatestRequest(page)
    }

    private fun defaultLatestRequest(page: Int) = GET("$baseUrl/archive/page/$page/", headers)

    /**
     * Some sites doesn't support page for /updates/sort/newest/
     *  - JoyMii
     *  - XArt (doesn't have any content at all)
     * This URL is often not showing consistent contents
     */
    private fun alternativeLatestRequest(page: Int) = GET("$baseUrl/updates/sort/newest/mpage/$page/", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val searchType = filters.filterIsInstance<SearchTypeFilter>().first().selected
        return if (query.isNotEmpty()) {
            val url = "$baseUrl/search/$searchType/".toHttpUrl().newBuilder()
                .addPathSegment(query.trim())
                .addPathSegments("mpage/$page/")
                .build()

            GET(url, headers)
        } else {
            val sortFilter = filters.filterIsInstance<SortFilter>().first()
            val tagsFilter = filters.filterIsInstance<TagsFilter>().first()
            val modelTagsFilter = filters.filterIsInstance<ModelTagsFilter>().first()
            val modelAgeFilter = filters.filterIsInstance<AgesFilter>().first()
            val modelCountriesFilter =
                filters.filterIsInstance<ModelCountriesFilter>().first()

            val url = baseUrl.toHttpUrl().newBuilder().apply {
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
                            addPathSegment(if (sortFilter.selected == "newest") "newest" else "popular")
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
                            addPathSegment(if (sortFilter.selected == "newest") "newest" else "popular")
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

            GET(url, headers)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    protected fun launchIO(block: () -> Unit) = scope.launch { block() }
    private var tagsFetchAttempt = 0
    private var tags = emptyList<Tag>()

    protected open fun getTags() {
        launchIO {
            if (tags.isEmpty() && tagsFetchAttempt < 3) {
                runCatching {
                    tags = client.newCall(GET("$baseUrl/updates/", headers))
                        .execute().asJsoup()
                        .select("#filter-a span[data-placeholder='Tags'] span:has(> input)")
                        .mapNotNull {
                            Tag(
                                it.select("label").text(),
                                it.select("input").attr("value"),
                            )
                        }
                }
                tagsFetchAttempt++
            }
        }
    }

    override fun getFilterList(): FilterList {
        getTags()
        getModelTags()
        val filters = mutableListOf(
            Filter.Header("Below filters are ignored when doing text search except Sort\nText search only support Galleries & Models"),
            SearchTypeFilter(searchTypeOptions),
            Filter.Separator(),
            Filter.Header("Some source might not support Trending"),
            SortFilter(),
            Filter.Separator(),
        )

        if (tags.isEmpty()) {
            filters.add(Filter.Header("Press 'reset' to attempt to load tags"))
        } else {
            filters.add(TagsFilter(tags))
        }

        filters.add(Filter.Separator())
        filters.add(Filter.Header("Model filters are ignored when Tags filter is selected.\nTrending is supported if only 1 Model's tag is selected."))
        if (modelTags.isEmpty()) {
            filters.add(Filter.Header("Press 'reset' to attempt to load Model tags"))
        } else {
            filters.add(ModelTagsFilter(modelTags))
        }
        if (modelCountries.isEmpty()) {
            filters.add(Filter.Header("Press 'reset' to attempt to load Model countries"))
        } else {
            filters.add(ModelCountriesFilter(modelCountries))
        }
        filters.add(AgesFilter())

        filters.add(Filter.Separator())
        filters.add(Filter.Header("Open WebView to watch video (chapter with only 1 photo)"))

        return FilterList(filters)
    }

    /**
     * The Uri used to browse for popular/trending/newest galleries:
     * - <domain>/models/
     * - <domain>/updates/
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

    override fun searchMangaParse(response: Response): MangasPage {
        val mangaFromElement = when {
            /* Support all three:
             - models browsing /models/ to make each model a title with multiple chapters of her galleries
             - model search /search/model/ to show each gallery as a separated title (just like normal browsing)
             - and model-tag
              They all return model-entries */
            response.request.url.toString()
                .contains("/model(s|-tag)?/".toRegex()) -> ::modelMangaFromElement

            else -> ::searchMangaFromElement
        }

        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map { element ->
            mangaFromElement(element)
        }
        val hasNextPage = searchMangaNextPageSelector().let { document.select(it).first() } != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        document.selectFirst("header#top h1")?.run {
            title = text()
        }
        document.selectFirst("p.link-btn")?.run {
            artist = select("a[href*=/model/]").eachText().joinToString()
            author = selectFirst("a")?.text()
            genre = (listOf(author, artist) + select("a[href*=/tag/]").eachText()).joinToString()
        }
        description = document.selectFirst("#content > p")?.text()
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    override fun mangaDetailsParse(response: Response): SManga = when {
        response.request.url.toString().contains("/model/") ->
            modelMangaDetailsParse(response.asJsoup())

        else ->
            mangaDetailsParse(response.asJsoup())
    }

    override fun chapterListRequest(manga: SManga) = when {
        manga.url.contains("/model/") ->
            modelChapterListRequest(manga)
        else ->
            GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.selectFirst(galleryListSelector)?.run {
            // select separately so if a model doesn't have any content then it will return an empty list
            select(gallerySelector)
                .map { chapterFromElement(it) }
        } ?: document.selectFirst(videoSelector)?.run {
            listOf(
                SChapter.create().apply {
                    name = "Video"
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        } ?: listOf(
            SChapter.create().apply {
                name = "Gallery"
                setUrlWithoutDomain(response.request.url.toString())
            },
        )
    }

    /* Models */
    private var modelTagsFetchAttempt = 0
    private var modelTags = emptyList<Tag>()
    private var modelCountries = emptyList<Country>()

    protected open fun getModelTags() {
        launchIO {
            if (modelTagsFetchAttempt < 3 && (modelTags.isEmpty() || modelCountries.isEmpty())) {
                runCatching {
                    client.newCall(GET("$baseUrl/models/", headers))
                        .execute().asJsoup().run {
                            modelTags =
                                select("#filter-b span[data-placeholder='Tags'] span:has(> input)")
                                    .mapNotNull {
                                        Tag(
                                            "M: " + it.select("label").text(),
                                            it.select("input").attr("value"),
                                        )
                                    }
                            modelCountries =
                                select("#filter-b span[data-placeholder='Country'] span:has(> input)")
                                    .mapNotNull {
                                        Country(
                                            it.select("label").text(),
                                            it.select("input").attr("value"),
                                        )
                                    }
                        }
                }
                modelTagsFetchAttempt++
            }
        }
    }

    protected open fun modelChapterListRequest(manga: SManga): Request {
        val url = (baseUrl + manga.url).toHttpUrl().newBuilder().apply {
            /* Must use sort/latest to get correct title (instead of description),
              also will list chapters in timely manner */
            addPathSegments("sort/latest")
        }.build()
        return GET(url, headers)
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

    protected open fun modelMangaDetailsParse(document: Document) = SManga.create().apply {
        document.selectFirst("article.module-model")?.run {
            val stats = selectFirst(".header-model").also {
                val model = selectFirst("h1")?.text()
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
    }

    /**
     * This is mainly used for model as a manga with each of her galleries as a chapter
     */
    override fun chapterFromElement(element: Element): SChapter {
        val isVideo = element.selectFirst(videoTitleSelector) != null
        return SChapter.create().apply {
            // Use img-overlay to get correct set's name without duplicate model's name
            with(element.selectFirst(".img-overlay > p > a")!!) {
                setUrlWithoutDomain(absUrl("href"))
                name = if (isVideo) "Video: ${text()}" else text()
            }
        }
    }

    override fun chapterListSelector() = throw UnsupportedOperationException()

    override fun pageListParse(document: Document): List<Page> = document.select(".list-gallery a[href^=https://cdn.], $videoSelector")
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

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    protected fun Element.imgAttr(): String = when {
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        hasAttr("data-cfsrc") -> attr("abs:data-cfsrc")
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        else -> attr("abs:src")
    }
}
