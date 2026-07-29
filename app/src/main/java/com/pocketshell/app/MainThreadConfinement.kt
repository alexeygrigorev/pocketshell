package com.pocketshell.app

import android.os.Looper

/**
 * Thrown when a Main-confined view-model entry point is entered from a thread
 * that is not the Android main thread (issue #1829).
 *
 * An [IllegalStateException] rather than a bespoke hierarchy: an off-Main call
 * into a Main-confined reducer is a programming error at the CALL SITE, not a
 * recoverable runtime condition. Fix the caller (dispatch to Main); never
 * catch this.
 */
internal class MainThreadConfinementViolation(
    message: String,
) : IllegalStateException(message)

/**
 * Issue #1829 — runtime enforcement of PocketShell's Main-thread confinement
 * contract for view-model entry points.
 *
 * ## Why a runtime throw and not something cheaper
 *
 * The per-screen view models are Main-confined **by construction**:
 * `viewModelScope` is `Dispatchers.Main.immediate`, their `bind` / emit entry
 * points are non-suspend straight-line code, and their bookkeeping is plain,
 * non-`@Volatile` state (`emitGeneration`, `bound`, `params`, `lastRequest`,
 * `hostId`, …) mutated with **check-then-act** sequences. On Main such a
 * sequence — e.g. `FolderListViewModel.emitReady(synchronous = true)`'s
 * `++emitGeneration` → `HostTreeModel.buildProjection` → re-check — is atomic,
 * because there is no suspension point and nothing else can run on Main in
 * between. Off Main it is not atomic at all.
 *
 * Until this guard existed, that confinement was enforced by **nothing**: no
 * annotation, no assertion, no memory barrier. #1823 measured the consequence
 * on a real device — an off-Main `bind` loses its synchronous cold-start seed
 * to a concurrent Main-dispatched `emitReady` in **23–25%** of binds (10/40 and
 * 28/120; on Main: 0/120), so `_state` stays `Loading`. That is the
 * user-visible #867 / #1109 rebuild flash, arriving **silently** — no crash, no
 * log, just an occasional wrong first frame. A rare wrong render is far harder
 * to diagnose than an immediate, unmissable throw, so the contract fails loudly
 * at the seam instead.
 *
 * ## Alternatives considered and deliberately rejected
 *
 *  - **`@MainThread` alone.** Lint-only. It does not flag an instrumentation
 *    thread calling `bind`, which is precisely where every real off-Main caller
 *    came from (nine `androidTest` sites at the time of writing). It would buy
 *    the appearance of enforcement without the substance.
 *  - **`@Volatile` on the generation counter.** The defect is check-then-act,
 *    not visibility. Volatile narrows the window without closing the contract,
 *    and a narrower race is *harder* to diagnose, not safer.
 *
 * ## The one place this is a no-op, and why that is safe
 *
 * Returns without checking when there is no main looper at all. That happens
 * only under an `android.os.Looper` **stub** — a plain, non-Robolectric JVM
 * unit test running with the app module's
 * `testOptions.unitTests.isReturnDefaultValues = true`, where
 * `Looper.getMainLooper()` returns `null` and there is no thread identity to
 * compare against. Production and Robolectric always have a real main looper,
 * so the guard is live on every path that can reach a user. The regression
 * suite (`Issue1829MainThreadConfinementTest`) is a Robolectric class for
 * exactly that reason: it exercises the real comparison, not the stub.
 *
 * @param entryPoint the `Class.method` label reported in the failure message.
 */
internal fun requireMainThread(entryPoint: String) {
    val mainLooper = Looper.getMainLooper() ?: return
    if (Looper.myLooper() === mainLooper) return
    throw MainThreadConfinementViolation(
        "issue #1829: $entryPoint is Main-confined but was called from thread " +
            "'${Thread.currentThread().name}'. It mutates plain non-@Volatile " +
            "view-model state with check-then-act sequences that are atomic only " +
            "on Main; off Main they silently drop state updates (the #867/#1109 " +
            "Loading flash). Dispatch to Main first — a Compose LaunchedEffect, " +
            "viewModelScope, or Instrumentation.runOnMainSync in a test.",
    )
}
