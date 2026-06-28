package eu.kanade.tachiyomi.extension.id.mikoroku

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Blogger Atom JSON feed (https://www.blogger.com/docs/atom-gdata-api).
 *
 * MikoRoku's content is split across two Blogger blogs:
 *  - mikoroku.top   -> manga catalog / details
 *  - mikodrive.my.id -> chapter posts (images live inside each post's content)
 *
 * Both expose the same `/feeds/posts/default?alt=json` shape, so a single set of
 * DTOs covers listing, search, details and chapter parsing.
 */

@Serializable
class FeedDto(
    val feed: FeedBodyDto? = null,
)

@Serializable
class FeedBodyDto(
    val entry: List<EntryDto>? = emptyList(),
    @SerialName("openSearch\$totalResults") val totalResults: TotalResultDto? = null,
)

@Serializable
class TotalResultDto(
    @SerialName("\$t") val t: String = "0",
)

@Serializable
class EntryDto(
    val title: TextDto? = null,
    val published: TextDto? = null,
    val updated: TextDto? = null,
    val content: TextDto? = null,
    @SerialName("media\$thumbnail") val thumbnail: ThumbnailDto? = null,
    val category: List<CategoryDto>? = emptyList(),
    @SerialName("link") val links: List<LinkDto>? = emptyList(),
) {
    private val titleText: String get() = title?.t.orEmpty()

    val categories: List<String> get() = category?.map { it.term }.orEmpty()

    /** Absolute Blogger URL of this post, e.g. `https://www.mikoroku.top/2026/03/...html`. */
    val postUrl: String? get() = links?.firstOrNull { it.rel == "alternate" }?.href

    /**
     * Builds an [SManga] from a catalog feed entry.
     *
     * The URL stored is the post URL of the **catalog** blog (mikoroku.top). The
     * chapter feed query key (the manga title) is derived from the entry title, so
     * we only need the title + thumbnail to build a browseable card.
     */
    fun toSManga(): SManga = SManga.create().apply {
        title = titleText
        url = postUrl.orEmpty()
        thumbnail_url = thumbnail?.url?.let(::upgradeThumb)
    }

    /**
     * Builds an [SChapter] from a chapter feed entry (mikodrive.my.id).
     */
    fun toSChapter(dateUpload: Long = 0L): SChapter = SChapter.create().apply {
        name = titleText
        url = postUrl.orEmpty()
        date_upload = dateUpload
        chapter_number = CHAPTER_REGEX.find(titleText)?.value?.toFloatOrNull() ?: -1f
    }

    private fun upgradeThumb(url: String): String = url
        // s72-c -> s500 for a sharper cover, keep non-cropped variants as-is
        .replace(Regex("""\/s\d+-c[\/]"""), "/s500/")
        .replace(Regex("""\/s\d+[\/]"""), "/s500/")
}

@Serializable
class TextDto(
    @SerialName("\$t") val t: String = "",
)

@Serializable
class ThumbnailDto(
    val url: String = "",
)

@Serializable
class CategoryDto(
    val term: String = "",
)

@Serializable
class LinkDto(
    val rel: String = "",
    val href: String = "",
)

private val CHAPTER_REGEX = Regex("""\d+(?:\.\d+)?""")
