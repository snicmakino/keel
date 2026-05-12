package smoke

import io.ktor.util.AttributeKey

fun main() {
  val key = AttributeKey<String>("smoke")
  println("ktor-utils cinterop smoke: ${key.name}")
}
