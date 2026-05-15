package dev.atvremote.protocol

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Regression test for Fix I-1: the pair-verify M2 await must be bounded.
 *
 * A real [java.net.ServerSocket] accepts the TCP connection but never writes
 * a PV_Next frame, so [AppleTvRemote.connect] must throw
 * [TimeoutCancellationException] within ~5 s rather than hanging forever.
 *
 * This test uses wall-clock time because [kotlinx.coroutines.withTimeout] in
 * [RemoteConnect.connect] is a real-clock timeout (the IO dispatcher runs on
 * real threads, not virtual time). A JUnit 5 method-level @Timeout of 15 s
 * catches the regression case (a hang) without being flaky.
 */
class RemoteConnectTimeoutTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun connectTimesOutWhenPeerNeverSendsPvNext() {
        val server = ServerSocket(0)
        val port = server.localPort

        // Background thread: accept the connection, then hold the client socket
        // open forever (never writes anything, so PV_Next is never received).
        val serverThread = Thread {
            try {
                val client = server.accept()
                // Block until interrupted or the server socket is closed.
                runCatching { client.getInputStream().read() }
                runCatching { client.close() }
            } catch (_: Exception) {}
        }
        serverThread.isDaemon = true
        serverThread.start()

        val device = AppleTvDevice(
            id = "test-id",
            name = "Test ATV",
            host = "127.0.0.1",
            port = port,
            model = null,
            pairable = false,
        )
        val dummyBytes = ByteArray(32) { it.toByte() }
        val credentials = HapCredentials(
            clientId = dummyBytes.copyOf(),
            clientLtsk = dummyBytes.copyOf(),
            clientLtpk = dummyBytes.copyOf(),
            atvId = dummyBytes.copyOf(),
            atvLtpk = dummyBytes.copyOf(),
        )

        try {
            assertFailsWith<TimeoutCancellationException> {
                // runBlocking drives the suspend function on a real thread so that
                // withTimeout uses the real clock (matching RemoteConnect's Dispatchers.IO).
                runBlocking {
                    AppleTvRemote.connect(device, credentials)
                }
            }
        } finally {
            runCatching { server.close() }
            serverThread.interrupt()
        }
    }
}
