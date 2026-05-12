package eu.kanade.tachiyomi.extension.pt.taimumangas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.extractNextJs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class TaimuMangas : HttpSource() {

    override val name = "Taimu Mangas"

    override val baseUrl = "https://taimumangas.rzword.xyz"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 2, 1, TimeUnit.SECONDS)
        .rateLimitHost(API_URL.toHttpUrl(), 2, 1, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    private fun rscHeaders(): Headers = headersBuilder()
        .add("RSC", "1")
        .build()

    // ================================ Popular =======================================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/biblioteca?sort_by=total_views&sort_order=desc&page=$page", rscHeaders())

    override fun popularMangaParse(response: Response): MangasPage {
        val library = response.extractNextJs<TaimuLibraryDto>(::isLibraryData)
            ?: return MangasPage(emptyList(), false)

        val mangas = library.series.map { it.toSManga() }
        return MangasPage(mangas, library.pagination.hasNext)
    }

    // ================================ Latest =======================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/biblioteca?page=$page", rscHeaders())

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ================================ Search =======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("biblioteca")
            addQueryParameter("name", query)
            addQueryParameter("sort_by", "total_views")
            addQueryParameter("sort_order", "desc")
            addQueryParameter("page", page.toString())
        }
        return GET(urlBuilder.build(), rscHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ================================ Details =======================================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", rscHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = response.extractNextJs<TaimuSeriesDetailDto>(::isSeriesDetail)!!

        return detail.toSManga()
    }

    // ================================ Chapters =======================================

    override fun chapterListParse(response: Response): List<SChapter> {
        val detail = response.extractNextJs<TaimuSeriesDetailDto>(::isSeriesDetail)
            ?: return emptyList()

        val chapters = detail.chapters.chapters.map { it.toSChapter() }.toMutableList()

        // Fetch remaining chapter pages if there are more
        if (detail.chapters.hasNext) {
            val seriesCode = response.request.url.pathSegments.last { it.isNotEmpty() }
            for (page in 2..detail.chapters.totalPages) {
                try {
                    val pageDetail = client.newCall(
                        GET("$baseUrl/series/$seriesCode?page=$page", rscHeaders()),
                    ).execute().use { pageResponse ->
                        pageResponse.extractNextJs<TaimuSeriesDetailDto>(::isSeriesDetail)
                    }
                        ?: break
                    chapters.addAll(pageDetail.chapters.chapters.map { it.toSChapter() })
                } catch (_: Exception) {
                    break
                }
            }
        }

        return chapters.sortedByDescending { it.date_upload }
    }

    // ================================ Pages =======================================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", rscHeaders())

    override fun pageListParse(response: Response): List<Page> {
        val readerData = response.extractNextJs<TaimuReaderDataDto>(::isReaderData)
            ?: return emptyList()

        return readerData.chapter.pages
            .sortedBy { it.number }
            .mapIndexed { index, page ->
                Page(index, imageUrl = "$API_URL/media/${page.path}")
            }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val headers = headersBuilder()
            .add("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, headers)
    }

    // ================================ Predicates =======================================

    /**
     * Predicate to identify library data in the RSC payload.
     * Library data contains "series" array and "pagination" object.
     */
    private fun isLibraryData(element: JsonElement): Boolean {
        if (element !is JsonObject) return false
        return "series" in element && "pagination" in element
    }

    /**
     * Predicate to identify series detail data in the RSC payload.
     * Series detail contains "seriesData" wrapper or the detail fields directly.
     */
    private fun isSeriesDetail(element: JsonElement): Boolean {
        if (element !is JsonObject) return false
        return "coverImage" in element &&
            "statusOriginal" in element &&
            "chapters" in element
    }

    /**
     * Predicate to identify reader/chapter data in the RSC payload.
     * Reader data has "chapterData" wrapper containing "series" and "chapter" with "pages".
     */
    private fun isReaderData(element: JsonElement): Boolean {
        if (element !is JsonObject) return false
        if ("series" !in element || "chapter" !in element) return false
        val chapter = element["chapter"]
        return chapter is JsonObject && "pages" in chapter
    }

    companion object {
        const val API_URL = "https://api.taimumangas.com"
    }
}
