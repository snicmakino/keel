package kolt.daemon.protocol

// Parses kotlinc-style `path:line:column: severity: message` strings as
// produced by BTA's KotlinLogger into structured Diagnostic records.
// Anything that does not match the shape stays as a plain-text message
// (the caller holds it as free-text stderr).
//
// Scoped for B-2c: Linux absolute paths only. Windows drive letters
// (`C:\...`) are out of scope because the daemon runs exclusively on
// Linux/macOS-style filesystems in the current kolt release.
object DiagnosticParser {

    // Greedy path capture up to the last `:L:C:` triple. `\d+` for both
    // line and column anchors the regex against the positional prefix so
    // a colon inside a message body cannot be misread as a position. The
    // severity token includes `_` so multi-word kotlinc severities like
    // `strong_warning` match (`[A-Za-z]+` alone would drop those lines
    // silently into plain-text stderr).
    private val LINE_REGEX: Regex =
        Regex("""^(?<path>.+):(?<line>\d+):(?<col>\d+):\s*(?<sev>[A-Za-z_]+):\s*(?<msg>.*)$""")

    fun parseLine(line: String): Diagnostic? {
        val match = LINE_REGEX.matchEntire(line) ?: return null
        val severity = toSeverity(match.groups["sev"]!!.value) ?: return null
        return Diagnostic(
            severity = severity,
            file = match.groups["path"]!!.value,
            line = match.groups["line"]!!.value.toInt(),
            column = match.groups["col"]!!.value.toInt(),
            message = match.groups["msg"]!!.value.trim(),
        )
    }

    // Splits a flat list of captured logger lines into the structured
    // `diagnostics` field for `Message.CompileResult` and the residual
    // plain-text lines the daemon still surfaces via `stderr`. The
    // partition preserves input order within each bucket so a reader
    // scanning stderr and the diagnostics list in parallel sees a
    // consistent sequence.
    fun parseMessages(lines: List<String>): Pair<List<Diagnostic>, List<String>> {
        val diagnostics = mutableListOf<Diagnostic>()
        val plain = mutableListOf<String>()
        for (line in lines) {
            val parsed = parseLine(line)
            if (parsed != null) diagnostics.add(parsed) else plain.add(line)
        }
        return diagnostics to plain
    }

    // `strong_warning` is kotlinc's opt-in-required severity; collapse it
    // to plain Warning so IDE-style rendering still surfaces it rather
    // than demoting the whole line into free-text stderr.
    private fun toSeverity(raw: String): Severity? = when (raw.lowercase()) {
        "error" -> Severity.Error
        "warning", "strong_warning" -> Severity.Warning
        "info" -> Severity.Info
        else -> null
    }
}
