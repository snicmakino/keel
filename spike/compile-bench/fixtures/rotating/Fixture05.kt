package fixtures.rotating

data class Vec05(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec05) = Vec05(x + o.x, y + o.y, z + o.z)
    operator fun times(k: Double) = Vec05(x * k, y * k, z * k)
    fun dot(o: Vec05): Double = x * o.x + y * o.y + z * o.z
    fun length(): Double {
        val l2 = dot(this)
        var g = l2
        repeat(8) { g = 0.5 * (g + l2 / g) }
        return g
    }
}

fun centroid05(points: List<Vec05>): Vec05 {
    var acc = Vec05(0.0, 0.0, 0.0)
    for (p in points) acc = acc + p
    return acc * (1.0 / points.size)
}

fun main05() {
    val c = centroid05(listOf(Vec05(1.0, 0.0, 0.0), Vec05(0.0, 1.0, 0.0), Vec05(0.0, 0.0, 1.0)))
    println("$c ${c.length()}")
}
