package eu.kanade.tachiyomi.multisrc.hiper

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

abstract class Hiper :
    HttpSource(),
    ConfigurableSource {

    protected val preferences by getPreferencesLazy()

    protected open val mangaPath: String = "manga"

    override val supportsLatest: Boolean = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .add("X-Hpx-Nexus", "hpx-block-f91")

    private val acceptHeaders = headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // Fetch baseUrl with accept headers which then populates a cookie
            if (response.code == 401) {
                response.close()
                network.client.newCall(GET(baseUrl, acceptHeaders)).execute().close()
                chain.proceed(chain.request())
            } else {
                response
            }
        }
        .build()

    // ============================ Popular ====================================

    private val popularFilter = FilterList(OrderByFilter("", arrayOf("" to "popular")))

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", popularFilter)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================ Latest ====================================
    private val latestFilter = FilterList(OrderByFilter("", arrayOf("" to "newest")))

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(page, "", latestFilter)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================ Search ====================================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val limit = 30
        val typeValue = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selected()
        val statusValue = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selected()
        val ratingValue = filters.filterIsInstance<RatingFilter>().firstOrNull()?.selected()
        val genresValue = filters.filterIsInstance<GenresFilter>().firstOrNull()?.checked.orEmpty()

        val input = buildJsonObject {
            putJsonObject("0") {
                putJsonObject("json") {
                    put("q", query)
                    filters.filterIsInstance<OrderByFilter>().forEach { filter ->
                        put("sort", filter.selected())
                    }
                    putJsonObject("filters") {
                        if (genresValue.isEmpty()) {
                            put("genres", null)
                        } else {
                            putJsonArray("genres") { genresValue.forEach { add(it) } }
                        }
                        put("type", typeValue)
                        put("status", statusValue)
                        put("contentRating", ratingValue)
                        put("author", null)
                        put("artist", null)
                        put("year", null)
                    }

                    put("limit", limit)
                    put("offset", (page - 1) * limit)
                    put("maxRating", preferences.getString(MAX_RATING_PREF, MAX_RATING_DEFAULT))
                }
                putJsonObject("meta") {
                    putJsonObject("values") {
                        if (genresValue.isEmpty()) put("filters.genres", buildJsonArray { add("undefined") })
                        if (typeValue == null) put("filters.type", buildJsonArray { add("undefined") })
                        if (statusValue == null) put("filters.status", buildJsonArray { add("undefined") })
                        if (ratingValue == null) put("filters.contentRating", buildJsonArray { add("undefined") })
                        put("filters.author", buildJsonArray { add("undefined") })
                        put("filters.artist", buildJsonArray { add("undefined") })
                        put("filters.year", buildJsonArray { add("undefined") })
                    }
                }
            }
        }.toString()

        val url = "$baseUrl/api/trpc/search.query".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", input)
            .build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val element = response.parseAs<List<JsonElement>>().first()
        val dto = element["result"]["data"]["json"]?.parseAs<WrapperContent>() ?: return MangasPage(emptyList(), false)
        return MangasPage(dto.hits.map { it.toSManga(mangaPath) }, dto.hits.isNotEmpty())
    }

    // ============================ Details ===================================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url
            .substringAfterLast("$mangaPath/")
            .substringBefore("#")
            .takeIf(String::isNotBlank)
            ?: throw IOException("Migrate from $name to $name")

        val input = buildJsonObject {
            putJsonObject("0") {
                put("json", null)
                putJsonObject("meta") {
                    putJsonArray("values") {
                        add("undefined")
                    }
                }
            }
            putJsonObject("1") {
                putJsonObject("json") {
                    put("slug", slug)
                }
            }
        }

        val url = "$baseUrl/api/trpc/auth.me,series.bySlugWithGenres".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", input.toString())
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val element = response.parseAs<List<JsonElement>>().last()
        return element["result"]["data"]["json"]!!.parseAs<MangaDto>().toSManga(mangaPath)
    }

    // ============================ Chapters ==================================

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast("#").toLongOrNull()
            ?: throw IOException("Migrate from $name to $name")
        val input = buildJsonObject {
            putJsonObject("0") {
                putJsonObject("json") {
                    putJsonArray("values") {
                        add("undefined")
                    }
                }
            }

            putJsonObject("1") {
                putJsonObject("json") {
                    put("seriesId", mangaId)
                    put("chapterId", null)
                    put("sort", "best")
                    put("page", 1)
                    put("limit", 20)
                }
                putJsonObject("meta") {
                    putJsonObject("values") {
                        put("chapterId", buildJsonArray { add("undefined") })
                    }
                }
            }

            putJsonObject("2") {
                putJsonObject("json") {
                    put("seriesId", mangaId)
                }
            }
        }

        val url = "$baseUrl/api/trpc/auth.me,comments.list,series.chapters".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", input.toString())
            .fragment(manga.url)
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaPath = response.request.url.fragment!!
        val element = response.parseAs<List<JsonElement>>().last()
        val chaptersDTO = element["result"]["data"]["json"]!!.parseAs<List<ChapterDto>>()
        return chaptersDTO.map { it.toSChapter(mangaPath) }
    }

    // ============================ Pages =====================================

    override fun pageListRequest(chapter: SChapter): Request {
        val slug = chapter.url
            .substringAfterLast("$mangaPath/")
            .substringBefore("#")
            .takeIf(String::isNotBlank)
            ?: throw IOException("Migrate from $name to $name")

        val input = buildJsonObject {
            putJsonObject("0") {
                put("json", null)
                putJsonObject("meta") {
                    putJsonArray("values") {
                        add("undefined")
                    }
                }
            }
            putJsonObject("1") {
                putJsonObject("json") {
                    put("slug", slug)
                }
            }
            putJsonObject("2") {
                putJsonObject("json") {
                    put("seriesSlug", slug)
                    put("chapterNumber", chapter.chapter_number)
                }
            }
            putJsonObject("3") {
                putJsonObject("json") {
                    put("position", "footer_bottom")
                }
            }
        }

        val url = "$baseUrl/api/trpc/auth.me,series.bySlug,reader.chapterPages".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", input.toString())
            .build()

        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val element = response.parseAs<List<JsonElement>>().last()
        val pages = element["result"]["data"]["json"]?.parseAs<List<PageDto>>() ?: return emptyList()
        return pages.map(PageDto::toPage)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================ Preferences ================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = MAX_RATING_PREF
            title = "Default max rating"
            summary = "Restricts content to the selected rating or below.\nCurrently: %s"
            entries = MAX_RATING_ENTRIES
            entryValues = MAX_RATING_VALUES
            setDefaultValue(MAX_RATING_DEFAULT)
        }.let(screen::addPreference)
    }

    // ============================ Filters ====================================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        RatingFilter(),
        TypeFilter(),
        StatusFilter(),
        GenresFilter(),
    )

    open class OrderByFilter(displayName: String, private val vals: Array<Pair<String, String>>, state: Int = 0) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
        fun selected() = vals[state].second
    }

    class SortFilter :
        OrderByFilter(
            "Sort",
            arrayOf(
                "Relevance" to "relevance",
                "Popularity" to "popular",
                "Score" to "score",
                "Recent Updated" to "recent",
                "Newest" to "newest",
                "Oldest" to "oldest",
                "A-Z" to "alphabetical",
            ),
        )

    open class SelectFilter(displayName: String, private val vals: Array<Pair<String, String?>>, state: Int = 0) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
        fun selected(): String? = vals[state].second
    }

    class RatingFilter :
        SelectFilter(
            "Rating",
            (
                listOf(
                    "All" to null,
                ) + MAX_RATING_ENTRIES.zip(MAX_RATING_VALUES).map { it.first to it.second }
                )
                .toTypedArray(),
        )

    class TypeFilter :
        SelectFilter(
            "Type",
            arrayOf(
                "All" to null,
                "Manga" to "manga",
                "Manhwa" to "manhwa",
                "Manhua" to "manhua",
                "Novel" to "novel",
                "Webtoon" to "webtoon",
                "One Shot" to "one_shot",
            ),
        )

    class StatusFilter :
        SelectFilter(
            "Status",
            arrayOf(
                "All" to null,
                "Ongoing" to "ongoing",
                "Completed" to "completed",
                "Hiatus" to "hiatus",
                "Cancelled" to "cancelled",
            ),
        )

    class GenreCheckBox(val value: String) : Filter.CheckBox(value)

    class GenresFilter :
        Filter.Group<GenreCheckBox>(
            "Genres",
            GENRE_LIST.map { GenreCheckBox(it) },
        ) {
        val checked get() = state.filter { it.state }.map { it.value }
    }

    companion object {
        private const val MAX_RATING_PREF = "MAX_RATING"
        private const val MAX_RATING_DEFAULT = "pornographic"
        private val MAX_RATING_ENTRIES = arrayOf("Pornographic", "Erotica", "Suggestive", "Safe")
        private val MAX_RATING_VALUES = arrayOf("pornographic", "erotica", "suggestive", "safe")

        private val GENRE_LIST = listOf(
            "4-Koma",
            "Action",
            "Adaptation",
            "Adult",
            "Adventure",
            "Age Gap",
            "Aliens",
            "Ancient Korea",
            "Anthology",
            "Campus",
            "Childhood Friends",
            "Comedy",
            "Cooking",
            "Crime",
            "Crossdressing",
            "Dance",
            "Delinquents",
            "Demons",
            "Doujinshi",
            "Drama",
            "Ecchi",
            "Escolar",
            "Fantasy",
            "Fellatio/Blowjob",
            "Fetish",
            "Full Color",
            "Furry",
            "Gender Bender",
            "Genderswap",
            "Ghosts",
            "Girls' Love",
            "Gore",
            "Guideverse",
            "Gyaru",
            "Hair Color Change",
            "Harem",
            "Hentai",
            "Heroes",
            "Historical",
            "Horror",
            "Human-Nonhuman Relationship",
            "Isekai",
            "Josei",
            "Korea",
            "Korean Ambience",
            "Korean BL",
            "Long Strip",
            "Long-Haired Male Character/s",
            "Long-Haired Male Lead",
            "Love Triangle/s",
            "Low Fantasy",
            "Maduro",
            "Mafia",
            "Magic",
            "Male Protagonist",
            "Manga",
            "Martial Arts",
            "Masculine Uke",
            "Mature",
            "Mecha",
            "Medical",
            "Military",
            "Monster Girls",
            "Monsters",
            "Monsters Invade Earth",
            "Murim",
            "Muscular Male Lead",
            "Muscular Uke",
            "Music",
            "Mystery",
            "Nameverse",
            "Ninja",
            "Office Workers",
            "Older Uke Younger Seme",
            "Oneshot",
            "Orphan Female Lead",
            "Police",
            "Post-Apocalyptic",
            "Psychological",
            "Red-Haired Male Lead",
            "Red-Haired Seme",
            "Regression",
            "Reincarnation",
            "Revenge",
            "Romance",
            "Samurai",
            "School Life",
            "Sci-fi",
            "Secret Relationship",
            "Seinen",
            "Sexual Violence",
            "Shota",
            "Shoujo",
            "Shoujo Ai",
            "Shounen",
            "Size Difference",
            "Slice of Life",
            "Smut",
            "Sobrenatural",
            "Sports",
            "Superhero",
            "Supernatural",
            "Survival",
            "Suspense",
            "Thriller",
            "Time Travel",
            "Tower",
            "Tragedy",
            "Uncensored",
            "Video Games",
            "Villainess",
            "Violence",
            "Virtual Reality",
            "Web Comic",
            "Webtoon",
            "Wuxia",
            "Yaoi",
            "Yuri",
        )
    }
}
