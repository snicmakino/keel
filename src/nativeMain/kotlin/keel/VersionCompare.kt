package keel

/**
 * Compares two Maven-style version strings.
 * Splits on `.` and `-`. Numeric segments are compared numerically.
 * Known qualifiers are ordered: SNAPSHOT < alpha < beta < rc < "" (release).
 */
fun compareVersions(a: String, b: String): Int {
    val partsA = splitVersion(a)
    val partsB = splitVersion(b)

    val maxLen = maxOf(partsA.size, partsB.size)
    for (i in 0 until maxLen) {
        val pa = if (i < partsA.size) partsA[i] else VersionSegment.Numeric(0)
        val pb = if (i < partsB.size) partsB[i] else VersionSegment.Numeric(0)
        val cmp = pa.compareTo(pb)
        if (cmp != 0) return cmp
    }
    return 0
}

private sealed class VersionSegment : Comparable<VersionSegment> {
    data class Numeric(val value: Long) : VersionSegment()
    data class Qualifier(val rank: Int, val numericSuffix: Long) : VersionSegment()
    data object Release : VersionSegment()

    override fun compareTo(other: VersionSegment): Int {
        return sortKey().compareTo(other.sortKey())
    }

    private fun sortKey(): Pair<Int, Long> = when (this) {
        is Numeric -> Pair(RANK_NUMERIC, value)
        is Qualifier -> Pair(rank, numericSuffix)
        is Release -> Pair(RANK_RELEASE, 0L)
    }

    private fun Pair<Int, Long>.compareTo(other: Pair<Int, Long>): Int {
        val cmp = first.compareTo(other.first)
        return if (cmp != 0) cmp else second.compareTo(other.second)
    }

    companion object {
        const val RANK_SNAPSHOT = 0
        const val RANK_ALPHA = 1
        const val RANK_BETA = 2
        const val RANK_RC = 3
        const val RANK_RELEASE = 4
        const val RANK_NUMERIC = 5
    }
}

private fun splitVersion(version: String): List<VersionSegment> {
    val segments = mutableListOf<VersionSegment>()
    val tokens = version.split('.', '-')
    for (token in tokens) {
        segments.add(parseSegment(token))
    }
    return segments
}

private fun parseSegment(token: String): VersionSegment {
    // Pure numeric
    token.toLongOrNull()?.let { return VersionSegment.Numeric(it) }

    // Qualifier with optional numeric suffix (e.g. "alpha2", "rc1")
    val lower = token.lowercase()
    val qualifierMatch = Regex("""^(snapshot|alpha|beta|rc)(\d*)$""").find(lower)
    if (qualifierMatch != null) {
        val name = qualifierMatch.groupValues[1]
        val suffix = qualifierMatch.groupValues[2].toLongOrNull() ?: 0L
        val rank = when (name) {
            "snapshot" -> VersionSegment.RANK_SNAPSHOT
            "alpha" -> VersionSegment.RANK_ALPHA
            "beta" -> VersionSegment.RANK_BETA
            "rc" -> VersionSegment.RANK_RC
            else -> VersionSegment.RANK_RELEASE
        }
        return VersionSegment.Qualifier(rank, suffix)
    }

    // Unknown qualifier treated as release-level
    return VersionSegment.Qualifier(VersionSegment.RANK_RELEASE, 0L)
}
