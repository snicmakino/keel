package fixtures.rotating

enum class Level08 { DEBUG, INFO, WARN, ERROR }

data class LogLine08(val level: Level08, val msg: String, val tags: List<String>)

class Logger08(val minimum: Level08 = Level08.INFO) {
    private val sink = mutableListOf<LogLine08>()
    fun log(level: Level08, msg: String, vararg tags: String) {
        if (level.ordinal >= minimum.ordinal) sink += LogLine08(level, msg, tags.toList())
    }
    fun drain(): List<LogLine08> {
        val copy = sink.toList()
        sink.clear()
        return copy
    }
}

fun groupByLevel08(lines: List<LogLine08>): Map<Level08, Int> {
    val counts = mutableMapOf<Level08, Int>()
    for (l in lines) counts[l.level] = (counts[l.level] ?: 0) + 1
    return counts
}

fun main08() {
    val log = Logger08()
    log.log(Level08.INFO, "hello", "boot")
    log.log(Level08.ERROR, "oops", "io", "fatal")
    println(groupByLevel08(log.drain()))
}
