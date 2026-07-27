package eu.kanade.tachiyomi.extension.all.elitebabes

import eu.kanade.tachiyomi.multisrc.masonry.Country
import eu.kanade.tachiyomi.multisrc.masonry.Masonry
import eu.kanade.tachiyomi.multisrc.masonry.SelectFilter
import eu.kanade.tachiyomi.multisrc.masonry.SortFilter
import eu.kanade.tachiyomi.multisrc.masonry.Tag
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Features:
 * - Support Highlight (galleries collection which are put on various external domain)
 * - Support Channels (filter as gallery's author, i.e. click on title's author such as MetArt) (/erotic-art-channels/)
 * - Support Collections (/collections/)
 * - Support browse Boards (/boards/) & /search for Pins (/pins/)
 */
@Source
abstract class EliteBabes : Masonry() {
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

    class ChannelFilter(channels: List<Tag> = emptyList()) : SelectFilter("Channels", channels.map { it.name to it.uriPart })

    class BoardFilter(boards: List<Tag> = emptyList()) : SelectFilter("Boards", boards.map { it.name to it.uriPart })

    private suspend fun fetchChannels(): List<Tag> = listOf(Tag("Off", "")) +
        client.get("$baseUrl/erotic-art-channels/").asJsoup()
            .select("ul.list-gallery figure > a")
            .map {
                Tag(
                    it.select("img").attr("alt"),
                    it.attr("href").removeSuffix("/").substringAfterLast("/"),
                )
            }

    private suspend fun fetchBoards(): List<Tag> = listOf(Tag("Off", "")) +
        client.get("$baseUrl/boards/").asJsoup()
            .select("ul.list-gallery figure > a")
            .map {
                Tag(
                    it.select("img").attr("alt"),
                    it.attr("href").removeSuffix("/").substringAfterLast("/"),
                )
            }

    @Serializable
    class EliteBabesFilterData(
        @Suppress("unused")
        val tags: List<Tag> = emptyList(),
        @Suppress("unused")
        val modelTags: List<Tag> = emptyList(),
        @Suppress("unused")
        val modelCountries: List<Country> = emptyList(),
        val channels: List<Tag> = emptyList(),
        val boards: List<Tag> = emptyList(),
    )

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val tagsDeferred = async { fetchTags() }
        val modelDeferred = async { fetchModelTagsAndCountries() }
        val channelsDeferred = async { fetchChannels() }
        val boardsDeferred = async { fetchBoards() }

        val tags = tagsDeferred.await()
        val (modelTags, modelCountries) = modelDeferred.await()

        jsonInstance.encodeToJsonElement(
            EliteBabesFilterData(
                tags = tags,
                modelTags = modelTags,
                modelCountries = modelCountries,
                channels = channelsDeferred.await(),
                boards = boardsDeferred.await(),
            ),
        )
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<EliteBabesFilterData>()

        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Highlights ignore Browse/Search, Tags, Models & Channels/Boards filters"),
            HighlightsFilter(highlights),
            Filter.Separator(),
            Filter.Header("Channels supports Sort but ignore Browse/Search, Tags Models & Boards filter"),
        )
        filterData?.channels?.takeIf { it.isNotEmpty() }?.let { filters.add(ChannelFilter(it)) }

        filters.add(Filter.Separator())
        filters.add(Filter.Header("Boards supports Sort but ignore Browse/Search, Tags & Models filter"))
        filterData?.boards?.takeIf { it.isNotEmpty() }?.let { filters.add(BoardFilter(it)) }
        filters.add(Filter.Separator())

        filters.addAll(super.getFilterList(data).list)

        return FilterList(filters)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val highlightsFilter = filters.firstInstanceOrNull<HighlightsFilter>() ?: HighlightsFilter(highlights)
        val channelFilter = filters.firstInstanceOrNull<ChannelFilter>() ?: ChannelFilter()
        val boardFilter = filters.firstInstanceOrNull<BoardFilter>() ?: BoardFilter()
        val sortFilter = filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()

        val document = when {
            highlightsFilter.state != 0 -> client.get(highlightsFilter.selected)
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

                client.get(url)
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

                client.get(url)
            }
            else -> return super.getSearchMangaList(page, query, filters)
        }.asJsoup()

        return parseSearchMangaList(document)
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

    override fun parseSearchMangaList(document: Document): MangasPage {
        val requestUrl = document.location()
        return when {
            highlights.any { it.second == requestUrl } -> {
                /* Handle filter for highlights */
                MangasPage(
                    mangas = document.select("div.item a[href]:has(img)")
                        .map(::highlightMangaFromElement),
                    hasNextPage = false,
                )
            }
            requestUrl.contains("/collections/") -> {
                val mangas = document.select(popularMangaSelector()).map(::collectionMangaFromElement)
                val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
                MangasPage(mangas, hasNextPage)
            }
            /* Support all three:
             - boards browsing /e/
             - pins browsing
             - pin search
              They all return pin-entries */
            requestUrl.contains("/(e|pins|list_item)/".toRegex()) -> {
                val mangas =
                    document.select("$galleryListSelector > li:not(:has(.icon-play, a[href*='/video/']))")
                        .map(::pinMangaFromElement)
                val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
                MangasPage(mangas, hasNextPage)
            }
            else -> super.parseSearchMangaList(document)
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

    override fun parseMangaDetails(document: Document, manga: SManga): SManga = when {
        manga.url.contains("/collection/nr/") ->
            collectionMangaDetailsParse(document, manga)
        manga.url.contains("/pin/") ->
            pinMangaDetailsParse(document, manga)
        else ->
            super.parseMangaDetails(document, manga)
    }

    private fun collectionMangaDetailsParse(document: Document, manga: SManga) = SManga.create().apply {
        url = manga.url
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

    private fun pinMangaDetailsParse(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        thumbnail_url = document.selectFirst("figure > img")?.imgAttr()
        title = document.select("figure .img-overlay > h1").text()
        genre = document.select("figure .img-overlay > p > a[href*='/e/']").text().removePrefix("@")
        author = document.select("figure .img-overlay > p:contains(Brought By) > a").text()
        artist = document.select("ul > li > a[href*='/model/'] > img").attr("alt").trim()
        status = SManga.COMPLETED
    }

    override fun parseChapterList(document: Document, manga: SManga): List<SChapter> = when {
        manga.url.contains("/pin/") ->
            listOf(
                SChapter.create().apply {
                    name = "Photo"
                    url = manga.url
                },
            )
        else ->
            super.parseChapterList(document, manga)
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
