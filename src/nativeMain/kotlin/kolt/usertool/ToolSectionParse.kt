package kolt.usertool

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kolt.resolve.Coordinate

// Charset shared by group / artifact / version / classifier per design.md
// §Data Models. Maven coordinate grammar is otherwise opaque, so kolt only
// rejects shapes that would break URL/path construction or our own colon
// splitting; it does not enforce e.g. SemVer on the version segment.
private val COORD_SEGMENT_REGEX = Regex("""^[A-Za-z0-9._-]+$""")

/**
 * Parse a `[tools]` `coords` value into `(Coordinate, classifier?)`.
 *
 * Accepts `group:artifact:version` and `group:artifact:version:classifier`. All four segments must
 * be non-empty and match `[A-Za-z0-9._-]+`. Any other shape returns `Err(reason)`; the caller
 * (`parseToolSection`) is expected to attach the offending alias to the message.
 */
fun parseCoordsString(s: String): Result<Pair<Coordinate, String?>, String> {
  if (s.isEmpty()) {
    return Err("coords must not be empty")
  }
  val parts = s.split(":")
  if (parts.size !in 3..4) {
    return Err(
      "coords '$s' must be of the form group:artifact:version[:classifier] " +
        "(found ${parts.size} colon-separated segments)"
    )
  }
  val group = parts[0]
  val artifact = parts[1]
  val version = parts[2]
  val classifier = if (parts.size == 4) parts[3] else null

  if (!COORD_SEGMENT_REGEX.matches(group)) {
    return Err("coords '$s': group '$group' must match [A-Za-z0-9._-]+ and be non-empty")
  }
  if (!COORD_SEGMENT_REGEX.matches(artifact)) {
    return Err("coords '$s': artifact '$artifact' must match [A-Za-z0-9._-]+ and be non-empty")
  }
  if (!COORD_SEGMENT_REGEX.matches(version)) {
    return Err("coords '$s': version '$version' must match [A-Za-z0-9._-]+ and be non-empty")
  }
  if (classifier != null && !COORD_SEGMENT_REGEX.matches(classifier)) {
    return Err("coords '$s': classifier '$classifier' must match [A-Za-z0-9._-]+ and be non-empty")
  }
  return Ok(Coordinate(group, artifact, version) to classifier)
}
