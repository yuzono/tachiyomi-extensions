import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "XArt Hunter"
    versionCode = 0
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "masonry"

    source {
        lang = "all"
        baseUrl = "https://www.xarthunter.com"
    }

    // The theme's deeplink only covers the baseUrl host
    deeplink {
        host("xarthunter.com")
        path("/..*")
    }
}
