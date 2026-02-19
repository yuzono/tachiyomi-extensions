package eu.kanade.tachiyomi.extension.all.hentai3

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getFilters(): FilterList = FilterList(
    Filter.Header("Add quote (\"...\") for exact search query match"),
    SortFilter(),
    Filter.Separator(),
    Filter.Header("Separate tags with commas (,)"),
    Filter.Header("Prepend with dash (-) to exclude"),
    Filter.Header("Use 'Male Tags' or 'Female Tags' for specific categories. 'Tags' searches all other categories."),
    AdvSearchEntryFilter("Tags", "tag"),
    AdvSearchEntryFilter("Female Tags", "tag", "female"),
    AdvSearchEntryFilter("Male Tags", "tag", "male"),
    AdvSearchEntryFilter("Series", "serie"),
    AdvSearchEntryFilter("Artists", "artist"),
    AdvSearchEntryFilter("Characters", "character"),
    AdvSearchEntryFilter("Groups", "group"),
    AdvSearchEntryFilter("Categories", "category"),
    Filter.Header("Uploaded valid units are h, d, w, m, y."),
    Filter.Header("example: >20d or <20d"),
    AdvSearchEntryFilter("Uploaded", "added"),
    Filter.Header("Filter by pages, for example: 20 or >20 or <20"),
    AdvSearchEntryFilter("Pages", "pages"),
    OffsetPageFilter(),
    FavoriteFilter(),
)

open class AdvSearchEntryFilter(name: String, val type: String, val specific: String = "") : Filter.Text(name)

class OffsetPageFilter : Filter.Text("Offset results by # pages")

internal class FavoriteFilter : Filter.CheckBox("Favorites only", false)

internal class SortFilter :
    UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Recent", ""),
            Pair("Popular: All Time", "popular"),
            Pair("Popular: Week", "popular-7d"),
            Pair("Popular: Today", "popular-24h"),
        ),
    )

internal open class UriPartFilter(displayName: String, val pairs: Array<Pair<String, String>>) : Filter.Select<String>(displayName, pairs.map { it.first }.toTypedArray()) {
    fun toUriPart() = pairs[state].second
}

internal inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T
