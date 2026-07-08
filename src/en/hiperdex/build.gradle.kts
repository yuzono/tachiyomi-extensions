plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hiperdex"
    versionCode = 30
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "madara"
    kmkVersionCode = 1

    source {
        lang = "en"
        baseUrl {
            custom("https://hiperdex.com")
        }
    }
}

dependencies {

    implementation(project(":lib:randomua"))
}
