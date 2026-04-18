package fixture

import kotlinx.serialization.Serializable

// `@Serializable` is the canonical smoke test for the serialization compiler
// plugin: when the plugin runs, kotlinc synthesises `Foo$$serializer` and a
// `serializer()` method on `Foo.Companion`. When the plugin does NOT run, the
// annotation is inert and only `Foo.class` is emitted. The harness keys off
// the presence of the `$$serializer` class to verify plugin delivery.
@Serializable
data class Foo(val a: String, val b: Int)
