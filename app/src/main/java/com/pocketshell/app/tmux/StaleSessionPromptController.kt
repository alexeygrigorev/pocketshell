package com.pocketshell.app.tmux

import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.core.storage.dao.HostDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1155 / #666 — the APP-LEVEL owner of the "This session no longer
 * exists" recovery prompt.
 *
 * ## Why an app-level owner (the reopen, 2026-07-03)
 *
 * Part B first wired the recreate prompt into the folder tree
 * ([com.pocketshell.app.projects.FolderListViewModel]) only. That works for a
 * NORMAL tap of a persisted session ROW ([TmuxConnectTrigger.OpenExisting]) —
 * the user came FROM the tree, so its view model is still bound and collecting
 * the no-replay [SessionLifecycleSignals.staleSessions] broadcast when the
 * genuinely-gone attach fails.
 *
 * But the maintainer's dogfood path is a **cold restore**
 * ([TmuxConnectTrigger.ColdRestore]): the app process-restores DIRECTLY onto the
 * last session screen, so the folder tree was NEVER opened and its view model
 * does not exist — nothing is subscribed to the no-replay stale broadcast, and
 * the prompt was silently lost (the user just landed on the bare host list). The
 * connection core already PROBES `tmux has-session` and refuses to resurrect a
 * gone session (issue #666 preflight in [TmuxSessionViewModel]); the missing
 * piece was surfacing the recovery prompt to the user on that path.
 *
 * This controller is a process-scoped [Singleton] that subscribes to the stale
 * broadcast ONCE at construction (injected into `MainActivity`, so it is alive
 * from `onCreate`, long before any cold-restore connect emits) and holds the
 * latest gone session in a [StateFlow]. `MainActivity` renders a single
 * app-level "Create a new session in this folder, or go home?" dialog off it, so
 * the recovery prompt appears regardless of which screen the app restored onto —
 * cold restore, an OpenExisting tap, whatever. One owner, one dialog (D22
 * hard-cut): the folder tree no longer raises its own prompt (it still drops the
 * confirmed-gone row from its list for accuracy).
 *
 * The stale broadcast only fires from the genuinely-gone attach-fail path
 * (`TmuxSessionNotFoundException` → `failSessionEnded` → `emitStaleSession`); a
 * transient reconnect blip rides the reconnect ladder and NEVER reaches it, so
 * this prompt never appears on a mere network hiccup.
 */
@Singleton
class StaleSessionPromptController internal constructor(
    signals: SessionLifecycleSignals,
    // Issue #1155 REOPEN (2026-07-03): the create-in-folder seam. The "Create
    // session" recovery action creates a FRESH tmux session in the gone session's
    // folder over the SAME gateway path the normal "new session in this folder"
    // action uses (`tmux create-detached` / `new-session -A -c <folder>`), rather
    // than re-navigating to the dead cold-restore destination — which the screen
    // classifies as a [TmuxConnectTrigger.ColdRestore] whose `has-session`
    // preflight then REFUSES to create (the #666 no-resurrect guard), so the
    // recreate was a silent no-op on the cold-restore path. Routing through the
    // gateway makes the create deterministic regardless of which screen the app
    // restored onto. Null on the bare test constructor (which does not exercise
    // the create).
    private val gateway: FolderListGateway?,
    private val hostDao: HostDao?,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** Production Hilt entry point. */
    @Inject
    constructor(
        signals: SessionLifecycleSignals,
        gateway: FolderListGateway,
        hostDao: HostDao,
    ) : this(signals, gateway, hostDao, Dispatchers.IO)

    /**
     * Test convenience: the original signal-only constructor, for JVM tests that
     * assert the prompt subscription/clear wiring without the create seam.
     */
    @androidx.annotation.VisibleForTesting
    internal constructor(signals: SessionLifecycleSignals) :
        this(signals, gateway = null, hostDao = null, ioDispatcher = Dispatchers.Unconfined)

    // Main.immediate so the StateFlow flip lands on the same thread Compose
    // reads it from; the subscription is established once at construction.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _prompt = MutableStateFlow<StaleSession?>(null)

    /**
     * The most recent genuinely-gone session the user should be prompted to
     * recreate (or leave via "go home"), or `null` when there is nothing to
     * recover. `MainActivity` observes this and renders the app-level dialog.
     */
    val prompt: StateFlow<StaleSession?> = _prompt.asStateFlow()

    init {
        scope.launch {
            signals.staleSessions.collect { _prompt.value = it }
        }
    }

    /**
     * Clear the active prompt — called once the user has resolved it (created a
     * fresh session in the folder or gone home).
     */
    fun clear() {
        _prompt.value = null
    }

    /**
     * Issue #1155 REOPEN (2026-07-03): the "Create session" recovery action.
     *
     * Creates a FRESH tmux session named [sessionName] in [folderPath] over the
     * SAME gateway create path the normal "new session in this folder" action
     * uses ([FolderListGateway.createSession] → `tmux create-detached` /
     * `new-session -A -c <folder>`), and returns the resolved session name.
     *
     * This is deliberately the GATEWAY path, NOT a re-navigate to the (now dead)
     * cold-restore destination: on the cold-restore path the recovery navigate
     * lands on a destination the screen classifies as [TmuxConnectTrigger.ColdRestore]
     * (`dest == restoredTmuxDestination`), whose `has-session` preflight then
     * REFUSES to create the gone session (the #666 no-resurrect guard) — so the
     * recreate was a silent no-op. Creating the session server-side FIRST makes it
     * deterministic; the caller then attaches to the now-existing session.
     *
     * [folderPath] is the gone session's working directory; a null/blank folder
     * recreates in the host home directory (`~`), so the user always recovers —
     * never a blank/error. Returns a failure when no create seam is wired (a bare
     * test controller) or the host row / create exec fails.
     */
    suspend fun createSessionInFolder(
        hostId: Long,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        folderPath: String?,
    ): Result<String> {
        val gateway = this.gateway
        val hostDao = this.hostDao
        if (gateway == null || hostDao == null) {
            return Result.failure(
                IllegalStateException("StaleSessionPromptController has no create-session seam wired"),
            )
        }
        val host = withContext(ioDispatcher) { hostDao.getById(hostId) }
            ?: return Result.failure(IllegalStateException("Host $hostId not found for stale-session recreate"))
        val cwd = folderPath?.trim()?.takeUnless { it.isEmpty() } ?: DEFAULT_RECREATE_START_DIRECTORY
        return gateway.createSession(
            host = host,
            keyPath = keyPath,
            passphrase = passphrase,
            sessionName = sessionName,
            cwd = cwd,
            startCommand = null,
        )
    }

    /**
     * Test-only injection of a stale session, bypassing the signal subscription,
     * for JVM tests that assert `MainActivity`'s dialog wiring off the [prompt]
     * state without a live [SessionLifecycleSignals].
     */
    @androidx.annotation.VisibleForTesting
    internal fun offerForTest(stale: StaleSession) {
        _prompt.value = stale
    }

    private companion object {
        /**
         * Fallback create directory for a gone session with no known folder — the
         * host home (`~`). Mirrors the folder create path's default so a
         * null/blank-folder recovery still lands in a real directory.
         */
        const val DEFAULT_RECREATE_START_DIRECTORY: String = "~"
    }
}
