package eu.kanade.tachiyomi.extension.en.kenscans

import eu.kanade.tachiyomi.multisrc.iken.Iken

class KenScans : Iken(
    "Ken Scans",
    "en",
    "https://kenscans.com",
    "https://api.kenscans.com"
) {
    override val versionId = 2
}
