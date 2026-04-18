# bta-compat-138 spike

**Throwaway. Not production code.**

Two spikes sharing the same matrix + classloader topology:

1. **`bta-compat`** (issue #138) — is `kotlin-build-tools-api` binary-stable
   enough that an adapter compiled against API 2.3.20 can load API+impl pairs
   at 2.1.0 / 2.2.x at runtime through the daemon's
   `SharedApiClassesClassLoader` + `URLClassLoader` topology?
2. **`plugin-smoke`** (issue #143) — can the CLI-style `-Xplugin=<path>`
   passthrough (via `applyArgumentStrings`) deliver compiler plugins to
   pre-2.3.20 impls without using the 2.3.20-only structured `COMPILER_PLUGINS`
   key?

See `REPORT.md` for both verdicts.

## Usage

```
# #138 BTA compat matrix
./gradlew -p spike/bta-compat-138 run --args="bta-compat fixtures/linear-10 /tmp/bta-compat-work"

# #143 plugin passthrough
./gradlew -p spike/bta-compat-138 run --args="plugin-smoke fixtures/plugin-serialization /tmp/bta-compat-plugin-work"
```

The matrix (`2.1.0`, `2.2.20`, `2.3.0`, `2.3.10`, `2.3.20`) is hard-coded in
`build.gradle.kts`. Each impl version ships with a matching `kotlin-stdlib`,
a `kotlinx-serialization-core` runtime, and a matching
`kotlin-serialization-compiler-plugin-embeddable` jar in its own resolvable
configuration so the plugin-smoke fixture matches the impl.

For each impl version the `bta-compat` harness:

1. Builds `URLClassLoader(impl jars, parent = SharedApiClassesClassLoader())`
2. Calls `KotlinToolchains.loadImplementation(loader)`
3. Runs a cold compile on `fixtures/linear-10`, touches `F2.kt`, runs an incremental compile
4. Classifies the outcome (`GREEN` / `RED_LINKAGE` / `RED_METHOD` / `RED_OTHER` / `COMPILE_ERROR`)

The `plugin-smoke` harness additionally:

1. Compiles `fixtures/plugin-serialization/Foo.kt` (a single `@Serializable`
   data class) with `applyArgumentStrings(listOf("-Xplugin=<serialization-plugin>.jar"))`
2. Inspects the output directory for `Foo$$serializer.class` — the class
   kotlinc synthesises only when the serialization compiler plugin actually ran
3. Classifies the outcome (`GREEN` / `RED_COMPILE` / `RED_NO_GEN` / `RED_METHOD` / `RED_LINKAGE` / `RED_OTHER`)

Every phase is wrapped so a thrown `LinkageError` / `NoSuchMethodError` does
not abort the matrix — we want to see *which* versions break and *how*.
