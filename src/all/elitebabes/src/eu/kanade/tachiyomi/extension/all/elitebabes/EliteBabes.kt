package eu.kanade.tachiyomi.extension.all.elitebabes

import eu.kanade.tachiyomi.multisrc.masonry.Masonry
import eu.kanade.tachiyomi.multisrc.masonry.ModelFilterGroup
import eu.kanade.tachiyomi.multisrc.masonry.SelectFilter
import eu.kanade.tachiyomi.multisrc.masonry.SortFilter
import eu.kanade.tachiyomi.multisrc.masonry.Tag
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Features:
 * - Support Highlight (galleries collection which are put on various external domain)
 * - Support Channels (filter as gallery's author, i.e. click on title's author such as MetArt) (/erotic-art-channels/)
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

    private suspend fun fetchChannels(): List<Tag> = listOf(Tag("Off", "")) +
        client.get("$baseUrl/erotic-art-channels/").asJsoup()
            .select("#tagsList li > a")
            .map {
                Tag(
                    it.selectFirst("span")!!.ownText(),
                    it.attr("href").removeSuffix("/").substringAfterLast("/"),
                )
            }

    // Only browsing galleries has a video listing (/sort/videos/)
    override val sortOptions = super.sortOptions + Pair("Video only", "videos")

    @Serializable
    class EliteBabesFilterData(
        @Suppress("unused")
        val tags: List<Tag>,
        @Suppress("unused")
        val modelFilters: List<ModelFilterGroup>,
        @Suppress("unused")
        val boards: List<Tag>,
        val channels: List<Tag>,
    )

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val channelsDeferred = async { fetchChannels() }
        val data = fetchMasonryFilterData()

        EliteBabesFilterData(
            tags = data.tags,
            modelFilters = data.modelFilters,
            boards = data.boards,
            channels = channelsDeferred.await(),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<EliteBabesFilterData>()

        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Highlights ignores other filters"),
            HighlightsFilter(highlights),
            Filter.Separator(),
        )
        filterData?.channels?.takeIf { it.isNotEmpty() }?.let {
            filters.add(Filter.Header("Channels supports Sort but ignore Browse/Search, Tags Models & Boards filter"))
            filters.add(ChannelFilter(it))
        }
        filters.add(Filter.Separator())

        filters.addAll(super.getFilterList(data).list)

        return FilterList(filters)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val highlightsFilter = filters.firstInstanceOrNull<HighlightsFilter>() ?: HighlightsFilter(highlights)
        val channelFilter = filters.firstInstanceOrNull<ChannelFilter>() ?: ChannelFilter()
        val sortFilter = filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()

        return when {
            highlightsFilter.state != 0 -> parseSearchMangaList(client.get(highlightsFilter.selected).asJsoup())
            channelFilter.state != 0 -> getListingPage("${channelFilter.selected}/s/${sortFilter.selected}/", page)
            else -> super.getSearchMangaList(page, query, filters)
        }
    }

    override fun parseSearchMangaList(document: Document): MangasPage {
        if (highlights.none { it.second == document.location() }) return super.parseSearchMangaList(document)

        return MangasPage(
            mangas = document.select("div.item a[href]:has(img)")
                .map(::highlightMangaFromElement),
            hasNextPage = false,
        )
    }

    private fun highlightMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.select("img").attr("alt")
        thumbnail_url = element.select("img").first()?.imgAttr()
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }
}
