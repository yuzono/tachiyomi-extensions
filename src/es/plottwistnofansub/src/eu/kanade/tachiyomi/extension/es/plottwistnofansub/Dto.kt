package eu.kanade.tachiyomi.extension.es.plottwistnofansub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ChapterApiResponse(
    @SerialName("chapters_to_display") val chapters: List<ChapterDto>,
)

@Serializable
class ChapterDto(
    val name: String,
    @SerialName("name_extend") val nameExtend: String = "",
    val link: String,
    val date: String,
)
