import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Metart Hunter"
    versionCode = 0
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "masonry"

    source {
        lang = "all"
        baseUrl = "https://www.metarthunter.com"
    }

    // The theme's deeplink only covers the baseUrl host
    deeplink {
        host("metarthunter.com")
        path("/..*")
    }
}
