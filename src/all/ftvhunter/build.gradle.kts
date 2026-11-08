import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "FTV Hunter"
    versionCode = 0
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "masonry"

    source {
        lang = "all"
        baseUrl = "https://www.ftvhunter.com"
    }

    // The theme's deeplink only covers the baseUrl host
    deeplink {
        host("ftvhunter.com")
        path("/..*")
    }
}
