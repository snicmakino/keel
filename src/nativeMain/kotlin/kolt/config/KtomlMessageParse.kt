package kolt.config

import kolt.infra.suggest.closestMatch

// ktoml encodes parse-position into the exception message text rather than
// exposing it via a public field — the relevant `ParseException`,
// `IllegalTypeException`, etc. are `internal` to the ktoml-core module.
// The pre-version-bump format is `"Line N: <detail>"` (note the trailing
// space). Bumping ktoml major can change this format; the
// `ConfigParseMessageFormatTest` fixture pins the regex against a synthetic
// broken kolt.toml so a format drift surfaces as a RED test.
internal val LINE_NO_REGEX = Regex("^Line (\\d+): ")

// Returns (lineNumber, restOfMessage). Falls back to (null, original) when
// the prefix is missing or malformed; (null, "") when the input itself is null.
internal fun extractKtomlLineNo(message: String?): Pair<Int?, String> {
  if (message == null) return null to ""
  val match = LINE_NO_REGEX.find(message) ?: return null to message
  val n = match.groupValues[1].toIntOrNull()
  val rest = message.substring(match.range.last + 1)
  return n to rest
}

// Top-level kolt.toml sections recognised today. R3.4 (narrowed) only
// suggests when the typo'd key is at top level — nested unknowns surface
// the key path without a suggestion. Updating this list when a new
// top-level section is added keeps the suggestion accurate; the drift
// guard test pins the contents against the RawKoltConfig field set.
internal val KNOWN_TOP_LEVEL_SECTIONS: List<String> =
  listOf(
      "build",
      "cinterop",
      "classpaths",
      "dependencies",
      "fmt",
      "kotlin",
      "repositories",
      "run",
      "test",
      "test-dependencies",
      "tools",
    )
    .sorted()

private val UNKNOWN_KEY_REGEX = Regex("^Unknown key received: <([^>]+)> in scope <([^>]*)>")

// ktoml-core 0.7.1 names the implicit root TOML node "rootNode" (see
// `com.akuleshov7.ktoml.tree.nodes.TomlFile.name`). UnknownNameException
// surfaces top-level typos with `scope <rootNode>`; nested scopes carry
// the parent section name (e.g. `<kotlin>`). The constant lives here so a
// future ktoml bump that renames the root surfaces as a single-edit fix
// and the empirical-pin test in ConfigParseMessageFormatTest catches it.
private const val KTOML_ROOT_SCOPE = "rootNode"

// Returns (offendingKey, suggestion). When the message does not match
// ktoml's UnknownNameException format, both are null. Nested-scope unknowns
// return the key but null suggestion (R3.4 narrowed scope).
internal fun parseUnknownKey(detail: String): Pair<String?, String?> {
  val match = UNKNOWN_KEY_REGEX.find(detail) ?: return null to null
  val key = match.groupValues[1]
  val scope = match.groupValues[2]
  if (scope != KTOML_ROOT_SCOPE) return key to null
  return key to closestMatch(key, KNOWN_TOP_LEVEL_SECTIONS)
}
