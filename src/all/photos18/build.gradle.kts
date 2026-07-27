import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Photos18"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl = "https://www.photos18.com"
    }

    deeplink {
        path("/..*/v/..*")
    }
}
