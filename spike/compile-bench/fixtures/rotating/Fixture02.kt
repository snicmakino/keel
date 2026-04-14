package fixtures.rotating

sealed class Shape02 {
    data class Circle(val r: Double) : Shape02()
    data class Square(val side: Double) : Shape02()
}

fun Shape02.area02(): Double = when (this) {
    is Shape02.Circle -> 3.14159 * r * r
    is Shape02.Square -> side * side
}

class Registry02<T : Any> {
    private val items = mutableListOf<T>()
    fun add(v: T) { items += v }
    fun snapshot(): List<T> = items.toList()
}

fun describe02(s: Shape02): String = "shape=${s::class.simpleName} area=${s.area02()}"

fun main02() {
    val reg = Registry02<Shape02>()
    reg.add(Shape02.Circle(2.0))
    reg.add(Shape02.Square(3.0))
    reg.snapshot().forEach { println(describe02(it)) }
}
