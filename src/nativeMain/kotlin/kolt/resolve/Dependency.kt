package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

data class InvalidCoordinate(val input: String)

data class Coordinate(val group: String, val artifact: String, val version: String)

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

private fun buildMavenUrl(
  coord: Coordinate,
  baseUrl: String,
  extension: String,
  classifier: String? = null,
): String {
  val groupPath = coord.group.replace('.', '/')
  val suffix = if (classifier != null) "-$classifier" else ""
  return "$baseUrl/$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}$suffix.$extension"
}

private fun buildRelativePath(
  coord: Coordinate,
  extension: String,
  classifier: String? = null,
): String {
  val groupPath = coord.group.replace('.', '/')
  val suffix = if (classifier != null) "-$classifier" else ""
  return "$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}$suffix.$extension"
}

fun buildDownloadUrl(coord: Coordinate, baseUrl: String): String =
  buildMavenUrl(coord, baseUrl, "jar")

fun buildCachePath(coord: Coordinate): String = buildRelativePath(coord, "jar")

fun buildSourcesDownloadUrl(coord: Coordinate, baseUrl: String): String =
  buildMavenUrl(coord, baseUrl, "jar", classifier = "sources")

fun buildSourcesCachePath(coord: Coordinate): String =
  buildRelativePath(coord, "jar", classifier = "sources")

fun buildPomDownloadUrl(coord: Coordinate, baseUrl: String): String =
  buildMavenUrl(coord, baseUrl, "pom")

fun buildPomCachePath(coord: Coordinate): String = buildRelativePath(coord, "pom")

fun buildModuleDownloadUrl(coord: Coordinate, baseUrl: String): String =
  buildMavenUrl(coord, baseUrl, "module")

fun buildModuleCachePath(coord: Coordinate): String = buildRelativePath(coord, "module")

// Native klib URLs are not derivable from the coordinate alone: a variant
// may publish a platform klib plus cinterop sub-klibs whose file names carry
// a `-cinterop-<name>` suffix (e.g. `ktor-utils-linuxx64-3.4.3-cinterop-threadUtils.klib`).
// The actual file name comes from `GradleFile.url` in the .module metadata;
// callers pass it as `fileName`. See ADR 0010.
fun buildKlibDownloadUrl(coord: Coordinate, baseUrl: String, fileName: String): String {
  val groupPath = coord.group.replace('.', '/')
  return "$baseUrl/$groupPath/${coord.artifact}/${coord.version}/$fileName"
}

fun buildKlibCachePath(coord: Coordinate, fileName: String): String {
  val groupPath = coord.group.replace('.', '/')
  return "$groupPath/${coord.artifact}/${coord.version}/$fileName"
}

fun buildClasspath(paths: List<String>): String = paths.joinToString(":")
