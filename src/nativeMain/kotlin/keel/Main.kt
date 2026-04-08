package keel

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "build" -> doBuild()
        "run" -> {
            val config = doBuild()
            doRun(config)
        }
        else -> {
            eprintln("error: unknown command '${args[0]}'")
            printUsage()
            exitProcess(1)
        }
    }
}

private fun printUsage() {
    eprintln("usage: keel <command>")
    eprintln("")
    eprintln("commands:")
    eprintln("  build    Compile the project")
    eprintln("  run      Build and run the project")
}

private fun loadProjectConfig(): KeelConfig {
    val jsonString = try {
        readFileAsString("keel.json")
    } catch (e: ConfigParseException) {
        eprintln("error: ${e.message}")
        exitProcess(1)
    }
    return try {
        parseConfig(jsonString)
    } catch (e: ConfigParseException) {
        eprintln("error: ${e.message}")
        exitProcess(1)
    }
}

private fun checkVersion(config: KeelConfig) {
    val (exitCode, output) = executeAndCapture("kotlinc -version 2>&1")
    if (exitCode != 0) {
        eprintln("warning: could not determine kotlinc version")
        return
    }
    val installedVersion = parseKotlincVersion(output)
    if (installedVersion == null) {
        eprintln("warning: could not parse kotlinc version from: $output")
        return
    }
    if (installedVersion != config.kotlin) {
        eprintln("warning: keel.json specifies kotlin ${config.kotlin}, but kotlinc $installedVersion is installed")
    }
}

private fun doBuild(): KeelConfig {
    val config = loadProjectConfig()
    checkVersion(config)

    val cmd = buildCommand(config)
    ensureDirectory(BUILD_DIR)

    println("compiling ${config.name}...")
    val exitCode = executeCommand(cmd.args)
    if (exitCode != 0) {
        eprintln("error: compilation failed with exit code $exitCode")
        exitProcess(1)
    }
    println("built ${cmd.outputPath}")
    return config
}

private fun doRun(config: KeelConfig) {
    val cmd = runCommand(config)

    if (!fileExists(cmd.jarPath)) {
        eprintln("error: ${cmd.jarPath} not found. Run 'keel build' first.")
        exitProcess(1)
    }

    val exitCode = executeCommand(cmd.args)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}
