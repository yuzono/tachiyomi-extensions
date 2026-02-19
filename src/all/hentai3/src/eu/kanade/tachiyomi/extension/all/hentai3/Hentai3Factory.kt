package eu.kanade.tachiyomi.extension.all.hentai3

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class Hentai3Factory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Hentai3("all", ""),
        Hentai3("en", "english", "en"),
        Hentai3("ja", "japanese", "jpn"),
        Hentai3("ko", "korean", "kor"),
        Hentai3("zh", "chinese", "zho"),
        Hentai3("es", "spanish", "spa"),
        Hentai3("ru", "russian", "rus"),
        Hentai3("pt", "portuguese", "por"),
        Hentai3("fr", "french", "fra"),
        Hentai3("th", "thai", "tha"),
        Hentai3("it", "italian", "ita"),
        Hentai3("vi", "vietnamese", "vie"),
        Hentai3("ar", "arabic", "ara"),
        Hentai3("mo", "mongolian"),
        Hentai3("id", "indonesian"),
        Hentai3("jv", "javanese"),
        Hentai3("tl", "tagalog"),
        Hentai3("my", "burmese"),
        Hentai3("tr", "turkish"),
        Hentai3("uk", "ukrainian"),
        object : Hentai3("pl", "polish") {
            // lang changed from po to pl
            override val id = 7940950215101782907
        },
        Hentai3("fi", "finnish"),
        Hentai3("de", "german"),
        Hentai3("nl", "dutch"),
        Hentai3("cs", "czech"),
        Hentai3("hu", "hungarian"),
        Hentai3("bg", "bulgarian"),
        Hentai3("is", "icelandic"),
        Hentai3("la", "latin"),
        Hentai3("ceb", "cebuano"),
    )
}
