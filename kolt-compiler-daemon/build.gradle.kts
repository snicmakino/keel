plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    mavenCentral()
}

val compilerHostClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val fixtureStdlib: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.michael-bull.kotlin-result:kotlin-result-jvm:2.3.1")

    compilerHostClasspath("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
    fixtureStdlib("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("kolt.daemon.MainKt")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kolt.daemon.compilerJars", compilerHostClasspath.asPath)
    systemProperty("kolt.daemon.stdlibJars", fixtureStdlib.asPath)
}
