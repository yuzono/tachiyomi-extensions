package eu.kanade.tachiyomi.extension.id.kumapoi

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KumaPoi : MangaThemesia("KumaPoi", "https://kumapoi.info", "id") {
    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override val hasProjectPage = true
    override val seriesTitleSelector = "div span.entry-title"

    // Override mangaDetailsParse to handle potential null values and prevent NullPointerException
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            val seriesDetails = document.selectFirst(seriesDetailsSelector)
            if (seriesDetails != null) {
                // Safely extract title
                val titleElement = seriesDetails.selectFirst(seriesTitleSelector)
                if (titleElement != null) {
                    title = titleElement.text().ifBlank { "No Title" }
                } else {
                    title = "No Title"
                }

                // Safely extract artist
                val artistElement = seriesDetails.selectFirst(seriesArtistSelector)
                artist = artistElement?.ownText()?.removeEmptyPlaceholder()

                // Safely extract author
                val authorElement = seriesDetails.selectFirst(seriesAuthorSelector)
                author = authorElement?.ownText()?.removeEmptyPlaceholder()

                // Safely extract description
                val descriptionElements = seriesDetails.select(seriesDescriptionSelector)
                description = descriptionElements.joinToString("\n") { it.text() }.trim()
                    .ifBlank { "No description available." }

                // Safely extract alternative name and add to description
                val altNameElement = seriesDetails.selectFirst(seriesAltNameSelector)
                val altName = altNameElement?.ownText()?.takeIf { !it.isNullOrBlank() }
                if (!altName.isNullOrBlank()) {
                    description = "$description\n\n$altNamePrefix$altName".trim()
                }

                // Safely extract genres
                val genreElements = seriesDetails.select(seriesGenreSelector)
                val genres = genreElements.map { it.text() }.toMutableList()

                // Safely add series type to genre
                val typeElement = seriesDetails.selectFirst(seriesTypeSelector)
                val typeText = typeElement?.ownText()?.takeIf { !it.isNullOrBlank() }
                typeText?.let { genres.add(it) }

                genre = genres.map { genre ->
                    genre.lowercase(java.util.Locale.forLanguageTag(lang)).replaceFirstChar { char ->
                        if (char.isLowerCase()) {
                            char.titlecase(java.util.Locale.forLanguageTag(lang))
                        } else {
                            char.toString()
                        }
                    }
                }.joinToString { it.trim() }.ifEmpty { "No genres" }

                // Safely extract status
                val statusElement = seriesDetails.selectFirst(seriesStatusSelector)
                status = statusElement?.text()?.parseStatus() ?: SManga.UNKNOWN

                // Safely extract thumbnail
                val thumbnailElement = seriesDetails.selectFirst(seriesThumbnailSelector)
                thumbnail_url = thumbnailElement?.imgAttr()?.ifBlank { null }
            } else {
                // Set default values if seriesDetails is null
                title = "No Title"
                author = null
                artist = null
                description = "No description available."
                genre = "No genres"
                status = SManga.UNKNOWN
                thumbnail_url = null
            }
        }
    }

    // Override chapterFromElement to handle potential null values
    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val urlElements = element.select("a")
        val href = urlElements.attr("href")
        if (href.isNotBlank()) {
            setUrlWithoutDomain(href)
        } else {
            setUrlWithoutDomain("")
        }

        val chapterTitleElement = element.select(".lch a, .chapternum").first()
        val chapterTitle = chapterTitleElement?.text() ?: urlElements.firstOrNull()?.text() ?: "Chapter"
        name = chapterTitle.ifBlank {
            urlElements.firstOrNull()?.text() ?: "Chapter"
        }

        val dateElement = element.selectFirst(".chapterdate")
        date_upload = dateElement?.text()?.parseChapterDate() ?: 0L
    }
}
