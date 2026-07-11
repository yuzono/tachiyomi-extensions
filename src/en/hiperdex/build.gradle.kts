plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hiperdex"
    versionCode = 82
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "hiper"

    source {
        lang = "en"
        baseUrl {
            custom("https://hiperdex.tv")
        }
    }
}
