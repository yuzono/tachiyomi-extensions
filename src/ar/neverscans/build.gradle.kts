plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NeverScans"
    versionCode = 0
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "ar"
        baseUrl = "https://neverscans.com"
        skipCodeGen.set(true)
    }
}
