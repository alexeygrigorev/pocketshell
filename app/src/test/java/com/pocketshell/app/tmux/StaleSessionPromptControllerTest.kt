package com.pocketshell.app.tmux

import com.pocketshell.app.projects.FolderImportPayload
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.projects.FolderListResult
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1155 / #666 — the APP-LEVEL stale-session recovery prompt owner.
 *
 * ## Reopen (2026-07-03): cold restore silently recreated the gone session
 *
 * Part B first wired the recreate prompt into the folder tree
 * (`FolderListViewModel`) ONLY, whose no-replay subscription to
 * [SessionLifecycleSignals.staleSessions] is alive only while the tree is on the
 * back stack. The maintainer's dogfood path is a COLD RESTORE straight onto the
 * last session screen — the folder tree was never opened, so its view model does
 * not exist, so nothing was subscribed and the recovery prompt was silently lost
 * (the connection core still refused to resurrect the gone session; the missing
 * piece was surfacing the prompt).
 *
 * [StaleSessionPromptController] is the fix: a process-scoped singleton that is
 * subscribed to the stale broadcast from app start (injected into `MainActivity`),
 * so it catches the genuinely-gone broadcast REGARDLESS of which screen the app
 * restored onto, and `MainActivity` renders one app-level "create in this folder,
 * or go home?" dialog off its [StaleSessionPromptController.prompt] state.
 *
 * These tests drive the REAL wiring (emit on [SessionLifecycleSignals] → the
 * controller's subscription surfaces the prompt) with NO folder tree bound — the
 * cold-restore condition that the base, tree-only owner could not cover.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StaleSessionPromptControllerTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val goneFolder = "/home/alex/git/pocketshell"

    /**
     * The reopen reproduction, at the delivery layer: a genuinely-gone broadcast
     * fires with NO folder tree bound (the cold-restore condition). The base,
     * tree-only owner would drop it on the floor; the app-level controller
     * surfaces it as a prompt — the maintainer's "this session no longer exists"
     * recovery, instead of the silent recreate.
     */
    @Test
    fun coldRestoreStaleEmitSurfacesPromptWithNoTreeBound() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val signals = SessionLifecycleSignals()
        val controller = StaleSessionPromptController(signals)
        runCurrent()

        assertNull("no prompt before the gone-session broadcast", controller.prompt.value)

        // The cold-restore attach confirmed the session is genuinely gone.
        signals.emitStaleSession(hostId = 7L, sessionName = "work", folderPath = goneFolder)
        runCurrent()

        val prompt = controller.prompt.value
        assertNotNull(
            "a cold-restore gone-session broadcast must surface the app-level recovery prompt",
            prompt,
        )
        assertEquals(7L, prompt!!.hostId)
        assertEquals("work", prompt.sessionName)
        assertEquals(goneFolder, prompt.folderPath)
    }

    /**
     * Class coverage — the OpenExisting tap path fires the SAME broadcast, so the
     * one app-level owner covers both the cold-restore and the in-tree tap without
     * a second dialog.
     */
    @Test
    fun openExistingStaleEmitSurfacesPrompt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val signals = SessionLifecycleSignals()
        val controller = StaleSessionPromptController(signals)
        runCurrent()

        signals.emitStaleSession(hostId = 3L, sessionName = "beta", folderPath = "/srv/beta")
        runCurrent()

        assertEquals("beta", controller.prompt.value?.sessionName)
        assertEquals("/srv/beta", controller.prompt.value?.folderPath)
    }

    /**
     * Class coverage — the missing-data case: a gone session with NO known folder
     * still surfaces the prompt (never a blank/dropped recovery). The app-level
     * dialog falls its label back to "home" and recreates in the host home dir.
     */
    @Test
    fun nullFolderStaleEmitStillSurfacesPrompt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val signals = SessionLifecycleSignals()
        val controller = StaleSessionPromptController(signals)
        runCurrent()

        signals.emitStaleSession(hostId = 9L, sessionName = "orphan", folderPath = null)
        runCurrent()

        val prompt = controller.prompt.value
        assertNotNull("a null-folder gone session must STILL surface the prompt", prompt)
        assertEquals("orphan", prompt!!.sessionName)
        assertNull(prompt.folderPath)
    }

    /**
     * Class coverage — NO spurious prompt. The stale broadcast only ever fires
     * from the genuinely-gone attach-fail path; a transient reconnect blip never
     * emits it (proven on the emitter side in `TmuxSessionWarmOpenTest`). With no
     * broadcast the controller stays clear — the transient-never-prompts guarantee
     * at the delivery layer.
     */
    @Test
    fun noBroadcastMeansNoPrompt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val signals = SessionLifecycleSignals()
        val controller = StaleSessionPromptController(signals)
        runCurrent()

        // A transient reconnect emits a KILL or nothing on this bus, never a
        // stale-session; assert the controller does not fabricate a prompt.
        signals.emitKilled(hostId = 7L, sessionName = "work")
        runCurrent()

        assertNull("only a genuinely-gone broadcast may surface the prompt", controller.prompt.value)
    }

    /** The user resolving the prompt (recreate / go home) clears it. */
    @Test
    fun clearResetsPrompt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val signals = SessionLifecycleSignals()
        val controller = StaleSessionPromptController(signals)
        runCurrent()

        signals.emitStaleSession(hostId = 7L, sessionName = "work", folderPath = goneFolder)
        runCurrent()
        assertNotNull(controller.prompt.value)

        controller.clear()
        runCurrent()
        assertNull("clear() must reset the prompt after the user resolves it", controller.prompt.value)
    }

    // ---------------------------------------------------------------------------
    // Issue #1155 REOPEN (2026-07-03): the "Create session" recovery ACTION creates
    // a fresh session in the gone session's folder over the REAL gateway create
    // path, instead of re-navigating to the dead cold-restore destination (which
    // the screen classifies as ColdRestore, whose has-session preflight REFUSES to
    // create — the silent no-op the reopen reports).
    // ---------------------------------------------------------------------------

    /**
     * The recreate goes through [FolderListGateway.createSession] with the gone
     * session's name AND its folder as the `cwd` — the deterministic create the
     * cold-restore navigate no-op could not do. Returns the resolved session name.
     */
    @Test
    fun createSessionInFolderCreatesInTheStaleFolderViaGateway() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingGateway(resolvedName = "work")
        val controller = StaleSessionPromptController(
            SessionLifecycleSignals(),
            gateway = gateway,
            hostDao = FakeHostDao(host(id = 7L)),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = controller.createSessionInFolder(
            hostId = 7L,
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "work",
            folderPath = goneFolder,
        )

        assertTrue("gateway create must succeed", result.isSuccess)
        assertEquals("work", result.getOrNull())
        assertEquals("the gateway must be asked to create the gone session name", "work", gateway.lastSessionName)
        assertEquals(
            "the recreate must target the STALE session's folder (cwd = folderPath)",
            goneFolder,
            gateway.lastCwd,
        )
        assertNull("a plain recovery create never auto-launches a start command", gateway.lastStartCommand)
    }

    /**
     * Class coverage — the missing-data case: a null/blank folder recreates in the
     * host home directory (`~`), never a blank/error. The `cwd` handed to the
     * gateway falls back to `~`.
     */
    @Test
    fun createSessionInFolderNullFolderFallsBackToHome() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingGateway(resolvedName = "orphan")
        val controller = StaleSessionPromptController(
            SessionLifecycleSignals(),
            gateway = gateway,
            hostDao = FakeHostDao(host(id = 9L)),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = controller.createSessionInFolder(
            hostId = 9L,
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "orphan",
            folderPath = null,
        )

        assertTrue(result.isSuccess)
        assertEquals("a null folder recreates in the host home dir", "~", gateway.lastCwd)
    }

    /**
     * A create against an unknown host id fails cleanly (never throws / hangs), so
     * the caller can recover the user to the list rather than silently doing
     * nothing.
     */
    @Test
    fun createSessionInFolderFailsWhenHostMissing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = RecordingGateway(resolvedName = "x")
        val controller = StaleSessionPromptController(
            SessionLifecycleSignals(),
            gateway = gateway,
            hostDao = FakeHostDao(host(id = 7L)),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = controller.createSessionInFolder(
            hostId = 999L,
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "x",
            folderPath = goneFolder,
        )

        assertTrue("an unknown host must surface a failure", result.isFailure)
        assertFalse("the gateway must not be called for an unknown host", gateway.called)
    }

    private fun host(id: Long): HostEntity =
        HostEntity(id = id, name = "h", hostname = "example", username = "u", keyId = 1L)

    private class FakeHostDao(private val host: HostEntity) : HostDao {
        override fun getAll(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun getById(id: Long): HostEntity? = host.takeIf { it.id == id }
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun insert(host: HostEntity): Long = error("not used")
        override suspend fun update(host: HostEntity) = error("not used")
        override suspend fun delete(host: HostEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }

    private class RecordingGateway(private val resolvedName: String) : FolderListGateway {
        var called: Boolean = false
        var lastSessionName: String? = null
        var lastCwd: String? = null
        var lastStartCommand: String? = null

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
        ): Result<String> {
            called = true
            lastSessionName = sessionName
            lastCwd = cwd
            lastStartCommand = startCommand
            return Result.success(resolvedName)
        }

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult = error("not used")

        override suspend fun createEmptyProject(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            parentPath: String,
            folderName: String,
        ): Result<String> = error("not used")

        override suspend fun importFile(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            folderPath: String,
            payload: FolderImportPayload,
        ): Result<String> = error("not used")

        override suspend fun killSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> = error("not used")
    }
}
