package eu.kanade.tachiyomi.extension.all.myreadingmanga

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class MyReadingMangaFactory : SourceFactory {
    override fun createSources(): List<Source> = languageList.map { MyReadingManga(it.tachiLang, it.siteLang, it.latestLang) }
}

private data class Source(val tachiLang: String, val siteLang: String, val latestLang: String = siteLang)

// These should all be valid. Add a language code and uncomment to enable
private val languageList = listOf(
    Source("ar", "Arabic"),
    Source("id", "Bahasa Indonesia", "bahasa"),
//    Source("bg", "Bulgarian"),
//    Source("zh_hk", "Cantonese"),
    Source("zh", "Chinese"),
    Source("zh_tw", "Chinese (Traditional)", "traditional-chinese"),
    Source("hr", "Croatian"),
//    Source("cs", "Czech"),
//    Source("da", "Danish"),
    Source("en", "English"),
    Source("fil", "Filipino"),
//    Source("fi", "Finnish"),
//    Source("nl", "Flemish Dutch", "flemish-dutch"),
    Source("fr", "French"),
    Source("de", "German"),
//    Source("el", "Greek"),
//    Source("he", "Hebrew"),
//    Source("hi", "Hindi"),
    Source("hu", "Hungarian"),
    Source("it", "Italian"),
    Source("ja", "jp"),
    Source("ko", "Korean"),
    Source("lt", "Lithuanian"),
//    Source("ms", "Malay"),
//    Source("no", "Norwegian Bokmål", "norwegian-bokmal"),
    Source("fa", "Persian"),
    Source("pl", "Polish"),
    Source("pt-BR", "Portuguese"),
//    Source("ro", "Romanian"),
    Source("ru", "Russian"),
    Source("sk", "Slovak"),
    Source("es", "Spanish"),
    Source("sv", "Swedish"),
    Source("th", "Thai"),
    Source("tr", "Turkish"),
    Source("vi", "Vietnamese"),
)
