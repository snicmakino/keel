package kolt.cli

import kolt.build.daemon.BOOTSTRAP_JDK_VERSION
import kolt.config.KoltConfig

// Effective JDK major version for the JVM that kolt-managed tools run on:
// `config.build.jdk` if pinned, else the bootstrap JDK. Parses major-only
// strings like "21" / "25" per ADR 0017; returns null when the source is
// non-numeric so callers can default to omitting any version-gated flag.
internal fun jdkMajorVersionFor(config: KoltConfig): Int? =
  (config.build.jdk ?: BOOTSTRAP_JDK_VERSION).takeWhile { it.isDigit() }.toIntOrNull()
