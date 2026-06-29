package com.pocketshell.app

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Issue #1085 (F2): a single process-lifetime [CoroutineScope] that owns the
 * SLOW connection-teardown IO handed off from a ViewModel's `onCleared` —
 * the warm-lease refcount-- ([com.pocketshell.core.ssh.SshLease.release]), the
 * raw SSH-socket close, and the tmux `detach-client` round-trip.
 *
 * `onCleared` runs on the MAIN thread. On a half-open / wedged transport (the
 * maintainer's WIFI↔cellular handoff scenario), the SSH/lease close rides its
 * full bounded ceiling, and a nested `runBlocking` socket close in
 * `RealSshShell` / `RealSshSession` cannot be interrupted by a coroutine
 * timeout — so doing the teardown synchronously on Main parks the UI thread for
 * multiple seconds, an ANR backing out of a screen (#1085 F2). Handing the work
 * to THIS scope lets `onCleared` return immediately while the slow close still
 * runs to COMPLETION in the background:
 *
 * - The scope is a process singleton — it OUTLIVES every ViewModel and is never
 *   cancelled, so the refcount is always decremented and the socket always
 *   closed even though the VM is already gone (no lease leak).
 * - [SupervisorJob] so one VM's teardown failure cannot cancel another's.
 * - [Dispatchers.IO] because the work is blocking socket / transport IO.
 * - A [CoroutineExceptionHandler] is installed so an unexpected uncaught throw
 *   in a teardown coroutine is logged rather than reaching the thread's
 *   default uncaught handler (process death). Callers still wrap each step in
 *   `runCatching`; this is the belt-and-suspenders net.
 *
 * ViewModels default their teardown scope to [scope] and override it in tests
 * (via a `setTeardownScopeForTest` seam) so a unit test can drive the hand-off
 * deterministically and confirm the teardown completed off the Main thread.
 */
public object AppTeardownScope {
    private const val TAG = "AppTeardownScope"

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "connection teardown coroutine failed", throwable)
    }

    public val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
}
