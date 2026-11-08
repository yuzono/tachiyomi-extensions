plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 2
    libVersion = "1.6"

    // Galleries are /<slug>/, which a path pattern can't tell apart from listings; getMangaByUrl filters them
    deeplink {
        path("/..*")
    }
}
