package keel.cli

import com.github.michaelbull.result.getOrElse
import keel.config.KeelPaths
import keel.config.resolveKeelPaths
import keel.infra.*
import keel.tool.installJdkToolchain
import keel.tool.installKotlincToolchain
import kotlin.system.exitProcess

private val KNOWN_TOOLCHAINS = listOf("kotlinc", "jdk")

internal fun doToolchain(args: List<String>) {
    if (args.isEmpty()) {
        printToolchainUsage()
        exitProcess(EXIT_CONFIG_ERROR)
    }
    when (args[0]) {
        "install" -> doToolchainInstall()
        "list" -> doToolchainList()
        "remove" -> doToolchainRemove(args.drop(1))
        else -> {
            printToolchainUsage()
            exitProcess(EXIT_CONFIG_ERROR)
        }
    }
}

private fun printToolchainUsage() {
    eprintln("usage: keel toolchain <subcommand>")
    eprintln("")
    eprintln("subcommands:")
    eprintln("  install    Install toolchains defined in keel.toml")
    eprintln("  list       List installed toolchains")
    eprintln("  remove     Remove an installed toolchain (e.g. keel toolchain remove kotlinc 2.1.0)")
}

private fun doToolchainInstall() {
    val config = loadProjectConfig()
    val paths = resolveKeelPaths(EXIT_BUILD_ERROR)
    installKotlincToolchain(config.kotlin, paths, EXIT_BUILD_ERROR)
    if (config.jdk != null) {
        installJdkToolchain(config.jdk, paths, EXIT_BUILD_ERROR)
    }
}

private fun doToolchainList() {
    val paths = resolveKeelPaths(EXIT_BUILD_ERROR)
    val kotlincVersions = listInstalledVersions("${paths.toolchainsDir}/kotlinc")
    val jdkVersions = listInstalledVersions("${paths.toolchainsDir}/jdk")
    println(formatToolchainList(kotlincVersions, jdkVersions))
}

private fun listInstalledVersions(dir: String): List<String> {
    if (!fileExists(dir)) return emptyList()
    return listDirectoryEntries(dir).getOrElse { emptyList() }
}

internal fun formatToolchainList(kotlincVersions: List<String>, jdkVersions: List<String>): String {
    if (kotlincVersions.isEmpty() && jdkVersions.isEmpty()) {
        return "no toolchains installed"
    }
    val sections = mutableListOf<String>()
    if (kotlincVersions.isNotEmpty()) {
        sections.add("kotlinc:\n" + kotlincVersions.joinToString("\n") { "  $it" })
    }
    if (jdkVersions.isNotEmpty()) {
        sections.add("jdk:\n" + jdkVersions.joinToString("\n") { "  $it" })
    }
    return sections.joinToString("\n\n")
}

internal data class ToolchainRemoveArgs(val name: String, val version: String)

internal fun validateToolchainRemoveArgs(args: List<String>): Pair<ToolchainRemoveArgs?, String?> {
    if (args.size < 2) {
        return null to "usage: keel toolchain remove <name> <version>"
    }
    val name = args[0]
    if (name !in KNOWN_TOOLCHAINS) {
        return null to "error: unknown toolchain '$name' (available: ${KNOWN_TOOLCHAINS.joinToString(", ")})"
    }
    return ToolchainRemoveArgs(name, args[1]) to null
}

internal fun resolveToolchainPathForRemove(name: String, version: String, paths: KeelPaths): String? {
    val dir = "${paths.toolchainsDir}/$name/$version"
    return if (fileExists(dir)) dir else null
}

private fun doToolchainRemove(args: List<String>) {
    val (parsed, error) = validateToolchainRemoveArgs(args)
    if (parsed == null) {
        eprintln(error!!)
        exitProcess(EXIT_CONFIG_ERROR)
    }

    val paths = resolveKeelPaths(EXIT_BUILD_ERROR)
    val toolchainPath = resolveToolchainPathForRemove(parsed.name, parsed.version, paths)
    if (toolchainPath == null) {
        eprintln("error: ${parsed.name} ${parsed.version} is not installed")
        exitProcess(EXIT_BUILD_ERROR)
    }

    removeDirectoryRecursive(toolchainPath).getOrElse { err ->
        eprintln("error: could not remove ${err.path}")
        exitProcess(EXIT_BUILD_ERROR)
    }
    println("removed ${parsed.name} ${parsed.version}")
}
