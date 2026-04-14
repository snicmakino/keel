package fixtures.rotating

sealed interface Tree06<out T> {
    data object Empty : Tree06<Nothing>
    data class Node<T>(val value: T, val left: Tree06<T>, val right: Tree06<T>) : Tree06<T>
}

fun <T : Comparable<T>> insert06(t: Tree06<T>, v: T): Tree06<T> = when (t) {
    Tree06.Empty -> Tree06.Node(v, Tree06.Empty, Tree06.Empty)
    is Tree06.Node -> when {
        v < t.value -> Tree06.Node(t.value, insert06(t.left, v), t.right)
        v > t.value -> Tree06.Node(t.value, t.left, insert06(t.right, v))
        else -> t
    }
}

fun <T> size06(t: Tree06<T>): Int = when (t) {
    Tree06.Empty -> 0
    is Tree06.Node -> 1 + size06(t.left) + size06(t.right)
}

fun main06() {
    var t: Tree06<Int> = Tree06.Empty
    for (v in listOf(5, 3, 7, 1, 4, 6, 8)) t = insert06(t, v)
    println(size06(t))
}
