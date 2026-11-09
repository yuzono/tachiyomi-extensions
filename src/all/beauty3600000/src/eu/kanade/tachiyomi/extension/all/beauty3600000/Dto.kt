package eu.kanade.tachiyomi.extension.all.beauty3600000

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
class PostDto(
    val id: Int,
    private val title: RenderedDto,
    private val content: RenderedDto,
    @SerialName("date_gmt") private val dateGmt: String,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = id.toString()
        title = this@PostDto.title.rendered.decodeHtmlEntities()
            .takeIf { it.isNotBlank() }
            ?: throw Exception("Title is mandatory")
        thumbnail_url = images(baseUrl).firstOrNull()?.absUrl("src")
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    fun toSChapter() = SChapter.create().apply {
        url = id.toString()
        name = "Gallery"
        date_upload = DateTimeFormatter.ISO_LOCAL_DATE_TIME.tryParseDateTime(dateGmt, ZoneOffset.UTC)
    }

    fun toPageList(baseUrl: String) = images(baseUrl).mapIndexed { i, img ->
        Page(i, imageUrl = img.absUrl("src"))
    }

    private fun images(baseUrl: String) = Jsoup.parseBodyFragment(content.rendered, baseUrl).select("img")
}

@Serializable
class RenderedDto(
    val rendered: String,
)

private fun String.decodeHtmlEntities(): String = Parser.unescapeEntities(this, false)

@Serializable
class TermDto(
    val id: Int,
    val name: String,
)
