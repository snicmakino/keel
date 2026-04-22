#!/usr/bin/env bash
#
# assemble-dist.sh — release tarball stitcher for kolt.
#
# Runs `kolt build` serially in three projects (root, kolt-jvm-compiler-daemon,
# kolt-native-compiler-daemon), fetches the `kotlin-build-tools-impl`
# classpath from Maven Central with sha256 pins, and prepares
# `dist/<tarball-root>/`. Writing the final tarball layout (Task 3.3) is
# handled by a subsequent task.
#
# Fail-fast: any non-zero build or sha mismatch aborts the remaining steps.
#
# References: ADR 0018 §1, §4; ADR 0019 §3; daemon-self-host design §Flow 2.
set -euo pipefail

# kotlin-build-tools-impl classpath pin.
#
# ADR 0019 §3 requires `-impl` to load via a child URLClassLoader parented by
# SharedApiClassesClassLoader, so we ship its runtime closure on a separate
# on-disk classpath (`libexec/kolt-bta-impl/`) rather than bundling into the
# daemon fat jar or routing through the kolt resolver. The set and sha256s
# below were observed from `./gradlew :kolt-jvm-compiler-daemon:stageBtaImplJars`
# against the pinned `kotlin-build-tools-impl:2.3.20` dependency.
#
# Exclusions vs the staged set: the daemon's `kolt.toml` [dependencies] declares
# `org.jetbrains.kotlin:kotlin-build-tools-api = 2.3.20`, whose resolver closure
# already emits `kotlin-build-tools-api`, `kotlin-stdlib`, and `annotations-13.0`
# into `libexec/<daemon>/deps/`. Shipping those three a second time here would
# duplicate jars on the runtime classpath; we keep only what `-impl` brings in
# addition to the api closure.
#
# Format: "group:artifact:version:filename" (:-separated; filename is the
# exact Maven Central artifact name, including `-jvm` / version variants).
BTA_IMPL_JARS=(
  "org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.20:kotlin-build-tools-impl-2.3.20.jar"
  "org.jetbrains.kotlin:kotlin-build-tools-cri-impl:2.3.20:kotlin-build-tools-cri-impl-2.3.20.jar"
  "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20:kotlin-compiler-embeddable-2.3.20.jar"
  "org.jetbrains.kotlin:kotlin-compiler-runner:2.3.20:kotlin-compiler-runner-2.3.20.jar"
  "org.jetbrains.kotlin:kotlin-daemon-client:2.3.20:kotlin-daemon-client-2.3.20.jar"
  "org.jetbrains.kotlin:kotlin-daemon-embeddable:2.3.20:kotlin-daemon-embeddable-2.3.20.jar"
  "org.jetbrains.kotlin:kotlin-reflect:1.6.10:kotlin-reflect-1.6.10.jar"
  "org.jetbrains.kotlin:kotlin-script-runtime:2.3.20:kotlin-script-runtime-2.3.20.jar"
  "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0:kotlinx-coroutines-core-jvm-1.8.0.jar"
)

# SHA-256 digests in `sha256sum -c` input format: "<digest>  <filename>".
# Paired by filename with BTA_IMPL_JARS; order is not significant.
BTA_IMPL_SHA256=(
  "9681bc2164a8bd9f6ddf4c085cf4a836b92d27506667d73bab9f6d855336c910  kotlin-build-tools-impl-2.3.20.jar"
  "54eced630f28124ccfe2e464e6cacc281e528a8a50a89725721554c9693548fe  kotlin-build-tools-cri-impl-2.3.20.jar"
  "976f989d0b5f5d80e8e8a8ad4b73da0bfc27fdd965b9fa38362b2be79ecc1337  kotlin-compiler-embeddable-2.3.20.jar"
  "c069f30a403be70c8152f8aa9f25eccba188eead54263ae02af14421437a2208  kotlin-compiler-runner-2.3.20.jar"
  "c71a7c1be8fbff04e0af45c9ee8cb7a61d8953bca6b8bf225a5719c725a90b44  kotlin-daemon-client-2.3.20.jar"
  "8870bab840b8087c96c4ddc06088b4aedf5131c408af3674306304f1f96af3f4  kotlin-daemon-embeddable-2.3.20.jar"
  "3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203  kotlin-reflect-1.6.10.jar"
  "6fcdb7da6e65cf8cc43e5aabab94bdcc48825e7933686f8a1bf694eb88f8e00e  kotlin-script-runtime-2.3.20.jar"
  "9860906a1937490bf5f3b06d2f0e10ef451e65b95b269f22daf68a3d1f5065c5  kotlinx-coroutines-core-jvm-1.8.0.jar"
)

MAVEN_CENTRAL_BASE="https://repo1.maven.org/maven2"

# fetch_bta_impl — download the pinned `-impl` classpath into
# `${DIST_ROOT}/libexec/kolt-bta-impl/` and verify sha256s. Non-zero exit on
# any curl failure or sha mismatch (set -e carries the sha256sum -c failure).
fetch_bta_impl() {
  local target_dir="$DIST_ROOT/libexec/kolt-bta-impl"
  mkdir -p "$target_dir"
  local entry group artifact version filename group_path url
  for entry in "${BTA_IMPL_JARS[@]}"; do
    IFS=':' read -r group artifact version filename <<< "$entry"
    group_path="${group//./\/}"
    url="${MAVEN_CENTRAL_BASE}/${group_path}/${artifact}/${version}/${filename}"
    echo "assemble-dist: fetching ${group}:${artifact}:${version}"
    curl -fsSL -o "${target_dir}/${filename}" "$url"
  done
  echo "assemble-dist: verifying sha256 for kolt-bta-impl"
  (
    cd "$target_dir"
    printf '%s\n' "${BTA_IMPL_SHA256[@]}" | sha256sum -c -
  )
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Extract version from root kolt.toml (single `version = "..."` line).
VERSION="$(grep -E '^version = "' kolt.toml | head -n 1 | sed -E 's/^version = "(.*)"/\1/')"
if [[ -z "$VERSION" ]]; then
  echo "assemble-dist: failed to read version from kolt.toml" >&2
  exit 1
fi

DIST_ROOT="dist/kolt-${VERSION}-linux-x64"

# Wipe any previous dist/ so reruns are safe (design §release 配管: rerun safe).
rm -rf dist
mkdir -p "$DIST_ROOT"

KOLT="${KOLT:-./build/bin/linuxX64/releaseExecutable/kolt.kexe}"
# Resolve KOLT to an absolute path so the per-project `cd` below does not
# break relative defaults.
case "$KOLT" in
  /*) ;;
  *) KOLT="$ROOT_DIR/$KOLT" ;;
esac
if [[ ! -x "$KOLT" ]]; then
  echo "assemble-dist: KOLT binary not found or not executable: $KOLT" >&2
  exit 1
fi

# Run 3 x kolt build serially. `set -e` plus the subshell `exit` propagate
# failure so later projects do not run if an earlier one fails.
for project in "." "kolt-jvm-compiler-daemon" "kolt-native-compiler-daemon"; do
  echo "assemble-dist: building $project"
  (
    cd "$project"
    "$KOLT" build
  )
done

# Fetch kotlin-build-tools-impl from Maven Central (Task 3.2). Runs after the
# three `kolt build` invocations so that a broken local build fails before we
# touch the network.
fetch_bta_impl

# Task 3.3 will populate $DIST_ROOT/{bin,libexec} and emit the tarball.

echo "assemble-dist: skeleton ready at $DIST_ROOT"
