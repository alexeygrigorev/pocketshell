package com.pocketshell.app.proof

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_LOADING_TAG
import com.pocketshell.app.projects.STALE_SESSION_DIALOG_TAG
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.app.tmux.StaleSession
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #666 — a tmux session the user killed elsewhere must NOT be
 * resurrected on app resume.
 *
 * The maintainer's exact dogfood journey (reported twice): you are in a
 * session, you leave the app (to screenshot/annotate), you kill that session
 * on the computer, you come back — and the app reopens the killed session AND,
 * because it no longer exists, RECREATES it server-side (`tmux new-session -A`
 * is attach-OR-create). Expected: a gone session must not be recreated; drop
 * to the host/session list.
 *
 * This test reproduces it on the deterministic Docker `agents` fixture:
 *
 *  1. Attach to a real seeded tmux session through the normal journey
 *     (host -> session picker -> Attach).
 *  2. `moveToState(CREATED)` so `MainActivity.onStop` persists the last
 *     session into [com.pocketshell.app.session.LastSessionStore] (#177).
 *  3. Kill that tmux session over a sidecar SSH connection — it is now gone
 *     on the server.
 *  4. `recreate()` the activity, which drives `onSaveInstanceState` +
 *     `onCreate(savedInstanceState != null)` — the process-death-resume path
 *     that reads the persisted snapshot and cold-restores into it
 *     (`TmuxConnectTrigger.ColdRestore`).
 *
 * Acceptance:
 *  - The killed session is NOT recreated on the server: a `tmux has-session`
 *    probe taken AFTER the restore still fails (the bug recreated it here).
 *  - The app drops to the host list (a host row is visible) instead of
 *    showing a resurrected, empty session screen.
 *
 * Artifacts (process.md "Terminal Artifact Review"): a timings file plus a
 * has-session probe log so a reviewer can confirm from the SAME run that the
 * session stayed gone and the restore landed on the list.
 */
@RunWith(AndroidJUnit4::class)
class ColdRestoreGoneSessionNoResurrectE2eTest {

    // Issue #788: createAndroidComposeRule<MainActivity>() so the Compose test
    // clock drives the SAME foreground activity the Termux TerminalView interop
    // child is placed into — fixing the #470 swiftshader interop-placement /
    // enumeration stall. The rule launches MainActivity in its `before()`, so the
    // remote tmux session + DB host row are seeded BEFORE launch by the chain.
    // The rule-owned scenario also drives `recreate()` (the process-death restore
    // path) in the body — the rule tracks the recreated activity.
    val compose = createAndroidComposeRule<MainActivity>()

    // Issue #470 blocker #1 (grant) + #788 seed-before-launch ordering:
    //   grant perms -> clear prefs + seed remote session + DB host row -> launch.
    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private val timings = mutableListOf<String>()

    @After
    fun tearDown() {
        // Issue #788: restore RESUMED before the rule's auto-close so close()
        // does not crash if the body left the recreated scenario non-RESUMED.
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        runBlocking {
            if (::fixtureKey.isInitialized) {
                runCatching { killRemoteSession(fixtureKey) }
            }
        }
        clearLastSessionPrefs()
    }

    /**
     * Issue #788: clear last-session prefs + seed the remote tmux session + DB
     * host row BEFORE MainActivity launches (run by [SeedBeforeLaunchRule]). The
     * pref clear must precede launch so MainActivity reads a clean baseline (the
     * test then persists the last session itself via the lifecycle path). Both
     * test methods seed identically.
     */
    private suspend fun seedBeforeLaunch() {
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        clearLastSessionPrefs()
        // Real tmux session, named to match a picker entry so the normal attach
        // journey can reach it. The `tmux` shim delegates to the real binary, so
        // has-session / kill-session are authoritative.
        seedTmuxSession(key)
        assertTrue("seeded session must be alive before the journey", sessionAlive(key))
        hostRowTag = seedDockerHost(key)
    }

    @Test
    fun coldRestoreToKilledSessionDoesNotRecreateAndLandsOnList() { runBlocking {
        val key = fixtureKey

        // ---- (1) Attach to the seeded session via the normal journey.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

        // ---- (2) Background -> onStop persists the last session (#177).
        val stopAt = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)
        recordTiming("stop_drain_ms", SystemClock.elapsedRealtime() - stopAt)

        // ---- (3) Kill the session on the server. It is now GONE.
        val killAt = SystemClock.elapsedRealtime()
        killRemoteSession(key)
        recordTiming("kill_session_ms", SystemClock.elapsedRealtime() - killAt)
        assertTrue(
            "the session must be gone on the server after kill-session",
            !sessionAlive(key),
        )
        recordTiming("session_alive_after_kill", if (sessionAlive(key)) 1L else 0L)

        // ---- (4) Resume via recreate -> savedInstanceState != null -> the
        // process-death cold-restore path reads the persisted snapshot and
        // attaches ColdRestore.
        val resumeAt = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        recordTiming("recreate_ms", SystemClock.elapsedRealtime() - resumeAt)

        // ---- (5) Give the cold-restore attach-only preflight time to run,
        // find the session gone, and route to the list.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            onHostList(hostRowTag)
        }

        // ---- Acceptance A: the killed session was NOT recreated server-side.
        // The bug's second symptom is `new-session -A` recreating it. A
        // has-session probe taken now must still fail.
        val stillGone = !sessionAlive(key)
        recordTiming("session_recreated_after_restore", if (stillGone) 0L else 1L)
        writeText(
            "has-session-probe.txt",
            buildString {
                appendLine("session=$SEEDED_SESSION")
                appendLine("alive_after_restore=${!stillGone}")
                appendLine("expected_alive_after_restore=false")
            },
        )
        assertTrue(
            "REGRESSION: the killed session `$SEEDED_SESSION` was RECREATED on resume " +
                "(tmux has-session succeeded) — cold-restore must not resurrect it",
            stillGone,
        )

        // ---- Acceptance B: the app dropped to the host list, not a
        // resurrected empty session screen.
        assertTrue(
            "expected to land on the host list after a gone-session restore; " +
                "a host row should be visible",
            onHostList(hostRowTag),
        )
        val sessionScreenStillUp = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        assertTrue(
            "the resurrected session screen must NOT be showing after a gone-session restore",
            !sessionScreenStillUp,
        )

        recordTiming("restore_to_list_ms", SystemClock.elapsedRealtime() - resumeAt)
        writeTimings()
        Unit
    } }

    /**
     * Issue #834 — DELETING an agent session must NOT auto-open the (deleted)
     * session's Conversation/Terminal view on the next resume.
     *
     * The maintainer's dogfood report: "when we deleted an agent session it
     * still automatically opens the conversation view." Root cause: the killed
     * session is the persisted "last active" record in
     * [com.pocketshell.app.session.LastSessionStore]; nothing invalidated it on
     * a kill, so the process-death restore re-opened it → #818 lands on its
     * Conversation tab (showing a deleted session is the #686 hazard).
     *
     * This drives the EXACT delete journey on the deterministic Docker `agents`
     * fixture, through the production singletons:
     *
     *  1. Attach to a real seeded session via the normal journey.
     *  2. Background → `MainActivity.onStop` persists it (#177).
     *  3. Broadcast a confirmed kill over the SAME singleton
     *     [com.pocketshell.app.tmux.SessionLifecycleSignals] that BOTH delete
     *     entry points use (`FolderListViewModel.killSession` /
     *     `TmuxSessionViewModel.killCurrentSession`). `MainActivity`'s observer
     *     hands it to the store, which clears the matching restore record and
     *     tombstones the identity. (We also kill it server-side so the journey
     *     is faithful.)
     *  4. `recreate()` → the process-death cold-restore path.
     *
     * Acceptance: the deleted session's restore record is GONE, the app lands
     * on the host list, and NO session screen (Conversation/Terminal) is shown
     * for the deleted session.
     */
    @Test
    fun deletingActiveSessionDoesNotAutoOpenItOnResume() { runBlocking {
        val key = fixtureKey

        // ---- (1) Attach to the seeded session via the normal journey.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

        // ---- (2) Background -> onStop persists the last session (#177).
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)

        // ---- (3) DELETE the session: kill it server-side AND broadcast the
        // confirmed-kill lifecycle signal on the production singleton, exactly
        // as both Stop entry points do. MainActivity's #834 observer must
        // invalidate the restore record. Resolve the host id we just seeded so
        // the (hostId, sessionName) identity matches what was persisted.
        killRemoteSession(key)
        assertTrue("session must be gone server-side after delete", !sessionAlive(key))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val entryPoint = EntryPointAccessors
            .fromApplication(ctx, TestAccessEntryPoint::class.java)
        val killedHostId = hostRowTag.removePrefix(HOST_ROW_TAG_PREFIX).toLong()
        // The activity is in CREATED (still STARTED-collected? no — CREATED is
        // below STARTED). Bring it to STARTED so the repeatOnLifecycle observer
        // is collecting, emit the kill, then let it drain.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        delay(LIFECYCLE_DRAIN_MS)
        entryPoint.sessionLifecycleSignals().emitKilled(killedHostId, SEEDED_SESSION)
        delay(LIFECYCLE_DRAIN_MS)

        // The store must no longer hold the deleted session as a restore target.
        val storedAfterKill = entryPoint.lastSessionStore().read(maxAgeMillis = Long.MAX_VALUE)
        val restoreClearedForKilled =
            storedAfterKill == null ||
                !(storedAfterKill.hostId == killedHostId &&
                    storedAfterKill.sessionName == SEEDED_SESSION)
        writeText(
            "issue834-restore-record.txt",
            buildString {
                appendLine("killed_host_id=$killedHostId")
                appendLine("killed_session=$SEEDED_SESSION")
                appendLine("stored_host_id=${storedAfterKill?.hostId}")
                appendLine("stored_session=${storedAfterKill?.sessionName}")
                appendLine("restore_cleared_for_killed=$restoreClearedForKilled")
                appendLine("expected_restore_cleared=true")
            },
        )
        assertTrue(
            "REGRESSION (#834): the deleted session is STILL the last-session " +
                "restore target — it will auto-open (→ #818 Conversation) on resume",
            restoreClearedForKilled,
        )

        // ---- (4) Resume via recreate -> process-death cold-restore path.
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // ---- (5) The app must land on the host list, NOT a deleted-session
        // screen. Give the route a moment to settle.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            onHostList(hostRowTag)
        }

        assertTrue(
            "after deleting the session the app must land on the host list, " +
                "not auto-open the deleted session",
            onHostList(hostRowTag),
        )
        val sessionScreenStillUp = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        assertTrue(
            "the deleted session's screen (Conversation/Terminal) must NOT be " +
                "showing on resume after a delete",
            !sessionScreenStillUp,
        )
        Unit
    } }

    /**
     * Issue #1155 (Part B) on-device emitter proof. When the persisted last
     * session is confirmed GENUINELY GONE on the real cold-restore attach path
     * (the exact journey the #666 test drives: attach → background → kill
     * externally → recreate/cold-restore), the app must broadcast a
     * [StaleSession] on the production singleton [SessionLifecycleSignals] so the
     * folder tree can raise the "create a new session in this folder?" recovery
     * prompt instead of leaving the user on a blank list. This is the
     * genuinely-gone branch only — a transient reconnect never reaches this path
     * (covered red/green in the JVM `TmuxSessionWarmOpenTest`). Same Docker
     * `agents` fixture + same lifecycle path as the sibling tests, so the signal
     * is proven on the real gone-session journey, not a proxy.
     */
    @Test
    fun coldRestoreToGoneSessionBroadcastsStaleSignalForRecreatePrompt() { runBlocking {
        val key = fixtureKey

        // Subscribe to the production stale-session signal BEFORE the restore so
        // the no-replay broadcast at attach-fail time is observed.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val entryPoint = EntryPointAccessors
            .fromApplication(ctx, TestAccessEntryPoint::class.java)
        val staleEvents = java.util.Collections.synchronizedList(mutableListOf<StaleSession>())
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        collectorScope.launch {
            entryPoint.sessionLifecycleSignals().staleSessions.collect { staleEvents.add(it) }
        }
        delay(LIFECYCLE_DRAIN_MS)

        // ---- Attach to the seeded session via the normal journey.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

        // ---- Background -> persist last session, then kill it on the server.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)
        killRemoteSession(key)
        assertTrue("session must be gone on the server after kill", !sessionAlive(key))

        // ---- Cold-restore into the gone session.
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // The genuinely-gone attach must broadcast a StaleSession naming the gone
        // session, so the folder tree can offer the recreate prompt.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            staleEvents.any { it.sessionName == SEEDED_SESSION }
        }
        val fired = synchronized(staleEvents) { staleEvents.toList() }
        writeText(
            "stale-session-signal.txt",
            buildString {
                appendLine("expected_stale_session=$SEEDED_SESSION")
                appendLine("stale_events=${fired.map { it.sessionName }}")
                appendLine("stale_folders=${fired.map { it.folderPath }}")
            },
        )
        collectorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertTrue(
            "a genuinely-gone cold-restore must broadcast a StaleSession for the " +
                "folder tree's recreate prompt; saw ${fired.map { it.sessionName }}",
            fired.any { it.sessionName == SEEDED_SESSION },
        )
        Unit
    } }

    /**
     * Issue #1155 (Part B) blocker 3 — the maintainer's PRIMARY gesture. A NORMAL
     * TAP of a persisted session row whose tmux session was killed externally must
     * reach the "This session no longer exists — create a new session in this
     * folder?" recreate DIALOG, NOT silently recreate a fresh shell via
     * `new-session -A` (the reported "it was there but as a shell, not the agent").
     *
     * Journey on the deterministic Docker `agents` fixture:
     *  1. host row -> folder list (the seeded session row is shown).
     *  2. Kill that tmux session server-side (external removal — the RARE case the
     *     persistent tree can't know about).
     *  3. TAP the still-shown (advisory-cached) session row -> the OpenExisting
     *     connect preflights `tmux has-session`, sees it gone, drops back to the
     *     folder tree AND broadcasts the stale-session signal.
     *  4. The folder tree (bound on the backstack) raises the recreate dialog.
     *
     * Acceptance: the `STALE_SESSION_DIALOG_TAG` recreate dialog is shown after the
     * tap, and the session was NOT resurrected server-side.
     */
    @Test
    fun openExistingTapOfGoneSessionShowsRecreateDialog() { runBlocking {
        val key = fixtureKey

        // ---- (1) host -> folder list; the seeded session row is present.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)

        // ---- (2) Kill the session server-side (external removal).
        killRemoteSession(key)
        assertTrue("session must be gone server-side after kill", !sessionAlive(key))

        // ---- (3) TAP the still-shown persisted row -> OpenExisting -> preflight
        // confirms gone -> drop back + stale-session broadcast.
        compose.onNodeWithText(SEEDED_SESSION).performClick()

        // ---- (4) The folder tree raises the recreate dialog.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        val dialogShown = compose
            .onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        val resurrected = sessionAlive(key)
        writeText(
            "open-existing-tap-recreate-dialog.txt",
            buildString {
                appendLine("tapped_session=$SEEDED_SESSION")
                appendLine("recreate_dialog_shown=$dialogShown")
                appendLine("session_resurrected_server_side=$resurrected")
                appendLine("expected_recreate_dialog_shown=true")
                appendLine("expected_session_resurrected=false")
            },
        )
        assertTrue(
            "REGRESSION (#1155 blocker 3): tapping a gone persisted session must " +
                "show the recreate dialog, not silently recreate a shell",
            dialogShown,
        )
        assertTrue(
            "tapping a gone session must NOT resurrect it server-side (no silent " +
                "`new-session -A`)",
            !resurrected,
        )
        Unit
    } }

    /**
     * Issue #1155 (Part A) blocker 4 — the cold deep-link-back INSTANT render. The
     * maintainer's recurring #867/#1109 symptom is the "Loading workspace tree"
     * flash when returning to the folder tree. With the process-start warm
     * ([com.pocketshell.app.App.onCreate] → `TreeClientCache.warmAll`), a cold
     * process that deep-links back into the tree must paint the persisted tree
     * (the seeded session row) with NO Loading panel.
     *
     * Journey:
     *  1. host -> folder list (the tree reconciles + persists to the client cache).
     *  2. Deep-link into the session, then `recreate()` the activity (COLD process:
     *     App.onCreate re-warms the client cache from disk).
     *  3. Back out to the folder tree.
     *
     * Acceptance: the persisted session row is shown and the `FOLDER_LIST_LOADING_TAG`
     * Loading panel is NOT present when it appears (no rebuild flash). The reviewer
     * additionally confirms the no-flash visually on the emulator.
     */
    @Test
    fun coldDeepLinkBackToFolderTreeRendersPersistedTreeNoLoadingFlash() { runBlocking {
        // ---- (1) host -> folder list; let the tree settle + persist to the cache.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        delay(LIFECYCLE_DRAIN_MS)

        // ---- (2) Deep-link into the session, then COLD-recreate the process.
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // ---- (3) Back out to the folder tree (the cold-restored session screen or
        // wherever the restore landed) and assert the persisted tree paints.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithText(SEEDED_SESSION, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        // When the persisted row is present, the full-screen Loading panel must NOT
        // be — i.e. the tree rendered from the warmed cache, not a rebuild flash.
        val loadingShown = compose
            .onAllNodesWithTag(FOLDER_LIST_LOADING_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        val rowShown = compose
            .onAllNodesWithText(SEEDED_SESSION, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        writeText(
            "cold-deep-link-back-instant-render.txt",
            buildString {
                appendLine("persisted_row_shown=$rowShown")
                appendLine("loading_panel_shown_with_row=$loadingShown")
                appendLine("expected_persisted_row_shown=true")
                appendLine("expected_loading_panel_shown_with_row=false")
            },
        )
        assertTrue(
            "the cold deep-link-back must paint the persisted session row from the " +
                "warmed client cache",
            rowShown,
        )
        assertTrue(
            "REGRESSION (#1155 Part A): the folder tree showed the full-screen " +
                "Loading panel alongside the persisted row (rebuild flash)",
            !loadingShown,
        )
        Unit
    } }

    // ---------------------------------------------------------------- Helpers

    private fun onHostList(hostRowTag: String): Boolean =
        compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

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
                name = "issue666-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue666 GoneRestore",
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

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SEEDED_SESSION)} " +
                    "${shellQuote("printf 'ISSUE666-READY\\n'; exec sleep 600")}",
            )
            appendLine("sleep 1")
            appendLine("tmux has-session -t ${shellQuote(SEEDED_SESSION)}")
        }
        val exec = runScript(key, script)
        assertTrue(
            "expected tmux seeding to succeed; stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun killRemoteSession(key: String) {
        runScript(
            key,
            "tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true",
        )
    }

    /** True iff the seeded tmux session currently exists on the server. */
    private suspend fun sessionAlive(key: String): Boolean {
        val exec = runScript(
            key,
            "tmux has-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null && echo ALIVE || echo GONE",
        )
        return exec?.stdout?.contains("ALIVE") == true
    }

    private suspend fun runScript(key: String, script: String) =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }.getOrNull()

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            // Issue #788: tolerate the transient "No compose hierarchies found"
            // ISE on the first frames (and during the recreate transition) under
            // createAndroidComposeRule.
            runCatching {
                compose.onAllNodesWithText(text, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    /**
     * Issue #788: cold-compose-aware host-row presence poll under
     * createAndroidComposeRule (MainActivity cold compose can take ~28s on a
     * contended swiftshader emulator). Early-exits the instant the row appears.
     */
    private fun waitForHostRowPresent(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE666_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE666_TIMINGS ${file.absolutePath}")
        return file
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

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE666_TIMING $line")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue666GoneRestore"
        const val DEVICE_DIR_NAME: String = "issue666-cold-restore-gone-session"

        // A picker entry name shipped by the deterministic `agents` fixture so
        // the normal attach journey reaches it; `tmux` itself is the real
        // binary, so has-session/kill-session are authoritative.
        const val SEEDED_SESSION: String = "claude-main"

        const val LIFECYCLE_DRAIN_MS: Long = 750L
        const val RESTORE_TIMEOUT_MS: Long = 20_000L
    }
}
