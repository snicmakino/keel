---
status: accepted
date: 2026-04-09
---

# ADR 0005: Follow Gradle Module Metadata redirects for KMP JVM artifacts

## Summary

- KMP root artifacts (e.g. `okhttp:5.0.0`) contain no JVM bytecode; the actual JVM jar lives in a separate artifact identified by an `available-at` block in the `.module` file. (§1)
- `parseJvmRedirect(moduleJson)` in `GradleMetadata.kt` returns `JvmRedirect(group, module, version)` read verbatim from `available-at` — no suffix guessing. (§2)
- Redirect fires only when **every** JVM variant has an `available-at` block; if any JVM variant lacks one, no redirect occurs. (§3)
- On the JVM side, Gradle metadata is used only for variant selection; POM-based dependency walking continues from the redirect target. (§4)
- `.module` fetches are memoised per resolution run and shared with the native resolver (ADR 0010). (§5)

## Context and Problem Statement

Once kolt's transitive resolver could walk plain Maven POMs, it broke on modern Kotlin Multiplatform libraries. The trigger case was OkHttp 5.x: `kolt build` completed resolution, downloaded a JAR, put it on the classpath, and then kotlinc failed with `unresolved reference` for every library symbol. The JAR was there; the coordinate was correct. But the root `okhttp:5.0.0` artifact is a metadata-only JAR — the actual JVM bytecode lives in `okhttp-jvm:5.0.0`.

This redirect is not in the POM. It lives in the `.module` JSON file alongside the POM and JAR. Inside `.module`, each target is a `variant` with `attributes` (including `org.jetbrains.kotlin.platform.type`) and optionally an `available-at` block:

```json
"variants": [
  {
    "name": "jvmApiElements-published",
    "attributes": { "org.jetbrains.kotlin.platform.type": "jvm" },
    "available-at": {
      "url": "../../okhttp-jvm/5.0.0/okhttp-jvm-5.0.0.module",
      "group": "com.squareup.okhttp3",
      "module": "okhttp-jvm",
      "version": "5.0.0"
    }
  }
]
```

Gradle, Maven, and Coursier all follow this redirect transparently. kolt did not, which is why the empty JAR ended up on the classpath. Hardcoding a `-jvm` suffix was rejected immediately — the `available-at` coordinate is authoritative and may point anywhere, including a different group or artifact name.

## Decision Drivers

- KMP libraries (OkHttp 5.x, kotlinx-*, ktor, Arrow) must resolve without requiring users to know about `-jvm` suffixes.
- Redirect target coordinate must be read verbatim from `available-at`; no heuristics.
- Plain Maven libraries with no `.module` file must continue resolving unchanged.
- The metadata parser must be reusable for native resolution (ADR 0010) without duplication.

## Decision Outcome

Chosen option: **parse `.module` for JVM variant selection, then continue with POMs**, because metadata is required for redirect detection but POMs already carry all dependency information once the redirect is followed.

### §1 Metadata-only JARs at KMP roots

KMP root artifacts are placeholders; their JARs contain only a manifest and `META-INF`. The actual per-target artifacts (JVM, native, Android) are published separately and referenced via `available-at`. Without following the redirect, the empty JAR ends up on the classpath and kotlinc finds no symbols.

### §2 Verbatim coordinate extraction

`parseJvmRedirect(moduleJson)` in `resolve/GradleMetadata.kt` is a pure function returning `JvmRedirect(group, module, version)` or `null`. The redirect coordinate is taken directly from `available-at.group` / `module` / `version`. On absent or unparseable `.module`, the function returns `null` and POM-only resolution proceeds immediately.

### §3 All-or-nothing redirect rule

Redirect fires only when every JVM variant has an `available-at` block. If any JVM variant lacks one, `parseJvmRedirect` returns `null` — the library provides its own JVM jar. This rule was added in commit `c20bf37` to fix a regression on `kotlin-test`: its metadata has multiple JVM-family variants, and the first one pointed to `kotlin-test-junit`, breaking plain `kotlin-test` consumers.

### §4 Metadata for selection, POMs for walking

After following the redirect, kolt switches to POM-based dependency walking from the redirect target. Metadata is not read for dependency information on the JVM side. (The native side does the opposite — see ADR 0010, where metadata is the sole source of truth.) The decoder is configured `ignoreUnknownKeys = true` so Gradle evolutions of the metadata schema do not break kolt. Only `variants[].attributes.platform.type`, `variants[].available-at`, and (for native) `variants[].files[]` and `variants[].dependencies[]` are read; the full Gradle Module Metadata 1.1 spec is not implemented.

### §5 Memoised `.module` downloads

Each metadata file is parsed at most once per resolution run. The cached result is shared with the native resolver so that a dependency appearing on both sides pays one network round-trip.

### Consequences

**Positive**
- OkHttp 5.x, kotlinx-*, ktor, Arrow, and similar KMP libraries resolve and compile without user intervention.
- Redirect targets are read verbatim from `available-at` — no suffix heuristics, no breakage on non-standard names.
- Libraries without a `.module` file fall through to POM-only resolution at zero extra cost.
- `GradleMetadata.kt` was extended with `parseNativeRedirect` and `parseNativeArtifact` (ADR 0010) reusing the same JSON schema bindings and cache keys.

**Negative**
- Every dependency incurs a `.module` GET on a cold cache; misses are cached as negative results for the run but still cost a round-trip.
- A subtly malformed `.module` causes silent fallback to POM-only resolution and a broken classpath — accepted to avoid per-library special cases.
- When every JVM variant has `available-at`, kolt follows the first one in declaration order. For all observed libraries, all JVM variants redirect to the same target; a genuinely divergent library would require a richer selection rule.
- The `kotlin-test`-class of bug required a real regression to find; future unusual metadata shapes could surprise similarly.

### Confirmation

`parseJvmRedirect` is covered by unit tests in `GradleMetadataTest.kt`, including the `c20bf37` regression case. PR review checks that new KMP library additions resolve to a non-empty classpath entry.

## Alternatives considered

1. **Hardcode the `-jvm` suffix.** Rejected — suffix is convention, not contract; non-conforming libraries silently produce a broken classpath.
2. **Require users to declare `okhttp-jvm` explicitly.** Rejected — pushes KMP metadata knowledge onto the user and breaks `kolt add` ergonomics.
3. **Treat metadata as authoritative on JVM too.** Rejected — POMs already encode everything needed after the redirect is followed; parallel walk strategies duplicate effort.
4. **Fetch metadata lazily only when the POM looks suspicious.** Rejected — there is no way to detect a metadata-only JAR from the POM without downloading and inspecting the JAR, which costs more than a small `.module` GET.

## Related

- `src/nativeMain/kotlin/kolt/resolve/GradleMetadata.kt` — `parseJvmRedirect` and shared JSON schema
- `src/nativeMain/kotlin/kolt/resolve/TransitiveResolver.kt` — call site for JVM redirect handling
- Commit `7d58f02` — initial Gradle Module Metadata support for KMP
- Commit `c20bf37` — `kotlin-test` fix: do not redirect if any JVM variant lacks `available-at`
- ADR 0010 — Gradle Module Metadata for native resolution; uses the same parser, treats metadata as authoritative
