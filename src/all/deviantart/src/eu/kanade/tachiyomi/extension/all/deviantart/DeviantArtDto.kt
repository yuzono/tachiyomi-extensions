package eu.kanade.tachiyomi.extension.all.deviantart

import kotlinx.serialization.Serializable

@Serializable
data class Deviation(
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
data class Author(
    val userId: Int,
    val useridUuid: String, // "8460dab0-bcab-49e8-bbae-18dc5c677b55"
    val username: String, // "shw67",
    val usericon: String, // "https://a.deviantart.net/avatars-big/s/h/shw67.jpg?2"
)

@Serializable
data class Media(
    val baseUri: String, // "https://images-wixmp-ed30a86b8c4ca887773594c2.wixmp.com/f/8460dab0-bcab-49e8-bbae-18dc5c677b55/dgnxoqd-054db8b2-5baf-41f3-809f-39b94925415b.jpg"
    val prettyName: String, // "beach__58__by_shw67_dgnxoqd"
    val token: List<String>? = null,
    // "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1cm46YXBwOjdlMGQxODg5ODIyNjQzNzNhNWYwZDQxNWVhMGQyNmUwIiwiaXNzIjoidXJuOmFwcDo3ZTBkMTg4OTgyMjY0MzczYTVmMGQ0MTVlYTBkMjZlMCIsIm9iaiI6W1t7ImhlaWdodCI6Ijw9MTgwMCIsInBhdGgiOiJcL2ZcLzg0NjBkYWIwLWJjYWItNDllOC1iYmFlLTE4ZGM1YzY3N2I1NVwvZGdueG9xZC0wNTRkYjhiMi01YmFmLTQxZjMtODA5Zi0zOWI5NDkyNTQxNWIuanBnIiwid2lkdGgiOiI8PTEyMDAifV1dLCJhdWQiOlsidXJuOnNlcnZpY2U6aW1hZ2Uub3BlcmF0aW9ucyJdfQ.aF5SPOVJhAKAMgJxReo14ywf5upUjQg4_e56npVlLeo",
    // "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1cm46YXBwOjdlMGQxODg5ODIyNjQzNzNhNWYwZDQxNWVhMGQyNmUwIiwiaXNzIjoidXJuOmFwcDo3ZTBkMTg4OTgyMjY0MzczYTVmMGQ0MTVlYTBkMjZlMCIsIm9iaiI6W1t7InBhdGgiOiJcL2ZcLzg0NjBkYWIwLWJjYWItNDllOC1iYmFlLTE4ZGM1YzY3N2I1NVwvZGdueG9xZC0wNTRkYjhiMi01YmFmLTQxZjMtODA5Zi0zOWI5NDkyNTQxNWIuanBnIn1dXSwiYXVkIjpbInVybjpzZXJ2aWNlOmZpbGUuZG93bmxvYWQiXX0.V6UcjpOb2UYTe0inj8ffUkLNWR6zlwRyZ1_4VKyp4GQ"
) {
    fun getCoverUrl(): String = token
        ?.let { "$baseUri?token=${token.random()}" }
        ?: baseUri
}

@Serializable
data class User(
    val user: Author,
    val deviations: List<Deviation>,
)

@Serializable
data class Collection(
    val collection: CollectionInfo,
    val deviations: List<Deviation>,
)

@Serializable
data class CollectionInfo(
    val folderId: Int,
    val name: String,
    val owner: Author,
)
