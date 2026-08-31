package com.pocketshell.app.tmux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2174 — REAL-HOST (G10/D33) proof for the pane-content capture path
 * on a host whose sshd exports no UTF-8 locale.
 *
 * ## What this fixture actually does (measured, not assumed)
 *
 * The Docker `agents` fixture's sshd hands a bare SSH exec (sshj /
 * `SshSession.exec`, no `sh -lc`) **no** `LANG`/`LC_*`. On that client:
 *
 *  - `display-message -p` / `list-sessions -F` / `show-options -v` run
 *    `utf8_sanitize()`: `ПРИВЕТ` → `______`. This is the #2160 mechanism,
 *    and it is the live half of [TmuxClient.captureWithCursor] (the cursor
 *    query is a `display-message -p`).
 *  - `capture-pane -p` / `capture-pane -p -e` dump the grid **without**
 *    sanitising, on both tmux 3.6b (this fixture) and tmux 3.4 (the
 *    maintainer's box, measured with `env -i` + an isolated `-L` socket).
 *    A UTF-8-locale fixture is not why capture-pane stays intact — the
 *    verb itself does not take that path.
 *
 * So a test that asserts "bare `capture-pane` returns `______`" **cannot
 * enter the failing state on any tmux we ship against**. That is the
 * #2160 locale-trap in a different costume: a green that would also be
 * green with the bug present. This class therefore:
 *
 *  1. HARD-ASSERTS the empty-locale precondition (no `assumeTrue` skip).
 *  2. HARD-ASSERTS the live mechanism on the heal-lane verb:
 *     `display-message -p '#{pane_title}'` mangles without `-u` and
 *     preserves with `${TmuxRead.CLIENT}` (non-vacuity).
 *  3. Drives the production [TmuxClient.captureWithCursor] /
 *     [TmuxClient.capturePaneTextViaExec] path and asserts the grid
 *     still contains `ПРИВЕТ` (no regression; also the safety net if
 *     a future tmux starts sanitising capture-pane).
 *
 * The JVM sibling (`Issue2174LocaleProofPaneCaptureTest`) is the
 * reproduce-first ratchet: its FakeSession applies the sanitiser to
 * capture-pane unless `-u` is present, so dropping `-u` from either
 * production command reddens before this host is involved.
 */
@RunWith(AndroidJUnit4::class)
class Issue2174LocaleProofPaneCaptureDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private val cleanupCommands = mutableListOf<String>()

    private val suffix = System.currentTimeMillis().toString().takeLast(8)
    private val sessionName = "issue2174-$suffix"

    @Before
    fun setUp(): Unit { runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation().context.assets
            .open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(20_000) {
                    withSession { it.exec(cleanupCommands.joinToString("\n")) }
                }
            }
            Unit
        }
    } }

    /**
     * Fixture + mechanism, asserted rather than assumed. If the exec
     * channel ever gains a UTF-8 locale, OR if `display-message -p`
     * stops sanitising a non-ASCII format expansion, this class has
     * stopped being a reproduction of the live heal-lane verb.
     */
    @Test
    fun nonUtf8LocaleFixturePrecondition(): Unit { runBlocking {
        val env = withTimeout(20_000) {
            withSession { it.exec("env | sort").stdout }
        }
        val localeVars = env.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("LANG=") || it.startsWith("LC_") }
            .toList()
        assertTrue(
            "#2174 depends on the `agents` fixture handing its SSH exec " +
                "channel NO UTF-8 locale. The fixture now exports $localeVars, " +
                "so this class is no longer a reproduction. Re-anchor it " +
                "instead of skipping.",
            localeVars.isEmpty(),
        )

        val probeSession = "issue2174-probe-$suffix"
        cleanupCommands += "tmux kill-session -t '=$probeSession' 2>/dev/null || true"
        val probe = seedAndMeasure(probeSession)
        assertTrue(
            "the seed must store the pane title intact under `${TmuxRead.CLIENT}`. " +
                "Probe:\n${probe.raw}",
            probe.titleDashU.contains(CYRILLIC),
        )
        assertTrue(
            "#2174 NON-VACUITY: a bare `tmux display-message -p` on this " +
                "fixture must sanitise ПРИВЕТ to `$MANGLED`. That is the live " +
                "heal-lane verb inside captureWithCursor. If this stops " +
                "mangling, the fixture can no longer enter the failing " +
                "state. Probe:\n${probe.raw}",
            probe.titleBare.contains(MANGLED) && !probe.titleBare.contains(CYRILLIC),
        )
        assertTrue(
            "`${TmuxRead.CLIENT} display-message -p` must preserve ПРИВЕТ. " +
                "Probe:\n${probe.raw}",
            probe.titleDashU.contains(CYRILLIC),
        )
    } }

    /**
     * Production path: the real [TmuxClient] exec-lane captures against
     * this non-UTF-8 host. capture-pane itself does not sanitise on the
     * tmux we ship against (see class KDoc); this is the no-regression
     * half plus the safety net if that ever changes.
     */
    @Test
    fun productionCapturePreservesNonAsciiOnThisNonUtf8Host(): Unit { runBlocking {
        cleanupCommands += "tmux kill-session -t '=$sessionName' 2>/dev/null || true"
        val probe = seedAndMeasure(sessionName)
        assertTrue(
            "precondition: the pane must hold ПРИВЕТ before we capture. Probe:\n${probe.raw}",
            probe.gridDashU.contains(CYRILLIC),
        )
        assertTrue(
            "NON-VACUITY: display-message on THIS pane must still mangle " +
                "without `-u`, otherwise this host is no longer the reported " +
                "state. Probe:\n${probe.raw}",
            probe.titleBare.contains(MANGLED) && !probe.titleBare.contains(CYRILLIC),
        )
        assertTrue("seed must produce a pane id; probe:\n${probe.raw}", probe.paneId.startsWith("%"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            withTimeout(60_000) {
                withSession { session ->
                    val client = TmuxClientFactory(scope).create(
                        session = session,
                        sessionName = sessionName,
                        createIfMissing = true,
                    )
                    client.use {
                        it.connect()
                        val heal = it.captureWithCursor(
                            probe.paneId,
                            scrollbackLines = 200,
                            timeoutMs = 2_500L,
                        )
                        assertFalse(
                            "heal capture must not be an error; got ${heal.capture.output}",
                            heal.capture.isError,
                        )
                        val healText = heal.capture.output.joinToString("\n")
                        assertTrue(
                            "#2174: captureWithCursor must still return ПРИВЕТ on " +
                                "this host (no regression). Got:\n$healText",
                            healText.contains(CYRILLIC),
                        )

                        val visible = it.capturePaneTextViaExec(
                            probe.paneId,
                            timeoutMs = 2_500L,
                        )
                        assertFalse(visible.isError)
                        assertTrue(
                            "#2174: capturePaneTextViaExec (visible) must still " +
                                "return ПРИВЕТ. Got:\n${visible.output}",
                            visible.output.joinToString("\n").contains(CYRILLIC),
                        )

                        val scrollback = it.capturePaneTextViaExec(
                            probe.paneId,
                            timeoutMs = 2_500L,
                            scrollbackLines = 200,
                        )
                        assertFalse(scrollback.isError)
                        assertTrue(
                            "#2174: capturePaneTextViaExec (scrollback) must still " +
                                "return ПРИВЕТ. Got:\n${scrollback.output}",
                            scrollback.output.joinToString("\n").contains(CYRILLIC),
                        )
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    } }

    private data class Probe(
        val paneId: String,
        val titleBare: String,
        val titleDashU: String,
        val gridDashU: String,
        val raw: String,
    )

    private suspend fun seedAndMeasure(name: String): Probe {
        val raw = withTimeout(45_000) {
            withSession { session ->
                session.exec(
                    buildString {
                        appendLine("set -eu")
                        appendLine("tmux kill-session -t '=$name' 2>/dev/null || true")
                        appendLine(
                            "tmux new-session -d -x 80 -y 24 -s '$name' -- " +
                                "sh -c 'printf \"$CYRILLIC $CHECK\\n\"; exec sleep 300'",
                        )
                        appendLine("pane=\$(${TmuxRead.CLIENT} display-message -p -t '=$name:' '#{pane_id}')")
                        appendLine("${TmuxRead.CLIENT} select-pane -t \"\$pane\" -T '$CYRILLIC'")
                        appendLine("i=0")
                        appendLine("while [ \"\$i\" -lt 50 ]; do")
                        appendLine(
                            "  if ${TmuxRead.CLIENT} capture-pane -p -t \"\$pane\" | " +
                                "grep -a -q -- '$CYRILLIC'; then break; fi",
                        )
                        appendLine("  i=\$((i + 1)); sleep 0.1")
                        appendLine("done")
                        appendLine("printf 'PANE=%s\\n' \"\$pane\"")
                        appendLine(
                            "printf 'TITLE_BARE=%s\\n' " +
                                "\"\$(tmux display-message -p -t \"\$pane\" '#{pane_title}')\"",
                        )
                        appendLine(
                            "printf 'TITLE_DASHU=%s\\n' " +
                                "\"\$(${TmuxRead.CLIENT} display-message -p -t \"\$pane\" '#{pane_title}')\"",
                        )
                        appendLine(
                            "printf 'GRID_DASHU=%s\\n' " +
                                "\"\$(${TmuxRead.CLIENT} capture-pane -p -t \"\$pane\" | tr -d '\\r')\"",
                        )
                    },
                ).stdout
            }
        }
        fun field(key: String): String = raw.lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")
            .orEmpty()
        return Probe(
            paneId = field("PANE").trim(),
            titleBare = field("TITLE_BARE"),
            titleDashU = field("TITLE_DASHU"),
            gridDashU = field("GRID_DASHU"),
            raw = raw,
        )
    }

    private suspend fun <T> withSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 20_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private companion object {
        const val CYRILLIC = "ПРИВЕТ"
        const val MANGLED = "______"
        const val CHECK = "✓"
    }
}
