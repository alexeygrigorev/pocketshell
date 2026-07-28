package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CONTENT_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CREATE_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CWD_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_SHELL_TAG
import com.pocketshell.app.projects.SessionNamePolicy
import com.pocketshell.app.projects.SshFolderListGateway
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #898 (reviewer Finding 1 + Finding 2, then Blocker A class-coverage) —
 * connected red→green proof for the in-session "+ New session" rich-sheet
 * create path, against the deterministic Docker `agents` fixture on host port
 * `2222`.
 *
 * ## The bug this reproduces (Finding 1 / Blocker A)
 *
 * Every in-session "+ New session" trigger opens the rich
 * [com.pocketshell.app.projects.SessionTypePickerSheet]; Create derives the new
 * session's name from the chosen folder
 * ([com.pocketshell.app.projects.derivedSessionName]). If it derives that name
 * WITHOUT the host's already-known session names, a SECOND "New session" in the
 * SAME folder as an existing session derives the IDENTICAL base name, and the
 * gateway's attach-if-exists create (`tmux new-session -A`) never makes a
 * genuinely new session out of it. The user taps Create expecting a second
 * session and gets nothing new. (#1820 established what actually happens at
 * that point: not a silent no-op, but a hard `open terminal failed: not a
 * terminal` — see the #1820 section below.)
 *
 * The first #898 fix patched only the KEBAB trigger; the SWITCHER trigger
 * `dismiss()`ed the picker (state → Idle, no rows) and opened the sheet WITHOUT
 * reloading, so its known-names set was empty and the collision survived there
 * (the reviewer's Blocker A). The fix now routes ALL triggers through a single
 * `openNewSessionSheet()` helper that (re)loads the host session list. This test
 * therefore drives BOTH reachable entry points — kebab AND switcher — so the
 * class is covered, not just the one instance.
 *
 * ## Why this is end-to-end, NOT a stubbed `onCreate`
 *
 * The defect lives in the screen's REAL create lambda (`derivedSessionName(…)` →
 * `viewModel.createSession` →
 * [com.pocketshell.app.projects.FolderListGateway.createSession]) and in the
 * REAL entry-point wiring. So this test drives the PRODUCTION [MainActivity] →
 * host card → attach → real trigger → real rich sheet → real Create, over
 * Docker. A stubbed `onCreate` would bypass precisely the lines where the bug is.
 *
 * ## Red→green
 *
 * Per entry point: seed an existing tmux session named `tmp-<folder>-<suffix>`
 * whose cwd is an outside-home folder (so the derived base is unambiguous
 * regardless of `$HOME`), open that trigger's "+ New session", pick Shell, set
 * the Start folder to the SAME folder, and Create.
 *  - PRE-FIX: the derived name is the base, the `-A` create no-ops, and the host
 *    still has exactly ONE matching session → the load-bearing assertion FAILS.
 *  - POST-FIX: the host resolves `<base>-2`, a genuinely new session → the host
 *    has BOTH `<base>` AND `<base>-2` → PASS.
 *
 * ## Issue #1820: why the journey test alone was not enough
 *
 * This journey was the second-most costly flaky class on `main` — 12 of 27 runs
 * affected. The cause was not the test: on 2026-07-28 the in-session picker
 * published `Ready` carrying a session list that OMITTED the very session the
 * app was attached to over `-CC`, so the screen's client-side de-dupe produced
 * the colliding base name and the create failed with
 * `open terminal failed: not a terminal`. The journey could only observe that
 * intermittently, because it depended on winning a race inside the picker.
 *
 * #1820 moved the uniqueness decision to the host (see
 * [com.pocketshell.app.projects.SessionNamePolicy]), which has two consequences
 * for this file. First, the journey above is now DETERMINISTIC in the failing
 * input: the screen always asks for the bare base, so every run exercises what
 * used to be the rare racing state. Second,
 * [collidingBaseNameStillYieldsAGenuinelyNewSessionOnTheHost] below pins the
 * host-side contract directly on a real tmux, without needing any race at all —
 * that is the deterministic red→green for #1820.
 *
 * Artifacts under
 * `<media>/additional_test_output/issue898-in-session-new-session-collision/`.
 */
@RunWith(AndroidJUnit4::class)
class TmuxInSessionNewSessionCollisionDockerTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Grant runtime permissions before the activity launches so the system
    // GrantPermissionsActivity never steals focus from the Compose hierarchy.
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val createdSessions = mutableSetOf<String>()
    private val createdFolders = mutableSetOf<String>()

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching {
                withTimeout(20_000) {
                    // Issue #1820: `=` forces tmux's EXACT session match.
                    // Without it `kill-session -t '<base>'` prefix-matches, so
                    // cleaning up `<base>` could also take out an unrelated
                    // `<base>-…` session on the shared fixture.
                    val kill = createdSessions.joinToString("; ") {
                        "tmux kill-session -t '=$it' 2>/dev/null || true"
                    }
                    val rm = createdFolders.joinToString("; ") {
                        "rm -rf '$it' 2>/dev/null || true"
                    }
                    sshExec("$kill; $rm")
                }
            }
        }
    }

    @Test
    fun inSessionNewSessionInSameFolderGetsSuffixedSessionNotCollisionAllEntryPoints() { runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        // Fresh summary file for this run (both entry points append to it).
        artifactFile("summary.txt").writeText("")
        val hostRowTag = seedDockerHost(key, "Issue898 InSession NewSession")

        // Class coverage (Blocker A): run the SAME same-folder collision check
        // for BOTH reachable "+ New session" entry points. Each gets its own
        // folder/session so the two runs don't interfere.
        runEntryPoint(EntryPoint.KEBAB, hostRowTag)
        runEntryPoint(EntryPoint.SWITCHER, hostRowTag)
    } }

    /**
     * Issue #1820 — the DETERMINISTIC red→green, on a real tmux over real SSH.
     *
     * The journey test above can only reach the defect when it wins a race
     * inside the picker (12/27 `main` runs). This drives the identical *input*
     * the app produces whenever its session list is empty, stale, or simply
     * wrong — "create a session in this folder, named `<base>`" while `<base>`
     * is already live — straight at the production create path, so there is no
     * race to win and no fixture that can mask it.
     *
     * PRE-FIX (`createSessionOnSession` with no name policy): the idempotent
     * `tmuxctl create-detached` / `tmux new-session -A -d` is handed the name
     * that is already taken, so the host ends up with exactly ONE session → RED.
     * POST-FIX: the host resolves `<base>-2` before creating, both sessions
     * exist, and the RESOLVED name is what comes back → GREEN.
     *
     * A detail worth stating because the codebase repeats the opposite: `tmux
     * new-session -A -d -s <taken>` is NOT a silent no-op over an SSH exec. `-A`
     * turns the call into an attach, `-d` is not the "detach others" flag (`-D`
     * is), and with no tty the attach dies with `open terminal failed: not a
     * terminal` — which is precisely the error the maintainer's CI run recorded.
     * So the pre-#1820 collision was not a subtle mis-attach the user might not
     * notice; it was a visible failure with no session created.
     *
     * Class coverage (G2), because the reported instance is one of several:
     *  - a plain Shell create (the reported case),
     *  - a LAUNCH create (`startCommand != null`), which before #1820 hit the
     *    #976 collision guard and hard-failed instead of making a session,
     *  - a THIRD session in the same folder, which must walk to `-3` rather
     *    than stopping at `-2`,
     *  - [SessionNamePolicy.ExactName], which must NOT disambiguate — the
     *    stale-session recovery path depends on getting the name it asked for.
     */
    @Test
    fun collidingBaseNameStillYieldsAGenuinelyNewSessionOnTheHost() { runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        val suffix = "host-${System.nanoTime().toString().takeLast(6)}"
        val folder = "/tmp/issue1820-$suffix"
        val baseName = "tmp-issue1820-$suffix"
        createdFolders += folder
        // Register every name the walk can produce so tearDown cleans up even
        // when an assertion aborts the test part-way.
        listOf(baseName, "$baseName-2", "$baseName-3").forEach { createdSessions += it }

        sshExec(
            "mkdir -p '$folder'; " +
                "tmux kill-session -t '=$baseName' 2>/dev/null || true; " +
                "tmux new-session -d -s '$baseName' -c '$folder' " +
                "'while true; do sleep 60; done'",
        )
        assertEquals(
            "[host] exactly the one seeded session must exist before create; " +
                "got ${listSessionsMatching(baseName)}",
            listOf(baseName),
            listSessionsMatching(baseName),
        )

        val gateway = SshFolderListGateway()
        val summary = StringBuilder()

        withSshSession { session ->
            // 1) Shell create with the COLLIDING base name — the reported case.
            val shellResolved = labelled("shell-create") {
                gateway.createSessionOnSession(
                    session = session,
                    sessionName = baseName,
                    cwd = folder,
                    startCommand = null,
                    namePolicy = SessionNamePolicy.UniqueOnHost,
                )
            }
            summary.appendLine("shell_requested=$baseName resolved=$shellResolved")
            assertEquals(
                "[host] a colliding Shell create must resolve to the '-2' name, not the base",
                "$baseName-2",
                shellResolved,
            )
            assertEquals(
                "[host] the '-2' session must genuinely exist alongside the original",
                listOf(baseName, "$baseName-2"),
                listSessionsMatching(baseName).sorted(),
            )

            // 2) A LAUNCH create in the same folder, again with the colliding
            //    base. Before #1820 this did not merely collide — the #976
            //    guard refused it outright, so "start an agent here" failed
            //    whenever the folder already had a session.
            val launchResolved = labelled("launch-create") {
                gateway.createSessionOnSession(
                    session = session,
                    sessionName = baseName,
                    cwd = folder,
                    startCommand = "printf 'issue1820 launch\\n'",
                    namePolicy = SessionNamePolicy.UniqueOnHost,
                )
            }
            summary.appendLine("launch_requested=$baseName resolved=$launchResolved")
            assertEquals(
                "[host] a colliding LAUNCH create must walk past '-2' to '-3'",
                "$baseName-3",
                launchResolved,
            )
            assertEquals(
                "[host] all three sessions must exist after the walk",
                listOf(baseName, "$baseName-2", "$baseName-3"),
                listSessionsMatching(baseName).sorted(),
            )

            // 3) ExactName must NOT disambiguate. Its one caller is the
            //    stale-session recovery action, which recreates a session that
            //    has GONE, under the name the user is recovering — so the name
            //    is free and the create must honour it verbatim. Handing that
            //    caller a `-4` would leave its follow-up attach pointing at a
            //    name that does not exist. Reproduce that state exactly: a name
            //    that is NOT taken, in a folder that already has three
            //    same-prefix siblings, so a stray disambiguation would be
            //    visible.
            val recoveryName = "$baseName-recovery"
            createdSessions += recoveryName
            createdSessions += "$recoveryName-2"
            val exactResolved = labelled("exact-create") {
                gateway.createSessionOnSession(
                    session = session,
                    sessionName = recoveryName,
                    cwd = folder,
                    startCommand = null,
                    namePolicy = SessionNamePolicy.ExactName,
                )
            }
            summary.appendLine("exact_requested=$recoveryName resolved=$exactResolved")
            assertEquals(
                "[host] ExactName must return the requested name verbatim",
                recoveryName,
                exactResolved,
            )
            assertEquals(
                "[host] ExactName must create exactly that session and nothing else",
                listOf(baseName, "$baseName-2", "$baseName-3", recoveryName),
                listSessionsMatching(baseName).sorted(),
            )
        }

        artifactFile("issue1820-host-resolution.txt").writeText(
            buildString {
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("folder=$folder")
                appendLine("seeded_session=$baseName")
                append(summary)
                appendLine("sessions_final=${listSessionsMatching(baseName).sorted()}")
            },
        )
        Log.i(LOG_TAG, "[host] issue1820 resolution: $summary")
    } }

    /**
     * Name the step a thrown create failure came from. Without this a
     * `RuntimeException` out of `createSessionOnSession` gives a stack line but
     * not WHICH of the three creates raised it, and the three mean very
     * different things.
     */
    private suspend fun <T> labelled(step: String, block: suspend () -> T): T =
        try {
            block()
        } catch (error: Throwable) {
            throw AssertionError("[host] $step failed: ${error.message}", error)
        }

    private enum class EntryPoint(val slug: String) {
        KEBAB("kebab"),
        SWITCHER("switcher"),
    }

    private suspend fun runEntryPoint(entry: EntryPoint, hostRowTag: String) {
        val suffix = "${entry.slug}-${System.nanoTime().toString().takeLast(6)}"
        val folder = "/tmp/issue898-$suffix"
        val baseName = "tmp-issue898-$suffix"
        val secondName = "$baseName-2"
        createdFolders += folder
        createdSessions += baseName
        createdSessions += secondName

        // 1) Seed the folder + an existing session named after that folder.
        sshExec(
            "mkdir -p '$folder'; " +
                "tmux kill-session -t '$baseName' 2>/dev/null || true; " +
                "tmux new-session -d -s '$baseName' -c '$folder' " +
                "'while true; do sleep 60; done'",
        )
        val seeded = listSessionsMatching(baseName)
        assertEquals(
            "[${entry.slug}] exactly the one seeded session must exist before create; got $seeded",
            listOf(baseName),
            seeded,
        )

        // 2) Launch the app, open the host, attach to the seeded session.
        launchedActivity?.close()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(baseName, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(baseName, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 2b) Issue #1820: the session screen renders its tag whether or not the
        // attach actually succeeded, so reaching it proves nothing about being
        // CONNECTED. That mattered: a failed attach leaves the view model with no
        // active target, and Create then falls through to the legacy
        // control-channel branch and silently does nothing — producing this
        // test's `-2`-missing signature for a completely different reason. Ask
        // the HOST instead: tmux itself reports whether a client is attached to
        // the session, so this is an authoritative precondition rather than a UI
        // guess, and a failure here names the real cause instead of masquerading
        // as a name collision. Being attached is also the maintainer's actual
        // state when they tap "+ New session", so this is the reported scenario,
        // not a convenient one.
        val attachDeadline = SystemClock.elapsedRealtime() + 30_000
        var attachedClients = 0
        while (SystemClock.elapsedRealtime() < attachDeadline) {
            attachedClients = attachedClientCount(baseName)
            if (attachedClients > 0) break
            SystemClock.sleep(500)
        }
        assertTrue(
            "[${entry.slug}] the app must be ATTACHED to $baseName before creating a " +
                "second session; tmux reports $attachedClients attached client(s). " +
                "A create tapped while unattached cannot reach the gateway, so this " +
                "is an attach failure, NOT the #1820 name collision.",
            attachedClients > 0,
        )

        // 3) Open this entry point's "+ New session" (the REAL screen wiring that
        // must load the picker state before opening the rich sheet).
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        when (entry) {
            EntryPoint.KEBAB -> {
                compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
                    .performClick()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText("+ New session", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("+ New session", useUnmergedTree = true).performClick()
            }
            EntryPoint.SWITCHER -> {
                // Kebab → "Switch session" opens the SessionSwitcherOverlay,
                // whose "New" button is the second reachable entry point.
                compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
                    .performClick()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText("Switch session", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Switch session", useUnmergedTree = true).performClick()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText("New", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("New", useUnmergedTree = true).performClick()
            }
        }

        // 4) The rich sheet is shown — pick Shell and point Start folder at the
        // SAME folder as the existing session (so the derived base collides).
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SESSION_TYPE_PICKER_CONTENT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SESSION_TYPE_PICKER_SHELL_TAG, useUnmergedTree = true)
            .performClick()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CWD_TAG, useUnmergedTree = true)
            .performTextClearance()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CWD_TAG, useUnmergedTree = true)
            .performTextInput(folder)
        compose.waitForIdle()
        // Give the trigger-fired picker load a beat to land the known names (a
        // single live `list-sessions`); filling the sheet above consumed most.
        SystemClock.sleep(1_500)
        captureFullDevice("01-${entry.slug}-rich-sheet-shell-same-folder")

        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()

        // 5) Poll the host: the fix must yield a genuinely NEW, `-2`-suffixed
        // session alongside the original — NOT a silent `-A` no-op collision.
        var matching: List<String> = emptyList()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            matching = listSessionsMatching(baseName)
            if (matching.contains(baseName) && matching.contains(secondName)) break
            SystemClock.sleep(500)
        }
        appendSummary(entry, folder, baseName, secondName, matching)
        captureFullDevice("02-${entry.slug}-after-create")

        Log.i(LOG_TAG, "[${entry.slug}] sessions after in-session create: $matching")
        assertTrue(
            "[${entry.slug}] the original folder session must still exist; got $matching",
            matching.contains(baseName),
        )
        // THE LOAD-BEARING ASSERTION (red on base, green with the fix): a second
        // same-folder "New session" via THIS entry point must create a genuinely
        // new `-2`-suffixed session, not silently collide/no-op onto the existing.
        assertTrue(
            "[${entry.slug}] a second same-folder 'New session' must create the " +
                "'-2' suffixed session (no `-A` collision no-op); got $matching",
            matching.contains(secondName),
        )
        assertEquals(
            "[${entry.slug}] exactly the original + the '-2' session must exist; got $matching",
            listOf(baseName, secondName),
            matching.sorted(),
        )
    }

    // ---------------------------------------------------------------- Helpers

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }

    private suspend fun sshExec(script: String): String =
        withSshSession { it.exec(script).stdout }

    /**
     * Issue #1820: one REAL SSH session against the Docker fixture, so the
     * gateway's create path runs its actual remote commands against an actual
     * tmux. A fake [com.pocketshell.core.ssh.SshSession] cannot execute the
     * host-side free-name walk, which is exactly the part that has to be right.
     */
    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T =
        withTimeout(60_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(readFixtureKey()),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow().use { block(it) }
        }

    /**
     * How many tmux clients are attached to [sessionName], straight from the
     * host (`#{session_attached}`). Issue #1820 uses this as the "the app really
     * is attached" precondition; `0` for a live session means the app's `-CC`
     * attach did not land, whatever the UI is showing.
     */
    private suspend fun attachedClientCount(sessionName: String): Int =
        sshExec(
            "tmux list-sessions -F '#{session_name} #{session_attached}' 2>/dev/null || true",
        )
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore(' ') == sessionName }
            ?.substringAfter(' ')
            ?.trim()
            ?.toIntOrNull()
            ?: 0

    /**
     * The live tmux session names that belong to [baseName]'s folder family
     * (`<base>` and its `-2`/`-3` suffixes), so a stray sibling session from
     * another run never pollutes the assertion.
     */
    private suspend fun listSessionsMatching(baseName: String): List<String> =
        sshExec("tmux list-sessions -F '#{session_name}' 2>/dev/null || true")
            .lineSequence()
            .map { it.trim() }
            .filter { it == baseName || it.startsWith("$baseName-") }
            .toList()

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue898-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
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
     * DIAGNOSTIC screenshot — **not** evidence of this test's outcome.
     *
     * `uiAutomation.takeScreenshot()` here routinely returns a frame that is
     * byte-identical to the previous capture (both reviewers on #1820 measured
     * matching md5s between the `01-*` and `02-*` pairs, both still showing the
     * sheet open with the keyboard up). The create's effect is host-side and
     * lands in tmux, not necessarily in the next composed frame, so a full-device
     * capture cannot show it.
     *
     * The AUTHORITATIVE artifact for every assertion in this class is the
     * host-side `tmux list-sessions` output recorded in `summary.txt` by
     * [appendSummary] — it is read straight off the fixture over SSH and is what
     * the assertions below compare against. Read that, not these PNGs.
     */
    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name-viewport.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "could not write screenshot ${file.absolutePath}"
                }
            }
            println("ISSUE898_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun appendSummary(
        entry: EntryPoint,
        folder: String,
        baseName: String,
        secondName: String,
        matching: List<String>,
    ) {
        val file = artifactFile("summary.txt")
        file.appendText(
            buildString {
                appendLine("=== entry_point=${entry.slug} ===")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("folder=$folder")
                appendLine("seeded_session=$baseName")
                appendLine("expected_new_session=$secondName")
                appendLine("sessions_after_create=${matching.sorted()}")
                appendLine("collision_avoided=${matching.contains(secondName)}")
                appendLine()
            },
        )
        println("ISSUE898_SUMMARY ${file.absolutePath}")
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue898InSession"
        const val DEVICE_DIR_NAME: String = "issue898-in-session-new-session-collision"
    }
}
