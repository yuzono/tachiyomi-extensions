package eu.kanade.tachiyomi.extension.en.allporncomic

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element

class AllPornComic : Madara("AllPornComic", "https://allporncomic.com", "en") {
    override val mangaSubString = "porncomic"

    override fun relatedMangaListSelector() = ".crp_related a.crp_link"

    override fun relatedMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        element.selectFirst(".crp_title")!!.let {
            title = it.ownText()
        }
        element.selectFirst("img")?.let {
            thumbnail_url = imageFromElement(it)
        }
    }
}
