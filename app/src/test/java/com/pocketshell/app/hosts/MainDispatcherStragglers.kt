package com.pocketshell.app.hosts

import kotlinx.coroutines.MainCoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Issue #2413 — post-test `Dispatchers.Main` ownership, with attribution.
 *
 * ## The failure this replaces
 *
 * A unit test that installs a test Main dispatcher and drives production code
 * which hops to a REAL dispatcher (`viewModelScope.launch { withContext(
 * Dispatchers.IO) { … } }`) can finish while that hop is still in flight. The
 * hop then completes on an IO worker and tries to resume on `Dispatchers.Main`
 * — which a plain `Dispatchers.resetMain()` has already reverted to the
 * *missing* platform dispatcher, because a non-Robolectric JVM unit test has no
 * Android main looper. `TestMainDispatcher.isDispatchNeeded` throws
 *
 * ```
 * java.lang.IllegalStateException: Dispatchers.Main was accessed when the
 * platform dispatcher was absent and the test dispatcher was unset.
 * ```
 *
 * on the IO worker. Nothing on that path belongs to the leaking test any more,
 * so the throw becomes an *uncaught coroutine exception*, which
 * `kotlinx-coroutines-test`'s process-global `ExceptionCollector` stores and
 * replays against whichever unrelated `runTest` enters next, as
 * `UncaughtExceptionsBeforeTest`. That is how #2413 reddened
 * `TreeRemoteSourceTest.upsertTree_buildsRequestAndReturnsTrueOnOk`, a pure
 * stubbed-stdout parse assertion with no concurrency at all, ~30 seconds after
 * the actual leak in `RepoBrowserViewModelTest`.
 *
 * ## Why taking the window over is the correct fix, not a mask
 *
 * `Dispatchers.Main` is *never* absent in production, nor under Robolectric;
 * the "missing Main" state is manufactured by the harness itself, in
 * `Dispatchers.resetMain()`. The `IllegalStateException` is therefore not a
 * product defect being swallowed — it is a harness artifact whose only effect
 * is to convert a test-hygiene leak into an unattributable failure of an
 * innocent, arbitrary sibling. [PostTestMainDispatcher] owns that window
 * instead, closing the cross-test blame channel structurally for every one of
 * the ~96 classes using [MainDispatcherRule], not just the one leak #2413
 * happened to surface. Under Robolectric there is a real main looper, so
 * [MainDispatcherRule] keeps using plain `Dispatchers.resetMain()` there and
 * this class is never involved.
 *
 * ## Recorded and dropped, never executed
 *
 * A straggler is recorded and then **dropped**. Dropping matches what already
 * happened on the unfixed path — the continuation never resumed there either,
 * it just threw on the way in — whereas *running* it lets a finished test's
 * coroutine mutate shared state while an unrelated test is executing. That is
 * measured, not assumed: an earlier revision of this class ran stragglers on a
 * daemon thread and turned 4 attributed reports into 16 extra failures across
 * `ForwardingControllerTest`, `WatchedFoldersViewModelTest`,
 * `ForwardingResumeSchedulerTest` and friends. Dropping keeps the blast radius
 * at zero.
 *
 * ## The leak is not hidden
 *
 * Every straggler is recorded with the test that owned Main when it escaped and
 * printed to stderr, and [failIfAnyRecorded] turns it into a hard failure at the
 * next [MainDispatcherRule] boundary, quoting the leaking suspension point. So a
 * leak fails loudly, naming its culprit, instead of reddening a random unrelated
 * class. A full `:app:testReleaseUnitTest` run produced zero stragglers outside
 * the two this issue's own regression test raises deliberately, so the guard
 * ships with no allowlist.
 */
internal object MainDispatcherStragglers {

    /** One escaped post-teardown `Dispatchers.Main` dispatch. */
    data class Straggler(
        /** JUnit display name of the test that owned Main when this escaped. */
        val owner: String,
        val coroutineContext: String,
        /**
         * The dropped task's `toString`. For a coroutine continuation this
         * names the production suspension point that leaked — e.g.
         * `Continuation at …RepoBrowserViewModel${'$'}refresh${'$'}1.invokeSuspend(
         * RepoBrowserViewModel.kt:269)` — which is the attribution that matters
         * when [owner] is only the window the straggler happened to land in.
         */
        val task: String,
    ) {
        override fun toString(): String =
            "leaked by '$owner': task=$task context=$coroutineContext"
    }

    private val lock = Any()
    private val recorded = mutableListOf<Straggler>()

    internal fun record(owner: String, context: CoroutineContext, task: Runnable) {
        val straggler = Straggler(
            owner = owner,
            coroutineContext = context.toString(),
            task = task.toString(),
        )
        synchronized(lock) { recorded += straggler }
        System.err.println("POCKETSHELL MAIN-DISPATCHER STRAGGLER (issue #2413): $straggler")
    }

    /** Removes and returns everything recorded so far. */
    fun drain(): List<Straggler> = synchronized(lock) {
        val snapshot = recorded.toList()
        recorded.clear()
        snapshot
    }

    /**
     * Hard-fails when any coroutine escaped a finished test onto Main.
     *
     * @param boundary human-readable description of where the check ran, so the
     *   report distinguishes "found on the way in" from "found on the way out".
     */
    fun failIfAnyRecorded(boundary: String) {
        val unexpected = drain()
        if (unexpected.isEmpty()) return
        throw AssertionError(
            buildString {
                append("issue #2413: ")
                append(unexpected.size)
                append(" coroutine(s) dispatched to Dispatchers.Main AFTER their test's ")
                append("MainDispatcherRule teardown, observed at ")
                append(boundary)
                append(".\n")
                append(
                    "This is a test-hygiene leak in the OWNING test named below, not in the " +
                        "test that is reporting it: work started under the test Main dispatcher " +
                        "hopped to a real dispatcher (typically withContext(Dispatchers.IO)) and " +
                        "was still in flight when the test ended. Await or cancel it before " +
                        "teardown (MainDispatcherRule.beforeResetMain), or inject a dispatcher " +
                        "confined to the test scheduler so the hop cannot outlive the test — see " +
                        "RepoBrowserViewModel.ioDispatcher for the worked example.\n",
                )
                unexpected.forEach { append("  - ").append(it).append('\n') }
            },
        )
    }
}

/**
 * The `Dispatchers.Main` [MainDispatcherRule] installs, in place of a bare
 * `Dispatchers.resetMain()`, once a looper-less JVM unit test has finished.
 *
 * Records every late dispatch against [owner] — the test that leaked it — and
 * drops it. See [MainDispatcherStragglers] for the full rationale.
 *
 * ## Why [immediate] still throws
 *
 * This dispatcher stays installed until the next [MainDispatcherRule] test calls
 * `Dispatchers.setMain`, so unrelated tests can run while it owns Main. Those
 * must not observe a *working* Main where the unfixed code gave them a missing
 * one: `androidx.lifecycle`'s `viewModelScope` reads `Dispatchers.Main.immediate`
 * and falls back to `EmptyCoroutineContext` (i.e. `Dispatchers.Default`) when it
 * throws, which is how every looper-less `ViewModel` unit test that does not use
 * [MainDispatcherRule] works at all. Measured: without this throw,
 * `CostsViewModelTest`'s `viewModelScope` silently rebound onto Main and its
 * `init` collector was recorded as a straggler instead of running.
 *
 * So [immediate] throws exactly like `MissingMainCoroutineDispatcher.immediate`
 * does, keeping *new* scopes byte-identical to the unfixed behaviour, while
 * continuations that captured `Dispatchers.Main` **before** teardown resume
 * through [dispatch] and get recorded instead of exploding. That is the one and
 * only behaviour this class changes.
 */
internal class PostTestMainDispatcher(private val owner: String) : MainCoroutineDispatcher() {

    override val immediate: MainCoroutineDispatcher
        get() = throw IllegalStateException(
            "issue #2413: Dispatchers.Main is unavailable between tests (owner '$owner'). " +
                "This mirrors Dispatchers.resetMain()'s missing platform dispatcher; a test " +
                "needing Main must install it via MainDispatcherRule.",
        )

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        MainDispatcherStragglers.record(owner, context, block)
    }

    override fun toString(): String = "PostTestMain[$owner]"
}
