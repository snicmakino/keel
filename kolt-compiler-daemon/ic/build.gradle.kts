plugins {
    kotlin("jvm") version "2.3.20"
    // `java-library` provides the `api` configuration, which is required because
    // :ic exposes kotlin-result types in the signature of IncrementalCompiler.compile.
    // The `kotlin("jvm")` plugin on its own applies only `java`, which lacks `api`.
    `java-library`
}

repositories {
    mavenCentral()
}

// Per ADR 0019 §3, the -impl artifact must be loaded through a child URLClassLoader
// parented by SharedApiClassesClassLoader so daemon-core classes never see
// @ExperimentalBuildToolsApi types transitively. Keeping -impl in a separate
// resolvable configuration (instead of `implementation`) means Gradle will not
// place it on the JVM classpath of consumers of this module — the daemon fat jar
// resolves it separately and passes the classpath via the --bta-impl-jars CLI flag.
val buildToolsImpl: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

// Test fixtures compile against a plain kotlin-stdlib so the cold-path smoke
// test does not depend on daemon-core's classpath.
val fixtureClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    // -api only — the impl loads reflectively at runtime via SharedApiClassesClassLoader
    // parent + URLClassLoader child. The daemon core module consumes this subproject
    // for IncrementalCompiler / IcRequest / IcResponse / IcError only; any import of a
    // `kotlin.build.tools.*` type outside this subproject is an ADR 0019 §3 violation
    // and is enforced by human review (see issue #112 acceptance criterion 2).
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.20")
    // `api` rather than `implementation`: IncrementalCompiler.compile returns
    // Result<IcResponse, IcError>, so kotlin-result types are part of this
    // module's public API and must be visible to consumers (daemon core) at
    // compile time. Today daemon core also declares kotlin-result directly, so
    // `implementation` would happen to work — but it would silently break the
    // day a second consumer imports :ic without its own kotlin-result dep.
    api("com.michael-bull.kotlin-result:kotlin-result-jvm:2.3.1")

    buildToolsImpl("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.20")
    fixtureClasspath("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kolt.ic.btaImplClasspath", buildToolsImpl.asPath)
    systemProperty("kolt.ic.fixtureClasspath", fixtureClasspath.asPath)
}
