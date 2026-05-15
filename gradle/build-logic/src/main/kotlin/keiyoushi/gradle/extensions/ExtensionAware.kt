package keiyoushi.gradle.extensions

import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.kotlin.dsl.extra

var ExtensionAware.baseVersionCode: Int
    get() = (extra.get("baseVersionCode") as Int) + kmkBaseVersionCode
    set(value) = extra.set("baseVersionCode", value)

// KMK -->
var ExtensionAware.kmkBaseVersionCode: Int
    get() = extra.getOrNull("kmkBaseVersionCode") as? Int ?: 0
    set(value) = extra.set("kmkBaseVersionCode", value)

private fun ExtraPropertiesExtension.getOrNull(name: String) = if (has(name)) get(name) else null
// KMK <--
