package fixtures.rotating

interface Parser04<T> {
    fun parse(s: String): T?
}

object IntParser04 : Parser04<Int> {
    override fun parse(s: String): Int? = s.toIntOrNull()
}

class ListParser04<T>(private val element: Parser04<T>, private val sep: String = ",") : Parser04<List<T>> {
    override fun parse(s: String): List<T>? {
        val parts = s.split(sep)
        val out = mutableListOf<T>()
        for (p in parts) {
            val v = element.parse(p.trim()) ?: return null
            out += v
        }
        return out
    }
}

fun main04() {
    val p = ListParser04(IntParser04)
    println(p.parse("1, 2, 3, 4"))
    println(p.parse("1, x, 3"))
}
