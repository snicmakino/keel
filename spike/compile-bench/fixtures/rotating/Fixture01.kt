package fixtures.rotating

data class Point01(val x: Int, val y: Int) {
    fun translate(dx: Int, dy: Int): Point01 = Point01(x + dx, y + dy)
}

class Counter01(private var value: Int = 0) {
    fun inc(): Int {
        value += 1
        return value
    }
    fun get(): Int = value
}

fun <T> wrap01(v: T): List<T> = listOf(v, v, v)

fun sum01(xs: List<Int>): Int {
    var acc = 0
    for (x in xs) acc += x
    return acc
}

fun main01() {
    val p = Point01(1, 2).translate(3, 4)
    val c = Counter01()
    c.inc(); c.inc()
    println("${p.x},${p.y} -> ${c.get()} -> ${wrap01(sum01(listOf(1, 2, 3)))}")
}
