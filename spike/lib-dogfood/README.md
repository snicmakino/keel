# lib-dogfood — Task 6.1 regression / invariant fixtures

Throwaway fixtures used to dogfood `kind = "lib"` end-to-end for spec
`lib-build-pipeline` (issue #30). Covers Requirements 2.4, 3.4, 4.4, 5.3
plus end-to-end closure of 2.1 and 3.1.

## Replay

Build the kolt binary once:

```bash
./gradlew linkReleaseExecutableLinuxX64
export KOLT=$PWD/build/bin/linuxX64/releaseExecutable/kolt.kexe
```

### JVM library (R2.1, R2.2, R2.3)

```bash
cd spike/lib-dogfood/jvm
"$KOLT" build
# → built build/jvmlibdog.jar in ~6s
unzip -l build/jvmlibdog.jar
#   META-INF/MANIFEST.MF
#   META-INF/kolt-<hash>.kotlin_module
#   dogfood/Util.class
unzip -p build/jvmlibdog.jar META-INF/MANIFEST.MF
#   Manifest-Version: 1.0
#   Created-By: 21.0.7 (Amazon.com Inc.)
#   (no Main-Class line — R2.3)
# (no kotlin/ or dependency packages — R2.2)

"$KOLT" test
# → tests passed; exit 0 (R5.1, R5.3)

"$KOLT" run
# → error: library projects cannot be run
# → exit 2 (R4.1, R4.3)
```

### Native library (R3.1, R3.2, R3.3)

```bash
cd spike/lib-dogfood/native
"$KOLT" build
# → built library build/nativelibdog-klib in ~5s
ls build/
#   nativelibdog-klib/   ← directory (stage 1 -nopack output)
#   .kolt-state.json
#   (no nativelibdog.kexe — R3.2)

"$KOLT" test
# → tests passed; exit 0 (R5.1)

"$KOLT" run
# → error: library projects cannot be run
# → exit 2 (R4.1, R4.3)
```

### Native app — non-regression (R3.4, R4.4)

```bash
cd spike/lib-dogfood/app-native
"$KOLT" build
# → built executable build/nativeappdog.kexe in ~7s
./build/nativeappdog.kexe
# → hello from native app
```

## Canonical error probes

Temporarily mutate `kind` or `main` in a fixture's `kolt.toml` to
observe the two parser rejections (each revert has been verified to
rebuild cleanly):

- `kind = "app"` + no `[build] main` →
  `error: [build] main is required for kind = "app"` (exit 2)
- `kind = "lib"` + `main = "..."` →
  `error: main has no meaning for a library; remove it` (exit 2)

## Artifact inventory (2026-04-22 run)

| Fixture | Command | Output | Size / shape |
|---|---|---|---|
| jvm | build | `build/jvmlibdog.jar` | 1336 B, 5 entries, no `Main-Class`, no `kotlin/` |
| jvm | test | exit 0, 1 test run | — |
| jvm | run | exit 2, canonical stderr | — |
| native | build | `build/nativelibdog-klib/` (directory) | klib with `default/{ir,linkdata,manifest,resources,targets}` |
| native | build | `build/nativelibdog.kexe` | ABSENT — R3.2 holds |
| native | test | exit 0, 1 test run | — |
| native | run | exit 2, canonical stderr | — |
| app-native | build | `build/nativeappdog.kexe` | 897952 B, runs and prints `hello from native app` |

## Baseline

`./gradlew check` exits 0 on branch `30/lib-build-pipeline` at task 6.1
entry — captured 2026-04-22.
