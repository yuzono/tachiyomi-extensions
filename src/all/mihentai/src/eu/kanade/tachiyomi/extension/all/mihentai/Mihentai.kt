package eu.kanade.tachiyomi.extension.all.mihentai

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Response
import eu.kanade.tachiyomi.source.model.SChapter


class Mihentai : MangaThemesia("Mihentai", "https://mihentai.com", "all") {
    private class StatusFilter : SelectFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Publishing", "publishing"),
            Pair("Finished", "finished"),
            Pair("Dropped", "drop"),
        ),
    )

    private class TypeFilter : SelectFilter(
        "Type",
        arrayOf(
            Pair("Default", ""),
            Pair("Manga", "Manga"),
            Pair("Manhwa", "Manhwa"),
            Pair("Manhua", "Manhua"),
            Pair("Webtoon", "webtoon"),
            Pair("One-Shot", "One-Shot"),
            Pair("Doujin", "doujin"),
        ),
    )

    override fun getFilterList(): FilterList = FilterList(
        listOf(
            StatusFilter(),
            TypeFilter(),
            OrderByFilter(intl["order_by_filter_title"], orderByFilterOptions),
            GenreListFilter(intl["genre_filter_title"], getGenreList()),
        ),
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        countViews(document)

        val chapters = document.select(chapterListSelector()).map { chapterFromElement(it) }

        // Add timestamp to latest chapter, taken from "Updated On".
        // So source which not provide chapter timestamp will have at least one
        if (chapters.isNotEmpty() && chapters.first().date_upload == 0L) {
            val date = document
                .select(".listinfo time[itemprop=dateModified], .fmed:contains(update) time, span:contains(update) time")
                .attr("datetime")
            if (date.isNotEmpty()) chapters.first().date_upload = parseUpdatedOnDate(date)
        }

        return chapters.isNotEmpty() : chapters.reversed() ? chapters
    }
}
