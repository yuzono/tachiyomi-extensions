import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Photos18"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl = "https://www.photos18.com"
    }

    deeplink {
        host("www.photos18.com")
        host("photos18.com")
        path("/v/..*")
        path("/zh-hans/v/..*")
    }
}
