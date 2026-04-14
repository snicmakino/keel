package fixtures.rotating

class RingBuffer09<T>(private val capacity: Int) {
    private val buf = arrayOfNulls<Any?>(capacity)
    private var head = 0
    private var count = 0
    fun push(v: T) {
        val idx = (head + count) % capacity
        buf[idx] = v
        if (count < capacity) count += 1 else head = (head + 1) % capacity
    }
    @Suppress("UNCHECKED_CAST")
    fun toList(): List<T> {
        val out = mutableListOf<T>()
        for (i in 0 until count) out += buf[(head + i) % capacity] as T
        return out
    }
    fun size(): Int = count
}

fun <T> slidingWindow09(xs: List<T>, size: Int): List<List<T>> {
    val rb = RingBuffer09<T>(size)
    val out = mutableListOf<List<T>>()
    for (x in xs) {
        rb.push(x)
        if (rb.size() == size) out += rb.toList()
    }
    return out
}

fun main09() {
    println(slidingWindow09(listOf(1, 2, 3, 4, 5), 3))
}
