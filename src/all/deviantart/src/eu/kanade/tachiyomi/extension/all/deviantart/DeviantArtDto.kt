package eu.kanade.tachiyomi.extension.all.deviantart

import kotlinx.serialization.Serializable

@Serializable
class Deviation(
    val deviationId: Int,
    val type: String, // "image", "film"
    val url: String, // "https://www.deviantart.com/shw67/art/Beach-58-1007661685"
    val title: String,
    val isVideo: Boolean,
    val publishedTime: String, // "2024-01-03T03:17:25-0800"
    val isMature: Boolean,
    val author: Author,
    val media: Media,
)

@Serializable
class Author(
    val userId: Int,
    val useridUuid: String, // "8460dab0-bcab-49e8-bbae-18dc5c677b55"
    val username: String, // "shw67",
    val usericon: String, // "https://a.deviantart.net/avatars-big/s/h/shw67.jpg?2"
)

@Serializable
class Media(
    private val baseUri: String?, // "https://images-wixmp-ed30a86b8c4ca887773594c2.wixmp.com/f/8460dab0-bcab-49e8-bbae-18dc5c677b55/dgnxoqd-054db8b2-5baf-41f3-809f-39b94925415b.jpg"
    private val prettyName: String?, // "beach__58__by_shw67_dgnxoqd"
    private val token: List<String>? = null,
    // "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1cm46YXBwOjdlMGQxODg5ODIyNjQzNzNhNWYwZDQxNWVhMGQyNmUwIiwiaXNzIjoidXJuOmFwcDo3ZTBkMTg4OTgyMjY0MzczYTVmMGQ0MTVlYTBkMjZlMCIsIm9iaiI6W1t7ImhlaWdodCI6Ijw9MTgwMCIsInBhdGgiOiJcL2ZcLzg0NjBkYWIwLWJjYWItNDllOC1iYmFlLTE4ZGM1YzY3N2I1NVwvZGdueG9xZC0wNTRkYjhiMi01YmFmLTQxZjMtODA5Zi0zOWI5NDkyNTQxNWIuanBnIiwid2lkdGgiOiI8PTEyMDAifV1dLCJhdWQiOlsidXJuOnNlcnZpY2U6aW1hZ2Uub3BlcmF0aW9ucyJdfQ.aF5SPOVJhAKAMgJxReo14ywf5upUjQg4_e56npVlLeo",
    // "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1cm46YXBwOjdlMGQxODg5ODIyNjQzNzNhNWYwZDQxNWVhMGQyNmUwIiwiaXNzIjoidXJuOmFwcDo3ZTBkMTg4OTgyMjY0MzczYTVmMGQ0MTVlYTBkMjZlMCIsIm9iaiI6W1t7InBhdGgiOiJcL2ZcLzg0NjBkYWIwLWJjYWItNDllOC1iYmFlLTE4ZGM1YzY3N2I1NVwvZGdueG9xZC0wNTRkYjhiMi01YmFmLTQxZjMtODA5Zi0zOWI5NDkyNTQxNWIuanBnIn1dXSwiYXVkIjpbInVybjpzZXJ2aWNlOmZpbGUuZG93bmxvYWQiXX0.V6UcjpOb2UYTe0inj8ffUkLNWR6zlwRyZ1_4VKyp4GQ"
    private val types: List<MediaType>?,
) {
    fun getCoverUrl(): String? = token
        ?.let { "$baseUri${getPreview()}?token=${token.random()}" }
        ?: baseUri

    fun getFullView(): String {
        if (types.isNullOrEmpty()) return ""
        return (types.firstOrNull { it.t == "fullview" } ?: types.maxByOrNull { it.w * it.h })
            ?.let { "/v1/fill/w_${it.w},h_${it.h}/$prettyName-fullview.jpg" }
            ?: ""
    }

    fun getPreview(): String {
        if (types.isNullOrEmpty()) return ""
        return (types.firstOrNull { it.t == "preview" } ?: types.maxByOrNull { it.w * it.h })
            ?.let { "/v1/fill/w_${it.w},h_${it.h}/$prettyName-pre.jpg" }
            ?: ""
    }

    fun getThumbnail(): String {
        if (types.isNullOrEmpty()) return ""
        return (types.firstOrNull { it.t == "400T" } ?: types.maxByOrNull { it.w * it.h })
            ?.let { "/v1/fill/w_${it.w},h_${it.h}/$prettyName-400t.jpg" }
            ?: ""
    }
}

@Serializable
class MediaType(
    val t: String, // fullview, social_preview, preview, 400T
    val c: String?, // path segments: /v1/fill/w_1600,h_2400,q_80,strp/<prettyName>-fullview.jpg
    val h: Int, // height
    val w: Int, // width
)

@Serializable
class User(
    val user: Author,
    val deviations: List<Deviation>,
)

@Serializable
class Collection(
    val collection: CollectionInfo,
    val deviations: List<Deviation>,
)

@Serializable
class CollectionInfo(
    val folderId: Int,
    val name: String,
    val owner: Author,
)

@Serializable
class RCacheContent(
    val relatedContent: RelatedContents?,
)

@Serializable
class RelatedContents(
    val relatedContent: List<Related>?,
)

@Serializable
class Related(
    val contentType: String?,
    val deviations: List<Deviation>?,
    val users: List<User>?,
    val collections: List<Collection>?,
)
