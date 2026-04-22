#!/usr/bin/env bash
#
# assemble-dist.sh — release tarball stitcher for kolt.
#
# Runs `kolt build` serially in three projects (root, kolt-jvm-compiler-daemon,
# kolt-native-compiler-daemon) and prepares an empty dist/<tarball-root>/
# directory. Fetching `kotlin-build-tools-impl` (Task 3.2) and writing the
# tarball layout (Task 3.3) are handled by subsequent tasks.
#
# Fail-fast: any non-zero build aborts the remaining steps.
#
# References: ADR 0018 §1, §4; daemon-self-host design §Flow 2.
set -euo pipefail

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

# Task 3.2 will fetch kotlin-build-tools-impl into $DIST_ROOT/libexec/kolt-bta-impl/.
# Task 3.3 will populate $DIST_ROOT/{bin,libexec} and emit the tarball.

echo "assemble-dist: skeleton ready at $DIST_ROOT"
