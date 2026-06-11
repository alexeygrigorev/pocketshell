package com.pocketshell.app.tmux

import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Issue #708: build an [SshLeaseManager] whose bounded cold connect (#687) runs
 * on the calling [TestScope]'s VIRTUAL clock instead of the production default
 * (`Dispatchers.IO` + `System.currentTimeMillis()`).
 *
 * `b5733d33` wrapped the cold dial in `withTimeoutOrNull(connectTimeoutMillis)`
 * on a real wall clock so a wedged handshake can always time out at 35s
 * regardless of the caller — correct for production. But a `runTest` body
 * advances a virtual clock; if the dial runs on a real thread, `advanceUntilIdle`
 * never drives it and the awaited lease state never arrives. Threading the
 * test's `testScheduler` into `connectTimeoutContext`/`nowMillis` makes the
 * bounded dial step deterministically under `advanceUntilIdle()`/`runCurrent()`.
 *
 * Production [SshLeaseManager] defaults are untouched — this injection is
 * test-only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.testLeaseManager(
    connector: SshLeaseConnector,
    scope: CoroutineScope = this,
    idleTtlMillis: Long = SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS,
): SshLeaseManager =
    SshLeaseManager(
        connector = connector,
        scope = scope,
        idleTtlMillis = idleTtlMillis,
        connectTimeoutContext = StandardTestDispatcher(testScheduler),
        nowMillis = { testScheduler.currentTime },
    )
