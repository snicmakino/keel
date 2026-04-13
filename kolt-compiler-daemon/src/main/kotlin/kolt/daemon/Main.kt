package kolt.daemon

import kolt.daemon.host.SharedCompilerHost
import kolt.daemon.server.DaemonConfig
import kolt.daemon.server.DaemonServer
import kolt.daemon.server.ExitReason
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

private data class CliArgs(
    val socketPath: Path,
    val compilerJars: List<File>,
)

fun main(args: Array<String>) {
    val cli = parseArgs(args) ?: run {
        System.err.println("usage: kolt-compiler-daemon --socket <path> --compiler-jars <classpath>")
        exitProcess(64)
    }
    val host = SharedCompilerHost(cli.compilerJars)
    val server = DaemonServer(cli.socketPath, host, DaemonConfig())
    val reason = server.serve()
    System.err.println("kolt-compiler-daemon: exiting (${reason::class.simpleName})")
    exitProcess(
        when (reason) {
            is ExitReason.Shutdown -> 0
            is ExitReason.IdleTimeout -> 0
            is ExitReason.MaxCompilesReached -> 0
            is ExitReason.HeapWatermarkReached -> 0
        },
    )
}

private fun parseArgs(args: Array<String>): CliArgs? {
    var socketPath: String? = null
    var compilerJars: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--socket" -> { socketPath = args.getOrNull(i + 1); i += 2 }
            "--compiler-jars" -> { compilerJars = args.getOrNull(i + 1); i += 2 }
            else -> return null
        }
    }
    if (socketPath == null || compilerJars == null) return null
    val jars = compilerJars.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }
    if (jars.isEmpty()) return null
    return CliArgs(Path.of(socketPath), jars)
}
