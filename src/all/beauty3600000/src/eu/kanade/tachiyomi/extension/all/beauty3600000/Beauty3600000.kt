package eu.kanade.tachiyomi.extension.all.beauty3600000

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Beauty3600000 : KeiSource() {

    override val supportsLatest = false

    // Text search is heavy on the site's server
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = connectTimeout(120.seconds)
        .readTimeout(120.seconds)
        .rateLimit(1, 30.seconds) { it.queryParameter("search") != null }
        .rateLimit(1)

    // ========================= Popular =========================

    override suspend fun getPopularManga(page: Int): MangasPage = getPostsPage(page)

    // ========================= Latest =========================

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // ========================= Search =========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val filterList = filters.ifEmpty { getFilterList() }
        val categoryFilter = filterList.firstInstance<CategoryFilter>()
        val tagFilter = filterList.firstInstance<TagFilter>()
        val trimmedQuery = query.trim()

        val noFilter = categoryFilter.state <= 0 && tagFilter.state <= 0
        if (noFilter && trimmedQuery.isEmpty()) return getPopularManga(page)

        val tagId = if (noFilter) {
            runCatching { getTermsBySlug("tags", trimmedQuery) }.getOrNull()
                ?.firstOrNull { it.name.equals(trimmedQuery, ignoreCase = true) }
                ?.id
        } else {
            null
        }

        return getPostsPage(page) {
            if (trimmedQuery.isNotEmpty() && tagId == null) {
                addQueryParameter("search", trimmedQuery)
            }

            when {
                categoryFilter.state > 0 -> addQueryParameter("categories", categoryFilter.toUriPart())
                tagFilter.state > 0 -> addQueryParameter("tags", tagFilter.toUriPart())
                tagId != null -> addQueryParameter("tags", tagId.toString())
            }
        }
    }

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (url.host != baseUrl.toHttpUrl().host) return MangasPage(emptyList(), false)

        val segments = url.pathSegments.filter(String::isNotEmpty)
        val taxonomy = when (segments.firstOrNull()) {
            "category" -> "categories"
            "tag" -> "tags"
            else -> return super.getMangasByUrl(url, page)
        }
        val termId = segments.getOrNull(1)
            ?.let { getTermsBySlug(taxonomy, it).firstOrNull()?.id }
            ?: return MangasPage(emptyList(), false)

        return getPostsPage(page) { addQueryParameter(taxonomy, termId.toString()) }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = url.queryParameter("p")?.trim()
        val apiUrl = apiUrl("posts").apply {
            if (id != null) {
                id.toIntOrNull()?.let { addQueryParameter("include", it.toString()) }
                    // Allow copy old entry's `manga.url` to search for old entry for migration which is in format "https://3600000.xyz/?p=/{slug}/"
                    ?: addQueryParameter("slug", id.removeSurrounding("/"))
            } else {
                val slug = url.pathSegments.lastOrNull { it.isNotBlank() }?.removeSuffix(".html") ?: return null
                addQueryParameter("slug", slug)
            }
        }.build()

        return client.get(apiUrl, Fetch.XHR).parsePosts().firstOrNull()?.toSManga()
    }

    // ========================= Details =========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val post = getPost(manga)
        val details = if (fetchDetails) {
            post.toSManga().apply { genre = getGenres(post.id) }
        } else {
            manga
        }
        return SMangaUpdate(details, listOf(post.toSChapter()))
    }

    private suspend fun getPost(manga: SManga): PostDto {
        val id = manga.url.toIntOrNull()
        val url = apiUrl("posts").apply {
            if (id != null) {
                addPathSegment(id.toString())
            } else {
                addQueryParameter("slug", manga.url.removeSurrounding("/"))
            }
        }.build()

        val response = client.get(url, Fetch.XHR)
        return if (id != null) {
            response.parseAs()
        } else {
            response.parsePosts().firstOrNull() ?: throw Exception("Post not found")
        }
    }

    private suspend fun getGenres(postId: Int): String? = listOf("categories", "tags")
        .parallelCatchingFlatMap { getPostTerms(postId, it) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString { it.name }

    private suspend fun getPostTerms(postId: Int, taxonomy: String): List<TermDto> {
        val url = apiUrl(taxonomy)
            .addQueryParameter("post", postId.toString())
            .build()
        return client.get(url, Fetch.XHR).parseAs()
    }

    private suspend fun getTermsBySlug(taxonomy: String, slug: String): List<TermDto> {
        val url = apiUrl(taxonomy)
            .addQueryParameter("slug", slug)
            .build()
        return client.get(url, Fetch.XHR).parseAs()
    }

    override val supportsRelatedMangas = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val postId = manga.url.toIntOrNull() ?: getPost(manga).id
        val tags = getPostTerms(postId, "tags")
            .sortedBy { it.name.startsWith('[') }

        return tags.parallelCatchingFlatMap { tag ->
            getPostsPage(1) { addQueryParameter("tags", tag.id.toString()) }.mangas
        }
    }

    override fun getMangaUrl(manga: SManga): String = manga.url.toIntOrNull()
        ?.let { "$baseUrl/?p=${manga.url}" }
        ?: "$baseUrl${manga.url}"

    // ========================= Chapters =========================

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/?p=${chapter.url}"

    // ========================= Pages =========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = apiUrl("posts")
            .addPathSegment(chapter.url)
            .build()
        return client.get(url, Fetch.XHR).parseAs<PostDto>().toPageList()
    }

    // ========================= Filters =========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("NOTE: Only one filter will be applied!"),
        Filter.Separator(),
        CategoryFilter(),
        TagFilter(),
    )

    // ========================= Helpers =========================

    private fun apiUrl(endpoint: String): HttpUrl.Builder = baseUrl.toHttpUrl().newBuilder()
        .addPathSegments(API_BASE)
        .addPathSegment(endpoint)

    private suspend fun getPostsPage(page: Int, configure: HttpUrl.Builder.() -> Unit = {}): MangasPage {
        val url = apiUrl("posts")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PER_PAGE.toString())
            .apply(configure)
            .build()
        val response = client.get(url, Fetch.XHR)
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 0
        val mangas = response.parsePosts().map { it.toSManga() }
        return MangasPage(mangas, page < totalPages)
    }

    // The site may print PHP warnings before the JSON
    private fun Response.parsePosts(): List<PostDto> = parseAs { jsonArrayRegex.find(it)?.value ?: "[]" }

    /**
     * Parallel implementation of [Iterable.flatMap], but running
     * the transformation function inside a try-catch block.
     */
    private suspend inline fun <A, B> Iterable<A>.parallelCatchingFlatMap(crossinline f: suspend (A) -> Iterable<B>): List<B> = withContext(Dispatchers.IO) {
        map {
            async {
                try {
                    f(it)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    companion object {
        private const val API_BASE = "wp-json/wp/v2"
        private const val PER_PAGE = 100
        private val jsonArrayRegex by lazy { Regex("""\[.*]\s*$""") }
    }
}
