package eu.kanade.tachiyomi.extension.ar.neverscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

@Source
open class NeverScans : HttpSource() {

    override val name = "NeverScans"

    override val lang = "ar"

    override val baseUrl = "https://neverscans.com"

    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manga", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("a[href^=/manga/]").mapNotNull { el ->
            val href = el.attr("href")
            val slug = href.removePrefix("/manga/")
            if (slug.isBlank() || slug.contains("/") || slug.contains("?")) return@mapNotNull null
            val img = el.select("img").firstOrNull()
            val title = img?.attr("alt")?.trim()?.ifBlank { null }
                ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = img?.attr("abs:src")
            }
        }.distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            val url = "$baseUrl/manga".toHttpUrl().newBuilder()
                .addQueryParameter("search", query)
                .build()
            GET(url, headers)
        } else {
            GET("$baseUrl/manga", headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val html = doc.outerHtml()
        val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val namePattern = Regex(""""name"\s*:\s*"([^"]+)"""")
        val descPattern = Regex(""""description"\s*:\s*"([^"]+)"""")
        val imgPattern = Regex(""""image"\s*:\s*"([^"]+)"""")

        var title = ""
        var description = ""
        var thumbnail = ""

        for (match in scriptPattern.findAll(html)) {
            val content = match.groupValues[1]
            if (!content.contains("@type")) continue
            if (!content.contains("Book") && !content.contains("CreativeWorkSeries")) continue

            namePattern.find(content)?.let { title = it.groupValues[1] }
            descPattern.find(content)?.let { description = it.groupValues[1] }
            imgPattern.find(content)?.let { thumbnail = it.groupValues[1] }
            break
        }

        if (title.isBlank()) {
            title = doc.select("h1").text()
        }

        return SManga.create().apply {
            this.title = title.ifBlank { return@apply }
            this.description = description
            this.thumbnail_url = thumbnail
            url = response.request.url.encodedPath
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val html = doc.outerHtml()
        val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val itemPattern = Regex(""""itemListElement"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val itemEntryPattern = Regex(
            """"url"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"([^"]+)"""",
        )

        for (match in scriptPattern.findAll(html)) {
            val content = match.groupValues[1]
            if (!content.contains("ItemList")) continue

            val itemListMatch = itemPattern.find(content) ?: continue
            val items = itemListMatch.groupValues[1]

            return itemEntryPattern.findAll(items).map { entry ->
                val url = entry.groupValues[1].removePrefix("https://neverscans.com")
                val name = entry.groupValues[2]
                SChapter.create().apply {
                    this.url = url
                    this.name = name
                    chapter_number = parseChapterNumber(url)
                }
            }.toList().sortedByDescending { it.chapter_number }
        }

        return emptyList()
    }

    private fun parseChapterNumber(url: String): Float {
        val path = url.trimEnd('/').substringAfterLast("/")
        val numStr = path
            .removePrefix("chapter-")
            .removeSuffix(".0")
        return numStr.toFloatOrNull() ?: 0f
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val html = doc.outerHtml()

        val rscPattern = Regex("""id:\s*"([a-f0-9-]+)"\s*,\s*index:\s*(\d+)\s*,\s*imageUrl:\s*"/api/public/page/[a-f0-9-]+"""")
        val rscMatches = rscPattern.findAll(html).toList()
        if (rscMatches.isNotEmpty()) {
            return rscMatches.map { match ->
                val index = match.groupValues[2].toIntOrNull() ?: 0
                val url = match.groupValues[3]
                Page(index - 1, imageUrl = "$baseUrl$url")
            }
        }

        val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val imagePattern = Regex(""""image"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val uuidPattern = Regex(""""(/api/public/page/[a-f0-9-]+)""")

        for (match in scriptPattern.findAll(html)) {
            val content = match.groupValues[1]
            if (!content.contains("api/public/page")) continue

            val imgMatch = imagePattern.find(content) ?: continue
            val imageArray = imgMatch.groupValues[1]
            val uuids = uuidPattern.findAll(imageArray)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            if (uuids.isNotEmpty()) {
                return uuids.mapIndexed { index, url ->
                    Page(index, imageUrl = "$baseUrl$url")
                }
            }
        }

        return emptyList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
