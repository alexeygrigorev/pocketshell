package com.pocketshell.app.portfwd

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.tmux.PortDetector
import com.pocketshell.core.portfwd.PortScanner
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2176 — the end-to-end proof of the feature's whole point: **a port a
 * session opened belongs to THAT session**.
 *
 * This runs against the deterministic `agents` Docker fixture with **two live
 * tmux sessions**, each holding a real listening socket, and drives the real
 * production path end to end:
 *
 *  - the real [SessionMentionedPortsTracker];
 *  - the real [PortScanner] `LISTEN` confirm over a real SSH exec (the fixture
 *    has no `ss`, so this exercises the `netstat` fallback — a genuinely
 *    non-happy host, which is precisely why the fixture is worth using);
 *  - the real `@ps_session_ports` tmux user option, written and read back over
 *    the real transport.
 *
 * ## Why the fixture is the right host, not a convenience
 *
 * The `agents` image's sshd exports **no locale**, so its exec channel puts tmux
 * out of UTF-8 mode and `utf8_sanitize()` mangles read-back values — issue
 * #2160, measured on this exact image. A durable-storage feature verified only
 * on the maintainer's UTF-8 box would look healthy and be broken for everyone
 * else. Reading the option back correctly HERE is therefore load-bearing
 * evidence, not incidental.
 *
 * Nothing is faked: no stub session, no injected scan, no pre-seeded store.
 */
@RunWith(AndroidJUnit4::class)
class Issue2176SessionPortsAttributionDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private val cleanupCommands = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val hostId = 2176L
    private val suffix = System.currentTimeMillis().toString().takeLast(8)
    private val sessionA = "issue2176-alpha-$suffix"
    private val sessionB = "issue2176-beta-$suffix"

    /**
     * One port inside [InterestingPortFilter]'s host-wide `3000..10000` denoise
     * range and one deliberately OUTSIDE it. Showing both proves the explicit
     * non-goal end to end: a session's own ports are never hidden by the
     * host-wide range filter, because attribution already solved the noise
     * problem the range exists for.
     */
    private val portA = 3000 + Random.nextInt(0, 900)
    private val portB = 41000 + Random.nextInt(0, 900)

    /** Mentioned, but nothing ever binds it. */
    private val deadPort = 45900 + Random.nextInt(0, 90)

    @Before
    fun setUp(): Unit { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyText = instrumentation.context.assets.open("test_key")
            .bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        scope.cancel()
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(20_000) {
                    withSshSession { it.exec(cleanupCommands.joinToString("\n")) }
                }
            }
        }
        Unit
    } }

    @Test
    fun portsAreAttributedToTheSessionThatOpenedThemAndSurviveAColdStart(): Unit { runBlocking {
        seedListeningSession(sessionA, portA)
        seedListeningSession(sessionB, portB)
        awaitListening(setOf(portA, portB))
        assertFalse(
            "the fixture must never have bound the 'dead' port, or the negative " +
                "case proves nothing",
            withSshSession { PortScanner.scan(it) }.any { it.port == deadPort },
        )

        withSshSession { session ->
            val store = SessionPortsStore()
            val trackerA = trackerFor(sessionA, store, session)
            val trackerB = trackerFor(sessionB, store, session)

            // Session A announces its own port — and also merely MENTIONS a port
            // nothing is listening on, the "agent echoed a URL out of a file"
            // case that the `LISTEN` confirm exists to reject.
            trackerA.confirmAndSurface(
                PortDetector.Candidate(portA, "Local:   http://localhost:$portA/"),
            )
            trackerA.confirmAndSurface(
                PortDetector.Candidate(deadPort, "the README mentions http://localhost:$deadPort/"),
            )
            trackerB.confirmAndSurface(
                PortDetector.Candidate(portB, "Serving HTTP on 0.0.0.0 port $portB ..."),
            )

            // ---- criterion 2: A's port is not in B's list, and vice versa ----
            val keyA = SessionPortsKey(hostId, sessionA)
            val keyB = SessionPortsKey(hostId, sessionB)
            assertEquals(listOf(portA), store.mentionsFor(keyA).map { it.port })
            assertEquals(listOf(portB), store.mentionsFor(keyB).map { it.port })

            // ---- criterion 3: the mentioned-but-not-listening port has no row ----
            assertFalse(
                "a port nothing is listening on must not become a row",
                store.hasRecorded(keyA, deadPort),
            )

            // ---- criterion 1: the row carries what the panel renders ----
            val rowA = store.mentionsFor(keyA).single()
            assertEquals("Local:   http://localhost:$portA/", rowA.matchedText)
            assertTrue(
                "the LISTEN scan's process name should reach the row; got '${rowA.process}'",
                rowA.process.isNotBlank(),
            )

            // ---- criterion 4: the durable host-side option, per session ----
            val storedA = awaitStoredPorts(session, sessionA, setOf(portA))
            val storedB = awaitStoredPorts(session, sessionB, setOf(portB))
            assertEquals(setOf(portA), storedA)
            assertEquals(setOf(portB), storedB)
            assertFalse("A's option must not carry B's port", portB in storedA)
            assertFalse("B's option must not carry A's port", portA in storedB)
            assertFalse("the dead port must never be persisted", deadPort in storedA)
        }

        // ---- criterion 4, the app-restart half: a COLD store recovers from the
        // host, still attributed per session, over the locale-proof read on a
        // fixture whose sshd exports no locale (#2160).
        withSshSession { session ->
            val coldStore = SessionPortsStore()
            trackerFor(sessionA, coldStore, session).ensureHostLoad()
            trackerFor(sessionB, coldStore, session).ensureHostLoad()

            val recoveredA = awaitRecovered(coldStore, SessionPortsKey(hostId, sessionA))
            val recoveredB = awaitRecovered(coldStore, SessionPortsKey(hostId, sessionB))

            assertEquals(listOf(portA), recoveredA.map { it.port })
            assertEquals(listOf(portB), recoveredB.map { it.port })
            assertEquals(
                "the matched line must survive the round trip intact on a " +
                    "non-UTF-8 host (#2160)",
                "Local:   http://localhost:$portA/",
                recoveredA.single().matchedText,
            )
        }
        Unit
    } }

    // ------------------------------------------------------------------ setup

    /**
     * Start a tmux session whose payload holds a listening TCP socket, so the
     * port genuinely belongs to that session and dies with it. `nc -l -p` is the
     * only listener the Alpine fixture ships.
     */
    private suspend fun seedListeningSession(sessionName: String, port: Int) {
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        withSshSession { session ->
            session.exec(
                "tmux new-session -d -s ${shellQuote(sessionName)} " +
                    shellQuote("nc -l -p $port"),
            )
        }
    }

    private suspend fun awaitListening(ports: Set<Int>) {
        withTimeout(45_000) {
            repeat(90) {
                val listening = withSshSession { PortScanner.scan(it) }.map { it.port }.toSet()
                if (ports.all { it in listening }) return@withTimeout
                delay(500)
            }
            error("fixture never reported $ports in LISTEN")
        }
    }

    private fun trackerFor(
        sessionName: String,
        store: SessionPortsStore,
        session: SshSession,
    ): SessionMentionedPortsTracker = SessionMentionedPortsTracker(
        detector = PortDetector(),
        store = store,
        scope = scope,
        sessionProvider = { session },
        sessionKeyProvider = { SessionPortsKey(hostId, sessionName) },
        appActive = { true },
        overlayPort = { null },
        setOverlayPort = {},
    )

    /**
     * Poll the REAL host option through the REAL production read command until
     * it decodes to the expected ports. Polling (rather than joining the
     * fire-and-forget write) is deliberate: the write must not be awaitable from
     * the detection path, which is the property [SessionPortsStore] documents.
     */
    private suspend fun awaitStoredPorts(
        session: SshSession,
        sessionName: String,
        expected: Set<Int>,
    ): Set<Int> = withTimeout(30_000) {
        var decoded = emptySet<Int>()
        repeat(60) {
            val raw = session.exec(SessionPortsHostOptions.readCommand(sessionName)).stdout
            decoded = SessionPortMentionCodec.decode(raw).map { it.port }.toSet()
            if (decoded == expected) return@withTimeout decoded
            delay(500)
        }
        decoded
    }

    private suspend fun awaitRecovered(
        store: SessionPortsStore,
        key: SessionPortsKey,
    ): List<SessionPortMention> = withTimeout(30_000) {
        repeat(60) {
            val rows = store.mentionsFor(key)
            if (rows.isNotEmpty()) return@withTimeout rows
            delay(500)
        }
        store.mentionsFor(key)
    }

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
