package eu.kanade.tachiyomi.extension.all.misskon

import eu.kanade.tachiyomi.source.model.Filter

class TopDays(val name: String, val uri: String)

class TopDaysFilter(displayName: String, private val days: Array<TopDays>) : Filter.Select<String>(displayName, days.map { it.name }.toTypedArray()) {
    fun toUriPart() = days[state].uri
}

fun getTopDaysList() = arrayOf(
    TopDays("<Select>", ""),
) + topDaysList()

fun topDaysList() = arrayOf(
    TopDays("Top 3 days", "top3"),
    TopDays("Top week", "top7"),
    TopDays("Top month", "top30"),
    TopDays("Top 2 months", "top60"),
)

class TagsFilter(displayName: String, private val tags: List<Pair<String, String>>) : Filter.Select<String>(displayName, tags.map { it.first }.toTypedArray()) {
    fun toUriPart() = tags[state].second
}

// console.log([...document.querySelectorAll(".tag-counterz a")].map((el) => `Tag("${el.innerText.trim()}", "${el.getAttribute('href')}"),`).join('\n'))
val TagList = setOf(
    Pair("<Select>", ""),
    Pair("--<Chinese>--", ""),
    Pair("[MTCos] 喵糖映画", "mtcos"),
    Pair("BoLoli", "bololi"),
    Pair("CANDY", "candy"),
    Pair("FEILIN", "feilin"),
    Pair("FToow", "ftoow"),
    Pair("GIRLT", "girlt"),
    Pair("HuaYan", "huayan"),
    Pair("HuaYang", "huayang"),
    Pair("IMISS", "imiss"),
    Pair("ISHOW", "ishow"),
    Pair("JVID", "jvid"),
    Pair("KelaGirls", "kelagirls"),
    Pair("Kimoe", "kimoe"),
    Pair("LegBaby", "legbaby"),
    Pair("MF", "mf"),
    Pair("MFStar", "mfstar"),
    Pair("MiiTao", "miitao"),
    Pair("MintYe", "mintye"),
    Pair("MISSLEG", "missleg"),
    Pair("MiStar", "mistar"),
    Pair("MTMeng", "mtmeng"),
    Pair("MyGirl", "mygirl"),
    Pair("PartyCat", "partycat"),
    Pair("QingDouKe", "qingdouke"),
    Pair("RuiSG", "ruisg"),
    Pair("SLADY", "slady"),
    Pair("TASTE", "taste"),
    Pair("TGOD", "tgod"),
    Pair("TouTiao", "toutiao"),
    Pair("TuiGirl", "tuigirl"),
    Pair("Tukmo", "tukmo"),
    Pair("UGIRLS", "ugirls"),
    Pair("UGIRLS - Ai You Wu App", "ugirls-ai-you-wu-app"),
    Pair("UXING", "uxing"),
    Pair("WingS", "wings"),
    Pair("XiaoYu", "xiaoyu"),
    Pair("XingYan", "xingyan"),
    Pair("XIUREN", "xiuren"),
    Pair("XR Uncensored", "xr-uncensored"),
    Pair("YouMei", "youmei"),
    Pair("YouMi", "youmi"),
    Pair("YouMi尤蜜", "youmiapp"),
    Pair("YouWu", "youwu"),
    Pair("--<Korean>--", ""),
    Pair("AG", "ag"),
    Pair("Bimilstory", "bimilstory"),
    Pair("BLUECAKE", "bluecake"),
    Pair("CreamSoda", "creamsoda"),
    Pair("DJAWA", "djawa"),
    Pair("Espacia Korea", "espacia-korea"),
    Pair("Fantasy Factory", "fantasy-factory"),
    Pair("Fantasy Story", "fantasy-story"),
    Pair("Glamarchive", "glamarchive"),
    Pair("HIGH FANTASY", "high-fantasy"),
    Pair("KIMLEMON", "kimlemon"),
    Pair("KIREI", "kirei"),
    Pair("KiSiA", "kisia"),
    Pair("Korean Realgraphic", "korean-realgraphic"),
    Pair("Lilynah", "lilynah"),
    Pair("Lookas", "lookas"),
    Pair("Loozy", "loozy"),
    Pair("Moon Night Snap", "moon-night-snap"),
    Pair("Paranhosu", "paranhosu"),
    Pair("PhotoChips", "photochips"),
    Pair("Pure Media", "pure-media"),
    Pair("PUSSYLET", "pussylet"),
    Pair("SAINT Photolife", "saint-photolife"),
    Pair("SWEETBOX", "sweetbox"),
    Pair("UHHUNG MAGAZINE", "uhhung-magazine"),
    Pair("UMIZINE", "umizine"),
    Pair("WXY ENT", "wxy-ent"),
    Pair("Yo-U", "yo-u"),
    Pair("--<Other>--", ""),
    Pair("AI Generated", "ai-generated"),
    Pair("Cosplay", "cosplay"),
    Pair("JP", "jp"),
    Pair("JVID", "jvid"),
    Pair("Patreon", "patreon"),
    Pair("--<More>--", ""),
)
