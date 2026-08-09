package com.pocketshell.app.insets

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/**
 * Issue #1821 — the keyboard-geometry oracle the nested-`imePadding()` sites did
 * not have.
 *
 * #1812 proved that a content-level `Modifier.imePadding()` inside a Material3
 * `ModalBottomSheet` is a no-op (the sheet's own
 * `Box(Modifier.fillMaxSize().imePadding())` at `ModalBottomSheet.kt:169` has
 * already CONSUMED the ime inset), and deleted the one site that had a
 * gate-wired oracle to measure against (`RootProjectAddSheet`, #1742). The
 * remaining ten sites had **no oracle at all**, which is why #1812 listed them
 * rather than sweeping them: an unmeasured deletion is exactly the
 * confident-argument-without-measurement shape this repo's process exists to
 * catch.
 *
 * This is the shared harness those sites needed. It is the #1742 / #780 model
 * distilled:
 *
 *  1. Hard-require that **no physical IME window** is visible, so a synthetic
 *     boundary can never be silently mixed with a real keyboard.
 *  2. Dispatch a synthetic `Type.ime()` inset to **every attached app-owned
 *     window root** — activity decor *and* any `ModalBottomSheet` window.
 *     Dispatching to the activity alone leaves a modal's Compose
 *     `WindowInsets.ime` at zero, which is a one-line mutation away from a
 *     vacuous green.
 *  3. Read geometry as `boundsInRoot` in the **owning** Compose root's
 *     coordinate space, and assert **containment** (never `assertIsDisplayed()`,
 *     which is satisfied by layout participation, not viewport containment).
 *
 * There is no `assumeTrue` / `assumeFalse` anywhere: an environment that cannot
 * reach the required state fails hard (F3).
 */
internal class SyntheticImeStage(
    private val compose: AndroidComposeTestRule<
        ActivityScenarioRule<ComponentActivity>,
        ComponentActivity,
        >,
) {

    /** The synthetic keyboard height, in px on the device under test. */
    val imeBottomPx: Int
        get() = (SYNTHETIC_IME_HEIGHT_DP * displayDensity()).toInt()

    /**
     * The synthetic navigation-bar height, in px on the device under test. It is
     * dispatched in BOTH keyboard states (see [dispatch]), so it is the boundary
     * that gives `navigationBarsPadding()` bite in the keyboard-DOWN state —
     * where the ime inset is zero and therefore cannot subsume it.
     */
    val navBarBottomPx: Int
        get() = (SYNTHETIC_NAV_BAR_HEIGHT_DP * displayDensity()).toInt()

    val density: Float
        get() = compose.density.density

    private val scenario: ActivityScenario<ComponentActivity>
        get() = compose.activityRule.scenario

    /**
     * Opts the ACTIVITY window out of decor inset fitting, and hard-requires the
     * API level [activeAppWindowRoots] needs.
     *
     * ## What this call is actually for — measured, not argued
     *
     * The previous version of this comment asserted a counterfactual ("without
     * it the decor consumes them and every measurement below would read zero").
     * The #1821 reviewer falsified it in one run on API 35, so it was re-derived
     * by deleting the call and running the whole suite on two API levels
     * (#1821 round 3, runs A/B/C/D):
     *
     * | device                  | this call | in-sheet (10) | standalone (5)  |
     * |-------------------------|-----------|---------------|-----------------|
     * | API 35 `test-2`         | present   | PASS          | PASS liftPx=648 |
     * | API 35 `test-2`         | DELETED   | PASS          | PASS liftPx=648 |
     * | API 34 `ps-api34-i1197` | present   | PASS          | PASS liftPx=648 |
     * | API 34 `ps-api34-i1197` | DELETED   | PASS          | FAIL liftPx=0.0 |
     *
     * So it is load-bearing for exactly ONE half, on exactly SOME devices:
     *
     *  - **Standalone content (the activity's own window) on API <= 34 — LOAD
     *    BEARING.** With decor fitting left on, the content view consumes the
     *    dispatched insets before Compose reads them: all 5 standalone cases
     *    failed at `liftPx=0.0` on API 34 with this call deleted (run D).
     *  - **API 35 with `targetSdk = 35` — INERT.** Android 15 enforces
     *    edge-to-edge for apps targeting 35, so the window is already opted out
     *    and deleting the call changed nothing: 15/15 green with all 15 geometry
     *    lines byte-identical to the run with it (run B vs run A).
     *  - **In-sheet content — never depends on it, at any API level.** A
     *    `ModalBottomSheet` draws in its OWN window, which the activity's decor
     *    setting does not touch: all 10 in-sheet cases stayed green in run D.
     *
     * It is kept, rather than hard-cut as a no-op, because this class admits
     * every device from API 29 up (the [check] below) and 29..34 is precisely
     * the range where run D shows it has bite. It is NOT kept "just in case".
     *
     * The [check] is a separate, independent precondition: [activeAppWindowRoots]
     * reads `WindowInspector.getGlobalWindowViews()`, which the platform added in
     * API 29 (`api-versions.xml`: `<class name="android/view/inspector/
     * WindowInspector" since="29">`).
     */
    fun startEdgeToEdge() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Issue #1821 synthetic-inset oracle requires API 29+ WindowInspector; " +
                "deviceApi=${Build.VERSION.SDK_INT}"
        }
        scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
    }

    /**
     * Drives the real IME down and HARD-asserts it is gone, in three steps that
     * are exactly what the body does — two independent signals plus a stability
     * re-check of the first:
     *
     *  1. no accessibility `TYPE_INPUT_METHOD` window is visible;
     *  2. no app window root reports a visible positive REAL ime inset;
     *  3. signal 1 stays empty for [PHYSICAL_IME_ABSENCE_STABILITY_MS] (a
     *     keyboard mid-dismissal must not read as "already gone").
     *
     * No skip — an emulator that refuses to put the keyboard away fails loudly
     * instead of silently measuring a mixed real/synthetic boundary.
     *
     * (Mechanism, not a measured claim: no #1821 run has driven signal 1 to a
     * POSITIVE, because none of these tests raises the real keyboard. What is
     * structural, and checkable by reading, is that it cannot self-skip — every
     * step is an `assertTrue`, never an `assumeTrue`.)
     */
    fun hideRealImeAndAssertHidden(message: String) {
        hideRealImeBestEffort()
        assertTrue(
            "$message Physical input-method window remained visible: " +
                visiblePhysicalImeWindows(),
            waitForWallClock(REAL_IME_TIMEOUT_MS) { visiblePhysicalImeWindows().isEmpty() },
        )
        assertTrue(
            "$message An app root still reports a visible positive real-IME inset.",
            waitForWallClock(REAL_IME_TIMEOUT_MS) { !readAnyAppRootImeVisible() },
        )
        assertNoPhysicalImeWindow(message)
    }

    fun hideRealImeBestEffort() {
        runCatching {
            scenario.onActivity { activity ->
                val inputMethodManager =
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                activeAppWindowRoots(activity).forEach { root ->
                    ViewCompat.getWindowInsetsController(root)
                        ?.hide(WindowInsetsCompat.Type.ime())
                    root.windowToken?.let { token ->
                        inputMethodManager.hideSoftInputFromWindow(token, 0)
                    }
                }
            }
        }
    }

    /**
     * Dispatches the synthetic inset set to every attached app-owned window
     * root and settles Compose, [INSET_DISPATCH_REPEATS] times.
     *
     * The repeat is unproven race-hardening, NOT a measured necessity, and this
     * comment says so because #1821 round 3 measured it: dropping
     * [INSET_DISPATCH_REPEATS] to `1` left all 15 cases green with all 15
     * geometry lines byte-identical (run G, API 35). The hypothesis it guards —
     * a `ModalBottomSheet` attaching its window between the first dispatch and
     * the first layout pass — is one a single green run cannot rule out, so a
     * second cheap dispatch is kept; what is NOT claimed is that anything here
     * has ever needed it.
     *
     * @param imeBottomPx the synthetic keyboard height (0 for keyboard-down).
     * @param requireExtraWindowRoot when `true`, hard-fails unless at least one
     *   non-decor app window root (i.e. the mounted modal) is attached. This is
     *   a precondition check on the FIXTURE, and it is NOT the mutation anchor —
     *   the anchor is the `roots.forEach` dispatch below, whose narrowing to the
     *   activity decor is what has been driven red (see the block comment
     *   there). Nothing has mutated this guard itself; it is green because the
     *   sheet window is present, and its bite would only show if a sheet
     *   silently stopped owning a window.
     */
    fun dispatch(imeBottomPx: Int, requireExtraWindowRoot: Boolean) {
        assertNoPhysicalImeWindow(
            "Issue #1821 synthetic dispatch must never be mixed with a real IME window.",
        )
        val statusBarTopPx = (SYNTHETIC_STATUS_BAR_HEIGHT_DP * displayDensity()).toInt()
        // Issue #1821 gives the NAV-BAR inset bite too. #1742's oracle dispatched
        // `navigationBars = Insets.of(0,0,0,0)`, which is exactly why #1812 could
        // say nothing about the sibling `navigationBarsPadding()` on these lines:
        // a zero inset cannot distinguish "redundant" from "load-bearing". A
        // nonzero one can, so the same before/after mutation that classifies
        // `imePadding()` also classifies `navigationBarsPadding()`.
        val navBarBottomPx = this.navBarBottomPx
        // Issue #1622: there is deliberately NO `setInsets(Type.systemBars(), …)`
        // call here, and adding one back is a bug. `systemBars()` is a COMPOUND
        // mask and `Builder.setInsets` copies the value into every constituent
        // slot, so this builder used to end by overwriting `navigationBars` with
        // `(0, 52dp, 0, 48dp)` — a navigation bar with a TOP edge, which no device
        // reports. `SheetContent`'s `navigationBarsPadding()` pads every edge of
        // that inset, so the phantom became a real 52 dp dead band inside the
        // composer the moment #1622 stopped an ancestor consuming the top inset:
        // a fixture defect presenting exactly as a product defect.
        //
        // The compound value is not lost by deleting the call —
        // `getInsets(systemBars())` returns the union of the three constituents
        // that ARE set below. [dispatchSyntheticWindowInsets] hard-fails if the
        // call is ever re-added; see its KDoc for the full mechanism and the
        // measured 136 px blast radius.
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottomPx > 0)
            .setInsets(
                WindowInsetsCompat.Type.navigationBars(),
                Insets.of(0, 0, 0, navBarBottomPx),
            )
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarTopPx, 0, 0))
            .build()
        repeat(INSET_DISPATCH_REPEATS) {
            scenario.onActivity { activity ->
                val roots = activeAppWindowRoots(activity)
                val activityDecor = activity.window.decorView
                check(roots.any { it === activityDecor }) {
                    "Active app roots must include the activity decor."
                }
                if (requireExtraWindowRoot) {
                    check(roots.any { it !== activityDecor }) {
                        "The mounted ModalBottomSheet window root disappeared before " +
                            "inset dispatch; the sheet must own its own window for this " +
                            "measurement to mean anything."
                    }
                }
                // ---- ROOT-CAUSE MUTATION ANCHOR (issue #1742/#1821) ----------
                // EVERY attached app-owned window root gets the inset, because a
                // ModalBottomSheet's content lives in its OWN window. Narrowing
                // this to `activityDecor` leaves the modal Compose tree at ime=0
                // and the lift assertions go red. Not a guess — mutation-proven
                // in #1821 round 2 (run R2E): with this line reduced to the
                // decor, all 10 `NestedImePaddingInSheetGeometryTest` cases
                // failed at `liftPx=0.0`, while the 5
                // `StandaloneContentImePaddingLivenessTest` cases stayed green
                // (their content lives in the activity's own window). That is
                // the non-vacuity guarantee for this whole oracle.
                roots.forEach { root -> dispatchSyntheticWindowInsets(root, insets) }
                // ---------------------------------------------------------------
            }
            compose.waitForIdle()
        }
    }

    /** Bounds of the node tagged [tag], in its own Compose root's space. */
    fun boundsOf(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /**
     * Waits until the node tagged [tag] exists AND its rect has been unchanged
     * for [stableWindowMs].
     *
     * The load-bearing half is the WAIT-UNTIL-IT-EXISTS half plus the hard
     * `false`-on-timeout return, which every caller asserts on. The stability
     * WINDOW is unproven hardening, and the original text here overstated it
     * ("`waitForIdle()` alone can return mid-flight ... a geometry sample taken
     * then is meaningless"). Measured in #1821 round 3: collapsing
     * [LAYOUT_STABLE_WINDOW_MS] to `0` — i.e. sampling the first rect that reads
     * back twice, with no settling — left all 15 cases green with all 15
     * geometry lines byte-identical (run H, API 35). On that device the
     * `waitForIdle()` inside `fetchSemanticsNodes` already covers the sheet's
     * slide-in, so the window is kept as cheap insurance for a slower emulator,
     * not because any run here has needed it.
     *
     * Returns `false` on timeout; every caller treats that as a HARD failure.
     */
    fun awaitStable(
        tag: String,
        stableWindowMs: Long = LAYOUT_STABLE_WINDOW_MS,
        timeoutMs: Long = LAYOUT_STABLE_TIMEOUT_MS,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: Rect? = readRectOrNull(tag)
        var lastChangedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            val current = readRectOrNull(tag)
            when {
                current == null -> {
                    last = null
                    lastChangedAt = now
                }
                current != last -> {
                    last = current
                    lastChangedAt = now
                }
                now - lastChangedAt >= stableWindowMs -> return true
            }
            SystemClock.sleep(50)
        }
        return false
    }

    private fun readRectOrNull(tag: String): Rect? = try {
        val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        if (nodes.isEmpty()) null else nodes.first().boundsInRoot
    } catch (_: AssertionError) {
        null
    } catch (_: ComposeNotIdleException) {
        // A busy tree is the same "rect unknown right now" condition as an
        // absent node, so the poll retries rather than failing.
        //
        // This catch is not redundant with the two below, and the comment that
        // used to sit here ("Compose's ComposeNotIdleException is not visible
        // from here") was wrong twice over (#1821 round 3): the type IS public
        // and importable, and it extends `java.lang.Exception` DIRECTLY —
        // `public final class androidx.compose.ui.test.junit4.android.
        // ComposeNotIdleException extends java.lang.Exception` — so neither
        // `IllegalStateException` nor `RuntimeException` ever caught it. The
        // false comment was hiding a real hole in this poll.
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /**
     * The Compose root that OWNS the node tagged [tag]. For a `ModalBottomSheet`
     * this is the modal's own window root, not the activity's — mixing the two
     * coordinate spaces is how a containment assertion silently stops meaning
     * anything.
     */
    fun owningRootOf(tag: String): SemanticsNode {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .single { it.root === node.root }
        assertTrue(
            "The owning Compose root must have nonzero geometry; bounds=${root.boundsInRoot}",
            root.boundsInRoot.width > 0f && root.boundsInRoot.height > 0f,
        )
        return root
    }

    /**
     * Containment assertion (F2/F3): the node tagged [tag] must lie fully inside
     * [root] AND fully above [keyboardTopPx], both in [root]'s coordinate space.
     *
     * This is deliberately NOT `assertIsDisplayed()`: a control shoved under the
     * keyboard still reports "displayed".
     *
     * Read it in the keyboard-UP state: that is the state whose boundary this is.
     */
    fun assertFullyAboveKeyboard(tag: String, root: SemanticsNode, keyboardTopPx: Float) =
        assertFullyAboveBoundary(
            tag = tag,
            root = root,
            boundaryTopPx = keyboardTopPx,
            boundaryName = "keyboard",
        )

    /**
     * The keyboard-DOWN sibling of [assertFullyAboveKeyboard]: the node tagged
     * [tag] must stay fully above the top edge of the synthetic navigation bar.
     *
     * This exists because the keyboard-up assertion **structurally cannot see**
     * a missing `navigationBarsPadding()`. With the keyboard up the ime inset
     * (295 dp) already subsumes the nav bar (48 dp), so dropping the nav padding
     * only *increases* the measured lift and every keyboard-up assertion stays
     * green while the bottom control sits 126 px under the nav bar. That exact
     * hole was found by the #1821 reviewer, who removed `navigationBarsPadding()`
     * from a kept site and got 15/15 green. Only the keyboard-down state, where
     * the ime inset is zero, exposes it.
     *
     * Call it while the keyboard-DOWN inset set is the one currently dispatched.
     */
    fun assertFullyAboveNavigationBar(tag: String, root: SemanticsNode, navBarTopPx: Float) =
        assertFullyAboveBoundary(
            tag = tag,
            root = root,
            boundaryTopPx = navBarTopPx,
            boundaryName = "navigation bar",
        )

    private fun assertFullyAboveBoundary(
        tag: String,
        root: SemanticsNode,
        boundaryTopPx: Float,
        boundaryName: String,
    ) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val rootBounds = root.boundsInRoot
        val slop = CONTAINMENT_SLOP_DP * density
        assertTrue(
            "Node '$tag' must belong to the same Compose coordinate root as the " +
                "root it is judged against.",
            node.root === root.root,
        )
        assertTrue(
            "Node '$tag' must have nonzero geometry. bounds=$bounds",
            bounds.width > 0f && bounds.height > 0f,
        )
        assertTrue(
            "Node '$tag' must be fully contained in its owning root. " +
                "node=$bounds root=$rootBounds",
            bounds.left >= rootBounds.left - slop &&
                bounds.top >= rootBounds.top - slop &&
                bounds.right <= rootBounds.right + slop &&
                bounds.bottom <= rootBounds.bottom + slop,
        )
        assertTrue(
            "Node '$tag' must stay fully ABOVE the $boundaryName boundary. " +
                "node=$bounds ${boundaryName.replace(' ', '_')}TopPx=$boundaryTopPx " +
                "root=$rootBounds",
            bounds.bottom <= boundaryTopPx + slop,
        )
    }

    fun activeAppWindowRoots(activity: ComponentActivity): List<View> =
        WindowInspector.getGlobalWindowViews()
            .asSequence()
            .map { it.rootView }
            .distinctBy { System.identityHashCode(it) }
            .filter { root ->
                root.isAttachedToWindow &&
                    root.context.applicationContext.packageName ==
                    activity.applicationContext.packageName
            }
            .toList()

    private fun assertNoPhysicalImeWindow(message: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + PHYSICAL_IME_ABSENCE_STABILITY_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val windows = visiblePhysicalImeWindows()
            assertTrue("$message visibleImeWindows=$windows", windows.isEmpty())
            SystemClock.sleep(50)
        }
    }

    private fun waitForWallClock(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return true
            SystemClock.sleep(50)
        }
        return predicate()
    }

    private fun readAnyAppRootImeVisible(): Boolean {
        var visible = false
        scenario.onActivity { activity ->
            visible = activeAppWindowRoots(activity).any { root ->
                val insets = ViewCompat.getRootWindowInsets(root)
                insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
            }
        }
        return visible
    }

    /**
     * Accessibility exposes the physical IME as a distinct
     * [AccessibilityWindowInfo.TYPE_INPUT_METHOD] window. That signal is
     * independent of the synthetic [WindowInsetsCompat] object dispatched to the
     * app roots, so it can catch a green synthetic boundary drawn while a real
     * keyboard is still covering the surface.
     *
     * (Mechanism, not a measured claim: the independence is structural — this
     * reads `uiAutomation.windows`, which the dispatched `WindowInsetsCompat`
     * cannot influence — but no #1821 run has observed a non-empty result,
     * because no test here raises the real keyboard.)
     */
    private fun visiblePhysicalImeWindows(): List<String> =
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .let { automation ->
                val serviceInfo = automation.serviceInfo
                if (
                    serviceInfo.flags and
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0
                ) {
                    serviceInfo.flags =
                        serviceInfo.flags or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    automation.serviceInfo = serviceInfo
                }
                automation.windows
                    .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .map { window ->
                        "active=${window.isActive} focused=${window.isFocused} bounds=" +
                            "${android.graphics.Rect().also(window::getBoundsInScreen)}"
                    }
            }

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    companion object {
        const val SYNTHETIC_IME_HEIGHT_DP = 295f
        const val SYNTHETIC_STATUS_BAR_HEIGHT_DP = 52f

        /**
         * A deliberately NONZERO gesture-nav bar, so `navigationBarsPadding()`
         * has bite in the same measurement (see [dispatch]).
         */
        const val SYNTHETIC_NAV_BAR_HEIGHT_DP = 48f
        const val CONTAINMENT_SLOP_DP = 2f
        const val INSET_DISPATCH_REPEATS = 2
        const val REAL_IME_TIMEOUT_MS = 30_000L
        const val PHYSICAL_IME_ABSENCE_STABILITY_MS = 300L
        const val LAYOUT_STABLE_WINDOW_MS = 250L

        /**
         * Generous "this is hung, not slow" ceiling. A CI swiftshader emulator
         * can stretch a sheet slide-in plus an inset relayout well past a
         * healthy device's ~500 ms.
         */
        const val LAYOUT_STABLE_TIMEOUT_MS = 10_000L

        /**
         * A bottom-anchored surface must move up by essentially the whole
         * keyboard. Half the inset is a generous floor that is still zero for
         * any layout that ignores the inset entirely.
         */
        const val MINIMUM_LIFT_FRACTION = 0.5f
    }
}
