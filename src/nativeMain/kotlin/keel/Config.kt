package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed class ConfigError {
    data class ParseFailed(val message: String) : ConfigError()
}

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

fun parseConfig(jsonString: String): Result<KeelConfig, ConfigError> {
    return try {
        Ok(json.decodeFromString<KeelConfig>(jsonString))
    } catch (e: SerializationException) {
        Err(ConfigError.ParseFailed("failed to parse keel.json: ${e.message}"))
    } catch (e: IllegalArgumentException) {
        Err(ConfigError.ParseFailed("failed to parse keel.json: ${e.message}"))
    }
}
