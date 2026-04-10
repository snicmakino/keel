package keel.build

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val BUILD_STATE_FILE = "$BUILD_DIR/.keel-state.json"

@Serializable
data class BuildState(
    @SerialName("config_mtime") val configMtime: Long,
    @SerialName("sources_newest_mtime") val sourcesNewestMtime: Long,
    @SerialName("output_mtime") val outputMtime: Long?,
    @SerialName("lockfile_mtime") val lockfileMtime: Long?,
    val classpath: String? = null
)

fun isBuildUpToDate(current: BuildState, cached: BuildState?): Boolean {
    if (cached == null) return false
    return current.configMtime == cached.configMtime &&
        current.sourcesNewestMtime == cached.sourcesNewestMtime &&
        current.outputMtime == cached.outputMtime &&
        current.lockfileMtime == cached.lockfileMtime
}

private val json = Json { prettyPrint = true }

fun serializeBuildState(state: BuildState): String =
    json.encodeToString(state)

fun parseBuildState(input: String): BuildState? =
    try {
        json.decodeFromString<BuildState>(input)
    } catch (_: Exception) {
        null
    }

