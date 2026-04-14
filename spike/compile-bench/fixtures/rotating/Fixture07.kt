package fixtures.rotating

data class Event07(val topic: String, val payload: String, val ts: Long)

class EventBus07 {
    private val handlers = mutableMapOf<String, MutableList<(Event07) -> Unit>>()
    fun subscribe(topic: String, h: (Event07) -> Unit) {
        handlers.getOrPut(topic) { mutableListOf() }.add(h)
    }
    fun publish(e: Event07) {
        handlers[e.topic]?.forEach { it(e) }
    }
}

fun <T, R> pipeline07(input: List<T>, vararg stages: (T) -> R): List<R> {
    val out = mutableListOf<R>()
    for (x in input) for (s in stages) out += s(x)
    return out
}

fun main07() {
    val bus = EventBus07()
    bus.subscribe("t") { println(it.payload) }
    bus.publish(Event07("t", "hi", 0))
    println(pipeline07(listOf(1, 2, 3), { it + 1 }, { it * 2 }))
}
