package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
internal data class RawLocalOverlayConfig(
  val test: RawTestSection? = null,
  val run: RawRunSection? = null,
  val repositories: Map<String, RawRepository>? = null,
)

private val overlayToml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))

internal fun parseLocalOverlay(
  tomlString: String,
  path: String,
): Result<RawLocalOverlayConfig, ConfigError> {
  return try {
    Ok(overlayToml.decodeFromString(RawLocalOverlayConfig.serializer(), tomlString))
  } catch (e: SerializationException) {
    Err(buildKtomlParseError(e.message, path, tomlString, sourceFile = KOLT_LOCAL_TOML))
  } catch (e: IllegalArgumentException) {
    Err(buildKtomlParseError(e.message, path, tomlString, sourceFile = KOLT_LOCAL_TOML))
  }
}
