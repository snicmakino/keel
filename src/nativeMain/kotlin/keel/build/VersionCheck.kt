package keel.build

fun parseKotlincVersion(output: String): String? {
    val line = output.lineSequence().firstOrNull() ?: return null
    val regex = Regex("""kotlinc-jvm\s+(\S+)""")
    return regex.find(line)?.groupValues?.get(1)
}
