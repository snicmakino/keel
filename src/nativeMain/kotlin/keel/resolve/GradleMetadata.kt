package keel.resolve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Redirect target when a Kotlin Multiplatform module's .module file
 * redirects the JVM variant to a platform-specific artifact.
 */
data class JvmRedirect(
    val group: String,
    val module: String,
    val version: String
)

/**
 * Parses a Gradle Module Metadata JSON string and extracts the JVM
 * platform redirect if present. Returns null when:
 * - the JSON is invalid
 * - no variant has `org.jetbrains.kotlin.platform.type` = "jvm"
 * - the JVM variant has no `available-at` redirect
 */
fun parseJvmRedirect(moduleJson: String): JvmRedirect? {
    val metadata = try {
        lenientJson.decodeFromString<GradleModuleMetadata>(moduleJson)
    } catch (_: Exception) {
        return null
    }

    for (variant in metadata.variants) {
        val platformType = variant.attributes["org.jetbrains.kotlin.platform.type"]
        if (platformType != "jvm") continue

        val availableAt = variant.availableAt ?: continue
        return JvmRedirect(
            group = availableAt.group,
            module = availableAt.module,
            version = availableAt.version
        )
    }
    return null
}

private val lenientJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class GradleModuleMetadata(
    val variants: List<GradleVariant> = emptyList()
)

@Serializable
private data class GradleVariant(
    val attributes: Map<String, String> = emptyMap(),
    @SerialName("available-at")
    val availableAt: AvailableAt? = null
)

@Serializable
private data class AvailableAt(
    val group: String,
    val module: String,
    val version: String
)
