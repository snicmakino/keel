package keel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ConfigParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class KeelConfig(
    val name: String,
    val version: String,
    val kotlin: String,
    val target: String,
    @SerialName("jvm_target") val jvmTarget: String = "17",
    val main: String,
    val sources: List<String>,
    val dependencies: Map<String, String> = emptyMap()
)

private val json = Json { ignoreUnknownKeys = true }

fun parseConfig(jsonString: String): KeelConfig {
    try {
        return json.decodeFromString<KeelConfig>(jsonString)
    } catch (e: SerializationException) {
        throw ConfigParseException("failed to parse keel.json: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw ConfigParseException("failed to parse keel.json: ${e.message}", e)
    }
}
