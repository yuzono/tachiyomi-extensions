plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hiperdex"
    versionCode = 81
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "hiper"
    kmkVersionCode = 1

    source {
        lang = "en"
        baseUrl {
            custom("https://hiperdex.tv")
        }
    }
}
