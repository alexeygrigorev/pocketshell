package com.pocketshell.app.release

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Exercises the real [ReleaseChecker.checkForUpdate] network path against a
 * tiny in-process HTTP server, covering the resource-hygiene fix (item 1 of the
 * 2026-07-03 hardening batch): the `HttpURLConnection` must always
 * `disconnect()`, the input reader must be closed, and on a non-200 (the common
 * GitHub 403 rate-limit) the `errorStream` must be drained + closed rather than
 * leaked. These run the exact production branches (403 + 200) end to end;
 * Robolectric supplies the Android runtime so `android.util.Log` resolves.
 *
 * A hand-rolled `ServerSocket` is used rather than `com.sun.net.httpserver`
 * (which is not on the Android unit-test classpath). Each response sets
 * `Connection: close`, so every poll opens a fresh connection — a leak of the
 * prior connection or its error stream would surface as a hang/failure here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReleaseCheckerNetworkTest {

    private lateinit var server: ServerSocket
    private val requestCount = AtomicInteger(0)

    @After
    fun tearDown() {
        server.close()
    }

    private fun startServer(status: Int, statusText: String, body: String) {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            val bytes = body.toByteArray()
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        requestCount.incrementAndGet()
                        // Drain the request head (request line + headers).
                        val reader = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val header = buildString {
                            append("HTTP/1.0 ").append(status).append(' ').append(statusText).append("\r\n")
                            append("Content-Type: application/json\r\n")
                            append("Content-Length: ").append(bytes.size).append("\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        socket.getOutputStream().apply {
                            write(header.toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun url(): String = "http://127.0.0.1:${server.localPort}/"

    @Test
    fun rateLimit403DrainsErrorStreamAndKeepsPollingCleanly() = runBlocking {
        // GitHub's real 403 carries a rate-limit JSON body that the client must
        // consume; leaving it unread + the connection undisconnected is the leak
        // this fix closes.
        startServer(403, "Forbidden", """{"message":"API rate limit exceeded for 1.2.3.4"}""")
        val checker = ReleaseChecker(latestReleaseUrl = url())

        // Poll several times. Every call must drive the error-stream drain +
        // disconnect and return a clean Failed.
        repeat(10) {
            val result = checker.checkForUpdate("0.1.0")
            assertTrue("expected a Failed result on 403", result is ReleaseCheckResult.Failed)
            // Issue #1456: the user sees a human category, never the raw
            // "GitHub returned HTTP 403". A 403 rate-limit is NOT auto-retried
            // (see requestCount assertion below), so exactly one request per poll.
            assertEquals(
                "rate-limited, try again later",
                (result as ReleaseCheckResult.Failed).reason,
            )
        }
        // 10 polls -> exactly 10 requests proves the 403 rate-limit path does
        // NOT auto-retry (issue #1456: don't hammer GitHub).
        assertEquals("every poll must reach the server exactly once (403 not retried)", 10, requestCount.get())
    }

    @Test
    fun serverError500_surfacesHumanReason_andIsNotRetried() = runBlocking {
        // A non-403 non-200 (e.g. a GitHub 5xx) maps to a human "server error"
        // banner naming the code, and — like 403 — is a returned Failed, not a
        // thrown exception, so it is NOT auto-retried (issue #1456).
        startServer(500, "Internal Server Error", """{"message":"oops"}""")
        val checker = ReleaseChecker(latestReleaseUrl = url())

        val result = checker.checkForUpdate("0.1.0")

        assertTrue("expected a Failed result on 500", result is ReleaseCheckResult.Failed)
        assertEquals("server error (HTTP 500)", (result as ReleaseCheckResult.Failed).reason)
        assertEquals("a non-200 must be a single attempt (not retried)", 1, requestCount.get())
    }

    @Test
    fun success200ParsesUpdateAndClosesConnection() = runBlocking {
        val body = """
            {
              "tag_name": "v9.9.9",
              "html_url": "https://example.com/releases/v9.9.9",
              "assets": [
                {"name":"pocketshell-9.9.9-debug.apk","browser_download_url":"https://example.com/a.apk"}
              ]
            }
        """.trimIndent()
        startServer(200, "OK", body)
        val checker = ReleaseChecker(latestReleaseUrl = url())

        val result = checker.checkForUpdate("0.1.0")

        assertTrue("expected an UpdateAvailable result", result is ReleaseCheckResult.UpdateAvailable)
        assertEquals("v9.9.9", (result as ReleaseCheckResult.UpdateAvailable).info.tagName)
        // A second poll must still succeed — the connection from the first was
        // closed cleanly, not leaked.
        assertTrue(checker.checkForUpdate("0.1.0") is ReleaseCheckResult.UpdateAvailable)
    }
}
