package fixtures.rotating

data class Money10(val amount: Long, val currency: String) {
    init { require(currency.length == 3) }
    operator fun plus(o: Money10): Money10 {
        require(currency == o.currency)
        return Money10(amount + o.amount, currency)
    }
}

interface Account10 {
    val id: String
    fun balance(): Money10
    fun deposit(m: Money10)
}

class InMemoryAccount10(override val id: String, currency: String) : Account10 {
    private var bal = Money10(0, currency)
    override fun balance(): Money10 = bal
    override fun deposit(m: Money10) { bal = bal + m }
}

fun totalByCurrency10(accounts: List<Account10>): Map<String, Long> {
    val out = mutableMapOf<String, Long>()
    for (a in accounts) {
        val b = a.balance()
        out[b.currency] = (out[b.currency] ?: 0) + b.amount
    }
    return out
}

fun main10() {
    val a = InMemoryAccount10("a", "JPY")
    a.deposit(Money10(100, "JPY"))
    a.deposit(Money10(250, "JPY"))
    println(totalByCurrency10(listOf(a)))
}
