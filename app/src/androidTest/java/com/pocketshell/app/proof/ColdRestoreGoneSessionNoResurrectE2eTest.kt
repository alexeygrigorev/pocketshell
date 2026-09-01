package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_LOADING_TAG
import com.pocketshell.app.projects.FOLDER_LIST_TITLE_TAG
import com.pocketshell.app.projects.STALE_SESSION_CONFIRM_TAG
import com.pocketshell.app.projects.STALE_SESSION_DIALOG_TAG
import com.pocketshell.app.projects.STALE_SESSION_GO_HOME_TAG
import com.pocketshell.app.session.LastSessionStore
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.app.tmux.StaleSession
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.SSH_HANDSHAKE_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_RECONNECT_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_LIVE_SEMANTICS_KEY
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SURFACE_PANE_PRESENT_SEMANTICS_KEY
import com.pocketshell.app.tmux.TMUX_TERMINAL_HELD_SEMANTICS_KEY
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.tmux.TmuxSessionGeneration
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
import com.termux.view.TerminalView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

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
 * session stayed gone and the restore landed on the list. The
 * `issue834-last-session-identity.txt` artifact also records the persisted
 * pair and the [LastSessionStore.LastSession.generation] contract: both
 * `tmuxSessionId` and `sessionCreated` are required.
 */
@RunWith(AndroidJUnit4::class)
class ColdRestoreGoneSessionNoResurrectE2eTest {
    private lateinit var trustedHostKeySha256: String

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
        // The Compose rule closes its scenario after @After. Close it here as
        // well so the next SeedBeforeLaunchRule cannot inherit this test's
        // activity-scoped TmuxSessionViewModel/lease owner. This is fixture
        // hygiene, not a production unregister/force-release shortcut: the
        // next launch asserts that the real owner registry is empty.
        runCatching { compose.activityRule.scenario.close() }
        runBlocking {
            if (::fixtureKey.isInitialized) {
                waitForNoRegisteredFixtureOwner("after ActivityScenario.close()")
                runCatching { killRemoteSession(fixtureKey) }
                // Issue #2237: the dismiss-lands-on-the-session-tree journey seeds a
                // SECOND live session so the tree it lands on has something to pick.
                runCatching { killRemoteSessionNamed(fixtureKey, SIBLING_SESSION) }
            }
        }
        clearLastSessionPrefs()
        clearProcessScopedStalePrompt()
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
        trustedHostKeySha256 = waitForSshFixtureReady(SshKey.Pem(key))
        // The prompt is process-scoped by design. Reset it before each seeded
        // launch so a failed preceding journey cannot cover the next test's
        // cold-restore/create path with an old host/session prompt.
        clearProcessScopedStalePrompt()
        clearLastSessionPrefs()
        // A previous test must have released its real activity/lease owner. Do
        // not force-unregister it here: a surviving owner is precisely the
        // stale-claude-main fixture contamination that makes a fresh handshake
        // look connected while the screen is actually Disconnected.
        waitForNoRegisteredFixtureOwner("before fixture seed")
        // Real tmux session, named to match a picker entry so the normal attach
        // journey can reach it. The `tmux` shim delegates to the real binary, so
        // has-session / kill-session are authoritative.
        seedTmuxSession(key)
        assertTrue("seeded session must be alive before the journey", sessionAlive(key))
        hostRowTag = seedDockerHost(key)
    }

    private fun clearProcessScopedStalePrompt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EntryPointAccessors
            .fromApplication(context, TestAccessEntryPoint::class.java)
            .staleSessionPromptController()
            .clear()
    }

    /**
     * The app-wide registry is the authoritative fixture-visible owner of a
     * live tmux client. A stale entry for the Docker endpoint can make the next
     * `claude-main` attach reuse/poison the old lease, so fixture setup must
     * observe its real removal rather than masking it with `forceUnregister`.
     */
    private fun waitForNoRegisteredFixtureOwner(stage: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activeClients = EntryPointAccessors
            .fromApplication(context, TestAccessEntryPoint::class.java)
            .activeTmuxClients()
        val deadline = SystemClock.elapsedRealtime() + OWNER_RELEASE_TIMEOUT_MS
        var owners = fixtureOwners(activeClients)
        while (owners.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(OWNER_RELEASE_SAMPLE_INTERVAL_MS)
            owners = fixtureOwners(activeClients)
        }
        assertTrue(
            "$stage left a live Docker tmux owner; close the activity/lease before " +
                "seeding claude-main: $owners",
            owners.isEmpty(),
        )
    }

    private fun fixtureOwners(
        activeClients: com.pocketshell.app.sessions.ActiveTmuxClients,
    ): List<com.pocketshell.app.sessions.ActiveTmuxClients.Entry> =
        activeClients.clients.value.values.filter {
            it.hostname == DEFAULT_HOST &&
                it.port == DEFAULT_PORT &&
                it.username == DEFAULT_USER
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
        waitForConnected("cold-restore dialog initial attach")

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
        // The screen tag only proves that the composable exists. The production
        // Connected state is the readiness gate for the lifecycle save below:
        // removing this wait must redden this exact journey instead of making
        // the fixed lifecycle drain stand in for an attached session.
        waitForConnected("issue834 active attach")

        // ---- (2) Background -> onStop persists the last session (#177).
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        // ---- (3) DELETE the session: kill it server-side AND broadcast the
        // confirmed-kill lifecycle signal on the production singleton, exactly
        // as both Stop entry points do. MainActivity's #834 observer must
        // invalidate the restore record. Resolve the host id we just seeded so
        // the (hostId, sessionName) identity matches what was persisted.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val entryPoint = EntryPointAccessors
            .fromApplication(ctx, TestAccessEntryPoint::class.java)
        val killedHostId = hostRowTag.removePrefix(HOST_ROW_TAG_PREFIX).toLong()
        // Do not use LIFECYCLE_DRAIN_MS as a persistence oracle. Poll the same
        // production singleton MainActivity reads until onStop has published
        // the fresh host/session record. Deliberately do NOT require either
        // generation field here: an incomplete production record must reach
        // the focused LastSession.generation assertion below, where the hard
        // null guards kill a missing tmux id or creation timestamp selectively.
        // The JVM LastSessionStoreTest and its mutation runner pin the same
        // three shapes without letting readiness hide a field mutant.
        val storedBeforeKill = waitForPersistedLastSession(
            entryPoint = entryPoint,
            hostId = killedHostId,
            sessionName = SEEDED_SESSION,
        )
        val persistedGeneration = storedBeforeKill.generation
        writeText(
            "issue834-last-session-identity.txt",
            buildString {
                appendLine("contract=LastSessionStore.LastSession.generation")
                appendLine("contract_requires_tmux_session_id=true")
                appendLine("contract_requires_session_created=true")
                appendLine("persisted_host_id=${storedBeforeKill.hostId}")
                appendLine("persisted_session=${storedBeforeKill.sessionName}")
                appendLine("persisted_tmux_session_id=${storedBeforeKill.tmuxSessionId}")
                appendLine("persisted_session_created=${storedBeforeKill.sessionCreated}")
                appendLine("persisted_generation=$persistedGeneration")
                appendLine("complete_generation=${persistedGeneration != null}")
            },
        )
        val killedGeneration = checkNotNull(persistedGeneration) {
            "persisted killed session must expose a complete " +
                "LastSessionStore.LastSession.generation from BOTH " +
                "tmuxSessionId and sessionCreated; observed " +
                "tmuxSessionId=${storedBeforeKill.tmuxSessionId} " +
                "sessionCreated=${storedBeforeKill.sessionCreated}"
        }
        killRemoteSession(key)
        assertTrue("session must be gone server-side after delete", !sessionAlive(key))
        // The activity is in CREATED (still STARTED-collected? no — CREATED is
        // below STARTED). Bring it to STARTED so the repeatOnLifecycle observer
        // is collecting, emit the kill, then let it drain.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        delay(LIFECYCLE_DRAIN_MS)
        entryPoint.sessionLifecycleSignals().emitKilled(
            hostId = killedHostId,
            generation = killedGeneration,
            lastKnownName = SEEDED_SESSION,
        )
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
                appendLine("killed_generation=$killedGeneration")
                appendLine("identity_contract=LastSessionStore.LastSession.generation " +
                    "requires tmuxSessionId + sessionCreated")
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
        waitForConnected("stale-dialog copy initial attach")

        // ---- Background -> persist last session, then kill it on the server.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)
        val storedBeforeKill = checkNotNull(
            entryPoint.lastSessionStore().read(maxAgeMillis = Long.MAX_VALUE),
        ) { "backgrounding the attached session must persist its exact restore identity" }
        val expectedGeneration = TmuxSessionGeneration(
            sessionId = checkNotNull(storedBeforeKill.tmuxSessionId) {
                "cold-restore stale proof needs the persisted tmux session id"
            },
            createdEpochSeconds = checkNotNull(storedBeforeKill.sessionCreated) {
                "cold-restore stale proof needs the persisted tmux creation timestamp"
            },
        )
        killRemoteSession(key)
        assertTrue("session must be gone on the server after kill", !sessionAlive(key))

        // ---- Cold-restore into the gone session.
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // The genuinely-gone attach must broadcast a StaleSession naming the gone
        // session, so the folder tree can offer the recreate prompt.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            staleEvents.any {
                it.sessionName == SEEDED_SESSION && it.generation == expectedGeneration
            }
        }
        val fired = synchronized(staleEvents) { staleEvents.toList() }
        writeText(
            "stale-session-signal.txt",
            buildString {
                appendLine("expected_stale_session=$SEEDED_SESSION")
                appendLine("expected_generation=$expectedGeneration")
                appendLine("stale_events=${fired.map { it.sessionName }}")
                appendLine("stale_generations=${fired.map { it.generation }}")
                appendLine("stale_folders=${fired.map { it.folderPath }}")
                appendLine("exact_generation_match=${fired.any { it.generation == expectedGeneration }}")
            },
        )
        collectorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertTrue(
            "a genuinely-gone cold-restore must broadcast a StaleSession for the " +
                "folder tree's recreate prompt with the persisted generation; saw " +
                "${fired.map { it.sessionName to it.generation }}",
            fired.any { it.sessionName == SEEDED_SESSION && it.generation == expectedGeneration },
        )
        Unit
    } }

    /**
     * Issue #1155 REOPEN (2026-07-03) — the maintainer's exact dogfood path. Open
     * a session, kill it on the computer, then 1–2 hours later just OPEN
     * PocketShell so it COLD-RESTORES straight onto that previous session. Part B
     * only wired the recovery dialog into the folder tree, which is never opened on
     * this path, so the prompt was silently lost and an empty session was created.
     *
     * This is the delivery reproduction: it drives the SAME attach → background →
     * kill-externally → recreate/cold-restore journey as
     * [coldRestoreToKilledSessionDoesNotRecreateAndLandsOnList], but asserts the
     * user actually SEES the app-level "This session no longer exists — create in
     * this folder, or go back to this host's sessions?" recovery DIALOG (with both
     * actions), and that the session was NOT resurrected server-side. On base
     * (tree-only owner) the dialog never appears on cold restore — this fails; with
     * the app-level [com.pocketshell.app.tmux.StaleSessionPromptController] it
     * appears. Where the dismiss action LANDS is #2237's
     * [dismissTapOnColdRestoreStaleDialogLandsOnHostSessionTree].
     */
    @Test
    fun coldRestoreToGoneSessionShowsBackToSessionsActionAndHostTree() { runBlocking {
        val key = fixtureKey

        // The destination assertion below must be load-bearing: the tree needs a
        // different live session to show after the gone-session dialog is dismissed.
        seedTmuxSessionNamed(key, SIBLING_SESSION, SIBLING_READY_MARKER)
        assertTrue(
            "the sibling session must be alive before the cold-restore dialog journey",
            sessionAliveNamed(key, SIBLING_SESSION),
        )

        // ---- Attach to the seeded session via the normal journey.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForConnected("stale-dialog copy initial attach")

        // ---- Background -> persist last session, then kill it on the server.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)
        killRemoteSession(key)
        assertTrue("session must be gone on the server after kill", !sessionAlive(key))

        // ---- Cold-restore into the gone session (savedInstanceState != null).
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // ---- The app-level recovery DIALOG must appear with the new action/copy.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty() &&
                    compose.onAllNodesWithTag(STALE_SESSION_GO_HOME_TAG, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
            }.getOrDefault(false)
        }
        val dialogShown = compose
            .onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        // Use merged semantics for the button's label: the test tag is on the
        // Button container while the visible label is its Text child.
        val dismissActionNode = compose.onNodeWithTag(STALE_SESSION_GO_HOME_TAG)
        val dismissActionSemantics = captureDismissActionEvidence(
            prefix = "issue2237-cold-restore-dialog-copy",
        )
        val backToSessionsShown = dismissActionSemantics.text == DISMISS_LABEL
        val globalTextLookupShown = compose
            .onAllNodesWithText(DISMISS_LABEL, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        val backToSessionsCopyShown = compose
            .onAllNodesWithText(
                DISMISS_MESSAGE_CLAUSE,
                substring = true,
                useUnmergedTree = true,
            )
            .fetchSemanticsNodes()
            .isNotEmpty()
        val resurrected = sessionAlive(key)

        // The test tag is attached to the production dismiss button itself. A
        // global text search can miss an AlertDialog's separate Compose root (the
        // fresh r2 run did exactly that), so the contract is asserted on the
        // tagged node's own merged semantics, including its click action.
        dismissActionNode
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertTextEquals(DISMISS_LABEL)
        assertTrue(
            "the stale-session dialog must explain that the action returns to this " +
                "host's sessions",
            backToSessionsCopyShown,
        )

        // The tap must exercise the non-null destination, not just the dialog copy.
        compose.onNodeWithTag(STALE_SESSION_GO_HOME_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                onFolderTree() && sessionRowShown(SIBLING_SESSION)
            }.getOrDefault(false)
        }
        val hostTreeShown = onFolderTree()
        val hostTitleShown = compose
            .onAllNodesWithText(HOST_NAME, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        val siblingShown = sessionRowShown(SIBLING_SESSION)
        val hostListShown = onHostList(hostRowTag)
        val dialogGoneAfterDismiss = !visibleTag(STALE_SESSION_DIALOG_TAG)
        writeText(
            "cold-restore-recreate-dialog.txt",
            buildString {
                appendLine("restored_session=$SEEDED_SESSION")
                appendLine("recreate_dialog_shown=$dialogShown")
                appendLine("dismiss_action_shown=$backToSessionsShown")
                appendLine("dismiss_action_label=$DISMISS_LABEL")
                appendLine("dismiss_action_semantics_text=${dismissActionSemantics.text}")
                appendLine("dismiss_action_semantics_content_description=${dismissActionSemantics.contentDescription}")
                appendLine("dismiss_action_semantics_has_click=${dismissActionSemantics.hasClickAction}")
                appendLine("global_text_lookup_shown=$globalTextLookupShown")
                appendLine("dismiss_message_clause_shown=$backToSessionsCopyShown")
                appendLine("session_resurrected_server_side=$resurrected")
                appendLine("host_tree_shown_after_dismiss=$hostTreeShown")
                appendLine("host_title_shown_after_dismiss=$hostTitleShown")
                appendLine("sibling_session_shown_after_dismiss=$siblingShown")
                appendLine("host_list_shown_after_dismiss=$hostListShown")
                appendLine("dialog_gone_after_dismiss=$dialogGoneAfterDismiss")
                appendLine("expected_recreate_dialog_shown=true")
                appendLine("expected_dismiss_action_shown=true")
                appendLine("expected_dismiss_action_label=$DISMISS_LABEL")
                appendLine("expected_dismiss_message_clause_shown=true")
                appendLine("expected_session_resurrected=false")
                appendLine("expected_host_tree_shown_after_dismiss=true")
                appendLine("expected_host_title_shown_after_dismiss=true")
                appendLine("expected_sibling_session_shown_after_dismiss=true")
                appendLine("expected_host_list_shown_after_dismiss=false")
                appendLine("expected_dialog_gone_after_dismiss=true")
            },
        )
        assertTrue(
            "REGRESSION (#1155 reopen): a cold restore onto a gone session must show " +
                "the recovery dialog, not silently create an empty session",
            dialogShown,
        )
        assertTrue(
            "the cold-restore recovery dialog must offer the leave-without-creating " +
                "action (#2237: '$DISMISS_LABEL' lands on this host's session tree)",
            backToSessionsShown,
        )
        assertTrue(
            "the cold-restore recovery dialog must use copy naming this host's " +
                "session tree, not the old home destination",
            backToSessionsCopyShown,
        )
        assertTrue(
            "a cold restore onto a gone session must NOT resurrect it server-side",
            !resurrected,
        )
        assertTrue(
            "the real cold-restore dismiss must land on the host's FolderList",
            hostTreeShown,
        )
        assertTrue(
            "the FolderList destination must retain this host identity",
            hostTitleShown,
        )
        assertTrue(
            "the host tree must expose the other live session immediately after dismiss",
            siblingShown,
        )
        assertTrue(
            "the new dismiss action must leave the host list",
            !hostListShown,
        )
        assertTrue(
            "the cold-restore dismiss action must close the recovery dialog",
            dialogGoneAfterDismiss,
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
     * Issue #2237 — the stale-session dialog's DISMISS action must keep the user on
     * the host whose session died: it lands on that host's SESSION TREE
     * ([AppDestination.FolderList]), never on the unrelated list of all hosts.
     *
     * This is the in-tree (OpenExisting tap) half of the class; the cold-restore
     * half is [dismissTapOnColdRestoreStaleDialogLandsOnHostSessionTree]. Before
     * #2237 the dismiss handler called `popToHostList()`, so on THIS path the tap
     * actively threw the user off the folder tree they were standing on and onto the
     * host list — that is the exact regression this asserts is gone (red on base:
     * `landed_on_host_list_after_dismiss=true`).
     *
     * Supersedes the #1155-blocker-1 `goHomeTapOnStaleDialogReturnsToHostList`,
     * whose expectation (host list) is the behaviour #2237 removes.
     */
    @Test
    fun dismissTapOnStaleDialogKeepsUserOnHostSessionTree() { runBlocking {
        val key = fixtureKey

        // ---- (1) host -> folder list; the seeded session row is present.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)

        // ---- (2) Kill the session server-side (external removal).
        killRemoteSession(key)
        assertTrue("session must be gone server-side after kill", !sessionAlive(key))

        // ---- (3) TAP the still-shown persisted row -> OpenExisting preflight
        // confirms gone -> back() to the folder tree + stale broadcast -> dialog.
        // Wait for the dialog AND for the drop back to the tree behind it to land:
        // the two are not ordered, and tapping into a dialog whose window is about
        // to be re-laid-out under a screen switch loses the tap.
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty() && onFolderTree()
            }.getOrDefault(false)
        }
        delay(LIFECYCLE_DRAIN_MS)

        // Sanity: the dialog sits over the FOLDER TREE, so the host row is NOT
        // visible yet. The dismiss must LEAVE it that way.
        val onHostListBeforeDismiss = onHostList(hostRowTag)

        // ---- (4) TAP the dismiss action.
        compose.onNodeWithTag(STALE_SESSION_GO_HOME_TAG, useUnmergedTree = true).performClick()

        // ---- The dialog goes away and the host's session tree stays up.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }.getOrDefault(false)
        }
        // Give any (unwanted) navigation to the host list time to land before the
        // assertion reads the tree — otherwise a slow pop would read as "stayed".
        delay(LIFECYCLE_DRAIN_MS)
        val landedOnHostList = onHostList(hostRowTag)
        val onSessionTree = onFolderTree()
        val dialogGone = compose
            .onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isEmpty()
        writeText(
            "issue2237-dismiss-in-tree-nav.txt",
            buildString {
                appendLine("path=open-existing tap (dialog raised over the folder tree)")
                appendLine("on_host_list_before_dismiss=$onHostListBeforeDismiss")
                appendLine("landed_on_host_list_after_dismiss=$landedOnHostList")
                appendLine("on_session_tree_after_dismiss=$onSessionTree")
                appendLine("dialog_dismissed=$dialogGone")
                appendLine("expected_on_host_list_before_dismiss=false")
                appendLine("expected_landed_on_host_list_after_dismiss=false")
                appendLine("expected_on_session_tree_after_dismiss=true")
                appendLine("expected_dialog_dismissed=true")
            },
        )
        captureFullDevice("issue2237-dismiss-in-tree")
        assertTrue(
            "precondition: the dialog must sit over the folder tree (NOT the host " +
                "list) so the dismiss navigation is actually exercised",
            !onHostListBeforeDismiss,
        )
        assertTrue("the stale-session dialog must be dismissed", dialogGone)
        assertTrue(
            "REGRESSION (#2237): dismissing the stale-session dialog threw the user " +
                "out to the HOST LIST; it must keep them on this host's session tree",
            !landedOnHostList,
        )
        assertTrue(
            "after dismissing, the host's session tree must be the visible screen",
            onSessionTree,
        )
        Unit
    } }

    /**
     * Issue #2237 — the maintainer's reported gesture, on the path where the
     * destination is actually load-bearing.
     *
     * On the COLD-RESTORE path the app process-restored straight onto the (now
     * dead) session with an EMPTY back stack, and the #666 automatic recovery
     * (`recoverToPreviousOrHostList`, which has nothing to pop) has ALREADY put the
     * user on the host list by the time the dialog is raised — measured, and
     * recorded as `on_host_list_before_dismiss=true`. That is what makes this the
     * load-bearing half: the dismiss handler has to BUILD this host's
     * [AppDestination.FolderList] out of the restored session's connection tuple and
     * navigate off the host list. On base it called `popToHostList()` and the user
     * stayed dumped on the list of all hosts (red); a dismiss that merely did
     * nothing would also leave them there (red). Only building + navigating to the
     * host's session tree passes.
     *
     * It also proves the POINT of landing there (acceptance criterion 2): a
     * DIFFERENT live session on the same host is immediately listed and tappable,
     * and opening it reaches the session screen off the already-warm lease — with no
     * "Loading workspace tree" rebuild flash in between.
     */
    @Test
    fun dismissTapOnColdRestoreStaleDialogLandsOnHostSessionTree() { runBlocking {
        val key = fixtureKey

        // A SECOND live session on the same host, so the tree the dismiss lands on
        // has a real alternative to pick (the whole reason for landing there).
        seedTmuxSessionNamed(key, SIBLING_SESSION, SIBLING_READY_MARKER)
        assertTrue(
            "the sibling session must be alive before the journey",
            sessionAliveNamed(key, SIBLING_SESSION),
        )
        // Add a distinct host row to make the routing oracle host-specific. The
        // second host is deliberately not connected; a successful dismiss must
        // still select the restored host's FolderList, not merely any tree.
        val otherHostRowTag = seedSecondaryDockerHost(key)

        // ---- (1) Attach to the seeded session via the normal journey.
        waitForHostRowsPresent(hostRowTag, otherHostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        val initialUiEvidence = waitForStableSessionUi(
            label = "cold-restore initial attach",
            expectedSessionName = SEEDED_SESSION,
            readyMarker = SEEDED_READY_MARKER,
        )
        val initialConnectionStatus = initialUiEvidence.statusName

        // ---- (2) Background -> onStop persists the last session (#177).
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)

        // ---- (3) Kill it on the server. It is now GONE.
        killRemoteSession(key)
        assertTrue("session must be gone on the server after kill", !sessionAlive(key))

        // ---- (4) Cold-restore into the gone session (savedInstanceState != null).
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // ---- (5) The app-level recovery dialog must appear, AND the #666 automatic
        // recovery behind it must have finished landing on the host list. Those two
        // are not ordered: the dialog is raised off the stale broadcast while the
        // navigator drop is still in flight, and clicking into a dialog whose window
        // is about to be re-laid-out under a screen switch loses the tap (observed
        // twice: `pre-dismiss ... hostRow=false` then a HostList route ~2s later,
        // with the prompt never cleared). Waiting for BOTH settles the frame and is
        // also the exact state the maintainer reports the dialog in.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty() &&
                    onHostList(hostRowTag) &&
                    onHostList(otherHostRowTag)
            }.getOrDefault(false)
        }
        // Preconditions that make the destination load-bearing: the session tree is
        // NOT up behind the dialog (so "we are already there" cannot pass the test),
        // and the #666 automatic recovery has already dropped the user onto the HOST
        // LIST (so the dismiss must navigate OFF it to satisfy #2237).
        val onSessionTreeBeforeDismiss = onFolderTree()
        val onHostListBeforeDismiss = onHostList(hostRowTag)
        val onOtherHostListBeforeDismiss = onHostList(otherHostRowTag)

        // ---- (6) TAP the dismiss action and observe the transition from its
        // FIRST frame. Do not sleep for a fixed settle delay before checking the
        // loading tag: that was the review hole, because a short reconnect flash
        // could end before the assertion ran.
        val connectAttemptsBeforeDismiss = TMUX_CONNECT_ATTEMPTS.get()
        val sshHandshakesBeforeDismiss = SSH_HANDSHAKE_ATTEMPTS.get()
        val dismissAt = SystemClock.elapsedRealtime()
        var transitionSettled = false
        var loadingSeenBeforeSettlement = false
        var loadingSeenAfterSettlement = false
        var transitionSamples = 0
        fun sampleDismissTransition() {
            transitionSamples += 1
            if (visibleTag(FOLDER_LIST_LOADING_TAG)) {
                if (transitionSettled) {
                    loadingSeenAfterSettlement = true
                } else {
                    loadingSeenBeforeSettlement = true
                }
            }
        }
        Log.i(
            LOG_TAG,
            "issue2237 pre-dismiss dialogs=${compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true).fetchSemanticsNodes().size}" +
                " goHomeNodes=${compose.onAllNodesWithTag(STALE_SESSION_GO_HOME_TAG, useUnmergedTree = true).fetchSemanticsNodes().size}" +
                " tree=${onFolderTree()} hostRow=${onHostList(hostRowTag)}",
        )
        compose.onNodeWithTag(STALE_SESSION_GO_HOME_TAG, useUnmergedTree = true).performClick()
        // The first sample is immediately after performClick; later samples are
        // bounded to 50ms so the proof covers the whole pre-settlement interval.
        sampleDismissTransition()
        Log.i(
            LOG_TAG,
            "issue2237 post-dismiss dialogs=${compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true).fetchSemanticsNodes().size}" +
                " tree=${onFolderTree()} hostRow=${onHostList(hostRowTag)}" +
                " sessionScreen=${compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).fetchSemanticsNodes().size}" +
                " sibling=${sessionRowShown(SIBLING_SESSION)}",
        )

        // ---- (7) The app must land on THIS HOST'S session tree, with the sibling
        // session listed. The condition is the settlement point: until both the
        // destination title and a real sibling row exist, any loading panel is a
        // pre-settlement flash and is load-bearing evidence.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            sampleDismissTransition()
            val settled = runCatching {
                onFolderTreeFor(HOST_NAME) && sessionRowShown(SIBLING_SESSION)
            }.getOrDefault(false)
            if (settled) {
                transitionSettled = true
                sampleDismissTransition()
                true
            } else {
                SystemClock.sleep(DISMISS_TRANSITION_SAMPLE_INTERVAL_MS)
                false
            }
        }
        val treeShownMs = SystemClock.elapsedRealtime() - dismissAt
        // Keep sampling briefly after settlement as well. This catches a late
        // loading/reconnect overlay and makes the artifact distinguish a clean
        // transition from a panel that appeared just after the row was painted.
        val postSettlementStartedAt = SystemClock.elapsedRealtime()
        val postSettlementDeadline =
            postSettlementStartedAt + DISMISS_POST_SETTLEMENT_OBSERVATION_MS
        while (SystemClock.elapsedRealtime() < postSettlementDeadline) {
            sampleDismissTransition()
            val remaining = postSettlementDeadline - SystemClock.elapsedRealtime()
            if (remaining > 0L) {
                SystemClock.sleep(minOf(DISMISS_TRANSITION_SAMPLE_INTERVAL_MS, remaining))
            }
        }
        val landedOnHostList = onHostList(hostRowTag)
        val onSessionTree = onFolderTreeFor(HOST_NAME)
        val wrongHostTreeShown = onFolderTreeFor(OTHER_HOST_NAME)
        val siblingRowShown = sessionRowShown(SIBLING_SESSION)
        val dialogGone = compose
            .onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isEmpty()
        val connectAttemptDelta = TMUX_CONNECT_ATTEMPTS.get() - connectAttemptsBeforeDismiss
        val sshHandshakeDelta = SSH_HANDSHAKE_ATTEMPTS.get() - sshHandshakesBeforeDismiss
        captureFullDevice("issue2237-dismiss-cold-restore-tree")

        // ---- (8) The listed sibling session must be immediately usable: tapping it
        // opens that session's screen off the same warm host connection.
        val siblingConnectAttemptsAtTap = TMUX_CONNECT_ATTEMPTS.get()
        val siblingSshHandshakesAtTap = SSH_HANDSHAKE_ATTEMPTS.get()
        val openAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText(SIBLING_SESSION).performClick()
        compose.waitUntil(timeoutMillis = CREATE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        val siblingOpenMs = SystemClock.elapsedRealtime() - openAt
        val siblingSessionOpened = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        val siblingUiEvidence = waitForStableSessionUi(
            label = "sibling session open",
            expectedSessionName = SIBLING_SESSION,
            readyMarker = SIBLING_READY_MARKER,
        )
        val siblingConnectionStatus = siblingUiEvidence.statusName
        // The first stable window proves the rendered screen is live. Capture
        // an authoritative screenshot, then require a SECOND stable window
        // after that capture. A stale owner that starts its retry ladder after
        // the first green VM sample must redden this post-capture oracle.
        captureFullDevice("issue2237-dismiss-cold-restore-sibling-open-pre-settlement")
        val siblingUiAfterScreenshotEvidence = waitForStableSessionUi(
            label = "sibling session open after authoritative screenshot",
            expectedSessionName = SIBLING_SESSION,
            readyMarker = SIBLING_READY_MARKER,
        )
        val siblingUiAfterCapture: SessionUiSnapshot
        captureFullDevice("issue2237-dismiss-cold-restore-sibling-open-connected")
        siblingUiAfterCapture = currentSessionUiSnapshot(SIBLING_SESSION)

        writeText(
            "issue2237-dismiss-cold-restore-nav.txt",
            buildString {
                appendLine("path=cold restore onto the gone session (empty back stack)")
                appendLine("gone_session=$SEEDED_SESSION")
                appendLine("sibling_session=$SIBLING_SESSION")
                appendLine("other_host_name=$OTHER_HOST_NAME")
                appendLine("other_host_row_tag=$otherHostRowTag")
                appendLine("initial_connection_status=$initialConnectionStatus")
                appendLine("sibling_connection_status=$siblingConnectionStatus")
                appendLine("initial_ui_stable_samples=${initialUiEvidence.stableSamples}")
                appendLine("initial_ui_stable_window_ms=${initialUiEvidence.stableWindowMs}")
                appendLine("initial_terminal_session_name=${initialUiEvidence.terminalSessionName}")
                appendLine("initial_terminal_output_seen=${initialUiEvidence.terminalOutputSeen}")
                appendLine("initial_session_label_count=${initialUiEvidence.sessionLabelCount}")
                appendLine("on_session_tree_before_dismiss=$onSessionTreeBeforeDismiss")
                appendLine("on_host_list_before_dismiss=$onHostListBeforeDismiss")
                appendLine("on_other_host_list_before_dismiss=$onOtherHostListBeforeDismiss")
                appendLine("landed_on_host_list_after_dismiss=$landedOnHostList")
                appendLine("on_session_tree_after_dismiss=$onSessionTree")
                appendLine("wrong_host_tree_shown_after_dismiss=$wrongHostTreeShown")
                appendLine("sibling_session_row_shown=$siblingRowShown")
                appendLine("loading_seen_before_settlement=$loadingSeenBeforeSettlement")
                appendLine("loading_seen_after_settlement=$loadingSeenAfterSettlement")
                appendLine("dismiss_transition_samples=$transitionSamples")
                appendLine("sibling_session_opened=$siblingSessionOpened")
                appendLine("dialog_dismissed=$dialogGone")
                appendLine("tree_shown_after_dismiss_ms=$treeShownMs")
                appendLine("post_settlement_observation_ms=$DISMISS_POST_SETTLEMENT_OBSERVATION_MS")
                appendLine("connect_attempt_delta_before_sibling_tap=$connectAttemptDelta")
                appendLine("ssh_handshake_delta_before_sibling_tap=$sshHandshakeDelta")
                appendLine("sibling_connect_attempts_at_tap=$siblingConnectAttemptsAtTap")
                appendLine("sibling_ssh_handshakes_at_tap=$siblingSshHandshakesAtTap")
                appendLine("sibling_connect_attempts_at_stable_start=${siblingUiEvidence.connectAttemptsAtStableStart}")
                appendLine("sibling_ssh_handshakes_at_stable_start=${siblingUiEvidence.sshHandshakesAtStableStart}")
                appendLine("sibling_connect_attempts_at_settlement=${siblingUiEvidence.connectAttemptsAtSettlement}")
                appendLine("sibling_ssh_handshakes_at_settlement=${siblingUiEvidence.sshHandshakesAtSettlement}")
                appendLine("expected_connect_sequence=one intentional sibling logical connect from tap to first ready; zero additional logical connects after first ready; zero SSH handshakes")
                appendLine("sibling_connect_attempt_delta_after_tap=${siblingUiEvidence.connectAttemptsAtSettlement - siblingConnectAttemptsAtTap}")
                appendLine("sibling_ssh_handshake_delta_after_tap=${siblingUiEvidence.sshHandshakesAtSettlement - siblingSshHandshakesAtTap}")
                appendLine("sibling_unexpected_connect_attempt_delta_after_ready=${siblingUiAfterScreenshotEvidence.connectAttemptsAtSettlement - siblingUiEvidence.connectAttemptsAtStableStart}")
                appendLine("sibling_ui_stable_samples=${siblingUiEvidence.stableSamples}")
                appendLine("sibling_ui_stable_window_ms=${siblingUiEvidence.stableWindowMs}")
                appendLine("sibling_post_screenshot_connect_attempts_at_settlement=${siblingUiAfterScreenshotEvidence.connectAttemptsAtSettlement}")
                appendLine("sibling_post_screenshot_ssh_handshakes_at_settlement=${siblingUiAfterScreenshotEvidence.sshHandshakesAtSettlement}")
                appendLine("sibling_post_screenshot_ui_stable_samples=${siblingUiAfterScreenshotEvidence.stableSamples}")
                appendLine("sibling_post_screenshot_ui_stable_window_ms=${siblingUiAfterScreenshotEvidence.stableWindowMs}")
                appendLine("sibling_terminal_session_name=${siblingUiEvidence.terminalSessionName}")
                appendLine("sibling_terminal_output_seen=${siblingUiEvidence.terminalOutputSeen}")
                appendLine("sibling_session_label_count_at_settlement=${siblingUiEvidence.sessionLabelCount}")
                appendLine("sibling_error_band_count_after_settlement=${siblingUiAfterCapture.errorBandCount}")
                appendLine("sibling_connection_pill_count_after_settlement=${siblingUiAfterCapture.connectionPillCount}")
                appendLine("sibling_reconnect_text_count_after_settlement=${siblingUiAfterCapture.reconnectTextCount}")
                appendLine("sibling_disconnected_text_count_after_settlement=${siblingUiAfterCapture.disconnectedTextCount}")
                appendLine("sibling_reconnecting_text_count_after_settlement=${siblingUiAfterCapture.reconnectingTextCount}")
                appendLine("sibling_screen_live_after_settlement=${siblingUiAfterCapture.sessionLive}")
                appendLine("sibling_terminal_held_after_settlement=${siblingUiAfterCapture.terminalHeld}")
                appendLine("sibling_surface_pane_present_after_settlement=${siblingUiAfterCapture.surfacePanePresent}")
                appendLine("sibling_session_label_count_after_settlement=${siblingUiAfterCapture.sessionLabelCount}")
                appendLine("sibling_connected_screenshot=issue2237-dismiss-cold-restore-sibling-open-connected-viewport.png")
                appendLine("sibling_ui_ready_after_screenshot=${siblingUiAfterCapture.isReadyFor(SIBLING_SESSION, SIBLING_READY_MARKER)}")
                appendLine("sibling_open_ms=$siblingOpenMs")
                appendLine("expected_on_session_tree_before_dismiss=false")
                appendLine("expected_on_host_list_before_dismiss=true")
                appendLine("expected_on_other_host_list_before_dismiss=true")
                appendLine("expected_landed_on_host_list_after_dismiss=false")
                appendLine("expected_on_session_tree_after_dismiss=true")
                appendLine("expected_folder_tree_title=$HOST_NAME")
                appendLine("expected_wrong_host_tree_shown_after_dismiss=false")
                appendLine("expected_initial_connection_status=Connected")
                appendLine("expected_sibling_connection_status=Connected")
                appendLine("expected_sibling_session_row_shown=true")
                appendLine("expected_loading_seen_before_settlement=false")
                appendLine("expected_loading_seen_after_settlement=false")
                appendLine("expected_connect_attempt_delta_before_sibling_tap=0")
                appendLine("expected_ssh_handshake_delta_before_sibling_tap=0")
                appendLine("expected_sibling_session_opened=true")
                appendLine("expected_dialog_dismissed=true")
                appendLine("expected_sibling_connect_attempt_delta_after_tap=1")
                appendLine("expected_sibling_ssh_handshake_delta_after_tap=0")
                appendLine("expected_sibling_unexpected_connect_attempt_delta_after_ready=0")
            },
        )

        assertTrue(
            "precondition: on the cold-restore path the session tree must NOT already " +
                "be up behind the dialog, or the destination is not exercised",
            !onSessionTreeBeforeDismiss,
        )
        assertTrue(
            "precondition: on cold restore the #666 automatic recovery drops to the " +
                "HOST LIST before the dialog is raised — that is the state whose " +
                "dismiss #2237 changes, so if it is not reached this test proves nothing",
            onHostListBeforeDismiss,
        )
        assertTrue(
            "precondition: the cold-restore host list must contain the distinct " +
                "second host, so the post-dismiss host identity is load-bearing",
            onOtherHostListBeforeDismiss,
        )
        assertTrue("the stale-session dialog must be dismissed", dialogGone)
        assertTrue(
            "REGRESSION (#2237): dismissing the cold-restore stale-session dialog " +
                "dropped the user on the HOST LIST; it must land on this host's " +
                "session tree",
            !landedOnHostList,
        )
        assertTrue(
            "dismissing must land on this host's session tree (FolderList)",
            onSessionTree,
        )
        assertTrue(
            "dismissing must select the stale prompt's host tree, not the other " +
                "configured host",
            !wrongHostTreeShown,
        )
        assertTrue(
            "the session tree must list the host's other live session `$SIBLING_SESSION` " +
                "so the user can pick it right away",
            siblingRowShown,
        )
        assertTrue(
            "REGRESSION (#2237): the host-tree loading panel appeared before the " +
                "FolderList settled; the warm lease must not flash a reconnect/dial " +
                "panel during the dismiss transition",
            !loadingSeenBeforeSettlement,
        )
        assertTrue(
            "REGRESSION (#2237): the host-tree loading panel appeared after the " +
                "FolderList settled; the warm lease must stay settled",
            !loadingSeenAfterSettlement,
        )
        assertEquals(
            "dismissing onto the warm host tree must not invoke another logical tmux connect",
            0,
            connectAttemptDelta,
        )
        assertEquals(
            "dismissing onto the warm host tree must not open another SSH handshake",
            0,
            sshHandshakeDelta,
        )
        assertTrue(
            "the listed sibling session must be tappable and open its session screen",
            siblingSessionOpened,
        )
        assertEquals(
            "the initial attach must establish a real connected fixture",
            "Connected",
            initialConnectionStatus,
        )
        assertEquals(
            "the sibling tap must open a connected session, not a Disconnected screen",
            "Connected",
            siblingConnectionStatus,
        )
        assertTrue(
            "the sibling UI must still be genuinely ready after the connected screenshot " +
                "was captured; a VM Connected value alone is not sufficient",
            siblingUiAfterCapture.isReadyFor(SIBLING_SESSION, SIBLING_READY_MARKER),
        )
        // TMUX_CONNECT_ATTEMPTS counts logical connect() calls, so opening the
        // selected sibling must advance it once even though the host SSH lease is
        // reused. The stale-owner regression is an EXTRA attempt after the sibling
        // is already ready; the next assertion is the selective oracle for it.
        assertEquals(
            "opening the sibling must issue exactly one intentional logical tmux connect; " +
                "same-host switches increment TMUX_CONNECT_ATTEMPTS while reusing SSH",
            siblingConnectAttemptsAtTap + 1,
            siblingUiEvidence.connectAttemptsAtStableStart,
        )
        assertEquals(
            "a stale old-session owner must not start another logical connect after the " +
                "sibling first becomes genuinely ready",
            siblingUiEvidence.connectAttemptsAtStableStart,
            siblingUiAfterScreenshotEvidence.connectAttemptsAtSettlement,
        )
        assertEquals(
            "a stale old-session owner must not start an SSH handshake after the sibling tap",
            siblingSshHandshakesAtTap,
            siblingUiAfterScreenshotEvidence.sshHandshakesAtSettlement,
        )
        assertTrue(
            "the sibling screenshot must correspond to the selected sibling terminal " +
                "and its visible fixture output",
            siblingUiAfterCapture.terminalOutputSeen,
        )
        Unit
    } }

    /**
     * Issue #1155 REOPEN (2026-07-03) blocker 2 — the "Create session" recovery
     * ACTION. The sibling `openExistingTapOfGoneSessionShowsRecreateDialog` only
     * asserts the dialog appears; nothing tapped "Create session" or proved a fresh
     * session is created in the gone session's folder. This drives the tap and
     * asserts:
     *
     *  - a fresh tmux session with the gone session's name is created server-side
     *    (it was killed; after the tap `tmux has-session` succeeds again), and
     *  - it is created in the STALE session's FOLDER — the recreated session's
     *    `pane_current_path` equals the exact `folderPath` the app broadcast on the
     *    production [SessionLifecycleSignals] stale signal (resolved to the host
     *    home dir when that folder is null/blank), proving the `-c <folder>` routing
     *    of `startDirectory = stalePrompt.folderPath`.
     *
     * It uses the OpenExisting tap path deliberately: after the gone preflight the
     * navigator `back()`s to the FOLDER TREE, so the recovery dialog sits over the
     * tree (not the session screen). Tapping "Create session" must then navigate to
     * a FRESH session and create it in the folder. If the recreate lambda is broken
     * (e.g. it drops to the list instead of navigating, or fails to pass the
     * folder), no session is created / it lands in the wrong dir → this test fails
     * (red→green on the action).
     */
    @Test
    fun createSessionTapOnStaleDialogRecreatesInStaleFolder() { runBlocking {
        val key = fixtureKey

        // Subscribe to the production stale-session signal to capture the EXACT
        // folder the app will recreate into (the `-c <folder>` under test).
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val entryPoint = EntryPointAccessors
            .fromApplication(ctx, TestAccessEntryPoint::class.java)
        val staleEvents = java.util.Collections.synchronizedList(mutableListOf<StaleSession>())
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        collectorScope.launch {
            entryPoint.sessionLifecycleSignals().staleSessions.collect { staleEvents.add(it) }
        }
        delay(LIFECYCLE_DRAIN_MS)

        // ---- (1) host -> folder list; the seeded session row is present.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)

        // ---- (2) Kill the session server-side (external removal).
        killRemoteSession(key)
        assertTrue("session must be gone server-side after kill", !sessionAlive(key))

        // ---- (3) TAP the still-shown persisted row -> OpenExisting preflight
        // confirms gone -> back() to the folder tree + stale broadcast -> dialog.
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        assertTrue("session must still be gone before Create is tapped", !sessionAlive(key))

        // The exact folder the production code will recreate into (== the
        // StaleSessionPromptController prompt's folderPath).
        val staleFolder = synchronized(staleEvents) {
            staleEvents.firstOrNull { it.sessionName == SEEDED_SESSION }?.folderPath
        }
        collectorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        // ---- TAP "Create session" -> recreate a fresh session in the SAME folder.
        compose.onNodeWithTag(STALE_SESSION_CONFIRM_TAG, useUnmergedTree = true).performClick()

        // The recreate lambda must NAVIGATE to a fresh session screen (base != null,
        // so it takes the navigate branch, not the go-home fallback). If the session
        // screen never appears the recreate lambda didn't fire at all.
        compose.waitUntil(timeoutMillis = CREATE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        val sessionScreenShown = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

        // ---- The recreate must create a fresh session server-side. Poll until the
        // new session exists (the UserTap connect runs `new-session -A -c <folder>`);
        // a fresh SSH connect + attach + create can take a while on a cold emulator.
        var recreated = false
        val deadline = SystemClock.elapsedRealtime() + CREATE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionAlive(key)) { recreated = true; break }
            delay(500)
        }

        // ---- Folder routing: the recreated session's working directory must equal
        // the stale folder the app broadcast (null/blank -> host home dir).
        val expectedPath = canonicalRemotePath(resolveExpectedFolder(key, staleFolder))
        val recreatedPath = if (recreated) canonicalRemotePath(sessionCurrentPath(key)) else null
        writeText(
            "create-session-tap-recreate.txt",
            buildString {
                appendLine("stale_session=$SEEDED_SESSION")
                appendLine("broadcast_folder_path=$staleFolder")
                appendLine("session_screen_shown_after_create_tap=$sessionScreenShown")
                appendLine("session_recreated_after_create_tap=$recreated")
                appendLine("expected_recreated_folder=$expectedPath")
                appendLine("actual_recreated_folder=$recreatedPath")
                appendLine("expected_session_screen_shown=true")
                appendLine("expected_session_recreated=true")
            },
        )
        assertTrue(
            "tapping 'Create session' must NAVIGATE to a fresh session screen " +
                "(the recreate branch fired, not the go-home fallback)",
            sessionScreenShown,
        )
        assertTrue(
            "REGRESSION (#1155 reopen): tapping 'Create session' must recreate the " +
                "gone session `$SEEDED_SESSION` server-side; has-session still fails",
            recreated,
        )
        assertEquals(
            "the recreated session must be created in the STALE session's folder " +
                "(-c <folder> routing of startDirectory = stalePrompt.folderPath)",
            expectedPath,
            recreatedPath,
        )
        Unit
    } }

    /** Issue #1832: the real MainActivity recovery dialog must retain and report a failed create. */
    @Test
    fun staleDialogCreateFailureRemainsVisibleAndDoesNotNavigate() { runBlocking {
        val key = fixtureKey
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)

        // Produce the genuine stale-session prompt through OpenExisting, then
        // remove the host row immediately before confirmation. The production
        // MainActivity's production stale-recreate handler therefore fails its
        // real host lookup without a synthetic UI seam or fake callback.
        killRemoteSession(key)
        assertTrue("session must be gone before opening the stale row", !sessionAlive(key))
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        val hostId = hostRowTag.removePrefix(HOST_ROW_TAG_PREFIX).toLong()
        deleteHostRow(hostId)
        shellOutput("logcat -c")

        val confirmAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(STALE_SESSION_CONFIRM_TAG, useUnmergedTree = true).performClick()
        val expectedError = "Couldn't create session: Host $hostId not found for stale-session recreate"
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onAllNodesWithText(
                    expectedError,
                    substring = true,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        val failureVisibleMs = SystemClock.elapsedRealtime() - confirmAt
        val dialogRetained = compose
            .onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        val sessionScreenShown = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        val logcat = shellOutput("logcat -d -v threadtime -t 2000 MainActivity:W *:S")
        captureFullDevice("issue1832-stale-recreate-failure")

        assertTrue("failed stale recreate must retain the production dialog", dialogRetained)
        assertTrue("failed stale recreate must not navigate to a session screen", !sessionScreenShown)
        assertTrue("failed stale recreate must leave the host session absent", !sessionAlive(key))
        assertTrue(
            "failure must be logged by MainActivity; logcat=$logcat",
            logcat.contains("stale-session-recreate-failed") &&
                logcat.contains("Host $hostId not found for stale-session recreate"),
        )
        writeText(
            "issue1832-stale-recreate-failure.txt",
            buildString {
                appendLine("journey=production stale dialog -> Create session -> MainActivity host-lookup failure")
                appendLine("dialog_retained=$dialogRetained")
                appendLine("visible_error=$expectedError")
                appendLine("failure_visible_ms=$failureVisibleMs")
                appendLine("session_screen_shown=$sessionScreenShown")
                appendLine("host_session_exists=${sessionAlive(key)}")
                appendLine("log_event=stale-session-recreate-failed")
            },
        )
        Unit
    } }

    /**
     * Issue #1155 REOPEN (2026-07-03) blocker 2 on the COLD-RESTORE path — the
     * maintainer's exact dogfood gesture for "Create session".
     *
     * The sibling [createSessionTapOnStaleDialogRecreatesInStaleFolder] proves the
     * "Create session" action on the in-tree OpenExisting path; this drives the SAME
     * recovery through the real cold-restore journey (attach → background → kill
     * externally → recreate/cold-restore → recovery dialog → tap "Create session")
     * and asserts the recovery actually recovers: a FRESH session with the gone name
     * is created server-side IN the stale folder (its `pane_current_path` equals the
     * broadcast `folderPath`) and the session screen is shown, not a blank.
     *
     * The recovery routes through the gateway create-in-folder path
     * ([StaleSessionPromptController.createSessionInFolder] →
     * `tmux create-detached` / `new-session -A -c <folder>`), so the create is
     * deterministic regardless of the navigate outcome — it does NOT depend on the
     * connect path's `new-session -A`, which the cold-restore `ColdRestore` trigger
     * refuses to run (the #666 no-resurrect guard). That guard is precisely why a
     * plain `navigate` back to the (dead) cold-restore destination — which the
     * screen re-classifies as `ColdRestore` whenever the recovery destination equals
     * `restoredTmuxDestination` (a persisted session with no `tmuxSessionId`) — was a
     * silent NO-OP; the gateway create sidesteps it.
     */
    @Test
    fun createSessionTapOnStaleDialogRecreatesInStaleFolderOnColdRestore() { runBlocking {
        val key = fixtureKey
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val entryPoint = EntryPointAccessors
            .fromApplication(ctx, TestAccessEntryPoint::class.java)

        // Capture the EXACT folder the app will recreate into (== the broadcast
        // stale folderPath == the restored startDirectory) so the `-c <folder>`
        // routing can be asserted.
        val staleEvents = java.util.Collections.synchronizedList(mutableListOf<StaleSession>())
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        collectorScope.launch {
            entryPoint.sessionLifecycleSignals().staleSessions.collect { staleEvents.add(it) }
        }
        delay(LIFECYCLE_DRAIN_MS)

        // ---- (1) Attach to the seeded session via the normal journey.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

        // ---- (2) Background -> onStop persists the last session (#177).
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)

        // ---- (3) Kill the session on the server. It is now GONE.
        killRemoteSession(key)
        assertTrue("session must be gone on the server after kill", !sessionAlive(key))

        // ---- (4) Cold-restore into the gone session (savedInstanceState != null).
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // ---- (5) The app-level recovery dialog must appear.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(STALE_SESSION_DIALOG_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        assertTrue("session must still be gone before Create is tapped", !sessionAlive(key))

        val staleFolder = synchronized(staleEvents) {
            staleEvents.firstOrNull { it.sessionName == SEEDED_SESSION }?.folderPath
        }
        collectorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        // ---- (6) TAP "Create session" -> recreate a fresh session in the SAME folder.
        compose.onNodeWithTag(STALE_SESSION_CONFIRM_TAG, useUnmergedTree = true).performClick()

        // The recovery must NAVIGATE to a fresh session screen (attaches to the
        // just-created session). If it re-enters the dead ColdRestore destination,
        // the screen never attaches and no session is created.
        compose.waitUntil(timeoutMillis = CREATE_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        val sessionScreenShown = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

        // ---- (7) The recreate must create a fresh session server-side.
        var recreated = false
        val deadline = SystemClock.elapsedRealtime() + CREATE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionAlive(key)) { recreated = true; break }
            delay(500)
        }

        // ---- Folder routing: the recreated session's cwd must equal the stale folder.
        val expectedPath = canonicalRemotePath(resolveExpectedFolder(key, staleFolder))
        val recreatedPath = if (recreated) canonicalRemotePath(sessionCurrentPath(key)) else null
        writeText(
            "cold-restore-create-session-tap-recreate.txt",
            buildString {
                appendLine("stale_session=$SEEDED_SESSION")
                appendLine("broadcast_folder_path=$staleFolder")
                appendLine("session_screen_shown_after_create_tap=$sessionScreenShown")
                appendLine("session_recreated_after_create_tap=$recreated")
                appendLine("expected_recreated_folder=$expectedPath")
                appendLine("actual_recreated_folder=$recreatedPath")
                appendLine("expected_session_screen_shown=true")
                appendLine("expected_session_recreated=true")
            },
        )
        assertTrue(
            "tapping 'Create session' on the COLD-RESTORE stale dialog must NAVIGATE " +
                "to a fresh session screen (the recreate fired, not a ColdRestore no-op)",
            sessionScreenShown,
        )
        assertTrue(
            "REGRESSION (#1155 reopen): tapping 'Create session' on cold restore must " +
                "recreate the gone session `$SEEDED_SESSION` server-side; has-session still fails",
            recreated,
        )
        assertEquals(
            "the cold-restore recreate must create in the STALE session's folder " +
                "(-c <folder> routing of the broadcast folderPath)",
            expectedPath,
            recreatedPath,
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

    private fun visibleTag(tag: String): Boolean =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)

    private fun onHostList(hostRowTag: String): Boolean =
        visibleTag(hostRowTag)

    /**
     * Issue #2237: true iff the host's session/folder tree
     * ([com.pocketshell.app.nav.AppDestination.FolderList]) is the visible screen.
     * The tree's header title tag is present for the whole screen, including while
     * it is still loading, so this identifies the DESTINATION rather than a
     * particular tree content state.
     */
    private fun onFolderTree(): Boolean =
        visibleTag(FOLDER_LIST_TITLE_TAG)

    /** Issue #2237: the FolderList header must be for the stale prompt's host. */
    private fun onFolderTreeFor(hostName: String): Boolean =
        runCatching {
            compose.onNodeWithTag(FOLDER_LIST_TITLE_TAG, useUnmergedTree = true)
                .assertTextEquals(hostName)
            true
        }.getOrDefault(false)

    /** Issue #2237: true iff a session row with [sessionName] is listed. */
    private fun sessionRowShown(sessionName: String): Boolean =
        compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun waitForHostRowsPresent(vararg hostRowTags: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            hostRowTags.all(::visibleTag)
        }
    }

    /** Return the activity-scoped VM's real status, not a screen tag proxy. */
    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var viewModel: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(viewModel) { "TmuxSessionViewModel not available" }
            .connectionStatus
            .value
    }

    private data class TerminalViewSnapshot(
        val visible: Boolean,
        val sessionName: String?,
        val transcriptText: String,
    )

    private data class SessionUiSnapshot(
        val status: TmuxSessionViewModel.ConnectionStatus,
        val sessionScreenCount: Int,
        val sessionLive: Boolean?,
        val terminalHeld: Boolean?,
        val surfacePanePresent: Boolean?,
        val errorBandCount: Int,
        val connectionPillCount: Int,
        val reconnectButtonCount: Int,
        val reconnectTextCount: Int,
        val disconnectedTextCount: Int,
        val reconnectingTextCount: Int,
        val sessionLabelCount: Int,
        val terminalViewVisible: Boolean,
        val terminalSessionName: String?,
        val terminalText: String,
    ) {
        fun isReadyFor(expectedSessionName: String, readyMarker: String): Boolean =
            status is TmuxSessionViewModel.ConnectionStatus.Connected &&
                sessionScreenCount == 1 &&
                sessionLive == true &&
                terminalHeld == false &&
                surfacePanePresent == true &&
                errorBandCount == 0 &&
                connectionPillCount == 0 &&
                reconnectButtonCount == 0 &&
                reconnectTextCount == 0 &&
                disconnectedTextCount == 0 &&
                reconnectingTextCount == 0 &&
                sessionLabelCount > 0 &&
                terminalViewVisible &&
                terminalText.contains(readyMarker)

        val terminalOutputSeen: Boolean
            get() = terminalViewVisible && terminalText.isNotBlank()
    }

    private data class StableSessionEvidence(
        val statusName: String,
        val terminalSessionName: String?,
        val terminalOutputSeen: Boolean,
        val sessionLabelCount: Int,
        val stableSamples: Int,
        val stableWindowMs: Long,
        val connectAttemptsAtStableStart: Int,
        val sshHandshakesAtStableStart: Int,
        val connectAttemptsAtSettlement: Int,
        val sshHandshakesAtSettlement: Int,
    )

    private fun sessionScreenBoolean(key: SemanticsPropertyKey<Boolean>): Boolean? =
        runCatching {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.config
                ?.getOrNull(key)
        }.getOrNull()

    private fun visibleNodeCount(tag: String): Int =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        }.getOrDefault(0)

    private fun visibleTextCount(text: String, substring: Boolean = true): Int =
        runCatching {
            compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        }.getOrDefault(0)

    private fun findTerminalView(root: View): TerminalView? {
        if (root is TerminalView) return root
        if (root !is ViewGroup) return null
        repeat(root.childCount) { index ->
            findTerminalView(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun currentTerminalViewSnapshot(): TerminalViewSnapshot {
        var snapshot = TerminalViewSnapshot(
            visible = false,
            sessionName = null,
            transcriptText = "",
        )
        compose.activityRule.scenario.onActivity { activity ->
            val terminal = findTerminalView(activity.window.decorView)
            val session = terminal?.currentSession
            val visibleRect = Rect()
            val visible = terminal != null &&
                terminal.isShown &&
                terminal.width > 0 &&
                terminal.height > 0 &&
                terminal.getGlobalVisibleRect(visibleRect) &&
                visibleRect.width() > 0 &&
                visibleRect.height() > 0
            snapshot = TerminalViewSnapshot(
                visible = visible,
                sessionName = session?.mSessionName,
                transcriptText = session?.emulator?.screen?.transcriptText.orEmpty(),
            )
        }
        return snapshot
    }

    private fun currentSessionUiSnapshot(expectedSessionName: String): SessionUiSnapshot {
        val terminal = currentTerminalViewSnapshot()
        return SessionUiSnapshot(
            status = currentConnectionStatus(),
            sessionScreenCount = visibleNodeCount(TMUX_SESSION_SCREEN_TAG),
            sessionLive = sessionScreenBoolean(TMUX_SESSION_LIVE_SEMANTICS_KEY),
            terminalHeld = sessionScreenBoolean(TMUX_TERMINAL_HELD_SEMANTICS_KEY),
            surfacePanePresent = sessionScreenBoolean(TMUX_SURFACE_PANE_PRESENT_SEMANTICS_KEY),
            errorBandCount = visibleNodeCount(TMUX_SESSION_ERROR_TAG),
            connectionPillCount = visibleNodeCount(TMUX_CONNECTION_STATUS_PILL_TAG),
            reconnectButtonCount = visibleNodeCount(TMUX_RECONNECT_BUTTON_TAG),
            reconnectTextCount = visibleTextCount("Reconnect"),
            disconnectedTextCount = visibleTextCount("Disconnected"),
            reconnectingTextCount = visibleTextCount("Reconnecting"),
            sessionLabelCount = visibleTextCount(expectedSessionName, substring = false),
            terminalViewVisible = terminal.visible,
            terminalSessionName = terminal.sessionName,
            terminalText = terminal.transcriptText,
        )
    }

    /**
     * Wait for the rendered session to be genuinely usable, not merely for its
     * activity-scoped VM to report Connected. The window is reset if either
     * production connect/handshake counter moves while the UI looks healthy;
     * this keeps a stale target or late reconnect from being laundered into a
     * green journey result.
     */
    private fun waitForStableSessionUi(
        label: String,
        expectedSessionName: String,
        readyMarker: String,
    ): StableSessionEvidence {
        val deadline = SystemClock.elapsedRealtime() + CREATE_TIMEOUT_MS
        var stableStartedAt = 0L
        var stableSamples = 0
        var connectAttemptsAtStableStart = -1
        var sshHandshakesAtStableStart = -1
        var lastSnapshot: SessionUiSnapshot? = null
        var samples = 0

        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            val snapshot = currentSessionUiSnapshot(expectedSessionName)
            lastSnapshot = snapshot
            samples += 1
            val connectAttempts = TMUX_CONNECT_ATTEMPTS.get()
            val sshHandshakes = SSH_HANDSHAKE_ATTEMPTS.get()
            if (snapshot.isReadyFor(expectedSessionName, readyMarker)) {
                if (stableStartedAt == 0L ||
                    connectAttempts != connectAttemptsAtStableStart ||
                    sshHandshakes != sshHandshakesAtStableStart
                ) {
                    stableStartedAt = now
                    stableSamples = 0
                    connectAttemptsAtStableStart = connectAttempts
                    sshHandshakesAtStableStart = sshHandshakes
                }
                stableSamples += 1
                val stableWindowMs = now - stableStartedAt
                if (stableWindowMs >= SESSION_UI_STABLE_WINDOW_MS) {
                    val settledSnapshot = currentSessionUiSnapshot(expectedSessionName)
                    val settledConnectAttempts = TMUX_CONNECT_ATTEMPTS.get()
                    val settledSshHandshakes = SSH_HANDSHAKE_ATTEMPTS.get()
                    lastSnapshot = settledSnapshot
                    if (
                        settledSnapshot.isReadyFor(expectedSessionName, readyMarker) &&
                        settledConnectAttempts == connectAttemptsAtStableStart &&
                        settledSshHandshakes == sshHandshakesAtStableStart
                    ) {
                        val statusName = settledSnapshot.status::class.simpleName ?: "unknown"
                        return StableSessionEvidence(
                            statusName = statusName,
                            terminalSessionName = settledSnapshot.terminalSessionName,
                            terminalOutputSeen = settledSnapshot.terminalText.contains(readyMarker),
                            sessionLabelCount = settledSnapshot.sessionLabelCount,
                            stableSamples = stableSamples,
                            stableWindowMs = stableWindowMs,
                            connectAttemptsAtStableStart = connectAttemptsAtStableStart,
                            sshHandshakesAtStableStart = sshHandshakesAtStableStart,
                            connectAttemptsAtSettlement = settledConnectAttempts,
                            sshHandshakesAtSettlement = settledSshHandshakes,
                        )
                    }
                    stableStartedAt = 0L
                    stableSamples = 0
                }
            } else {
                stableStartedAt = 0L
                stableSamples = 0
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0L) {
                SystemClock.sleep(minOf(SESSION_UI_SAMPLE_INTERVAL_MS, remaining))
            }
        }

        val last = lastSnapshot
        val tail = last?.terminalText
            ?.takeLast(160)
            ?.replace('\n', ' ')
            .orEmpty()
        throw AssertionError(
            "$label did not remain genuinely ready for " +
                "${SESSION_UI_STABLE_WINDOW_MS}ms after $samples samples; " +
                "lastStatus=${last?.status}; live=${last?.sessionLive}; " +
                "terminalHeld=${last?.terminalHeld}; pane=${last?.surfacePanePresent}; " +
                "errorBand=${last?.errorBandCount}; pill=${last?.connectionPillCount}; " +
                "reconnect=${last?.reconnectTextCount}; disconnected=${last?.disconnectedTextCount}; " +
                "reconnecting=${last?.reconnectingTextCount}; terminalVisible=${last?.terminalViewVisible}; " +
                "sessionLabel=${last?.sessionLabelCount}; terminalSession=${last?.terminalSessionName}; " +
                "terminalTail=$tail; " +
                "connectAttempts=${TMUX_CONNECT_ATTEMPTS.get()}; " +
                "sshHandshakes=${SSH_HANDSHAKE_ATTEMPTS.get()}",
        )
    }

    /**
     * A terminal composable can be visible while its VM is Disconnected. The
     * connected journey must establish and retain the production Connected state
     * before it kills the stale session, then repeat that oracle for the sibling.
     */
    private fun waitForConnected(label: String): String {
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        val status = currentConnectionStatus()
        val statusName = status::class.simpleName ?: "unknown"
        assertTrue(
            "$label did not reach Connected; final status=$statusName ($status)",
            status is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        return statusName
    }

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
                    name = HOST_NAME,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    trustedHostKeySha256 = trustedHostKeySha256,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    /**
     * Add a second, distinct host through the app's singleton Room instance.
     * Using the production DB instance makes the two-host precondition observable
     * by the live HostList flow; it does not attach or inject any connection.
     */
    private suspend fun seedSecondaryDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val entryPoint = EntryPointAccessors
            .fromApplication(appContext, TestAccessEntryPoint::class.java)
        val storedKey = SshKeyStorage.persistKey(
            context = appContext,
            sshKeyDao = entryPoint.sshKeyDao(),
            name = "issue2237-other-key-${System.currentTimeMillis()}",
            content = key,
        )
        val hostId = entryPoint.appDatabase().hostDao().insert(
            HostEntity(
                name = OTHER_HOST_NAME,
                hostname = OTHER_HOSTNAME,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = storedKey.id,
                tmuxInstalled = false,
                lastBootstrapAt = null,
            ),
        )
        return HOST_ROW_TAG_PREFIX + hostId
    }

    private suspend fun deleteHostRow(hostId: Long) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.hostDao().deleteById(hostId)
        } finally {
            db.close()
        }
    }

    private suspend fun seedTmuxSession(key: String) =
        seedTmuxSessionNamed(key, SEEDED_SESSION, SEEDED_READY_MARKER)

    private suspend fun seedTmuxSessionNamed(
        key: String,
        name: String,
        readyMarker: String = SEEDED_READY_MARKER,
    ) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(name)} " +
                    "${shellQuote("printf '$readyMarker\\n'; exec sleep 600")}",
            )
            appendLine("sleep 1")
            appendLine("tmux has-session -t ${shellQuote(name)}")
        }
        val exec = runScript(key, script)
        assertTrue(
            "expected tmux seeding of `$name` to succeed; stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun killRemoteSession(key: String) =
        killRemoteSessionNamed(key, SEEDED_SESSION)

    private suspend fun killRemoteSessionNamed(key: String, name: String) {
        runScript(
            key,
            "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true",
        )
    }

    /** True iff the seeded tmux session currently exists on the server. */
    private suspend fun sessionAlive(key: String): Boolean =
        sessionAliveNamed(key, SEEDED_SESSION)

    private suspend fun sessionAliveNamed(key: String, name: String): Boolean {
        val exec = runScript(
            key,
            "tmux has-session -t ${shellQuote(name)} 2>/dev/null && echo ALIVE || echo GONE",
        )
        return exec?.stdout?.contains("ALIVE") == true
    }

    /** The working directory (`pane_current_path`) of the seeded session, or "". */
    private suspend fun sessionCurrentPath(key: String): String {
        val exec = runScript(
            key,
            "tmux display-message -p -t ${shellQuote(SEEDED_SESSION)} '#{pane_current_path}' 2>/dev/null",
        )
        return exec?.stdout?.trim().orEmpty()
    }

    /**
     * The directory a recreate with [staleFolder] must land in: the folder itself
     * when the app broadcast one, else the host home dir (a null/blank folderPath
     * recreates with no `-c`, so `new-session -A` lands in `$HOME`).
     */
    private suspend fun resolveExpectedFolder(key: String, staleFolder: String?): String {
        val folder = staleFolder?.trim().orEmpty()
        if (folder.isNotEmpty() && folder != "~") return folder
        val home = runScript(key, "printf %s \"\$HOME\"")?.stdout?.trim().orEmpty()
        return home.ifEmpty { folder }
    }

    /**
     * Resolve a remote path to its canonical form so the recreate assertion is not
     * defeated by a symlinked home (`/home/x` vs `/root` vs a `realpath`ed cwd).
     */
    private suspend fun canonicalRemotePath(path: String): String {
        if (path.isBlank()) return path
        val exec = runScript(key = fixtureKey, script = "cd ${shellQuote(path)} 2>/dev/null && pwd -P || printf %s ${shellQuote(path)}")
        return exec?.stdout?.trim()?.ifBlank { path } ?: path
    }

    private suspend fun runScript(key: String, script: String) =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
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

    /**
     * Wait for the production onStop write, not for an arbitrary lifecycle
     * sleep. The host/session identity is enough to distinguish this fresh
     * record because [seedBeforeLaunch] clears the singleton store before the
     * activity is launched. Generation completeness is intentionally checked
     * by the caller: requiring both raw fields in this readiness loop would
     * turn a missing-field mutant into a timeout and hide the focused
     * [LastSessionStore.LastSession.generation] assertion.
     */
    private fun waitForPersistedLastSession(
        entryPoint: TestAccessEntryPoint,
        hostId: Long,
        sessionName: String,
    ): LastSessionStore.LastSession {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + LAST_SESSION_READY_TIMEOUT_MS
        var samples = 0
        var latest: LastSessionStore.LastSession? = null
        var lastReadError: Throwable? = null

        while (SystemClock.elapsedRealtime() < deadline) {
            samples += 1
            val candidate = runCatching {
                entryPoint.lastSessionStore().read(maxAgeMillis = Long.MAX_VALUE)
            }.onFailure { lastReadError = it }.getOrNull()
            latest = candidate
            val exactRecord = candidate?.let {
                it.hostId == hostId &&
                    it.sessionName == sessionName
            } == true
            if (exactRecord) {
                recordTiming(
                    "issue834_last_session_ready_ms",
                    SystemClock.elapsedRealtime() - startedAt,
                )
                recordTiming("issue834_last_session_samples", samples.toLong())
                return requireNotNull(candidate)
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0L) {
                SystemClock.sleep(minOf(LAST_SESSION_READY_SAMPLE_INTERVAL_MS, remaining))
            }
        }

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        recordTiming("issue834_last_session_ready_timeout_ms", elapsed)
        writeText(
            "issue834-last-session-readiness-timeout.txt",
            buildString {
                appendLine("wait=production LastSessionStore onStop persistence")
                appendLine("elapsed_ms=$elapsed")
                appendLine("samples=$samples")
                appendLine("expected_host_id=$hostId")
                appendLine("expected_session=$sessionName")
                appendLine("observed_host_id=${latest?.hostId}")
                appendLine("observed_session=${latest?.sessionName}")
                appendLine("observed_tmux_session_id=${latest?.tmuxSessionId}")
                appendLine("observed_session_created=${latest?.sessionCreated}")
                appendLine("last_read_error=${lastReadError?.let { it::class.simpleName + ": " + it.message }}")
                appendLine("readiness_contract=host_id_and_session_name_only")
                appendLine("generation_completeness_checked_after_readiness=true")
                appendLine("expected_generation_requires_tmux_session_id=true")
                appendLine("expected_generation_requires_session_created=true")
            },
        )
        throw AssertionError(
            "issue834 last-session persistence did not publish the exact " +
                "complete record within ${LAST_SESSION_READY_TIMEOUT_MS}ms; " +
                "expected hostId=$hostId session=$sessionName, observed=" +
                "hostId=${latest?.hostId} session=${latest?.sessionName} " +
                "tmuxSessionId=${latest?.tmuxSessionId} " +
                "sessionCreated=${latest?.sessionCreated} " +
                "lastReadError=${lastReadError?.message}",
        )
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE666_TEXT ${file.absolutePath}")
        return file
    }

    private fun shellOutput(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText() }
    }

    /**
     * Issue #2237: capture the semantics of the actual tagged dismiss button, not
     * a global text lookup. The stale dialog is an Android AlertDialog and owns a
     * separate Compose root; the button tag is therefore the authoritative
     * production node for this contract.
     */
    private fun captureDismissActionEvidence(prefix: String): DismissActionSemantics {
        val node = compose.onNodeWithTag(STALE_SESSION_GO_HOME_TAG).fetchSemanticsNode()
        val text = node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString(separator = " | ") { it.text }
        val contentDescription = node.config
            .getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()
            .joinToString(separator = " | ")
        val hasClickAction = node.config.contains(SemanticsActions.OnClick)
        val evidence = DismissActionSemantics(
            text = text,
            contentDescription = contentDescription,
            hasClickAction = hasClickAction,
        )
        writeText(
            "$prefix-semantics.txt",
            buildString {
                appendLine("test_tag=$STALE_SESSION_GO_HOME_TAG")
                appendLine("text=${evidence.text}")
                appendLine("content_description=${evidence.contentDescription}")
                appendLine("has_click_action=${evidence.hasClickAction}")
                appendLine("expected_text=$DISMISS_LABEL")
            },
        )
        captureFullDevice("$prefix-viewport")
        captureUiHierarchy("$prefix-ui")
        return evidence
    }

    private fun captureUiHierarchy(prefix: String) {
        val remotePath = "/sdcard/$prefix.xml"
        shellOutput("uiautomator dump --compressed $remotePath")
        writeText("$prefix.xml", shellOutput("cat $remotePath"))
    }

    private data class DismissActionSemantics(
        val text: String,
        val contentDescription: String,
        val hasClickAction: Boolean,
    )

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name-viewport.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
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
        const val SEEDED_READY_MARKER: String = "ISSUE666-READY"

        // Issue #2237: these are the production dialog's exact non-null dismiss
        // copy. The cold-restore dialog test asserts both the action and the
        // message, then taps it and verifies the typed host-tree destination.
        const val HOST_NAME: String = "Issue666 GoneRestore"
        const val OTHER_HOST_NAME: String = "Issue2237 OtherHost"
        const val OTHER_HOSTNAME: String = "issue2237-other.invalid"
        const val DISMISS_LABEL: String = "Back to sessions"
        const val DISMISS_MESSAGE_CLAUSE: String = "go back to this host's sessions"

        // Issue #2237: a SECOND live session on the same host. The stale-session
        // dialog's dismiss must land on that host's session tree, and the point of
        // landing there (instead of the host list) is that another existing session
        // is immediately pickable — so the tree needs one to pick.
        const val SIBLING_SESSION: String = "codex"
        const val SIBLING_READY_MARKER: String = "ISSUE2237-SIBLING-READY"

        const val LIFECYCLE_DRAIN_MS: Long = 750L
        const val RESTORE_TIMEOUT_MS: Long = 20_000L
        const val LAST_SESSION_READY_TIMEOUT_MS: Long = 20_000L
        const val LAST_SESSION_READY_SAMPLE_INTERVAL_MS: Long = 50L
        const val OWNER_RELEASE_TIMEOUT_MS: Long = 10_000L
        const val OWNER_RELEASE_SAMPLE_INTERVAL_MS: Long = 50L
        const val DISMISS_TRANSITION_SAMPLE_INTERVAL_MS: Long = 50L
        const val DISMISS_POST_SETTLEMENT_OBSERVATION_MS: Long = 750L
        const val SESSION_UI_SAMPLE_INTERVAL_MS: Long = 50L
        const val SESSION_UI_STABLE_WINDOW_MS: Long = 1_500L

        // A fresh SSH connect + tmux attach + `new-session -A -c <folder>` after the
        // recreate tap is slower than a restore probe (a brand-new connection, not a
        // warm reattach), so the create-session recreate gets a longer window.
        const val CREATE_TIMEOUT_MS: Long = 60_000L
    }
}
