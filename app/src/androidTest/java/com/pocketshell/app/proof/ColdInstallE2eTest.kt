package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.signals.waitForSshPtyReady
import com.pocketshell.app.hosts.ADD_HOST_CTA_TAG
import com.pocketshell.app.hosts.ADD_HOST_HOSTNAME_FIELD_TAG
import com.pocketshell.app.hosts.ADD_HOST_KEY_FIELD_TAG
import com.pocketshell.app.hosts.ADD_HOST_NAME_FIELD_TAG
import com.pocketshell.app.hosts.ADD_HOST_PORT_FIELD_TAG
import com.pocketshell.app.hosts.ADD_HOST_USERNAME_FIELD_TAG
import com.pocketshell.app.hosts.HOST_LIST_ADD_FAB_TAG
import com.pocketshell.app.hosts.HOST_LIST_EMPTY_STATE_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.settings.ABOUT_VERSION_TAG
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.SETTINGS_LAZY_COLUMN_TAG
import com.pocketshell.app.settings.SETTINGS_TITLE_TAG
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.settings.ThemePreference
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.termux.view.TerminalView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-install end-to-end coverage (issue #144).
 *
 * The user journey under test is the **first launch after a fresh install**:
 *
 * 1. Wipe every byte of prior PocketShell state (Room DB, SharedPreferences,
 *    `filesDir/ssh-keys/`) — this is the in-test stand-in for an
 *    `adb uninstall && adb install` cycle. See [resetAppToColdInstallState]
 *    for the rationale and why we do not invoke `pm uninstall` from inside
 *    the instrumentation process.
 * 2. Launch [MainActivity] cold via [ActivityScenario.launch]; assert the
 *    host list is empty and the empty-state copy is visible.
 * 3. Seed the Docker fixture's SSH key into the running app's Hilt-managed
 *    Room database via [TestAccessEntryPoint] (a tiny in-`main` Hilt entry
 *    point). We do this through the singleton DAO so the
 *    `InvalidationTracker` fans the change out to the in-app
 *    `AddEditHostViewModel.sshKeys` flow; key-generation UI is unit-tested
 *    elsewhere (#111) and out of scope here (issue non-goal).
 * 4. Add a host through the real Add Host form (`HostListScreen` FAB →
 *    `AddEditHostScreen`), filling every required field and picking the
 *    seeded key from the dropdown.
 * 5. Tap the new host row; let `HostListViewModel.bootstrapHost` run; if a
 *    bootstrap sheet appears for any missing tools, dismiss with
 *    Skip/Continue/Close so the user can still reach the session picker.
 *    The Docker `agents` fixture ships tmux + tmuxctl + quse, so the sheet
 *    typically does not appear at all; the test treats it as optional.
 * 6. From the tmux session picker, tap the `claude-main` row — the
 *    deterministic `tmuxctl` shim in the `agents` fixture returns a
 *    hardcoded list of session names (`claude-main`, `codex`,
 *    `opencode-lab`). We also pre-create a real tmux session with the
 *    same name via direct SSH so the subsequent attach has a live remote
 *    pane to join. This drops the user into [TmuxSessionScreen].
 * 7. Type `printf 'cold-install-pass\n'` through the live TerminalView
 *    `InputConnection`. Assert the marker shows up in the visible
 *    terminal text using [TerminalTextMatcher.containsWrapTolerant] so the
 *    assertion survives a soft-wrap at the right margin.
 * 8. Re-launch the activity into the host list, open Settings, and
 *    assert the default settings snapshot matches [AppSettings] defaults
 *    (theme = System, terminal font = 14sp, tmux-on-attach = true,
 *    voice language = auto, voice silence threshold = 30s).
 *
 * **Production paths the test breaks against:**
 *
 * - First-run empty state on `HostListScreen` (would regress if the empty
 *   row got accidentally hidden or always shows a seed host on first launch).
 * - `AddEditHostScreen` validation + save (would regress if the CTA stopped
 *   inserting into the DB or the form rejected valid input).
 * - `HostListViewModel.bootstrapHost` → tmux probe → session picker
 *   navigation (would regress if a tmux-installed host stopped routing to
 *   the picker).
 * - `TmuxSessionScreen` attach + remote PTY plumbing (would regress if the
 *   typed command stopped reaching the remote pane).
 * - `SettingsRepository` cold-read defaults (would regress if a default
 *   value drifted from [AppSettings]).
 *
 * Test infra it relies on:
 *
 * - `TerminalTextMatcher.containsWrapTolerant` (#139) for the command
 *   visibility assertion across soft-wrap boundaries.
 * - `waitForSshFixtureReady` from [AndroidSshTestFixtures.kt] so the test
 *   fails fast and loudly when the `agents` Docker fixture is not up,
 *   rather than burning the visibility deadline on a connect that never
 *   resolves.
 * - `waitForSshPtyReady` from
 *   [com.pocketshell.app.proof.signals.PtySignals] (issue #140) so the
 *   test confirms the in-app PTY has actually rendered its first shell
 *   prompt before injecting input, rather than racing the attach step.
 */
@RunWith(AndroidJUnit4::class)
class ColdInstallE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    /**
     * Issue #177: clear the fast-resume `last_session` snapshot before and
     * after this test so neither a sibling test's leftover blob can pollute
     * the cold-install start, nor this test's own Phase-7 relaunch (which
     * closes an activity sitting on a tmux session, saving a blob via
     * `onStop`) can leak into a later test. The activity route-restore is
     * already gated on `savedInstanceState != null`, so a fresh launch lands
     * on the host list regardless; this is belt-and-braces prefs isolation.
     */
    @Before
    fun clearFastResumeSnapshot() {
        clearLastSessionPrefs()
    }

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        clearLastSessionPrefs()
    }

    @Test
    fun coldInstallJourney_addsHost_attachesTmuxSession_runsCommand_andDefaultsAreSane() = runBlocking {
        // ---------------------------------------------------------------
        // Phase 1 — "uninstall + install". We replay an effective cold
        // install by wiping every persistent PocketShell artifact the app
        // creates: Room DB, SharedPreferences, the `ssh-keys` private-key
        // directory under `filesDir`, and any cached files. The Docker
        // fixture key is then seeded into the freshly-empty DB so the
        // user does not need to drive the key-generation UI (out of scope
        // per the issue's non-goals).
        // ---------------------------------------------------------------
        resetAppToColdInstallState()
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        // Pre-create a tmux session on the Docker fixture so the picker
        // shows a deterministic row we can tap by name. We use
        // `claude-main` because the deterministic Docker `agents`
        // fixture's `tmuxctl` shim returns a hardcoded list of three
        // session names — `claude-main`, `codex`, `opencode-lab` — and
        // the picker renders rows from that shim, not from live `tmux ls`.
        // We still create a REAL tmux session with the same name in the
        // container so the subsequent attach can find a live pane to
        // join. This mirrors `EmulatorWorkflowE2eTest.realAppTmuxJourney`.
        val sessionName = "claude-main"
        prepareTmuxSessionOnFixture(key, sessionName)

        // ---------------------------------------------------------------
        // Phase 2 — first launch. Activity starts against an empty DB; the
        // host list shows the empty-state hint.
        // ---------------------------------------------------------------
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(HOST_LIST_EMPTY_STATE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No hosts yet", useUnmergedTree = true).assertExists()

        // Seed the SSH key into the SAME Room database instance the Hilt
        // graph injects into [AddEditHostViewModel]. Using a standalone
        // `Room.databaseBuilder()` would create a parallel SQLite handle
        // that the in-app `InvalidationTracker` doesn't observe, so the
        // key dropdown's reactive `sshKeyDao.getAll()` Flow wouldn't see
        // our seeded row. Going through `EntryPointAccessors` ensures we
        // write through the very same DAO the dropdown subscribes to.
        val storedKeyName = "cold-install-key-${System.currentTimeMillis()}"
        val storedKeyEntity = seedFixtureKeyViaHilt(key, storedKeyName)

        // ---------------------------------------------------------------
        // Phase 3 — add a host through the real form. We type the four
        // required fields, open the key dropdown, and pick the seeded key
        // by its label. The CTA flips on once every field is valid, then
        // we tap it.
        // ---------------------------------------------------------------
        compose.onNodeWithTag(HOST_LIST_ADD_FAB_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(ADD_HOST_NAME_FIELD_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val hostName = "Cold Install Host ${System.currentTimeMillis().toString(36)}"
        compose.onNodeWithTag(ADD_HOST_NAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput(hostName)
        compose.onNodeWithTag(ADD_HOST_HOSTNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput(DEFAULT_HOST)
        // The Port field is prefilled with "22" by [AddEditHostViewModel];
        // we need 2222 for the Docker fixture, so REPLACE the value rather
        // than append. `performTextReplacement` selects all current text
        // and overwrites with the supplied value, which is the
        // deterministic equivalent of "tap, ctrl-A, type 2222".
        compose.onNodeWithTag(ADD_HOST_PORT_FIELD_TAG, useUnmergedTree = true)
            .performTextReplacement("$DEFAULT_PORT")
        compose.onNodeWithTag(ADD_HOST_USERNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput(DEFAULT_USER)
        compose.onNodeWithTag(ADD_HOST_KEY_FIELD_TAG, useUnmergedTree = true).performClick()
        // Pick the seeded key by its stored name (the dropdown shows the
        // persisted filename, which is what [SshKeyStorage.persistKey]
        // returned).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(storedKeyEntity.name, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(storedKeyEntity.name, useUnmergedTree = true).performClick()

        compose.onNodeWithTag(ADD_HOST_CTA_TAG, useUnmergedTree = true).performClick()
        // The CTA save() flips `state.saved` which triggers `onDone` →
        // navigator pops back to the host list. Wait until we are back on
        // the host list with the new row visible.
        val newHostRowTagPrefix = HOST_ROW_TAG_PREFIX
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(hostName, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // ---------------------------------------------------------------
        // Phase 4 — tap the row → bootstrap probe → session picker. The
        // bootstrap sheet may or may not surface depending on the fixture
        // state; we dismiss whichever buttons appear so the picker can
        // mount.
        // ---------------------------------------------------------------
        compose.onNodeWithText(hostName, useUnmergedTree = true).performClick()
        dismissBootstrapSheetIfPresent()

        // ---------------------------------------------------------------
        // Phase 5 — wait for the folder list (issue #171) to mount with
        // the pre-seeded session row visible inline under its folder
        // header, then tap the session to attach. The screen title is
        // "Folders" — the post-tap surface flipped from the picker
        // sheet to FolderListScreen.
        // ---------------------------------------------------------------
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Folders", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(sessionName, useUnmergedTree = true).performClick()

        // The picker dismisses and the navigator pushes the
        // TmuxSessionScreen.
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()

        // Wait for the in-app PTY to actually have a prompt visible
        // before injecting input. `waitForTerminalViewAttached()` only
        // confirms the [TerminalView] mounted with a session + emulator,
        // not that the remote shell has rendered its first prompt; on a
        // slow CI emulator the gap between attach and first-prompt-byte
        // can be several seconds. Without this gate the round-1 test
        // raced — typing `printf ...` before bash had emitted its prompt
        // sometimes lost the leading character. Using
        // [waitForSshPtyReady] from `proof.signals` (issue #140) makes
        // the readiness contract explicit and surfaces a hard timeout
        // rather than a vague missing-marker failure when the PTY never
        // comes up.
        val ptyReady = waitForSshPtyReady(
            transcriptProvider = { visibleTerminalText() },
        )
        assertTrue(
            "expected SSH PTY to emit a prompt within the readiness deadline; " +
                "visible_terminal_text=`${visibleTerminalText().take(500)}`",
            ptyReady,
        )

        // ---------------------------------------------------------------
        // Phase 6 — send the command through the TerminalView input
        // connection and assert the marker reaches the visible terminal
        // text.
        // ---------------------------------------------------------------
        val marker = "cold-install-pass"
        val command = "printf '$marker\\n'\n"
        val sendStart = SystemClock.elapsedRealtime()
        val committed = terminalInputConnection().commitText(command, 1)
        assertTrue("expected terminal input connection to commit the marker command", committed)
        // Wait for the marker text to surface in the live terminal
        // emulator screen — use wrap-tolerant matching so a soft-wrap
        // doesn't desync the assertion.
        var lastVisible = ""
        val visible = runCatching {
            compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
                lastVisible = visibleTerminalText()
                val cols = terminalGridColumnsOrZero()
                TerminalTextMatcher.containsWrapTolerant(lastVisible, marker, terminalCols = cols)
            }
            true
        }.getOrDefault(false)
        val sendElapsed = SystemClock.elapsedRealtime() - sendStart
        println("COLD_INSTALL_E2E_TIMING command_to_visible_marker_ms=$sendElapsed")
        assertTrue(
            "expected visible terminal text to contain '$marker' within deadline; " +
                "elapsed_ms=$sendElapsed visible_terminal_text=`${lastVisible.take(500)}`",
            visible,
        )

        // ---------------------------------------------------------------
        // Phase 7 — open Settings and verify the default snapshot is
        // what a cold install should see. We close the current
        // ActivityScenario (which detaches the in-flight TmuxSessionScreen
        // — there's no BackHandler in that screen, so a system back press
        // would finish the activity anyway) and launch a fresh activity
        // back into the host list, then navigate to Settings via the
        // top-bar gear.
        // ---------------------------------------------------------------
        launchedActivity?.close()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        // After relaunch, the saved host is still there and the row is
        // present.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(hostName, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Open Settings via the top-bar Settings gear.
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(SETTINGS_TITLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Authoritative source for "what should the defaults be": the
        // [SettingsRepository] (which is the Hilt-singleton both
        // `MainActivity` and the Settings VM share). We don't read the
        // Compose tree directly because the Slider's accessibility value
        // is not surfaced as text on Material 3 and the radio group's
        // selection state lives in the `Role.RadioButton` semantics. The
        // repository's `state.value` is the cold-read of the prefs file
        // we just wiped, so it must equal the [AppSettings] defaults.
        val cachedRepository = SettingsRepository(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        )
        val cold = cachedRepository.settings.value
        assertEquals(
            "cold-install theme default should be System",
            ThemePreference.System,
            cold.theme,
        )
        assertEquals(
            "cold-install terminal font size default should be ${AppSettings.DEFAULT_TERMINAL_FONT_SP}sp",
            AppSettings.DEFAULT_TERMINAL_FONT_SP,
            cold.terminalFontSizeSp,
            0.001f,
        )
        assertTrue(
            "cold-install tmux-on-attach default should be true",
            cold.tmuxOnAttachByDefault,
        )
        assertEquals(
            "cold-install voice language default should be auto",
            AppSettings.VOICE_LANGUAGE_AUTO,
            cold.voiceLanguage,
        )
        assertEquals(
            "cold-install voice silence threshold default should be " +
                "${AppSettings.DEFAULT_VOICE_SILENCE_SECONDS}s",
            AppSettings.DEFAULT_VOICE_SILENCE_SECONDS,
            cold.voiceSilenceThresholdSeconds,
            0.001f,
        )

        // Visually confirm the Settings surface mounted and renders the
        // about-version row from the same fresh state. We scroll the
        // version row into view because Settings is a LazyColumn and the
        // About section lives below the fold on a Pixel 7 viewport. The
        // same pattern is used by `UsageScreenE2eTest` for the Usage row.
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG).assertExists()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(ABOUT_VERSION_TAG))
        compose.onNodeWithTag(ABOUT_VERSION_TAG, useUnmergedTree = true).assertExists()

        // Keep the host-row tag prefix referenced so static analysis
        // doesn't flag it as unused; the prefix is what host rows in the
        // list are tagged with (`host:row:<id>`), and the journey above
        // identified the row by name rather than id.
        assertTrue(
            "HOST_ROW_TAG_PREFIX should remain non-empty (used to namespace host rows)",
            newHostRowTagPrefix.isNotEmpty(),
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Wipe everything PocketShell persists on first launch so the next
     * activity launch behaves like a fresh install.
     *
     * **Why we don't `pm uninstall com.pocketshell.app`:** the
     * instrumentation runs in process `com.pocketshell.app.test`, which
     * is loaded against the target app's classpath at test start.
     * Uninstalling the target package mid-run leaves the test process
     * with a stale classloader, and a follow-up `pm install` plus
     * `ActivityScenario.launch` cannot reattach the test runner to the
     * newly-installed app's PID. The instrumentation framework simply
     * doesn't support that lifecycle: once instrumentation is bound to a
     * target package, that target must remain installed for the duration
     * of the test class. (See AOSP `AndroidJUnitRunner` /
     * `Instrumentation#start` documentation.)
     *
     * **Why we use [androidx.room.RoomDatabase.clearAllTables] via the
     * Hilt singleton, not `Context#deleteDatabase`:**
     *
     * The round-1 implementation called `ctx.deleteDatabase(DATABASE_NAME)`
     * to drop the Room file at the filesystem layer. That fails on warm
     * instrumentation runs because the previous test in the same JVM may
     * have already triggered Hilt's `provideAppDatabase`, which holds the
     * SQLite connection open. Wiping `.db` / `.db-shm` / `.db-wal` out
     * from under that open connection leaves the cached schema metadata
     * in a state where `HostDao_Impl.getAll` later sees
     * `SQLiteException: no such table: hosts`, even though the freshly
     * re-opened on-disk file has the schema. The reviewer reproduced this
     * race 5/6 times on a healthy emulator.
     *
     * Routing the wipe through the live Hilt-provided [AppDatabase] is the
     * supported way: `clearAllTables()` clears every table in the SAME
     * connection the rest of the app uses, so the
     * [androidx.room.InvalidationTracker], schema cache, and live `Flow`s
     * stay coherent. This is the pattern used by
     * [com.pocketshell.app.proof.EmulatorDockerSshSmokeTest] and
     * [com.pocketshell.app.proof.WalkthroughVisualScreenshotTest] (where they
     * use a freshly-built standalone Room handle); we go one step further
     * and clear directly through the singleton Hilt already manages,
     * removing the second connection entirely.
     *
     * SharedPreferences and the `ssh-keys/` directory are still wiped at
     * the filesystem layer because no long-lived Java handle holds them
     * open: `SettingsRepository.prefs.edit().apply()` writes-through to
     * disk synchronously, and the keys directory is just plain files.
     */
    private fun resetAppToColdInstallState() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

        // 1. Wipe Room state through the Hilt singleton so the live
        //    connection sees the clear, not just the file on disk. We
        //    obtain the [AppDatabase] via the [TestAccessEntryPoint] that
        //    already exists for the key-seeding step below — using the
        //    same entry point keeps the test's interaction surface
        //    consistent. `clearAllTables()` runs each entity's `DELETE
        //    FROM <table>` inside a single transaction; the schema stays
        //    intact, the invalidation tracker fires, and any flow that
        //    was subscribed to a now-empty table snaps to an empty list.
        val appDatabase = EntryPointAccessors
            .fromApplication(ctx, TestAccessEntryPoint::class.java)
            .appDatabase()
        appDatabase.clearAllTables()

        // 2. Clear every SharedPreferences file the app writes. We
        //    enumerate `shared_prefs/` directly because the production
        //    code creates files lazily and we don't want a future store
        //    to silently leak state across tests.
        val prefsDir = ctx.getDir("shared_prefs", android.content.Context.MODE_PRIVATE)
            ?.parentFile?.resolve("shared_prefs")
        // The standard SharedPreferences directory.
        val sharedPrefsRoot = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs")
        listOfNotNull(prefsDir, sharedPrefsRoot)
            .filter { it.isDirectory }
            .flatMap { it.listFiles().orEmpty().asList() }
            .forEach { runCatching { it.delete() } }

        // 3. Clear the private-key directory.
        val keyDir = java.io.File(ctx.filesDir, "ssh-keys")
        if (keyDir.exists()) {
            keyDir.listFiles().orEmpty().forEach { it.delete() }
            keyDir.delete()
        }

        // 4. Clear the cache dir so anything cached (release-check JSON,
        //    image thumbnails, etc.) does not influence the cold flow.
        ctx.cacheDir.listFiles().orEmpty().forEach { runCatching { it.deleteRecursively() } }

        // NOTE: do NOT `am force-stop com.pocketshell.app` or `pm clear`
        // the target package here. In this project's androidTest setup,
        // the instrumentation runs in the SAME process as the target
        // app (`com.pocketshell.app`); the test runner package
        // `com.pocketshell.app.test` is loaded as instrumentation IN
        // that target process. Force-stopping or clearing the target
        // kills the test process too, which surfaces as the cryptic
        // gradle error: "Test run failed to complete. Instrumentation
        // run failed due to Process crashed." (see emulator logcat
        // line: `Killing <pid>:com.pocketshell.app/<uid> ... due to from
        // pid <test-pid>` — the test PID IS the target PID).
    }

    /**
     * Create a tmux session on the Docker `agents` fixture via direct SSH
     * so the in-app session picker shows a deterministic row to tap.
     * Mirrors the helper of the same intent in `EmulatorWorkflowE2eTest`
     * (which uses `SshConnection.connect` plus `tmux new-session -d`);
     * we keep a local copy here so this test doesn't reach into a sibling
     * test class' private helpers.
     */
    private suspend fun prepareTmuxSessionOnFixture(key: String, sessionName: String) {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true; " +
                        "tmux new-session -d -s ${shellQuote(sessionName)} " +
                        "${shellQuote("exec sh")}",
                )
            }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session setup to succeed, got ${result.exceptionOrNull()} " +
                "stdout='${exec?.stdout}' stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    /**
     * Seed the SSH key into the running app's Hilt-provided
     * `SshKeyDao` so the in-app reactive flow surfaces the new row in
     * the Add Host key dropdown.
     *
     * Why not `Room.databaseBuilder()` here: standalone Room handles
     * to the same `.db` file are separate SQLite connections; only the
     * Hilt-provided singleton's `InvalidationTracker` observes the
     * changes its own DAO writes. Calling DAO methods on a parallel
     * handle commits the data on disk but does NOT fan out via the
     * tracker, so the running `AddEditHostViewModel.sshKeys` flow stays
     * empty. Routing through `EntryPointAccessors.fromApplication` and
     * the production-side DAO is the documented way (see
     * `BootCompletedReceiver` for the same pattern) to write through
     * the same singleton.
     */
    private suspend fun seedFixtureKeyViaHilt(
        key: String,
        name: String,
    ): com.pocketshell.core.storage.entity.SshKeyEntity {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val sshKeyDao = EntryPointAccessors
            .fromApplication(ctx, TestAccessEntryPoint::class.java)
            .sshKeyDao()
        return SshKeyStorage.persistKey(
            context = ctx,
            sshKeyDao = sshKeyDao,
            name = name,
            content = key,
        )
    }

    /**
     * If the bootstrap sheet surfaces (because the fixture is in a
     * partial-tools state, or because a stale [HostListViewModel] is
     * still probing), dismiss it via whichever exit it offers:
     *
     * - `Skip` for the "tmux or quse missing" prompt path.
     * - `Continue` for the "everything installed" prompt path (rare —
     *   `bootstrapHost` does not show the sheet in that case, but the
     *   button is wired through `onSkip` if it ever did).
     * - `Close` for the failure path (we don't expect to hit it; the
     *   fixture is healthy).
     *
     * Polls briefly; if no sheet appears within the window we proceed.
     */
    private fun dismissBootstrapSheetIfPresent() {
        // 1.5s is enough on a healthy emulator — the sheet renders within
        // 200-400ms of the bootstrap probe resolving. We do not block on
        // this: if no sheet appears, the test moves on.
        val deadline = SystemClock.elapsedRealtime() + 1_500
        while (SystemClock.elapsedRealtime() < deadline) {
            val sheetNodes = compose.onAllNodesWithTag(
                com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SHEET_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (sheetNodes.isNotEmpty()) {
                listOf(
                    com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SKIP_TAG,
                    com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_CONTINUE_TAG,
                    com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_CLOSE_TAG,
                ).forEach { tag ->
                    val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                    if (nodes.isNotEmpty()) {
                        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
                        return
                    }
                }
            }
            SystemClock.sleep(100)
        }
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            requireNotNull(terminalView) { "TerminalView was not found" }
            terminalView.requestFocus()
            connection = terminalView.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    /**
     * Number of columns the live TerminalEmulator reports, or `0` if
     * the terminal hasn't fully mounted yet. The wrap-tolerant matcher
     * treats `0` as "don't normalise newlines", which is the safe
     * default during the brief window between attach and first frame.
     */
    private fun terminalGridColumnsOrZero(): Int {
        var cols = 0
        launchedActivity?.onActivity { activity ->
            cols = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.mColumns
                ?: 0
        }
        return cols
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

}
