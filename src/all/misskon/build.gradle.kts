import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MissKon"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    kmkVersionCode = 10

    source {
        lang = "all"
        baseUrl = "https://misskon.com"
    }
}
