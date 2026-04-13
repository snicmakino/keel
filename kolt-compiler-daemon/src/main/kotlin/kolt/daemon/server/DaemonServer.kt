package kolt.daemon.server

import com.github.michaelbull.result.mapBoth
import kolt.daemon.host.CompileRequest
import kolt.daemon.host.CompilerHost
import kolt.daemon.protocol.Diagnostic
import kolt.daemon.protocol.FrameCodec
import kolt.daemon.protocol.FrameError
import kolt.daemon.protocol.Message
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed interface ExitReason {
    data object Shutdown : ExitReason
    data object MaxCompilesReached : ExitReason
    data object IdleTimeout : ExitReason
    data object HeapWatermarkReached : ExitReason
}

class DaemonServer(
    private val socketPath: Path,
    private val host: CompilerHost,
    private val config: DaemonConfig = DaemonConfig(),
) {
    private val stopRequested = AtomicBoolean(false)
    private val compilesServed = AtomicInteger(0)
    private val lastActivityNanos = AtomicLong(System.nanoTime())
    @Volatile private var serverChannel: ServerSocketChannel? = null
    @Volatile private var exitReason: ExitReason? = null

    fun serve(): ExitReason {
        Files.deleteIfExists(socketPath)
        val address = UnixDomainSocketAddress.of(socketPath)
        val watchdog = startWatchdog()
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(address)
                serverChannel = server
                while (!stopRequested.get()) {
                    val client = try {
                        server.accept()
                    } catch (_: ClosedChannelException) {
                        break
                    }
                    lastActivityNanos.set(System.nanoTime())
                    client.use { handleConnection(it) }
                    lastActivityNanos.set(System.nanoTime())
                }
            }
        } finally {
            watchdog.interrupt()
            runCatching { Files.deleteIfExists(socketPath) }
        }
        return exitReason ?: ExitReason.Shutdown
    }

    fun stop() {
        requestExit(ExitReason.Shutdown)
    }

    private fun requestExit(reason: ExitReason) {
        if (stopRequested.compareAndSet(false, true)) {
            exitReason = reason
        }
        runCatching { serverChannel?.close() }
    }

    private fun startWatchdog(): Thread =
        Thread({
            while (!stopRequested.get()) {
                val idleMs = (System.nanoTime() - lastActivityNanos.get()) / 1_000_000
                if (idleMs >= config.idleTimeoutMillis) {
                    requestExit(ExitReason.IdleTimeout)
                    return@Thread
                }
                val sleepMs = (config.idleTimeoutMillis - idleMs).coerceAtLeast(10).coerceAtMost(500)
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "daemon-watchdog").apply {
            isDaemon = true
            start()
        }

    private fun handleConnection(client: SocketChannel) {
        val input = Channels.newInputStream(client)
        val output = Channels.newOutputStream(client)
        while (!stopRequested.get()) {
            val frame = FrameCodec.readFrame(input)
            val message = frame.mapBoth(
                success = { it },
                failure = { err ->
                    if (err is FrameError.Eof) return
                    return
                },
            )
            when (message) {
                is Message.Ping -> FrameCodec.writeFrame(output, Message.Pong)
                is Message.Shutdown -> {
                    requestExit(ExitReason.Shutdown)
                    return
                }
                is Message.Compile -> {
                    handleCompile(message, output)
                    val served = compilesServed.incrementAndGet()
                    if (served >= config.maxCompiles) {
                        requestExit(ExitReason.MaxCompilesReached)
                        return
                    }
                    if (heapUsedBytes() >= config.heapWatermarkBytes) {
                        requestExit(ExitReason.HeapWatermarkReached)
                        return
                    }
                }
                is Message.Pong, is Message.CompileResult -> {
                    // Clients should not send these; ignore and keep reading.
                }
            }
        }
    }

    private fun handleCompile(request: Message.Compile, output: java.io.OutputStream) {
        val result = host.compile(
            CompileRequest(
                sources = request.sources,
                classpath = request.classpath,
                outputPath = request.outputJar,
                moduleName = request.moduleName,
                extraArgs = request.extraArgs,
            ),
        )
        val reply: Message = result.mapBoth(
            success = { outcome ->
                Message.CompileResult(
                    exitCode = outcome.exitCode,
                    diagnostics = emptyList<Diagnostic>(),
                    stdout = outcome.stdout,
                    stderr = outcome.stderr,
                )
            },
            failure = { err ->
                Message.CompileResult(
                    exitCode = 2,
                    diagnostics = emptyList(),
                    stdout = "",
                    stderr = "compile host error: $err",
                )
            },
        )
        FrameCodec.writeFrame(output, reply)
    }

    private fun heapUsedBytes(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }
}
