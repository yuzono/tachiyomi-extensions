import groovy.lang.MissingPropertyException
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.extra

var ExtensionAware.baseVersionCode: Int
    get() = (extra.get("baseVersionCode") as Int) + kmkBaseVersionCode
    set(value) = extra.set("baseVersionCode", value)

var ExtensionAware.kmkBaseVersionCode: Int
    get() {
        return try {
            extra.get("kmkBaseVersionCode") as Int
        } catch (_: MissingPropertyException) {
            0
        }
    }
    set(value) = extra.set("kmkBaseVersionCode", value)
