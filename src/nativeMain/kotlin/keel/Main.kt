package keel

import com.github.michaelbull.result.getOrElse
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
    val jsonString = readFileAsString("keel.json").getOrElse { error ->
        eprintln("error: could not read ${error.path}")
        exitProcess(1)
    }
    return parseConfig(jsonString).getOrElse { error ->
        when (error) {
            is ConfigError.ParseFailed -> eprintln("error: ${error.message}")
        }
        exitProcess(1)
    }
}

private fun checkVersion(config: KeelConfig) {
    val output = executeAndCapture("kotlinc -version 2>&1").getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit,
            is ProcessError.PopenFailed,
            is ProcessError.SignalKilled -> eprintln("warning: could not determine kotlinc version")
            // executeAndCaptureでは発生しない
            is ProcessError.EmptyArgs,
            is ProcessError.ForkFailed,
            is ProcessError.WaitFailed -> eprintln("warning: could not determine kotlinc version")
        }
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
    ensureDirectory(BUILD_DIR).getOrElse { error ->
        eprintln("error: could not create directory ${error.path}")
        exitProcess(1)
    }

    println("compiling ${config.name}...")
    executeCommand(cmd.args).getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit -> eprintln("error: compilation failed with exit code ${error.exitCode}")
            is ProcessError.EmptyArgs -> eprintln("error: no command to execute")
            is ProcessError.ForkFailed -> eprintln("error: failed to start compiler process")
            is ProcessError.WaitFailed -> eprintln("error: failed waiting for compiler process")
            is ProcessError.SignalKilled -> eprintln("error: compiler process was killed")
            // executeCommandでは発生しない
            is ProcessError.PopenFailed -> eprintln("error: failed to start compiler process")
        }
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

    executeCommand(cmd.args).getOrElse { error ->
        when (error) {
            is ProcessError.NonZeroExit -> exitProcess(error.exitCode)
            is ProcessError.EmptyArgs,
            is ProcessError.ForkFailed,
            is ProcessError.WaitFailed,
            is ProcessError.SignalKilled -> exitProcess(1)
            // executeCommandでは発生しない
            is ProcessError.PopenFailed -> exitProcess(1)
        }
    }
}
