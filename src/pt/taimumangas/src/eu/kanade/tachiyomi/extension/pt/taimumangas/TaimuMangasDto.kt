package eu.kanade.tachiyomi.extension.pt.taimumangas

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ================================ Library/Biblioteca =======================================

@Serializable
data class TaimuLibraryDto(
    val series: List<TaimuLibrarySeriesDto>,
    val pagination: TaimuPaginationDto,
)

@Serializable
data class TaimuPaginationDto(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("total_pages") val totalPages: Int,
    @SerialName("has_next") val hasNext: Boolean,
)

@Serializable
data class TaimuLibrarySeriesDto(
    val code: String,
    val title: String,
    val cover: String,
    val status: String,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = this@TaimuLibrarySeriesDto.title
        url = "/series/${this@TaimuLibrarySeriesDto.code}"
        thumbnail_url = this@TaimuLibrarySeriesDto.cover.toMediaUrl()
        status = this@TaimuLibrarySeriesDto.status.toMangaStatus()
    }
}

// ================================ Series Detail =======================================

@Serializable
data class TaimuSeriesDetailDto(
    val title: String,
    val coverImage: String,
    val statusOriginal: String,
    val genres: List<String> = emptyList(),
    val author: TaimuPersonDto? = null,
    val artist: TaimuPersonDto? = null,
    val description: List<String> = emptyList(),
    val chapters: TaimuChaptersDataDto,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = this@TaimuSeriesDetailDto.title
        author = this@TaimuSeriesDetailDto.author?.name?.ifEmpty { null }
        artist = this@TaimuSeriesDetailDto.artist?.name?.ifEmpty { null }
        genre = this@TaimuSeriesDetailDto.genres.joinToString()
        description = this@TaimuSeriesDetailDto.description.joinToString("\n").trim()
        status = this@TaimuSeriesDetailDto.statusOriginal.toMangaStatus()
        thumbnail_url = this@TaimuSeriesDetailDto.coverImage.toMediaUrl()
    }
}

@Serializable
data class TaimuPersonDto(val name: String = "")

// ================================ Chapters =======================================

@Serializable
data class TaimuChaptersDataDto(
    val chapters: List<TaimuChapterDto>,
    @SerialName("total_pages") val totalPages: Int,
    @SerialName("has_next") val hasNext: Boolean,
)

@Serializable
data class TaimuChapterDto(
    val number: String,
    val code: String,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String,
) {
    fun toSChapter(): SChapter = SChapter.create().apply {
        val chapterTitle = this@TaimuChapterDto.title
        name = if (!chapterTitle.isNullOrBlank()) {
            "Capítulo ${this@TaimuChapterDto.number} - $chapterTitle"
        } else {
            "Capítulo ${this@TaimuChapterDto.number}"
        }
        url = "/reader/${this@TaimuChapterDto.code}"
        date_upload = DATE_FORMATTER.tryParse(this@TaimuChapterDto.createdAt)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}

// ================================ Reader/Pages =======================================

@Serializable
data class TaimuReaderDataDto(
    val series: TaimuReaderSeriesDto,
    val chapter: TaimuReaderChapterDto,
)

@Serializable
data class TaimuReaderSeriesDto(
    val title: String,
    val code: String,
)

@Serializable
data class TaimuReaderChapterDto(
    val number: String,
    val pages: List<TaimuPageDto>,
)

@Serializable
data class TaimuPageDto(
    val path: String,
    val number: Int,
)

// ================================ Trending (Homepage) =======================================

@Serializable
data class TaimuTrendingSeriesDto(
    val title: String,
    val code: String,
    val cover: String,
    val totalChapters: Int,
)

// ================================ Helpers =======================================

private fun String.toMangaStatus(): Int = when (this) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

internal fun String.toMediaUrl(): String? = if (isNotEmpty()) "${TaimuMangas.API_URL}/media/$this" else null
