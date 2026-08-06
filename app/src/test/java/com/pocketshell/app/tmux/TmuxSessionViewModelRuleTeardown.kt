package com.pocketshell.app.tmux

import androidx.lifecycle.viewModelScope
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.testsupport.GENEROUS_SETTLE_DEADLINE_MS
import com.pocketshell.testsupport.drainMainLooperUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Shared #1355 teardown registry for JVM fixtures that construct
 * [TmuxSessionViewModel] directly and later reset the process-global
 * `Dispatchers.Main`.
 *
 * `clearForTest()` invokes `onCleared()` directly, so it does not perform the
 * framework-owned `viewModelScope` cancellation. A bare `CoroutineScope.cancel`
 * also returns before a real-IO child has necessarily unwound. Both can leave a
 * continuation that reads `Dispatchers.Main` after the fixture resets the global
 * delegate, recreating the `TestMainDispatcher:72` race in an unrelated test.
 *
 * This registry makes the ordering structural:
 *
 * 1. clear every tracked VM while Main is installed;
 * 2. cancel every VM-owned root and every explicitly owned collaborator root;
 * 3. cancel-and-join the collaborator roots;
 * 4. use only [runCurrent] (never virtual-time advancement) to drain all VM
 *    roots to zero; and
 * 5. return to the caller, which only then resets Main.
 *
 * Issue #1765: it has exactly TWO bindings, so the drain algorithm above is
 * written once rather than copied per fixture:
 *
 *  - [MainDispatcherRule] consumers bind it through
 *    [tmuxSessionViewModelTeardown], which registers [drain] on the rule's
 *    `beforeResetMain` boundary; and
 *  - standalone `runTest` fixtures bind it through [StandaloneTmuxVmFixture],
 *    which owns its own `TestCoroutineScheduler` and calls [drain] after the
 *    `runTest` body returns — still inside the `runTest` block — but before
 *    `Dispatchers.resetMain()`.
 *
 * [runCurrent] is injected rather than taken from a rule because those two
 * bindings own different schedulers; it must only run already-due work and must
 * never advance virtual time (the #1110 lesson: advancing the clock trips the
 * #793 re-seed watchdog).
 */
internal class TmuxSessionViewModelRuleTeardown(
    private val runCurrent: () -> Unit,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private data class TrackedVm(
        val clear: () -> Unit,
        val cancelOwnScopes: () -> Unit,
        val activeOwnScopeChildCount: () -> Int,
        val debugActiveChildren: () -> String,
    )

    private data class TrackedRoot(
        val name: String,
        val job: Job,
    )

    private val vms = mutableListOf<TrackedVm>()
    private val roots = mutableListOf<TrackedRoot>()
    private var drained = false

    init {
        require(timeoutMs > 0L) { "timeoutMs must be > 0, was $timeoutMs" }
    }

    fun track(vm: TmuxSessionViewModel): TmuxSessionViewModel =
        vm.also {
            track(
                clear = {
                    vm.setProcessForegroundForClearedForTest(false)
                    vm.clearForTest()
                },
                cancelOwnScopes = vm::cancelOwnScopesForTest,
                activeOwnScopeChildCount = vm::activeOwnScopeChildCountForTest,
                debugActiveChildren = {
                    vm.viewModelScope.coroutineContext[Job]
                        ?.children
                        ?.filterNot(Job::isCompleted)
                        ?.joinToString(separator = "\n") { child -> child.debugTree() }
                        .orEmpty()
                },
            )
        }

    fun trackRoot(name: String, scope: CoroutineScope): CoroutineScope =
        scope.also {
            roots += TrackedRoot(
                name = name,
                job = requireNotNull(scope.coroutineContext[Job]) {
                    "Issue #1355: $name must have an owned root Job"
                },
            )
        }

    /**
     * Test adapter for the teardown invariant itself. Production-shaped callers
     * use [track]; both paths share the exact cancellation/drain implementation.
     */
    internal fun track(
        clear: () -> Unit,
        cancelOwnScopes: () -> Unit,
        activeOwnScopeChildCount: () -> Int,
        debugActiveChildren: () -> String = { "" },
    ) {
        check(!drained) { "cannot track a VM after the #1355 teardown drained" }
        vms += TrackedVm(clear, cancelOwnScopes, activeOwnScopeChildCount, debugActiveChildren)
    }

    internal fun drain() {
        if (drained) return
        drained = true

        var cleanupFailure: Throwable? = null
        fun capture(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                val firstFailure = cleanupFailure
                if (firstFailure == null) {
                    cleanupFailure = failure
                } else if (firstFailure !== failure) {
                    firstFailure.addSuppressed(failure)
                }
            }
        }

        vms.asReversed().forEach { vm -> capture(vm.clear) }
        // Let already-due Main work reach its next suspension before requesting
        // cancellation. In particular, Issue1574 ends one method immediately
        // after starting reconnect(); cancelling first can strand that
        // reconnect inside its NonCancellable close cascade. This is
        // runCurrent-only: watchdog deadlines never advance.
        capture(runCurrent)
        vms.forEach { vm -> capture(vm.cancelOwnScopes) }
        roots.forEach { root -> capture(root.job::cancel) }

        roots.forEach { root ->
            capture {
                drainUntil(
                    description = "owned ${root.name} root",
                    remaining = { if (root.job.isCompleted) 0 else 1 },
                    cancel = root.job::cancel,
                )
                // The bounded pump above makes this non-blocking. Keeping the
                // actual cancelAndJoin call is the load-bearing proof that a
                // real-IO factory/lease root cannot survive the rule boundary.
                runBlocking {
                    withTimeout(timeoutMs) {
                        root.job.cancelAndJoin()
                    }
                }
            }
        }

        capture {
            drainUntil(
                description = "TmuxSessionViewModel-owned coroutine",
                remaining = { vms.sumOf { it.activeOwnScopeChildCount() } },
                cancel = { vms.forEach { it.cancelOwnScopes() } },
                debug = {
                    vms.mapIndexedNotNull { index, vm ->
                        vm.debugActiveChildren().takeIf(String::isNotBlank)?.let {
                            "VM[$index]\n$it"
                        }
                    }.joinToString(separator = "\n")
                },
            )
        }

        roots.clear()
        vms.clear()
        cleanupFailure?.let { throw it }
    }

    private fun drainUntil(
        description: String,
        remaining: () -> Int,
        cancel: () -> Unit,
        debug: () -> String = { "" },
    ) {
        // Issue #2017: the bounded loop + the deadline come from the ONE audited
        // settle-pump instead of another hand-rolled `System.nanoTime()` budget.
        // `cancel()` every pass is a load-bearing SIDE EFFECT (a completion handler
        // can re-spawn a child), and `runCurrent()` must NEVER become virtual-time
        // advancement here (watchdog deadlines must not move), so both are the
        // injected per-tick drain; the zero-children exit condition stays the
        // load-bearing assertion and a genuine leak still HARD-FAILS.
        val quiesced = drainMainLooperUntil(
            deadlineMs = timeoutMs,
            sleepMs = 1L,
            onTick = {
                cancel()
                runCurrent()
            },
        ) {
            remaining() == 0
        }
        if (!quiesced) {
            throw AssertionError(
                "Issue #1355: ${remaining()} $description child(ren) did not quiesce within " +
                    "${timeoutMs}ms while Main was installed; resetting Main would " +
                    "recreate the TestMainDispatcher:72 race" +
                    debug().takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty(),
            )
        }
    }

    private companion object {
        // Issue #2017: the ONE audited generous wall-clock budget, not a local copy.
        const val DEFAULT_TIMEOUT_MS = GENEROUS_SETTLE_DEADLINE_MS
    }
}

private fun Job.debugTree(indent: String = ""): String =
    buildString {
        append(indent)
        append(this@debugTree)
        children.filterNot(Job::isCompleted).forEach { child ->
            append('\n')
            append(child.debugTree("$indent  "))
        }
    }

internal fun MainDispatcherRule.tmuxSessionViewModelTeardown(
    timeoutMs: Long = GENEROUS_SETTLE_DEADLINE_MS,
): TmuxSessionViewModelRuleTeardown =
    TmuxSessionViewModelRuleTeardown(
        runCurrent = ::runCurrent,
        timeoutMs = timeoutMs,
    ).also { registry -> beforeResetMain(registry::drain) }
