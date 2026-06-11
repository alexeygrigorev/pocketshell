package com.pocketshell.app.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.waitForComposeLayoutStable
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #613 — the reported screen.
 *
 * The maintainer's dogfood screenshot shows the root project picker
 * ("Search projects" + "Empty project" / "Clone git repo" quick actions +
 * a "workshops" suggestion) with the soft keyboard up: the filtered project
 * row is occluded by the keyboard. That screen is the [RootProjectAddSheet]
 * bottom sheet, NOT the [SessionTypePickerContent] that earlier rounds kept
 * fixing.
 *
 * This test mounts the REAL [RootProjectAddSheet] (the full `ModalBottomSheet`,
 * so the sheet's own height constraint + IME interaction match production),
 * raises the REAL soft keyboard, types a filter so a single project matches,
 * and asserts the matched project row + the search field both stay ABOVE the
 * keyboard top.
 *
 * On the unfixed layout (a partially-expanded sheet whose content is a single
 * `verticalScroll` Column ending in a fixed-height results LazyColumn) the
 * matched row is pushed below the keyboard and this assertion fails — i.e. it
 * is a genuine regression guard. The fix (fully-expanded sheet + pinned header
 * + weighted, scrolling results LazyColumn under `imePadding`) keeps the match
 * visible above the IME and the search field on screen.
 */
@RunWith(AndroidJUnit4::class)
class RootProjectAddSheetKeyboardLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun matchedProjectStaysAboveKeyboardWhileTyping() {
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
            compose.activity.runOnUiThread {
                val window = compose.activity.window
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }

            val imeVisible = waitForInputMethodVisible(
                scenario = compose.activityRule.scenario,
                expected = true,
                timeoutMs = 30_000L,
            )
            assertTrue(
                "IME must be visible for the keyboard-occlusion regression test",
                imeVisible,
            )

            // Filter to the single "workshops" match — the maintainer's exact
            // scenario (type to filter, one folder matches).
            compose.onNodeWithTag(ROOT_PROJECT_ADD_SEARCH_TAG).performTextInput("worksh")
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(matchTag).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                "matched row should settle before the IME overlap check",
                waitForComposeLayoutStable(compose, matchTag),
            )
            compose.onNodeWithTag(matchTag).assertIsDisplayed()

            val imeBottomInset = imeBottomInsetPx()
            assertTrue(
                "IME must report a positive bottom inset for the overlap check",
                imeBottomInset > 0,
            )

            // The sheet renders as a full-screen overlay window, so node
            // `boundsInRoot` are screen-relative. The keyboard top in those same
            // screen coordinates is the window height minus the IME inset. This
            // is an ABSOLUTE screen line, independent of the scrollable content's
            // own measured bounds — so a matched row that is scrolled/clipped
            // below the keyboard (the bug on the partially-expanded sheet) is
            // genuinely caught, instead of passing because the scrollable
            // content's bounds happen to extend behind the keyboard.
            val windowHeightPx = windowHeightPx()
            val keyboardTop = windowHeightPx - imeBottomInset

            val matchBounds = compose.onNodeWithTag(matchTag)
                .fetchSemanticsNode()
                .boundsInRoot
            val searchBounds = compose.onNodeWithTag(ROOT_PROJECT_ADD_SEARCH_TAG)
                .fetchSemanticsNode()
                .boundsInRoot

            assertTrue(
                "search field must stay visible above the keyboard while typing " +
                    "(search bottom=${searchBounds.bottom}, keyboard top=$keyboardTop)",
                searchBounds.bottom <= keyboardTop + 0.5f,
            )
            assertTrue(
                "matched project row must stay above the keyboard while typing " +
                    "(match bottom=${matchBounds.bottom}, keyboard top=$keyboardTop)",
                matchBounds.bottom <= keyboardTop + 0.5f,
            )

            // Optional hold so a reviewer/implementer can grab an external
            // `adb exec-out screencap` of the exact keyboard-up frame on
            // emulators where UiAutomation.takeScreenshot() returns null
            // (SwiftShader / headless). Gated by an instrumentation arg so CI
            // never waits:
            //   -Pandroid.testInstrumentationRunnerArguments.issue613KeyboardHoldMs=12000
            val holdMs = InstrumentationRegistry.getArguments()
                .getString("issue613KeyboardHoldMs")?.toLongOrNull() ?: 0L
            if (holdMs > 0L) {
                android.os.SystemClock.sleep(holdMs)
            }
        } finally {
            compose.activity.runOnUiThread {
                WindowInsetsControllerCompat(
                    compose.activity.window,
                    compose.activity.window.decorView,
                ).hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun imeBottomInsetPx(): Int {
        var bottom = 0
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            bottom = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.ime())
                ?.bottom
                ?: 0
        }
        return bottom
    }

    /** Full window (decor view) height in px — the screen-coordinate baseline. */
    private fun windowHeightPx(): Float {
        var height = 0
        compose.activityRule.scenario.onActivity { activity ->
            height = activity.window.decorView.height
        }
        return height.toFloat()
    }
}
