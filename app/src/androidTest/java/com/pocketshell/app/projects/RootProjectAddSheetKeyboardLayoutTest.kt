package com.pocketshell.app.projects

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.waitForComposeLayoutStable
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #613 — the reported screen. Issue #1742 — the deterministic proof.
 *
 * The maintainer's feedback screenshot shows the root project picker
 * ("Search projects" + "Empty project" / "Clone git repo" quick actions +
 * a "workshops" suggestion) with the soft keyboard up: the filtered project
 * row is occluded by the keyboard. That screen is the [RootProjectAddSheet]
 * bottom sheet, NOT the [SessionTypePickerContent] that earlier rounds kept
 * fixing.
 *
 * This test mounts the REAL [RootProjectAddSheet] (the full `ModalBottomSheet`,
 * so the sheet's own height constraint + IME interaction match production) and
 * types through the real focused field.
 *
 * ## Why the fixture changed in #1742
 *
 * A `ModalBottomSheet` lives in its **own window**. The previous fixture asked
 * `WindowInsetsControllerCompat(activity.window, activity.decorView)` to show
 * the real IME and then polled *activity-decor* insets, while the focused
 * search field it was judging lived in the modal window. Both audited nightly
 * runs therefore failed at that real-IME precondition — before a single
 * character was typed and before any geometry was measured — with every show
 * request stuck at `PHASE_CLIENT_REQUEST_IME_SHOW`. That was fixture drift,
 * not a production layout regression.
 *
 * The durable shape (the merged #780/#1734/#1800 model) is:
 *
 * 1. Keep the production modal mounted and keep the real click + text input.
 * 2. Hard-require that no physical IME window is visible, so the deterministic
 *    boundary can never be silently mixed with a real keyboard.
 * 3. Dispatch a synthetic `Type.ime()` inset to **every** attached app-owned
 *    window root (activity decor + the modal root). Dispatching to the
 *    activity only leaves the modal's Compose `WindowInsets.ime` at zero —
 *    that is the exact root cause, and it is a one-line mutation away.
 * 4. Observe that inset from inside the modal's own Compose tree via a
 *    test-only semantics key, and require exact equality with a nonzero value.
 * 5. Keep every geometry assertion in the **owning modal Compose root**'s
 *    coordinate space, asserting containment (not `assertIsDisplayed()`), and
 *    require the production sheet layout to materially lift AND shrink the
 *    results viewport, so a published-but-ignored inset cannot pass.
 *
 * Mutation note (#1742): removing `imePadding()` from `RootProjectAddSheetContent`
 * changed nothing measurable here — Material3's `ModalBottomSheet` wraps its
 * window content in `Modifier.fillMaxSize().imePadding()` and so has already
 * CONSUMED the ime inset before the sheet content is composed. #1812 confirmed
 * that on API 33/34/35 (byte-identical geometry with and without the modifier)
 * and deleted it, which makes THIS proof strictly sharper: the lift it measures
 * can now only come from Material's own sheet handling, so a Material3 upgrade
 * that stopped consuming the ime inset turns this test red instead of being
 * masked by a duplicate content-level `imePadding()`.
 *
 * The assertion with proven bite for the #613 mechanism itself is the SHRINK
 * bound: replacing the bounded, shrinkable content column with a fixed
 * `requiredHeight` (the pre-#613 shape) drops the viewport response to 127px
 * against a 774px keyboard and turns this proof red.
 *
 * There is no `assumeTrue`/`assumeFalse` anywhere: an environment that cannot
 * reach the required state fails hard.
 */
@RunWith(AndroidJUnit4::class)
class RootProjectAddSheetKeyboardLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun matchedProjectStaysAboveKeyboardWhileTyping() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        // Many candidates so the results list is long enough that, on the old
        // layout, the matched row lands under the keyboard.
        val candidates = (1..20).map { i ->
            RootProjectCandidate(
                path = "/home/alexey/git/project-$i",
                label = "project-$i",
                source = RootProjectSource.Scanned,
            )
        } + RootProjectCandidate(
            path = "/home/alexey/git/workshops",
            label = "workshops",
            source = RootProjectSource.Scanned,
        )
        val root = FolderTreeRoot(
            path = "/home/alexey/git",
            label = "git",
            folders = emptyList(),
            isWatched = true,
            addSheetProjects = candidates,
        )
        val matchPath = "/home/alexey/git/workshops"
        val matchTag = rootProjectCandidateTestTag(matchPath)

        compose.setContent {
            PocketShellTheme {
                RootProjectAddSheet(
                    root = root,
                    onDismiss = {},
                    onStartSession = {},
                    onCreateEmptyProject = {},
                    onCloneGitProject = {},
                    modifier = Modifier.observeSheetIme(),
                )
            }
        }

        // Wait for the sheet content to be laid out.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(ROOT_PROJECT_ADD_SEARCH_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }

        try {
            compose.onNodeWithTag(ROOT_PROJECT_ADD_SEARCH_TAG).performClick()

            // Filter to the single "workshops" match — the maintainer's exact
            // scenario (type to filter, one folder matches).
            compose.onNodeWithTag(ROOT_PROJECT_ADD_SEARCH_TAG).performTextInput("worksh")
            compose.onNodeWithTag(ROOT_PROJECT_ADD_SEARCH_TAG)
                .assertTextContains("worksh", substring = true)
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(matchTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 1
            }
            assertTrue(
                "The exact workshops row must settle after real text input.",
                waitForComposeLayoutStable(compose, matchTag),
            )
            // `worksh` -> `workshops` must be the ONLY surviving candidate: no
            // other project row, and no "create folder" offer standing in for
            // the match.
            candidates
                .filterNot { it.path == matchPath }
                .forEach { candidate ->
                    compose.onAllNodesWithTag(
                        rootProjectCandidateTestTag(candidate.path),
                        useUnmergedTree = true,
                    ).assertCountEquals(0)
                }
            compose.onAllNodesWithTag(
                ROOT_PROJECT_ADD_CREATE_MATCH_TAG,
                useUnmergedTree = true,
            ).assertCountEquals(0)

            // The deterministic boundary is only meaningful when no physical
            // keyboard window is competing with it.
            hideRealImeAndAssertHidden(
                "Issue #1742 synthetic modal-root proof cannot start while a real IME is up.",
            )

            // Capture keyboard-down geometry only after filtering, so the
            // before/after viewport comparison owns the exact same content.
            val keyboardDownListBounds = applyKeyboardDownAndReadListBounds()
            val syntheticIme = applyKeyboardUpAndReadModalGeometry()
            assertTrue(
                "The filtered result must settle after the modal-root IME layout pass.",
                waitForComposeLayoutStable(compose, matchTag),
            )
            val keyboardTop =
                syntheticIme.modalRoot.boundsInRoot.bottom - syntheticIme.observedImeBottomPx

            // Measure + publish the layout response BEFORE the containment
            // assertions so every run — including a red one that stops at the
            // first containment failure — carries the geometry evidence.
            val keyboardUpListBounds = compose
                .onNodeWithTag(ROOT_PROJECT_ADD_LIST_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
            val liftPx = keyboardDownListBounds.bottom - keyboardUpListBounds.bottom
            val shrinkPx = keyboardDownListBounds.height - keyboardUpListBounds.height
            println(
                "ISSUE1742_LAYOUT_RESPONSE observedImeBottomPx=" +
                    "${syntheticIme.observedImeBottomPx} liftPx=$liftPx shrinkPx=$shrinkPx " +
                    "keyboardDownList=$keyboardDownListBounds " +
                    "keyboardUpList=$keyboardUpListBounds keyboardTopPx=$keyboardTop " +
                    "modalRoot=${syntheticIme.modalRoot.boundsInRoot} " +
                    "density=${compose.density.density}",
            )

            listOf(
                ROOT_PROJECT_ADD_SEARCH_TAG,
                ROOT_PROJECT_ADD_EMPTY_PROJECT_TAG,
                ROOT_PROJECT_ADD_CLONE_TAG,
                ROOT_PROJECT_ADD_ROOT_SESSION_TAG,
                ROOT_PROJECT_ADD_LIST_TAG,
                matchTag,
            ).forEach { tag ->
                assertFullyContainedAboveKeyboard(
                    tag = tag,
                    modalRoot = syntheticIme.modalRoot,
                    keyboardTopPx = keyboardTop,
                )
            }

            assertTrue(
                "The #613 keyboard-aware sheet layout must materially LIFT the results viewport; " +
                    "keyboardDown=$keyboardDownListBounds keyboardUp=$keyboardUpListBounds " +
                    "liftPx=$liftPx observedImeBottomPx=${syntheticIme.observedImeBottomPx}",
                liftPx >= syntheticIme.observedImeBottomPx * MINIMUM_LIFT_FRACTION,
            )
            assertTrue(
                "The #613 keyboard-aware sheet layout must materially SHRINK the results viewport; " +
                    "keyboardDown=$keyboardDownListBounds keyboardUp=$keyboardUpListBounds " +
                    "shrinkPx=$shrinkPx observedImeBottomPx=${syntheticIme.observedImeBottomPx}",
                shrinkPx >= syntheticIme.observedImeBottomPx * MINIMUM_SHRINK_FRACTION,
            )
        } finally {
            applySyntheticInsets(
                imeBottomPx = 0,
                requireModalRoot = false,
                requireNoPhysicalIme = false,
            )
            hideRealImeBestEffort()
            compose.waitForIdle()
        }
    }

    private data class SyntheticImeGeometry(
        val observedImeBottomPx: Int,
        val modalRoot: SemanticsNode,
    )

    private fun applyKeyboardDownAndReadListBounds(): Rect {
        repeat(INSET_DISPATCH_REPEATS) {
            applySyntheticInsets(imeBottomPx = 0, requireModalRoot = true)
            compose.waitForIdle()
        }
        val observedIme = sheetObservedImeBottomPx()
        assertTrue(
            "Keyboard-down baseline must be observed as exact zero inside the mounted " +
                "modal Compose root; observedImeBottomPx=$observedIme",
            observedIme == 0,
        )
        assertTrue(
            "The results viewport must settle before keyboard-down geometry is captured.",
            waitForComposeLayoutStable(compose, ROOT_PROJECT_ADD_LIST_TAG),
        )
        return compose.onNodeWithTag(ROOT_PROJECT_ADD_LIST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
    }

    private fun applyKeyboardUpAndReadModalGeometry(): SyntheticImeGeometry {
        val expectedImeBottomPx = syntheticImeBottomPx()
        repeat(INSET_DISPATCH_REPEATS) {
            applySyntheticInsets(
                imeBottomPx = expectedImeBottomPx,
                requireModalRoot = true,
            )
            compose.waitForIdle()
        }

        val sheetNode = compose
            .onNodeWithTag(ROOT_PROJECT_ADD_SHEET_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val observedImeBottomPx =
            sheetNode.config.getOrNull(ROOT_PROJECT_ADD_SHEET_IME_BOTTOM_PX) ?: -1
        assertTrue(
            "Synthetic ime() inset must be exact and nonzero in the mounted " +
                "RootProjectAddSheet modal Compose root; activity-only dispatch or a zero " +
                "mutation must fail here. observedImeBottomPx=$observedImeBottomPx " +
                "expectedImeBottomPx=$expectedImeBottomPx",
            expectedImeBottomPx > 0 && observedImeBottomPx == expectedImeBottomPx,
        )

        val modalRoot = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .single { it.root === sheetNode.root }
        assertTrue(
            "The owning modal Compose root must have nonzero geometry; " +
                "bounds=${modalRoot.boundsInRoot}",
            modalRoot.boundsInRoot.width > 0f && modalRoot.boundsInRoot.height > 0f,
        )
        println(
            "ISSUE1742_MODAL_GEOMETRY root=${modalRoot.boundsInRoot} " +
                "observedImeBottomPx=$observedImeBottomPx " +
                "rootIdentity=${System.identityHashCode(modalRoot.root)} " +
                "sheetRootIdentity=${System.identityHashCode(sheetNode.root)}",
        )
        return SyntheticImeGeometry(
            observedImeBottomPx = observedImeBottomPx,
            modalRoot = modalRoot,
        )
    }

    private fun assertFullyContainedAboveKeyboard(
        tag: String,
        modalRoot: SemanticsNode,
        keyboardTopPx: Float,
    ) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val rootBounds = modalRoot.boundsInRoot
        val slop = CONTAINMENT_SLOP_DP * compose.density.density
        assertTrue(
            "Node '$tag' must belong to the same modal Compose coordinate root.",
            node.root === modalRoot.root,
        )
        assertTrue(
            "Node '$tag' must have nonzero geometry. bounds=$bounds",
            bounds.width > 0f && bounds.height > 0f,
        )
        assertTrue(
            "Node '$tag' must be fully contained in its owning modal root. " +
                "node=$bounds root=$rootBounds",
            bounds.left >= rootBounds.left - slop &&
                bounds.top >= rootBounds.top - slop &&
                bounds.right <= rootBounds.right + slop &&
                bounds.bottom <= rootBounds.bottom + slop,
        )
        assertTrue(
            "Node '$tag' must stay fully above the keyboard boundary in the same modal " +
                "coordinate root. node=$bounds keyboardTopPx=$keyboardTopPx root=$rootBounds",
            bounds.bottom <= keyboardTopPx + slop,
        )
    }

    private fun Modifier.observeSheetIme(): Modifier = composed {
        val density = LocalDensity.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        semantics {
            this[ROOT_PROJECT_ADD_SHEET_IME_BOTTOM_PX] = imeBottomPx
        }
    }

    private fun sheetObservedImeBottomPx(): Int =
        compose.onNodeWithTag(ROOT_PROJECT_ADD_SHEET_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(ROOT_PROJECT_ADD_SHEET_IME_BOTTOM_PX)
            ?: -1

    private fun applySyntheticInsets(
        imeBottomPx: Int,
        requireModalRoot: Boolean,
        requireNoPhysicalIme: Boolean = true,
    ) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Issue #1742 modal-root IME proof requires API 29+ WindowInspector; " +
                "deviceApi=${Build.VERSION.SDK_INT}"
        }
        if (requireNoPhysicalIme) {
            assertNoPhysicalImeWindow(
                "Issue #1742 synthetic dispatch must never be mixed with a real IME window.",
            )
        }
        val statusBarTopPx = (SYNTHETIC_STATUS_BAR_HEIGHT_DP * displayDensity()).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(
                WindowInsetsCompat.Type.ime(),
                Insets.of(0, 0, 0, imeBottomPx),
            )
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottomPx > 0)
            .setInsets(
                WindowInsetsCompat.Type.navigationBars(),
                Insets.of(0, 0, 0, 0),
            )
            .setInsets(
                WindowInsetsCompat.Type.statusBars(),
                Insets.of(0, statusBarTopPx, 0, 0),
            )
            .setInsets(
                WindowInsetsCompat.Type.systemBars(),
                Insets.of(0, statusBarTopPx, 0, 0),
            )
            .build()
        compose.activityRule.scenario.onActivity { activity ->
            val roots = activeAppWindowRoots(activity)
            val activityDecor = activity.window.decorView
            val modalRoots = roots.filterNot { it === activityDecor }
            check(roots.any { it === activityDecor }) {
                "Active app roots must include the activity decor."
            }
            if (requireModalRoot) {
                check(modalRoots.isNotEmpty()) {
                    "Mounted RootProjectAddSheet modal root disappeared before inset dispatch."
                }
            }
            println(
                "ISSUE1742_SYNTHETIC_WINDOW_ROOTS count=${roots.size} " +
                    "modalCount=${modalRoots.size} imeBottomPx=$imeBottomPx " +
                    "roots=${roots.map { "${it.javaClass.name}:${it.width}x${it.height}" }}",
            )

            // ---- ROOT-CAUSE MUTATION ANCHOR (issue #1742) -------------------
            // Every attached app-owned window root gets the inset, because the
            // focused search field lives in the ModalBottomSheet's OWN window.
            // Replacing this line with a dispatch to `activityDecor` only
            // reproduces the original nightly root cause: the modal Compose
            // tree keeps observing ime=0 and the exact-inset oracle above goes
            // red before any geometry is judged.
            roots.forEach { root ->
                ViewCompat.dispatchApplyWindowInsets(root, insets)
            }
            // -----------------------------------------------------------------
        }
    }

    private fun activeAppWindowRoots(activity: ComponentActivity): List<View> =
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

    /**
     * Drives the real IME down and HARD-asserts it is gone from every signal we
     * have: the accessibility input-method window, every app root's real ime
     * inset, and the framework decor visibility flag. No skip — an emulator that
     * refuses to put the keyboard away fails this proof loudly instead of
     * silently measuring a mixed real/synthetic boundary.
     */
    private fun hideRealImeAndAssertHidden(message: String) {
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
        assertFalse(
            "$message Activity decor did not report IME visibility=false.",
            waitForInputMethodVisible(
                scenario = compose.activityRule.scenario,
                expected = false,
                timeoutMs = REAL_IME_TIMEOUT_MS,
            ),
        )
        assertNoPhysicalImeWindow(message)
    }

    private fun hideRealImeBestEffort() {
        runCatching {
            compose.activityRule.scenario.onActivity { activity ->
                val inputMethodManager =
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                activeAppWindowRoots(activity).forEach { root ->
                    ViewCompat.getWindowInsetsController(root)
                        ?.hide(WindowInsetsCompat.Type.ime())
                    root.windowToken?.let { token ->
                        inputMethodManager.hideSoftInputFromWindow(token, 0)
                    }
                }
                ViewCompat.getWindowInsetsController(activity.window.decorView)
                    ?.hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

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
        compose.activityRule.scenario.onActivity { activity ->
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
     * app roots, so it catches a green synthetic boundary drawn while a real
     * keyboard is still covering the sheet.
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
                        val rootPackage = runCatching {
                            window.root?.packageName?.toString()
                        }.getOrNull()
                        "package=$rootPackage active=${window.isActive} " +
                            "focused=${window.isFocused} bounds=" +
                            "${android.graphics.Rect().also(window::getBoundsInScreen)}"
                    }
            }

    private fun syntheticImeBottomPx(): Int =
        (SYNTHETIC_IME_HEIGHT_DP * displayDensity()).toInt()

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private companion object {
        const val SYNTHETIC_IME_HEIGHT_DP = 295f
        const val SYNTHETIC_STATUS_BAR_HEIGHT_DP = 52f

        /**
         * The results viewport must move up by essentially the whole keyboard
         * (the sheet is bottom-anchored, and Material3's own
         * `ModalBottomSheet` ime padding translates it by the full inset).
         * Half the inset is a generous floor that still goes red the moment the
         * #613 layout stops responding.
         */
        const val MINIMUM_LIFT_FRACTION = 0.5f

        /**
         * The viewport must also genuinely SHRINK, not merely translate — a
         * sheet that slides up without shrinking pushes its header off the top.
         * The shrink is bounded by the sheet's `heightIn(max = 640.dp)` cap, so
         * this floor is lower than the lift floor by construction while still
         * being zero for any layout that ignores the inset.
         */
        const val MINIMUM_SHRINK_FRACTION = 0.25f
        const val CONTAINMENT_SLOP_DP = 2f
        const val INSET_DISPATCH_REPEATS = 2
        const val REAL_IME_TIMEOUT_MS = 30_000L
        const val PHYSICAL_IME_ABSENCE_STABILITY_MS = 500L
        val ROOT_PROJECT_ADD_SHEET_IME_BOTTOM_PX =
            SemanticsPropertyKey<Int>("RootProjectAddSheetImeBottomPx")
    }
}
