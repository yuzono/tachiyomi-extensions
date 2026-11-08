package eu.kanade.tachiyomi.multisrc.masonry

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable

abstract class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
) {
    // A saved search can restore an index past a fetched list that has since shrunk, which falls back to the first option
    val selected get() = options.getOrElse(state) { options.first() }.second
}

class SortFilter(options: List<Pair<String, String>> = sortFilterOptions) : SelectFilter("Sort by", options)

val sortFilterOptions = listOf(
    Pair("Trending", "trending"),
    Pair("Newest", "newest"),
    Pair("Popular", "popular"),
)

class SearchTypeFilter(options: List<Pair<String, String>>) : SelectFilter("Browse & Search for", options)

@Serializable
class Tag(val name: String, val uriPart: String)

class TagCheckBox(name: String, val uriPart: String) : Filter.CheckBox(name)

class TagsFilter(tags: List<Tag> = emptyList()) : Filter.Group<TagCheckBox>("Tags", tags.map { TagCheckBox(it.name, it.uriPart) })

/**
 * A section of the site's models filter, such as Country or Hair. Sites split it differently, so the sections
 * are read from the site; checked values of all sections with the same [slot] join in that part of the URL.
 */
@Serializable
class ModelFilterGroup(val name: String, val slot: String, val options: List<Tag>)

class ModelFilter(group: ModelFilterGroup) : Filter.Group<TagCheckBox>(group.name, group.options.map { TagCheckBox(it.name, it.uriPart) }) {
    val slot = group.slot
}

class AgesFilter : SelectFilter("Age", ageFilterOptions)

class BoardFilter(boards: List<Tag> = emptyList()) : SelectFilter("Boards", listOf(Pair("Off", "")) + boards.map { it.name to it.uriPart })

private val ageFilterOptions = listOf(Pair("All Ages", "0")) +
    (19..50).map {
        Pair(it.toString(), it.toString())
    }

/**
 * Cached filter data fetched by [eu.kanade.tachiyomi.multisrc.masonry.Masonry.fetchFilterData].
 * Extra fields added by subclasses' own filter data are ignored here thanks to `ignoreUnknownKeys`.
 *
 * No defaults on purpose: KeiSource keeps a cache that still parses for 3 days, so a cache written with an older
 * shape must fail to parse to be fetched again, instead of silently showing empty filters.
 */
@Serializable
class MasonryFilterData(
    val tags: List<Tag>,
    val modelFilters: List<ModelFilterGroup>,
    val boards: List<Tag>,
)
