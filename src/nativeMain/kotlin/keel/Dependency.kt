package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

data class InvalidCoordinate(val input: String)

data class Coordinate(
    val group: String,
    val artifact: String,
    val version: String
)

fun parseCoordinate(groupArtifact: String, version: String): Result<Coordinate, InvalidCoordinate> {
    val parts = groupArtifact.split(":")
    if (parts.size != 2) {
        return Err(InvalidCoordinate(groupArtifact))
    }
    val (group, artifact) = parts
    if (group.isEmpty() || artifact.isEmpty()) {
        return Err(InvalidCoordinate(groupArtifact))
    }
    return Ok(Coordinate(group, artifact, version))
}

fun buildDownloadUrl(coord: Coordinate): String {
    val groupPath = coord.group.replace('.', '/')
    return "https://repo1.maven.org/maven2/$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}.jar"
}

fun buildCachePath(coord: Coordinate): String {
    val groupPath = coord.group.replace('.', '/')
    return "$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}.jar"
}

fun buildClasspath(paths: List<String>): String = paths.joinToString(":")
