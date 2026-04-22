---
status: accepted
date: 2026-04-09
---

# ADR 0006: Use libcurl cinterop instead of Ktor Client

## Summary

- Ktor Client brought ~14 klib modules (~7 MB) and a full coroutine runtime just to make synchronous GET requests; the dependency cost was disproportionate to the usage. (§1)
- Replace Ktor with direct `curl_easy_*` cinterop via a `.def` binding for `curl/curl.h`. (§2)
- Downloaded data streams directly to disk via `CURLOPT_WRITEFUNCTION`; no in-memory buffering. (§3)
- Build-time requires `libcurl4-openssl-dev`; the `.def` file hardcodes an x86_64 include path. (§4)

## Context and Problem Statement

kolt uses HTTP to download JAR files and POM metadata from Maven Central. The original implementation used Ktor Client with the Curl engine (`ktor-client-curl`).

Ktor brought significant transitive dependencies into the build: ~14 Kotlin/Native klib modules including ktor-client-core, ktor-http, ktor-io, ktor-utils, ktor-network, ktor-websockets, ktor-sse, ktor-http-cio, ktor-network-tls, ktor-client-cio, ktor-serialization, kotlinx-coroutines-core, kotlinx-io-core, and atomicfu. Total klib size was ~7 MB, dominated by `ktor-client-curl-cinterop` (4.3 MB alone). The full coroutine runtime (`kotlinx-coroutines-core`, 864 KB) was required just to wrap synchronous libcurl calls in `runBlocking`.

kolt's HTTP needs are minimal: synchronous GET requests to download files. WebSocket, SSE, CIO engine, HTTP/2, and content negotiation are unused. Kotlin/Native build times are inherently slow due to LLVM compilation; with Ktor, a full build took ~1m 40s. Reducing the klib count is one of the few available levers.

## Decision Drivers

- Eliminate all transitive klib cost attributable to unused Ktor features.
- No coroutine runtime for synchronous downloads.
- Streaming file writes — no full in-memory buffering per download.
- TLS must work without reimplementing anything.

## Decision Outcome

Chosen option: **direct libcurl cinterop**, because Ktor's Curl engine was already a wrapper around libcurl, so TLS and network behaviour are identical at a fraction of the klib footprint.

### §1 Ktor dependency cost vs. actual usage

~14 klib modules for synchronous GETs is disproportionate. The coroutine runtime was needed only to call `runBlocking` around an already-synchronous libcurl operation.

### §2 cinterop binding

Define `src/nativeInterop/cinterop/libcurl.def` binding `curl/curl.h`. Use the `curl_easy_*` API: `curl_easy_init`, `curl_easy_setopt`, `curl_easy_perform`, `curl_easy_getinfo`, `curl_easy_cleanup`. Remove all Ktor and kotlinx-coroutines entries from `build.gradle.kts`. `downloadFile` becomes a plain synchronous function with no `HttpClient` lifecycle management.

### §3 Streaming writes

`CURLOPT_WRITEFUNCTION` callback writes downloaded data directly to a file via `fwrite` — no in-memory accumulation. The prior Ktor implementation used `readRawBytes()`, buffering the full response body.

### §4 Build-time constraints

Developers and CI must have `libcurl4-openssl-dev` (or equivalent) installed for the cinterop header-generation step. The `.def` file contains `-I/usr/include/x86_64-linux-gnu`, which may need adjustment for aarch64 or macOS. On macOS, `linkerOpts.osx = -lcurl` works without additional installation since libcurl ships with the OS. cinterop is Kotlin/Native only; a JVM build variant would need a separate HTTP implementation (e.g. `java.net.URL`). Error handling requires manual `curl_easy_perform` return-code and `CURLINFO_RESPONSE_CODE` checks rather than Ktor's typed `HttpResponse`.

### Consequences

**Positive**
- ~14 klib modules eliminated from compilation and linking.
- ~7 MB of unused klib dependencies removed from the binary.
- No coroutine wrapping or `HttpClient` lifecycle management.
- Streaming writes via libcurl callback.
- Same libcurl underneath as Ktor's Curl engine — identical TLS stack and network behaviour.

**Negative**
- Build-time dependency on `libcurl4-openssl-dev` on every developer machine and CI host.
- x86_64 include path hardcoded in `.def` — needs manual adjustment for other architectures.
- cinterop is Kotlin/Native only; a JVM build variant would require a separate HTTP implementation.
- Lower-level error handling: manual `curl_easy_perform` return codes and `CURLINFO_RESPONSE_CODE` instead of Ktor's typed `HttpResponse`.

### Confirmation

Resolved by the drop in klib count and binary size after the migration, and by integration tests that download real artifacts from Maven Central in CI.

## Alternatives considered

1. **Keep Ktor, accept slow builds.** Rejected — build time directly impacts development velocity on a tool whose value proposition is fast builds.
2. **Use `platform.posix` sockets for raw HTTP.** Rejected — HTTPS would require additional C library bindings (OpenSSL/mbedTLS), adding more complexity than it removes.
3. **Shell out to `curl` command.** Rejected — adds process fork/exec overhead per request and complicates error handling.

## Related

- `src/nativeInterop/cinterop/libcurl.def` — cinterop binding
- `src/nativeMain/kotlin/kolt/resolve/` — current HTTP implementation

---

> **Footnote (2026-04-22):** Phase 2 reversed this decision. kolt now uses `ktor-client-curl` for HTTP and `org.kotlincrypto.hash:sha2-256` for checksums. The size argument still held in principle, but Ktor's Curl engine gave TLS on Kotlin/Native without us reimplementing anything. See `src/nativeMain/kotlin/kolt/resolve/` for the current implementation.
