package eu.kanade.tachiyomi.extension.id.kumapoi

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class KumaPoi :
    MangaThemesia(
        "KumaPoi",
        "https://kumapoi.info",
        "id",
        dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.forLanguageTag("id")),
    ) {
    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override val hasProjectPage = true

    // override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        val seriesDetails = document.selectFirst(seriesDetailsSelector)
        val pageTitle = document.title().substringBefore(" - ").trim()

        if (seriesDetails == null) {
            title = pageTitle
            return@apply
        }

        title = seriesDetails.selectFirst(seriesTitleSelector)?.text().takeIf { it.isNullOrBlank().not() }
            ?: pageTitle
        artist = seriesDetails.selectFirst(seriesArtistSelector)?.ownText().removeEmptyPlaceholder()
        author = seriesDetails.selectFirst(seriesAuthorSelector)?.ownText().removeEmptyPlaceholder()
        description = seriesDetails.select(seriesDescriptionSelector).joinToString("\n") { it.text() }.trim()

        val altName = seriesDetails.selectFirst(seriesAltNameSelector)?.ownText().takeIf { it.isNullOrBlank().not() }
        altName?.let {
            description = "$description\n\n$altNamePrefix$altName".trim()
        }

        val genres = seriesDetails.select(seriesGenreSelector).map { it.text() }.toMutableList()
        seriesDetails.selectFirst(seriesTypeSelector)?.ownText().takeIf { it.isNullOrBlank().not() }?.let { genres.add(it) }
        genre = genres.map { genre ->
            genre.lowercase(Locale.forLanguageTag(lang)).replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase(Locale.forLanguageTag(lang))
                } else {
                    char.toString()
                }
            }
        }.joinToString { it.trim() }

        status = seriesDetails.selectFirst(seriesStatusSelector)?.text().parseStatus()
        thumbnail_url = seriesDetails.select(seriesThumbnailSelector).firstOrNull()?.imgAttr()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
    }
}
