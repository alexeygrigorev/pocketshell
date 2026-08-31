package com.pocketshell.app.proof

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionLatencyTelemetry
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.tmux.revealIdentityAdoption
import com.pocketshell.core.connection.RevealIdentityAdoption
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Issue #2338 — the ORDERING dependency itself: the 2nd+ `MainActivity` launch in one
 * instrumentation process must still attach its terminal.
 *
 * ## Why a whole class exists for one ordering property
 *
 * ~15 load-bearing journey classes went red at once on `main` with the same signature: the
 * FIRST test of the class passes and EVERY later test fails at `waitForTerminalViewAttached`
 * while SSH/tmux are demonstrably healthy (`tmux-connect-ready`, `paneCount=1`) and the
 * surface simply never measures (`tmux-client-size-known` absent). Fixing any ONE of those
 * classes would not pin the property; the class-covering pin is "launch, attach, tear down,
 * launch AGAIN in the same process, attach again".
 *
 * ## The state the second launch is in (and the fixture that creates it)
 *
 * A tmux session name is unique per server, so a route's `(tmuxSessionId, sessionCreated)`
 * pair is only ever as fresh as whatever produced it. The FIRST launch is what writes those
 * caches, so from the SECOND launch onward the route can carry the PREVIOUS generation of a
 * same-named session. [tearDown] kills the session and [seedBeforeLaunch] recreates it under
 * the same name, so the live generation genuinely changes between the two launches — exactly
 * what every affected journey class does.
 *
 * Since #2294 the first authoritative `list-panes` adopts the LIVE generation and re-keys the
 * reveal reducer + connection controller onto it. Before #2338's fix the screen refused to
 * follow that adoption whenever its route carried a generation, so the fused surface state
 * held the terminal forever and no `TerminalView` was ever built.
 *
 * ## Non-vacuity (the #780 model)
 *
 * The second launch HARD-ASSERTS that the failing state was actually entered: the reveal
 * reducer must report an exact-generation [RevealIdentityAdoption] whose `from` is an EXACT
 * durable id (i.e. the route carried a generation) and differs from `to`. If the fixture ever
 * stops producing a stale route generation this test FAILS loudly instead of turning into a
 * second copy of the happy path. No `assumeTrue` anywhere.
 *
 * Wired into `scripts/ci-journey-suite.sh` so it RUNS on the emulator-journey gate.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Issue2338SecondLaunchTerminalAttachJourneyE2eTest {

    // Issue #788/#848: launch-owned `createAndroidComposeRule<MainActivity>()` with
    // seed-before-launch via the RuleChain — NOT `createEmptyComposeRule()` + a hand-rolled
    // `ActivityScenario.launch` (the interop-placement stall).
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String

    private suspend fun seedBeforeLaunch() {
        BackgroundGraceTestOverride.setForTest(null)
        TmuxSessionLatencyTelemetry.resetForTest()
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedShellSession(key)
        hostRowTag = seedDockerHost(key)
    }

    @After
    fun tearDown() {
        BackgroundGraceTestOverride.setForTest(null)
        if (::fixtureKey.isInitialized) {
            // Kill the session so the NEXT launch's seeding recreates it with a NEW
            // `session_created` — that generation change is what makes the second launch's
            // cached route identity stale, which is the state under test.
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    /**
     * Launch 1 in this process: the baseline. This one always passed even with the bug, so
     * its job is to establish the caches (and the tmux generation) the second launch inherits.
     */
    @Test
    fun aFirstLaunchInProcessAttachesTerminal() {
        attachSeededTmuxSession("first launch")
        writeSummary(
            launchOrdinal = 1,
            adoption = revealIdentityAdoption(),
            note = "baseline launch — establishes the route/tree caches the next launch reuses",
        )
    }

    /**
     * Launch 2 in the SAME process: the reported defect. Before the fix this timed out at
     * `waitForTerminalViewAttached` after 30s with SSH/tmux fully healthy.
     */
    @Test
    fun bSecondLaunchInSameProcessStillAttachesTerminal() {
        attachSeededTmuxSession("second launch")

        // Non-vacuity: prove this launch really was in the reported state (a route carrying a
        // STALE exact generation that the pane listing re-keyed away from). A green that did
        // not enter the failing state proves nothing, so hard-fail rather than pass quietly.
        val adoption = revealIdentityAdoption()
        assertTrue(
            "the second launch must have re-keyed the reveal identity away from its route " +
                "(that is the #2338 state); observed adoption=$adoption",
            adoption != null && adoption.from != adoption.to,
        )
        val from = requireNotNull(adoption).from.value
        assertTrue(
            "the #2338 wedge is specifically a route that ALREADY carried an exact tmux " +
                "generation; observed from=$from to=${adoption.to.value}. A name-only `from` " +
                "means the fixture stopped reproducing the reported state — fix the fixture, " +
                "do not relax this assertion.",
            EXACT_TARGET_ID.matches(from),
        )
        writeSummary(
            launchOrdinal = 2,
            adoption = adoption,
            note = "reported defect: stale route generation re-keyed by list-panes; the screen " +
                "must follow the adoption instead of holding the terminal forever",
        )
    }

    // ---------------------------------------------------------------- Attach / wait

    private fun attachSeededTmuxSession(label: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached(label)
    }

    /**
     * The exact condition all ~15 affected classes wait on. It is the user-visible symptom:
     * no `TerminalView` session/emulator means the terminal surface was never built, so the
     * user is looking at the loading hold with a healthy connection behind it.
     */
    private fun waitForTerminalViewAttached(label: String) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val attached = runCatching {
            compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
                var ready = false
                compose.activityRule.scenario.onActivity { activity ->
                    val view = activity.window.decorView.findTerminalView()
                    ready = view?.currentSession != null && view.mEmulator != null
                }
                ready
            }
            true
        }.getOrDefault(false)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        recordTiming(label, elapsedMs, attached)
        assertTrue(
            "REGRESSION (#2338): the terminal never attached on the $label in this process " +
                "(waited ${elapsedMs}ms). The connection is healthy at this point — this is the " +
                "reveal-identity handoff wedge, not a transport failure. " +
                "revealAdoption=${revealIdentityAdoption()}",
            attached,
        )
    }

    private fun revealIdentityAdoption(): RevealIdentityAdoption? {
        var adoption: RevealIdentityAdoption? = null
        compose.activityRule.scenario.onActivity { activity ->
            adoption = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .revealIdentityAdoption.value
        }
        return adoption
    }

    // ---------------------------------------------------------------- Fixture

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue2338-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue2338 Second Launch",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    /**
     * Recreate the SAME-named session on every launch. Killing it in [tearDown] and creating
     * it again here is what changes tmux's `session_created`, which is what makes the second
     * launch's cached route identity stale.
     */
    private suspend fun seedShellSession(key: String) {
        val payload = "printf '$BANNER_MARKER ready\\n'; while true; do sleep 3600; done"
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine("tmux new-session -d -s ${shellQuote(SESSION_NAME)} ${shellQuote(payload)}")
            appendLine("sleep 1")
            appendLine("tmux list-sessions -F '#{session_name} #{session_id} #{session_created}'")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session seeding to succeed; exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    // ---------------------------------------------------------------- Artifacts

    private fun recordTiming(label: String, elapsedMs: Long, attached: Boolean) {
        val file = artifactFile("issue2338-timings.txt")
        file.appendText("terminal_attach label=$label attached=$attached elapsed_ms=$elapsedMs\n")
        println("ISSUE2338_TIMING label=$label attached=$attached elapsed_ms=$elapsedMs")
    }

    private fun writeSummary(
        launchOrdinal: Int,
        adoption: RevealIdentityAdoption?,
        note: String,
    ): File {
        val file = artifactFile("issue2338-launch-$launchOrdinal-summary.txt")
        file.writeText(
            buildString {
                appendLine("test=Issue2338SecondLaunchTerminalAttachJourneyE2eTest")
                appendLine("issue=2338")
                appendLine("launch_ordinal_in_process=$launchOrdinal")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("reveal_identity_adoption_from=${adoption?.from?.value}")
                appendLine("reveal_identity_adoption_to=${adoption?.to?.value}")
                appendLine("terminal_attached=true")
                appendLine("note=$note")
            },
        )
        println("ISSUE2338_TEXT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact directory ${dir.absolutePath}" }
        return File(dir, name)
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue2338SecondLaunch"
        const val DEVICE_DIR_NAME: String = "issue2338-second-launch-attach"
        const val SESSION_NAME: String = "issue2338-second-launch"
        const val BANNER_MARKER: String = "ISSUE2338-READY"

        /** `tmux:<hostId>:<sessionId>:<createdEpochSeconds>` — an EXACT durable identity. */
        val EXACT_TARGET_ID: Regex = Regex("""^tmux:\d+:\$\d+:\d+$""")

        val ATTACH_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L
    }
}
