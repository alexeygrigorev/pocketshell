package com.pocketshell.app.proof

import android.os.Build
import android.os.StrictMode
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #166 — `NetworkOnMainThreadException` regression guard for the
 * SSH attach + send + disconnect cycle.
 *
 * The reviewer of #151 noted that
 * [com.pocketshell.core.ssh.RealSshSession.close] is reached from the
 * Android Main thread on several call sites:
 *
 *  - `TmuxSessionViewModel.onCleared()` -> `closeCurrentConnection()` ->
 *    `clientRef?.close()` (which transitively calls
 *    `RealSshShell.close()`) and `sessionRef?.close()`
 *  - `TerminalLabActivity` shell teardown via `Activity.onDestroy`
 *
 * `SSHClient.disconnect()` sends an `SSH_MSG_DISCONNECT` packet — a real
 * socket write. With Android's StrictMode `detectNetwork()` policy
 * active that write throws `NetworkOnMainThreadException`. The #166 fix
 * dispatches the actual socket-level call onto `Dispatchers.IO` from
 * inside `RealSshSession.close()` (and the analogous fix in
 * `RealSshShell.close()`) so the calling thread (which may be Main)
 * never owns the socket write.
 *
 * This test is the regression guard for the fix:
 *
 *  1. Wait for the deterministic `agents` Docker fixture on host port
 *     2222 (same fixture used by [EmulatorDockerSshSmokeTest]).
 *  2. Install a `ThreadPolicy` on Main that detects network usage with
 *     `penaltyLog()` plus a custom
 *     [StrictMode.OnThreadViolationListener] (API 28+) that captures
 *     the violation so the test can assert on it.
 *  3. Open the SSH session via the production suspending entry point
 *     [SshConnection.connect]. The connect itself is intentionally
 *     driven from `Dispatchers.IO` because that is the production
 *     contract every real caller (Compose `LaunchedEffect`-wrapped
 *     `openShell`, `TmuxClient.connect`'s `withContext(Dispatchers.IO)`)
 *     already honours. Putting it on Main here would only assert
 *     against a misuse that does not exist in production.
 *  4. Drive a `session.exec("printf ...")` round-trip to prove the
 *     transport is live, then close the session on the Main thread via
 *     [androidx.test.platform.app.InstrumentationRegistry]
 *     `.getInstrumentation().runOnMainSync`. With the #166 fix in place
 *     the close call does not write to the socket on Main; without it
 *     `SSHClient.disconnect()` trips the installed `detectNetwork()`
 *     policy and the listener captures the violation.
 *  5. Wait briefly for any deferred policy callbacks, then assert no
 *     network violation was observed.
 *
 * If a future regression hoists socket I/O back to the calling thread,
 * the policy listener fires, [violationRef] captures the
 * `NetworkOnMainThreadException`, and the assertion at the end of the
 * test surfaces it with a clear failure message.
 *
 * Scope: the test exercises `RealSshSession.close()` on Main only. The
 * companion fix in `RealSshShell.close()` is exercised indirectly by the
 * existing `closeDisconnectsTheSession` integration test in
 * `shared/core-ssh/src/integrationTest/.../SshIntegrationTest.kt`
 * (which closes a live session via `AutoCloseable.use`) and the unit
 * tests in `RealSshSessionCloseIdempotencyTest`. Driving an interactive
 * shell open + close from this test added a per-shell channel reader
 * thread that kept the SSH transport busy with keep-alive traffic and
 * made the test hard to disambiguate from a backgrounded teardown on
 * shared-emulator CI; the close-the-session path is the canonical
 * NMTE call site noted by the issue, so the test focuses there.
 *
 * The companion Docker fixture is brought up by the standard
 * `scripts/terminal-workbench.sh` / `scripts/run-connected-tests.sh`
 * wrappers — no extra service is required.
 */
@RunWith(AndroidJUnit4::class)
class StrictModeNoNetworkOnMainE2eTest {

    private var originalPolicy: StrictMode.ThreadPolicy? = null
    private val violationRef: AtomicReference<Throwable?> = AtomicReference(null)

    @Before
    fun installStrictMode() {
        // Snapshot the previous Main-thread policy so [restoreStrictMode]
        // can put the emulator back the way it was. Sibling tests on the
        // same emulator should not see our detect-network bit.
        val captured: AtomicReference<StrictMode.ThreadPolicy?> = AtomicReference(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            captured.set(StrictMode.getThreadPolicy())
        }
        originalPolicy = captured.get()

        val builder = StrictMode.ThreadPolicy.Builder()
            .detectNetwork()
            .penaltyLog()
        val policy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder
                .penaltyListener(
                    { runnable -> runnable.run() },
                    StrictMode.OnThreadViolationListener { violation ->
                        // Only network violations are interesting for
                        // this test; other policy categories (disk I/O,
                        // resource mismatches) are noisy on a real
                        // device. The class name check is robust across
                        // SDK versions where the concrete subclass name
                        // has wobbled between
                        // `android.os.strictmode.NetworkViolation` and
                        // similar.
                        if (violation::class.java.simpleName.contains("Network")) {
                            violationRef.compareAndSet(null, violation)
                            Log.w(LOG_TAG, "issue166-strict-mode-network-violation", violation)
                        }
                    },
                )
                .build()
        } else {
            builder.build()
        }
        // Install on the Main thread — `detectNetwork()` is per-thread
        // and the issue is specifically about Main-thread socket writes
        // during `close()`.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            StrictMode.setThreadPolicy(policy)
        }
    }

    @After
    fun restoreStrictMode() {
        val previous = originalPolicy ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            StrictMode.setThreadPolicy(previous)
        }
    }

    @Test
    fun fullAttachAndDisconnectCycleEmitsNoNetworkOnMainViolation() = runBlocking {
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        // Step 1 (attach): connect. `SshConnection.connect` is suspending
        // and already wraps the sshj `connect` + `authPublickey` in
        // `withContext(Dispatchers.IO)`. Driving it from runBlocking on
        // the instrumentation thread mirrors how every real caller
        // (TmuxSessionViewModel, HostListViewModel, the proof-of-life
        // screen, the usage scheduler) reaches this code path — they all
        // suspend, never block on Main.
        val sessionResult = withTimeout(30_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            )
        }
        assertTrue(
            "expected SSH connect to Docker target to succeed, got ${sessionResult.exceptionOrNull()}",
            sessionResult.isSuccess,
        )
        val session: SshSession = sessionResult.getOrThrow()

        try {
            // Step 2 (send): exec is suspending and dispatches on
            // `Dispatchers.IO` from inside `RealSshSession.exec`. The
            // round-trip proves the live transport is healthy before we
            // drive the Main-thread close path. Asserting on the exec
            // result also catches a class of regressions where the close
            // would no-op because the transport never reached a healthy
            // state.
            val exec = session.exec("printf 'pocketshell-issue166-strict-mode-smoke\\n'")
            assertTrue(
                "expected exec stdout to contain the smoke marker, got " +
                    "stdout='${exec.stdout}' stderr='${exec.stderr}' exit=${exec.exitCode}",
                exec.stdout.contains("pocketshell-issue166-strict-mode-smoke"),
            )

            // Step 3 (disconnect, the load-bearing part of the test):
            // close the SSH session on Main. Without #166's
            // `runBlocking(Dispatchers.IO)` dispatch inside
            // `RealSshSession.close()`, this would write
            // `SSH_MSG_DISCONNECT` on Main and trip the installed
            // detect-network policy. With the fix the calling thread
            // (Main) is blocked for the duration of the disconnect but
            // does not own the socket call itself, so no violation
            // fires.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                session.close()
            }
        } catch (t: Throwable) {
            // Best-effort cleanup so a thrown assertion below does not
            // leak the live SSH transport. Wrapped in runCatching so a
            // stray exception during cleanup doesn't mask the original
            // failure.
            runCatching { session.close() }
            throw t
        }

        // Step 4: let any deferred policy callbacks land. The listener
        // is invoked on the executor we provided (synchronous), so a
        // 200 ms wait is a safety margin for the platform's internal
        // policy dispatcher rather than the strict path.
        Thread.sleep(200)

        val violation = violationRef.get()
        assertNull(
            "StrictMode detected a Main-thread network violation during the SSH " +
                "attach + send + disconnect cycle. This is exactly the latent " +
                "NetworkOnMainThreadException tracked in issue #166. The fix " +
                "must dispatch the socket-level call onto Dispatchers.IO. " +
                "Captured violation: $violation",
            violation,
        )
        // Defence-in-depth assertion: the test would still pass with
        // [violation] null if `session` failed to open. Guard against
        // that by re-asserting we actually had a healthy session.
        assertNotNull(
            "expected the SSH session smoke test to have produced a live session",
            session,
        )
    }

    private companion object {
        const val LOG_TAG = "Issue166StrictModeNet"
    }
}
