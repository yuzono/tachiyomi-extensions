/**
 * Add or remove modules to load as needed for local development here.
 */
loadAllIndividualExtensions()

/**
 * ===================================== COMMON CONFIGURATION ======================================
 */
include(":core")

// 1. "lib" klasörü altındaki kütüphaneleri otomatik yükle
File(rootDir, "lib").eachDir {
    include(":lib:${it.name}")
}

// 2. "lib-multisrc" klasörü altındaki temaları (initmanga dahil) otomatik yükle
// Eğer klasör adınız "initmanga" ise, bu kod onu otomatik olarak ":lib-multisrc:initmanga" olarak ekler.
File(rootDir, "lib-multisrc").eachDir {
    include(":lib-multisrc:${it.name}")
}

/**
 * ======================================== HELPER FUNCTION ========================================
 */
fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            // src/tr/merlinscans gibi uzantıları ekler
            include(":src:${dir.name}:${subdir.name}")
        }
    }
}

fun loadIndividualExtension(lang: String, name: String) {
    include(":src:${lang}:${name}")
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && !file.name.startsWith(".") && file.name != "build") {
            block(file)
        }
    }
}
