package com.pocketshell.app.release

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.NoSuchAlgorithmException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

/**
 * Issue #1456: the update-check banner used to dump the raw exception chain
 * (the cryptic conscrypt
 * `SocketException: NoSuchAlgorithmException ... DefaultSSLContextImpl$TLSv13`),
 * and a transient TLS/network blip nagged the user with no self-heal. This
 * suite proves:
 *
 *  1. every failure category maps to a short HUMAN banner reason — never the
 *     raw `simpleName: message` (the maintainer's reported symptom);
 *  2. the raw exception is still preserved in logcat for diagnosis;
 *  3. a transient failure that succeeds on the 2nd attempt self-heals (no
 *     banner), while a both-attempts-failed result still surfaces the human
 *     banner (issue #515's visible-failure contract);
 *  4. the GitHub 403 rate-limit path does NOT auto-retry.
 *
 * Robolectric supplies the Android runtime so `android.util.Log` resolves and
 * [ShadowLog] can assert the raw detail is still logged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReleaseCheckerFailureClassificationTest {

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    /** The exact conscrypt exception the maintainer saw on-device (issue #1456). */
    private fun conscryptSocketException(): SocketException =
        SocketException(
            "java.security.NoSuchAlgorithmException: Error constructing implementation " +
                "(algorithm: Default, provider: AndroidOpenSSL, " +
                "class: com.android.org.conscrypt.DefaultSSLContextImpl\$TLSv13)",
        ).apply {
            initCause(
                NoSuchAlgorithmException(
                    "Error constructing implementation (algorithm: Default, provider: " +
                        "AndroidOpenSSL, class: com.android.org.conscrypt.DefaultSSLContextImpl\$TLSv13)",
                ),
            )
        }

    // ---------------------------------------------------------------------
    // Red -> green: the reported conscrypt symptom, on the REAL public surface.
    // ---------------------------------------------------------------------

    /**
     * RED on base, GREEN with the fix — WITHOUT any new symbol so the reviewer
     * can run it against the unmodified `ReleaseChecker`. A connect to a closed
     * local port throws a `ConnectException` (a [SocketException], same category
     * as the conscrypt fault). Base builds the banner reason as
     * `"ConnectException: Connection refused"`; the fix classifies it to the
     * human "connection problem".
     */
    @Test
    fun realNetworkFailure_surfacesHumanReason_notRawExceptionDump() = runBlocking {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        // Socket closed above -> the port now refuses connections.
        val checker = ReleaseChecker(latestReleaseUrl = "http://127.0.0.1:$closedPort/")

        val result = checker.checkForUpdate("0.1.0")

        assertTrue("expected a Failed result", result is ReleaseCheckResult.Failed)
        val reason = (result as ReleaseCheckResult.Failed).reason
        assertEquals("connection problem", reason)
        // The user must NEVER see a class name or raw message.
        assertFalse("banner leaked an exception class name: $reason", reason.contains("Exception"))
        assertFalse("banner leaked a raw exception message: $reason", reason.contains("Connection refused"))
    }

    /**
     * The exact conscrypt `SocketException(cause=NoSuchAlgorithmException ...
     * DefaultSSLContextImpl)` injected into the real [ReleaseChecker.checkForUpdate]
     * path via the [ReleaseChecker.fetchRelease] seam. The banner reason is the
     * human category, and the raw chain survives in logcat.
     */
    @Test
    fun conscryptException_throughCheckForUpdate_isHuman_andRawStillLogged() = runBlocking {
        val checker = object : ReleaseChecker(retryBackoffMs = 0) {
            override fun fetchRelease(currentVersion: String): ReleaseCheckResult =
                throw conscryptSocketException()
        }

        val result = checker.checkForUpdate("0.1.0")

        assertTrue(result is ReleaseCheckResult.Failed)
        val reason = (result as ReleaseCheckResult.Failed).reason
        assertEquals("connection problem", reason)
        assertFalse(reason.contains("SocketException"))
        assertFalse(reason.contains("NoSuchAlgorithmException"))
        assertFalse(reason.contains("DefaultSSLContextImpl"))

        // Criterion: the full raw detail is NOT lost — it is still logged.
        val logs = ShadowLog.getLogs().filter { it.tag == "PsReleaseCheck" }
        assertTrue("expected a diagnostic log entry", logs.isNotEmpty())
        assertTrue(
            "raw conscrypt detail must remain in logcat",
            logs.any { it.msg.contains("DefaultSSLContextImpl") || it.msg.contains("SocketException") },
        )
        // The throwable itself is attached to the log for the full stack.
        assertTrue(
            "the raw throwable must be attached to a log entry",
            logs.any { it.throwable != null },
        )
    }

    // ---------------------------------------------------------------------
    // Class coverage (G2): every category -> its human message + transient flag.
    // ---------------------------------------------------------------------

    private val checker = ReleaseChecker()

    @Test
    fun classify_conscryptSocketException_isConnectionProblem_transient() {
        val c = checker.classifyFailure(conscryptSocketException())
        assertEquals("connection problem", c.message)
        assertTrue("conscrypt TLS-construction blip must be retried", c.transient)
    }

    @Test
    fun classify_rawNoSuchAlgorithm_isConnectionProblem_transient() {
        val c = checker.classifyFailure(
            NoSuchAlgorithmException("Error constructing implementation ... DefaultSSLContextImpl"),
        )
        assertEquals("connection problem", c.message)
        assertTrue(c.transient)
    }

    @Test
    fun classify_sslException_isConnectionProblem_transient() {
        val c = checker.classifyFailure(SSLHandshakeException("handshake failed"))
        assertEquals("connection problem", c.message)
        assertTrue(c.transient)
    }

    @Test
    fun classify_plainSocketException_isConnectionProblem_transient() {
        val c = checker.classifyFailure(SocketException("Connection reset"))
        assertEquals("connection problem", c.message)
        assertTrue(c.transient)
    }

    @Test
    fun classify_timeout_isTimedOut_transient() {
        val c = checker.classifyFailure(SocketTimeoutException("connect timed out"))
        assertEquals("timed out", c.message)
        assertTrue(c.transient)
    }

    @Test
    fun classify_unknownHost_isNoNetwork_notTransient() {
        val c = checker.classifyFailure(UnknownHostException("api.github.com"))
        assertEquals("no network connection", c.message)
        assertFalse("a DNS/connectivity failure must NOT auto-retry", c.transient)
    }

    @Test
    fun classify_unclassifiedException_isConnectionProblem_notTransient() {
        val c = checker.classifyFailure(IllegalStateException("boom"))
        assertEquals("connection problem", c.message)
        assertFalse(c.transient)
    }

    // ---------------------------------------------------------------------
    // Auto-retry orchestration (issue #1456 + #515 contract).
    // ---------------------------------------------------------------------

    @Test
    fun transientFailure_healsOnSecondAttempt_noBanner() = runBlocking {
        val calls = AtomicInteger(0)
        val checker = object : ReleaseChecker(retryBackoffMs = 0) {
            override fun fetchRelease(currentVersion: String): ReleaseCheckResult {
                return if (calls.incrementAndGet() == 1) {
                    throw conscryptSocketException()
                } else {
                    ReleaseCheckResult.UpToDate
                }
            }
        }

        val result = checker.checkForUpdate("0.1.0")

        assertEquals("the transient blip must self-heal", ReleaseCheckResult.UpToDate, result)
        assertEquals("must have retried exactly once", 2, calls.get())
    }

    @Test
    fun transientFailure_bothAttemptsFail_surfacesHumanBanner() = runBlocking {
        val calls = AtomicInteger(0)
        val checker = object : ReleaseChecker(retryBackoffMs = 0) {
            override fun fetchRelease(currentVersion: String): ReleaseCheckResult {
                calls.incrementAndGet()
                throw conscryptSocketException()
            }
        }

        val result = checker.checkForUpdate("0.1.0")

        // Issue #515: a genuine, persistent failure IS still surfaced.
        assertTrue(result is ReleaseCheckResult.Failed)
        assertEquals("connection problem", (result as ReleaseCheckResult.Failed).reason)
        assertEquals("must have made exactly two attempts", 2, calls.get())
    }

    @Test
    fun rateLimit403_isNotRetried() = runBlocking {
        val calls = AtomicInteger(0)
        val checker = object : ReleaseChecker(retryBackoffMs = 0) {
            override fun fetchRelease(currentVersion: String): ReleaseCheckResult {
                calls.incrementAndGet()
                // A 403 comes back as a returned Failed (not thrown), so the
                // orchestration must NOT retry it.
                return ReleaseCheckResult.Failed("rate-limited, try again later")
            }
        }

        val result = checker.checkForUpdate("0.1.0")

        assertTrue(result is ReleaseCheckResult.Failed)
        assertEquals("rate-limited, try again later", (result as ReleaseCheckResult.Failed).reason)
        assertEquals("403 rate-limit must be a single attempt (no hammering)", 1, calls.get())
    }
}
