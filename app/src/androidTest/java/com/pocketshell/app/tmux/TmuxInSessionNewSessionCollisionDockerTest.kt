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
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
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
 * SAME folder as an existing session derives the IDENTICAL base name. Because
 * the gateway creates with `tmux new-session -A` (attach-if-exists), an
 * identical name does NOT make a genuinely new session — it silently no-ops onto
 * the existing one. The user taps Create expecting a second session and gets
 * nothing new.
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
 * The defect lives in the screen's REAL create lambda (`derivedSessionName(…,
 * existingNames = <known names from sessionPickerState>)` →
 * `viewModel.createSession` →
 * [com.pocketshell.app.projects.FolderListGateway.createSession]) and in the
 * REAL entry-point wiring that must populate `sessionPickerState` first. So this
 * test drives the PRODUCTION [MainActivity] → host card → attach → real trigger
 * → real rich sheet → real Create, over Docker. A stubbed `onCreate` would
 * bypass precisely the lines where the bug is.
 *
 * ## Red→green
 *
 * Per entry point: seed an existing tmux session named `tmp-<folder>-<suffix>`
 * whose cwd is an outside-home folder (so the derived base is unambiguous
 * regardless of `$HOME`), open that trigger's "+ New session", pick Shell, set
 * the Start folder to the SAME folder, and Create.
 *  - PRE-FIX (the trigger doesn't load the names): the deriver returns the base,
 *    the `-A` create no-ops, and the host still has exactly ONE matching session
 *    → the load-bearing assertion FAILS (red).
 *  - POST-FIX: the deriver sees the known name and returns `<base>-2`, a
 *    genuinely new session → the host has BOTH `<base>` AND `<base>-2` → PASS.
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
                    val kill = createdSessions.joinToString("; ") {
                        "tmux kill-session -t '$it' 2>/dev/null || true"
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
    fun inSessionNewSessionInSameFolderGetsSuffixedSessionNotCollisionAllEntryPoints() = runBlocking {
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
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(readFixtureKey()),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow().use { it.exec(script).stdout }
        }

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
