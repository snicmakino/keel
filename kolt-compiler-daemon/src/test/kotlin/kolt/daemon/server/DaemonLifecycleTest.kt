package kolt.daemon.server

import com.github.michaelbull.result.Ok
import kolt.daemon.host.CompileOutcome
import kolt.daemon.host.CompileRequest
import kolt.daemon.host.CompilerHost
import kolt.daemon.protocol.FrameCodec
import kolt.daemon.protocol.Message
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DaemonLifecycleTest {

    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private lateinit var serverThread: Thread
    private lateinit var server: DaemonServer

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("kolt-daemon-lifecycle-")
        socketPath = socketDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.stop() }
        runCatching { serverThread.join(2_000) }
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(socketDir) }
    }

    @Test
    fun `server exits after serving maxCompiles compiles`() {
        val host = object : CompilerHost {
            override fun compile(request: CompileRequest) =
                Ok(CompileOutcome(0, "", ""))
        }
        server = DaemonServer(
            socketPath = socketPath,
            host = host,
            config = DaemonConfig(
                idleTimeoutMillis = 60_000,
                maxCompiles = 2,
                heapWatermarkBytes = Long.MAX_VALUE,
            ),
        )
        serverThread = Thread({ server.serve() }, "daemon-lifecycle").apply {
            isDaemon = true
            start()
        }
        waitForSocket()

        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(UnixDomainSocketAddress.of(socketPath))
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            repeat(2) {
                FrameCodec.writeFrame(
                    output,
                    Message.Compile(
                        workingDir = "/w",
                        classpath = emptyList(),
                        sources = listOf("A.kt"),
                        outputJar = "out.jar",
                        moduleName = "m",
                    ),
                )
                FrameCodec.readFrame(input)
            }
        }

        serverThread.join(2_000)
        assertFalse(serverThread.isAlive, "server should have exited after maxCompiles")
    }

    @Test
    fun `idle timeout stops server when no connection arrives`() {
        val host = object : CompilerHost {
            override fun compile(request: CompileRequest) =
                Ok(CompileOutcome(0, "", ""))
        }
        server = DaemonServer(
            socketPath = socketPath,
            host = host,
            config = DaemonConfig(
                idleTimeoutMillis = 300,
                maxCompiles = Int.MAX_VALUE,
                heapWatermarkBytes = Long.MAX_VALUE,
            ),
        )
        serverThread = Thread({ server.serve() }, "daemon-idle").apply {
            isDaemon = true
            start()
        }
        waitForSocket()

        serverThread.join(3_000)
        assertEquals(false, serverThread.isAlive, "server should have exited after idle timeout")
    }

    private fun waitForSocket() {
        val deadline = System.currentTimeMillis() + 2_000
        while (!Files.exists(socketPath) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }
}
