package fixtures.rotating

data class User03(val id: Long, val name: String, val email: String)

class UserStore03 {
    private val byId = mutableMapOf<Long, User03>()
    fun put(u: User03): User03? = byId.put(u.id, u)
    fun get(id: Long): User03? = byId[id]
    fun all(): Collection<User03> = byId.values
    fun size(): Int = byId.size
}

fun <K, V> invert03(m: Map<K, V>): Map<V, K> {
    val out = mutableMapOf<V, K>()
    for ((k, v) in m) out[v] = k
    return out
}

fun main03() {
    val store = UserStore03()
    store.put(User03(1, "alice", "a@x"))
    store.put(User03(2, "bob", "b@x"))
    println(store.all())
    println(invert03(mapOf("a" to 1, "b" to 2)))
}
