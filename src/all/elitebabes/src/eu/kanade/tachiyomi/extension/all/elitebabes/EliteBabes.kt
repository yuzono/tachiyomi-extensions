package eu.kanade.tachiyomi.extension.all.elitebabes

import eu.kanade.tachiyomi.multisrc.masonry.Masonry
import eu.kanade.tachiyomi.multisrc.masonry.SelectFilter
import eu.kanade.tachiyomi.multisrc.masonry.SortFilter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Features:
 * - Support Highlight (galleries collection which are put on various external domain)
 * - Support Channels (filter as gallery's author, i.e. click on title's author such as MetArt) (/erotic-art-channels/)
 * - Support Collections (/collections/)
 * - Support browse Boards (/boards/) & /search for Pins (/pins/)
 */
class EliteBabes : Masonry("Elite Babes", "https://www.elitebabes.com", "all") {
    /**
     * External sites which ref link back to main site.
     * This is like highlight from Channels, without sorting
     * Missing:
     *  - https://www.hegrehub.com
     *  - https://playmatehunter.com
     */
    private val highlights: List<Pair<String, String>> = listOf(
        Pair("Off", "-"),
        Pair("SexArt Models", "https://www.sexarthub.com/"),
        Pair("Femjoy Models", "https://www.femangels.com/"),
        Pair("Playboy Centerfolds", "https://www.centerfoldhunter.com/"),
        Pair("Rylsky Art", "https://www.rylskyhunter.com/"),
        Pair("Penthouse Sexy Pets", "https://www.penthousehub.com/"),
        Pair("Zemani", "https://www.zemanihunter.com/"),
        Pair("MetArt Heaven", "https://www.metheaven.com/"),
        Pair("MetArt X Models", "https://www.metxhunter.com/"),
        Pair("Erotic & Beauty", "https://www.eroticandbeauty.com/"),
        Pair("Als Scan", "https://www.alshunter.com/"),
        Pair("Digital Desire", "https://www.digitalsweeties.com/"),
        Pair("Errotica Babes", "https://www.erroticahunter.com/"),
        Pair("MPL Studios", "https://www.mplhunter.com/"),
        Pair("Photodromm Models", "https://www.drommhub.com/"),
        Pair("W4B - Watch 4 Beauty", "https://www.w4bhub.com/"),
        Pair("Domai Erotica", "https://www.domerotica.com/"),
        Pair("The Life Erotic", "https://www.tlehunter.com/"),
        Pair("Gravure Idols & Asian Glamour", "https://www.jperotica.com/"),
        Pair("Nubiles", "https://www.nubileshunter.com/"),
        Pair("Goddess Models", "https://www.goddesshunter.com/"),
        Pair("Erotic In Mind", "https://www.eroticinmind.com/"),
        Pair("Eternal Babes - Desire Girls", "https://www.eternalbabes.com/"),
        Pair("Viv Thomas", "https://www.vivhunter.com/"),
        Pair("Japanese Beauties", "https://www.jpbeauties.com/"),
        Pair("Pinup Models", "https://www.pinuphunter.com/"),
        Pair("X-Art", "https://www.xarthub.com/"),
        Pair("WoW Girls", "https://www.wowsweeties.com/"),
        Pair("Holy Randall", "https://www.randallhub.com/"),
        Pair("Gravure Models", "https://www.gravurehunter.com/"),
        Pair("Showy Beauty", "https://www.showyhub.com/"),
        Pair("Stunning18", "https://www.stunningsweeties.com/"),
        Pair("Amour Angels", "https://www.amourhub.com/"),
    )

    override val baseUrl
        get() = when {
            System.getenv("CI") == "true" ->
                (highlights + listOf(Pair("", "https://www.elitebabes.com/")))
                    .filterIndexed { idx, _ -> idx != 0 }
                    .joinToString("#, ") { it.second }
            else -> "https://www.elitebabes.com"
        }

    private class HighlightsFilter(highlights: List<Pair<String, String>>) : SelectFilter("Highlights", highlights)

    class ChannelFilter(channels: List<Pair<String, String>>) : SelectFilter("Channels", channels)

    private var channelsFetchAttempt = 0
    private var channels = emptyList<Pair<String, String>>()

    private fun getChannels() {
        launchIO {
            if (channels.isEmpty() && channelsFetchAttempt < 3) {
                runCatching {
                    channels = listOf(Pair("Off", "")) +
                        client.newCall(GET("$baseUrl/erotic-art-channels/", headers))
                            .execute().asJsoup()
                            .select("ul.list-gallery figure > a")
                            .mapNotNull {
                                Pair(
                                    it.select("img").attr("alt"),
                                    it.attr("href")
                                        .removeSuffix("/")
                                        .substringAfterLast("/"),
                                )
                            }
                }
                channelsFetchAttempt++
            }
        }
    }

    class BoardFilter(boards: List<Pair<String, String>>) : SelectFilter("Boards", boards)

    private var boardsFetchAttempt = 0
    private var boards = emptyList<Pair<String, String>>()

    private fun getBoards() {
        launchIO {
            if (boards.isEmpty() && boardsFetchAttempt < 3) {
                runCatching {
                    boards = listOf(Pair("Off", "")) +
                        client.newCall(GET("$baseUrl/boards/", headers))
                            .execute().asJsoup()
                            .select("ul.list-gallery figure > a")
                            .mapNotNull {
                                Pair(
                                    it.select("img").attr("alt"),
                                    it.attr("href")
                                        .removeSuffix("/")
                                        .substringAfterLast("/"),
                                )
                            }
                }
                boardsFetchAttempt++
            }
        }
    }

    override fun getFilterList(): FilterList {
        getChannels()
        getBoards()
        val filters = listOf(
            Filter.Header("Highlights ignore Browse/Search, Tags, Models & Channels/Boards filters"),
            HighlightsFilter(highlights),
            Filter.Separator(),
        ) + listOf(
            Filter.Header("Channels supports Sort but ignore Browse/Search, Tags Models & Boards filter"),
            if (channels.isEmpty()) {
                Filter.Header("Press 'reset' to attempt to load channels")
            } else {
                ChannelFilter(channels)
            },
            Filter.Separator(),
        ) + listOf(
            Filter.Header("Boards supports Sort but ignore Browse/Search, Tags & Models filter"),
            if (boards.isEmpty()) {
                Filter.Header("Press 'reset' to attempt to load boards")
            } else {
                BoardFilter(boards)
            },
            Filter.Separator(),
        ) + super.getFilterList().list

        return FilterList(filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val highlightsFilter = filters.filterIsInstance<HighlightsFilter>().first()
        val channelFilter = filters.filterIsInstance<ChannelFilter>().first()
        val boardFilter = filters.filterIsInstance<BoardFilter>().first()
        val sortFilter = filters.filterIsInstance<SortFilter>().first()

        return when {
            highlightsFilter.state != 0 -> GET(highlightsFilter.selected, headers)
            channelFilter.state != 0 -> {
                val channelUri = channelFilter.selected
                val sortUri = "s"

                val url = baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment(channelUri)
                    sortFilter.getUriPartIfNeeded(channelUri).also {
                        if (it.isBlank()) {
                            addPathSegments("page/$page/")
                        } else {
                            addPathSegments("$sortUri/$it")
                            addPathSegments("mpage/$page/")
                        }
                    }
                }.build()

                GET(url, headers)
            }
            boardFilter.state != 0 -> {
                val boardUri = boardFilter.selected
                val sortUri = "sort"

                val url = baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment("e")
                    addPathSegment(boardUri)
                    addPathSegments("$sortUri/${sortFilter.selected}")
                    if (page > 1) {
                        addPathSegments("mpage/$page/")
                    }
                }.build()

                GET(url, headers)
            }
            else -> super.searchMangaRequest(page, query, filters)
        }
    }

    /**
     * The Uri used to browse for popular/trending/newest:
     * - <domain>/models/
     * - <domain>/collections/
     * - <domain>/pins/
     * - <domain>/updates/
     *
     * @param searchType is value of [searchTypeOptions]
     */
    override fun getBrowseChannelUri(searchType: String): String = when (searchType) {
        "collection" -> "collections"
        "list_item" -> "pins"
        else -> super.getBrowseChannelUri(searchType)
    }

    override val searchTypeOptions = super.searchTypeOptions + listOf(
        Pair("Collections", "collection"),
        Pair("Pins", "list_item"),
    )

    override fun searchMangaParse(response: Response): MangasPage {
        val requestUrl = response.request.url.toString()
        return when {
            highlights.map { it.second }.any { it == requestUrl } -> {
                /* Handle filter for highlights */
                MangasPage(
                    mangas = response.asJsoup().select("div.item a[href]:has(img)")
                        .map { element -> highlightMangaFromElement(element) },
                    hasNextPage = false,
                )
            }
            response.request.url.toString().contains("/collections/") -> {
                val mangaFromElement = ::collectionMangaFromElement

                val document = response.asJsoup()
                val mangas = document.select(searchMangaSelector())
                    .map { element -> mangaFromElement(element) }
                val hasNextPage = searchMangaNextPageSelector().let { document.select(it).first() } != null

                MangasPage(mangas, hasNextPage)
            }
            /* Support all three:
             - boards browsing /e/
             - pins browsing
             - pin search
              They all return pin-entries */
            response.request.url.toString().contains("/(e|pins|list_item)/".toRegex()) -> {
                val mangaFromElement = ::pinMangaFromElement

                val document = response.asJsoup()
                val mangas =
                    document.select("$galleryListSelector > li:not(:has(.icon-play, a[href*='/video/']))")
                        .map { element -> mangaFromElement(element) }
                val hasNextPage = searchMangaNextPageSelector().let { document.select(it).first() } != null

                MangasPage(mangas, hasNextPage)
            }
            else -> super.searchMangaParse(response)
        }
    }

    private fun highlightMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.select("img").attr("alt")
        thumbnail_url = element.select("img").first()?.imgAttr()
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun collectionMangaFromElement(element: Element) = super.popularMangaFromElement(element).apply {
        status = SManga.ONGOING
        update_strategy = UpdateStrategy.ALWAYS_UPDATE
    }

    private fun pinMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.selectFirst("figure > a:has(img)")!!.apply {
            setUrlWithoutDomain(absUrl("href"))
        }.selectFirst("img")!!.run {
            title = attr("alt")
            thumbnail_url = imgAttr()
        }
        genre = element.select(".img-overlay > p > a[href*='/e/']").text().removePrefix("@")
        author = element.select(".img-overlay > p:contains(Brought By) > a").text()
        artist = element.select("ul > li > a[href*='/model/'] > img").attr("alt").trim()
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    override fun mangaDetailsParse(response: Response): SManga = when {
        response.request.url.toString().contains("/collection/nr/") ->
            collectionMangaDetailsParse(response.asJsoup())
        response.request.url.toString().contains("/pin/") ->
            pinMangaDetailsParse(response.asJsoup())
        else ->
            super.mangaDetailsParse(response)
    }

    private fun collectionMangaDetailsParse(document: Document) = SManga.create().apply {
        document.selectFirst("article.module-model")?.run {
            selectFirst(".header-model").run {
                title = selectFirst("h1")!!.text()
                thumbnail_url = selectFirst("figure img")?.imgAttr()
                description = select("ul.list-inline li")
                    .eachText().joinToString()
            }
            author = select("p:contains(by) a").text()
        }
        status = SManga.ONGOING
    }

    private fun pinMangaDetailsParse(document: Document): SManga = SManga.create().apply {
        thumbnail_url = document.selectFirst("figure > img")?.imgAttr()
        title = document.select("figure .img-overlay > h1").text()
        genre = document.select("figure .img-overlay > p > a[href*='/e/']").text().removePrefix("@")
        author = document.select("figure .img-overlay > p:contains(Brought By) > a").text()
        artist = document.select("ul > li > a[href*='/model/'] > img").attr("alt").trim()
        status = SManga.COMPLETED
    }

    override fun chapterListParse(response: Response): List<SChapter> = when {
        response.request.url.toString().contains("/pin/") ->
            listOf(
                SChapter.create().apply {
                    name = "Photo"
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        else ->
            super.chapterListParse(response)
    }

    override fun pageListParse(document: Document): List<Page> {
        val isPin = document.select("link[href*='/pin/']").isNotEmpty()
        return if (isPin) {
            document.select(".list-gallery-wide figure img[src^=https://cdn.]")
                .mapIndexed { idx, img -> Page(idx, imageUrl = img.imgAttr()) }
        } else {
            super.pageListParse(document)
        }
    }
}
