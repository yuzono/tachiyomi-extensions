package eu.kanade.tachiyomi.extension.all.hentai3

import keiyoushi.utils.tryParse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
import kotlin.time.Instant

internal object Hentai3Utils {
    fun getArtists(document: Document): String? {
        val artists = document.select("#main-info a[href*=/artists/]")
        return if (artists.isNotEmpty()) {
            artists.joinToString { it.cleanTag() }
        } else {
            null
        }
    }

    fun getGroups(document: Document): String? {
        val groups = document.select("#main-info a[href*=/groups/]")
        return if (groups.isNotEmpty()) {
            groups.joinToString { it.cleanTag() }
        } else {
            null
        }
    }

    fun getDescriptions(document: Document): String {
        val stringBuilder = StringBuilder()

        val categories = document.select("#main-info a[href*=/category/]")
        if (categories.isNotEmpty()) {
            stringBuilder.append("Categories: ")
            stringBuilder.append(categories.joinToString { it.cleanTag() })
            stringBuilder.append("\n")
        }

        val groups = getGroups(document)
        if (!groups.isNullOrBlank()) {
            stringBuilder.append("Groups: ")
            stringBuilder.append(groups)
            stringBuilder.append("\n")
        }
        stringBuilder.append("\n")

        val series = document.select("#main-info a[href*=/series/]")
        if (series.isNotEmpty()) {
            stringBuilder.append("Series: ")
            stringBuilder.append(series.joinToString { it.cleanTag() })
            stringBuilder.append("\n")
        }

        val characters = document.select("#main-info a[href*=/characters/]")
        if (characters.isNotEmpty()) {
            stringBuilder.append("Characters: ")
            stringBuilder.append(characters.joinToString { it.cleanTag() })
            stringBuilder.append("\n")
        }
        stringBuilder.append("\n")

        val languages = document.select("#main-info a[href*=/language/]")
        if (languages.isNotEmpty()) {
            stringBuilder.append("Languages: ")
            stringBuilder.append(languages.joinToString { it.cleanTag() })
        }

        return stringBuilder.toString()
    }

    // Everything listed under "Tags:", including the language and search links the site puts there
    fun getTags(document: Document): String {
        val tags = document.select("#main-info .tag-tags a.name")
        return tags.map { it.cleanTag() }.sorted().joinToString()
    }

    fun getNumPages(document: Document): String? = document.selectFirst("#main-info div.tag-container:containsOwn(Pages:) > span")?.cleanTag()

    fun getTime(document: Document): Long = Instant.tryParse(document.selectFirst("#main-info time")?.attr("datetime"))

    fun getCodes(document: Document): String? {
        val codes = document.select("#main-info h3 > strong")
        if (codes.isNotEmpty()) {
            return "Code: "
                .plus(
                    codes.eachText().filterNotNull().joinToString {
                        it.replace("d", "3hentai - #")
                            .replace("g", "nhentai - #")
                    },
                )
                .plus("\n\n")
        }
        return null
    }

    private fun Element.cleanTag(): String = text()
        .replace("(female)", "♀").replace("(male)", "♂")
        .replace(bracketRegex, "")
        .capitalizeEach()
        .trim()

    private val bracketRegex by lazy { Regex("""\(.*\)""") }

    private fun String.capitalizeEach() = this.split(" ").joinToString(" ") { s ->
        s.replaceFirstChar { sr ->
            if (sr.isLowerCase()) sr.titlecase(Locale.getDefault()) else sr.toString()
        }
    }
}
