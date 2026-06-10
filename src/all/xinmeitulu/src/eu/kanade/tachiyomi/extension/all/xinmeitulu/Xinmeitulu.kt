package eu.kanade.tachiyomi.extension.all.xinmeitulu

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import rx.Observable
import java.util.Locale

class Xinmeitulu : HttpSource() {
    override val baseUrl = "https://www.xinmeitulu.com"
    override val lang = "all"
    override val name = "Xinmeitulu"
    override val supportsLatest = false

    override val client = network.client.newBuilder().addInterceptor(::contentTypeIntercept).build()

    // Latest

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // Popular

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/page/$page")

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".container > .row > div:has(figure)").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.select("figure > a").attr("abs:href"))
                title = element.select("figcaption").text()
                thumbnail_url = element.select("img").attr("abs:data-original-")
                genre = element.select("a[rel='tag category']")
                    .joinToString {
                        it.text().removeSuffix("写真")
                            .translate()
                    }
            }
        }
        val hasNextPage = document.selectFirst(".next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = filters.ifEmpty { getFilterList() }
        val regionFilter = filterList.firstInstanceOrNull<RegionFilter>()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            regionFilter?.toUriPart()?.let {
                addPathSegment("area")
                addPathSegment(it)
            }
            addPathSegment("page")
            addPathSegment(page.toString())
            addQueryParameter("s", query)
        }.toString()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val slug = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return fetchSearchManga(page, "SLUG:$slug", filters)
        }

        return if (query.startsWith("SLUG:")) {
            val slug = query.removePrefix("SLUG:")
            client.newCall(GET("$baseUrl/photo/$slug", headers)).asObservableSuccess()
                .map { response -> MangasPage(listOf(mangaDetailsParse(response)), false) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    // Details

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val document = response.asJsoup()
        setUrlWithoutDomain(document.selectFirst("link[rel=canonical]")!!.attr("abs:href"))
        title = document.select(".container > h1").text()
        status = SManga.COMPLETED
        thumbnail_url = document.selectFirst("figure img")!!.attr("abs:data-original")
        description = document.select(".container > p").joinToString("\n") {
            val str = it.text()
            if (str.contains("拍摄机构：")) {
                author = str.replace("拍摄机构：", "").trim()
            }
            str.replace("拍摄机构：", "${"拍摄机构".translate()}: ")
                .replace("相关编号：", "${"相关编号".translate()}: ")
                .replace("图片数量：", "${"图片数量".translate()}: ")
                .replace("发行日期：", "${"发行日期".translate()}: ")
                .replace("出镜模特：", "${"出镜模特".translate()}: ")
                .replace("别名：", "\n${"别名".translate()}: ")
                .replace("生日：", "\n${"生日".translate()}: ")
                .replace("身高：", "\n${"身高".translate()}: ")
                .replace("三围：", "\n${"三围".translate()}: ")
                .replace("罩杯：", "\n${"罩杯".translate()}: ")
                .replace("杯", "-${"杯".translate()}")
                .replace("匿名", "匿名".translate())
                .replace("；", "")
                .trim()
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return listOf(
            SChapter.create().apply {
                setUrlWithoutDomain(document.selectFirst("link[rel=canonical]")!!.attr("abs:href"))
                name = "Gallery"
            },
        )
    }

    // Pages

    override fun pageListParse(response: Response) = response.asJsoup()
        .select(".container > div > figure img")
        .mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:data-original"))
        }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList(): FilterList = FilterList(
        RegionFilter(getRegionList()),
    )

    private class RegionFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("Region", vals)

    private fun getRegionList(): Array<Pair<String?, String>> = arrayOf(
        null to "全部".translate(),
        "zhongguodalumeinyu" to "中国大陆美女".translate(),
        "taiguomeinyu" to "泰国美女".translate(),
        "ribenmeinyu" to "日本美女".translate(),
        "hanguomeinyu" to "韩国美女".translate(),
        "taiwanmeinyu" to "台湾美女".translate(),
        "oumeimeinyu" to "欧美美女".translate(),
    )

    private fun String.translate(): String {
        if (Locale.getDefault().language.startsWith("zh")) return this
        return when (this) {
            // Region
            "全部" -> "All"
            "中国大陆美女" -> "Chinese beauty"
            "泰国美女" -> "Thailand beauty"
            "日本美女" -> "Japanese beauty"
            "韩国美女" -> "Korean beauty"
            "台湾美女" -> "Taiwanese beauty"
            "欧美美女" -> "European & American beauty"
            // Descriptions
            "拍摄机构" -> "Studio"
            "相关编号" -> "Issue number"
            "图片数量" -> "Photos"
            "发行日期" -> "Release date"
            "出镜模特" -> "Model"
            "别名" -> "Alias"
            "生日" -> "Birthday"
            "身高" -> "Height"
            "三围" -> "Measurements"
            "罩杯" -> "Cup size"
            "杯" -> "cup"
            "匿名" -> "Unknown"
            else -> this
        }
    }

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String?, String>>,
    ) : Filter.Select<String>(
        displayName,
        vals.map { it.second }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].first
    }

    companion object {
        private fun contentTypeIntercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (response.header("content-type")?.startsWith("image") == true) {
                val body = response.body.source().asResponseBody(jpegMediaType)
                return response.newBuilder().body(body).build()
            }
            return response
        }

        private val jpegMediaType = "image/jpeg".toMediaType()
    }
}
