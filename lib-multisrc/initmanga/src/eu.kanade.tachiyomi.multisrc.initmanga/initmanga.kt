package eu.kanade.tachiyomi.multisrc.initmanga

import android.annotation.SuppressLint
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

abstract class initmanga(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val mangaUrlDirectory: String = "seri",
    private val dateFormatStr: String = "yyyy-MM-dd'T'HH:mm:ss",
    private val popularUrlSlug: String = mangaUrlDirectory,
    private val latestUrlSlug: String = "son-guncellemeler",
) : ParsedHttpSource() {

    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val dateFormat by lazy { SimpleDateFormat(dateFormatStr, Locale.getDefault()) }
    private val STATIC_AES_KEY = "3b16050a4d52ef1ccb28dc867b533abfc7fcb6bfaf6514b8676550b2f12454fa"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/$popularUrlSlug/"
        } else {
            "$baseUrl/$popularUrlSlug/page/$page/"
        }
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "div.uk-panel"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val linkEl = element.selectFirst("h3 a")
            ?: element.selectFirst("div.uk-overflow-hidden a")
            ?: element.selectFirst("a")

        title = element.select("h3").text().trim().ifEmpty { "Bilinmeyen Seri" }
        setUrlWithoutDomain(linkEl?.attr("href") ?: "")
        thumbnail_url = element.select("img").let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
    }

    override fun popularMangaNextPageSelector() = "a:contains(Sonraki), a.next, #next-link a"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/$latestUrlSlug/page/$page/", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    @Deprecated("Use getSearchManga instead")
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.isNotBlank()) {
            return Observable.fromCallable {
                try {
                    val response = client.newCall(GET("$baseUrl/wp-json/initlise/v1/search?term=$query", headers)).execute()
                    val jsonArr = json.parseToJsonElement(response.body.string()).jsonArray
                    val mangas = jsonArr.map {
                        val obj = it.jsonObject
                        SManga.create().apply {
                            val rawTitle = obj["title"]?.jsonPrimitive?.content ?: ""
                            title = Jsoup.parse(rawTitle).text().trim()
                            setUrlWithoutDomain(obj["url"]?.jsonPrimitive?.content ?: "")
                            thumbnail_url = obj["thumb"]?.jsonPrimitive?.content
                        }
                    }
                    MangasPage(mangas, false)
                } catch (e: Exception) { MangasPage(emptyList(), false) }
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET("$baseUrl/page/$page/?s=$query", headers)
    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        description = document.select("div#manga-description p").text()
        genre = document.select("div#genre-tags a").joinToString { it.text() }
        thumbnail_url = document.selectFirst("div.single-thumb img")?.attr("abs:src")
            ?: document.selectFirst("a.story-cover img")?.attr("abs:src")
        val h1 = document.selectFirst("h1")?.text()
        val h2 = document.selectFirst("h2.uk-h3")?.text()
        title = if (!h2.isNullOrBlank()) h2 else h1 ?: "Başlık Yok"
    }

    @Deprecated("Use getChapterList instead")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapters = mutableListOf<SChapter>()
        var url = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
        var page = 0
        val maxPages = 50

        while (page++ < maxPages) {
            val response = client.newCall(GET(url, headers)).execute()
            if (!response.isSuccessful) {
                response.close()
                break
            }
            val doc = response.asJsoup()
            val items = doc.select(chapterListSelector())
            if (items.isEmpty()) break
            chapters.addAll(items.map { chapterFromElement(it) })
            url = doc.select("a:contains(Sonraki), #next-link a").first()?.attr("abs:href") ?: break
        }
        chapters
    }

    override fun chapterListSelector() = "div.chapter-item"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val a = element.selectFirst("a")!!
        setUrlWithoutDomain(a.attr("href"))
        name = element.select("h3").text().trim()
        date_upload = try {
            val d = element.select("time").attr("datetime")
            dateFormat.parse(d)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    @SuppressLint("NewApi")
    override fun pageListParse(document: Document): List<Page> {
        val html = document.html()
        val encMatch = Regex("""var\s+InitMangaEncryptedChapter\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)

        if (encMatch != null) {
            try {
                val encJson = json.parseToJsonElement(encMatch.groupValues[1]).jsonObject
                val ciphertext = encJson["ciphertext"]!!.jsonPrimitive.content
                val ivHex = encJson["iv"]!!.jsonPrimitive.content
                val saltHex = encJson["salt"]!!.jsonPrimitive.content

                var decryptedData: String? = null

                try {
                    decryptedData = CryptoAES.decryptWithStaticKey(ciphertext, STATIC_AES_KEY, ivHex)
                    if (decryptedData.isNullOrBlank() || (!decryptedData.trim().startsWith("<") && !decryptedData.trim().startsWith("["))) {
                        decryptedData = null
                    }
                } catch (_: Exception) { }

                if (decryptedData == null) {
                    val keyMatch = Regex(""""decryption_key"\s*:\s*"([^"]+)"""").find(html)
                    if (keyMatch != null) {
                        val pass = String(Base64.decode(keyMatch.groupValues[1], Base64.DEFAULT), StandardCharsets.UTF_8)
                        try { decryptedData = CryptoAES.decryptWithPassphrase(ciphertext, pass, saltHex, ivHex) } catch (_: Exception) { }
                    }
                }

                if (!decryptedData.isNullOrEmpty()) {
                    if (decryptedData.trim().startsWith("<")) {
                        return Jsoup.parseBodyFragment(decryptedData).select("img").mapIndexed { i, img ->
                            val src = img.attr("data-src").ifEmpty {
                                img.attr("src").ifEmpty { img.attr("data-lazy-src") }
                            }
                            val finalSrc = if (src.startsWith("/")) baseUrl + src else src
                            Page(i, "", finalSrc)
                        }
                    } else if (decryptedData.trim().startsWith("[")) {
                        return json.parseToJsonElement(decryptedData).jsonArray.mapIndexed { i, el ->
                            val src = el.jsonPrimitive.content
                            val finalSrc = if (src.startsWith("/")) baseUrl + src else src
                            Page(i, "", finalSrc)
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        return document.select("div#chapter-content img[src]").mapIndexed { i, img ->
            val src = img.attr("abs:src").ifEmpty { img.attr("data-src") }
            Page(i, "", src)
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Gerekli değil")

    object CryptoAES {
        fun decryptWithStaticKey(ciphertextBase64: String, keyHex: String, ivHex: String): String {
            val key = SecretKeySpec(hex(keyHex), "AES")
            val iv = IvParameterSpec(hex(ivHex))
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            return String(cipher.doFinal(Base64.decode(ciphertextBase64, Base64.DEFAULT)), StandardCharsets.UTF_8)
        }

        fun decryptWithPassphrase(ciphertextBase64: String, passphrase: String, saltHex: String, ivHex: String): String {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = PBEKeySpec(passphrase.toCharArray(), hex(saltHex), 999, 256)
            val key = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val iv = IvParameterSpec(hex(ivHex))
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            return String(cipher.doFinal(Base64.decode(ciphertextBase64, Base64.DEFAULT)), StandardCharsets.UTF_8)
        }

        private fun hex(s: String): ByteArray {
            val d = ByteArray(s.length / 2)
            for (i in d.indices) d[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
            return d
        }
    }
}
