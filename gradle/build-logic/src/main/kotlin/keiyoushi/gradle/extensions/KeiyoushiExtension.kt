package keiyoushi.gradle.extensions

import ContentWarning
import org.gradle.api.provider.Property

val VALID_LIB_VERSIONS = listOf("1.4")

abstract class KeiyoushiExtension {
    abstract val name: Property<String>
    abstract val className: Property<String>
    abstract val versionCode: Property<Int>
    abstract val contentWarning: Property<ContentWarning>
    abstract val theme: Property<String>
    abstract val libVersion: Property<String>
    abstract val baseUrl: Property<String>

    // KMK -->
    abstract val kmkVersionCode: Property<Int>
    // KMK <--
}

abstract class KeiyoushiMultisrcExtension {
    abstract val baseVersionCode: Property<Int>
    abstract val libVersion: Property<String>

    // KMK -->
    abstract val kmkBaseVersionCode: Property<Int>
    // KMK <--
}
