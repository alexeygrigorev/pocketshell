package com.pocketshell.app.projects

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #947 — UI gate (#641 / #567 / #657 / G9) for the host-version-mismatch
 * banner's one-tap **Update** button.
 *
 * The maintainer asked for an Update button on the FolderList host-version banner
 * (the arrow in the issue screenshot). The reviewer's blocker: the JVM render is
 * only the fast-first check — the acceptance for a maintainer-reported UI control
 * is a connected androidTest of the REAL production composable with viewport
 * CONTAINMENT (the #780 real-component model), proving the Update + Dismiss
 * controls are present, reachable, and NOT clipped in every state.
 *
 * This composes the PRODUCTION [CliVersionMismatchBanner] (the exact composable
 * the FolderList screen renders — `internal` for this test, no proxy/stand-in)
 * pinned to the bottom edge of a full-screen [Box] (its real placement on the
 * session-tree screen), in each of its three states:
 *
 *  - **Idle** — Update + Dismiss both present, ENABLED, clickable, and FULLY
 *    within the window root (`assertNodeFullyWithinRoot`, not a bare
 *    `assertIsDisplayed` — a control pushed off the right edge by the long
 *    version message still reports "displayed").
 *  - **Running** — the spinner is shown and contained; Update/Dismiss are
 *    replaced (no double-run), so the spinner stands in for the in-flight action.
 *  - **Failure** — the error line + the **Retry** + **Dismiss** controls are
 *    present and contained; no stuck spinner.
 *
 * Each state writes a full-device screenshot for the status comment (the
 * maintainer's arrow-target acceptance: Update next to Dismiss). NO Docker
 * fixture, NO SSH/tmux, NO self-skip — a pure Compose UI gate that runs per-push.
 */
@RunWith(AndroidJUnit4::class)
class CliVersionMismatchBannerUpdateButtonTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val message =
        "This host's pocketshell is 0.4.14; the app expects 0.4.16. " +
            "Update it on the host:\nuv tool install --upgrade " +
            "--exclude-newer-package pocketshell=2099-12-31 pocketshell\n" +
            "(or: pipx upgrade pocketshell / pip install -U pocketshell)"

    @Test
    fun idleState_updateAndDismissPresentReachableNotClipped() {
        setBanner(FolderListViewModel.CliVersionUpdateState.Idle)

        // Both controls present + clickable (reachability).
        compose.onNodeWithTag(FOLDER_LIST_CLI_VERSION_UPDATE_TAG)
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        compose.onNodeWithTag(FOLDER_LIST_CLI_VERSION_DISMISS_TAG)
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction()

        // CONTAINMENT (#657/F1): neither button is pushed off any edge by the
        // long multi-line version message — the #641-class occlusion check that a
        // bare assertIsDisplayed would NOT catch.
        compose.assertNodeFullyWithinRoot(FOLDER_LIST_CLI_VERSION_UPDATE_TAG)
        compose.assertNodeFullyWithinRoot(FOLDER_LIST_CLI_VERSION_DISMISS_TAG)
        // The whole banner is on-screen too.
        compose.assertNodeFullyWithinRoot(FOLDER_LIST_CLI_VERSION_BANNER_TAG)

        capture("issue-947-banner-idle-update-dismiss.png")
    }

    @Test
    fun runningState_spinnerShownAndContained_noButtons() {
        setBanner(FolderListViewModel.CliVersionUpdateState.Running)

        compose.onNodeWithTag(FOLDER_LIST_CLI_VERSION_UPDATE_SPINNER_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(FOLDER_LIST_CLI_VERSION_UPDATE_SPINNER_TAG)
        compose.assertNodeFullyWithinRoot(FOLDER_LIST_CLI_VERSION_BANNER_TAG)
        // While running the Update button is replaced by the spinner so a second
        // tap cannot double-run.
        compose.onNodeWithTag(FOLDER_LIST_CLI_VERSION_UPDATE_TAG).assertDoesNotExist()

        capture("issue-947-banner-running-spinner.png")
    }

    @Test
    fun failureState_errorAndRetryDismissPresentReachableNotClipped() {
        setBanner(
            FolderListViewModel.CliVersionUpdateState.Failure(
                "Update failed (exit 1):\nerror: network unreachable",
            ),
        )

        // The error line is shown + contained (no stuck spinner).
        compose.onNodeWithTag(FOLDER_LIST_CLI_VERSION_UPDATE_ERROR_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(FOLDER_LIST_CLI_VERSION_UPDATE_ERROR_TAG)
        compose.onNodeWithTag(FOLDER_LIST_CLI_VERSION_UPDATE_SPINNER_TAG).assertDoesNotExist()

        // Retry (re-uses the Update tag) + Dismiss present, reachable, contained.
        compose.onNodeWithTag(FOLDER_LIST_CLI_VERSION_UPDATE_TAG)
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        compose.onNodeWithTag(FOLDER_LIST_CLI_VERSION_DISMISS_TAG)
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        compose.assertNodeFullyWithinRoot(FOLDER_LIST_CLI_VERSION_UPDATE_TAG)
        compose.assertNodeFullyWithinRoot(FOLDER_LIST_CLI_VERSION_DISMISS_TAG)

        capture("issue-947-banner-failure-retry-dismiss.png")
    }

    // --- helpers ------------------------------------------------------------

    private fun setBanner(state: FolderListViewModel.CliVersionUpdateState) {
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                ) {
                    // The banner's REAL placement on the FolderList screen: a
                    // non-displacing overlay pinned to the bottom edge with the
                    // same 12dp padding the production call site uses.
                    CliVersionMismatchBanner(
                        message = message,
                        updateState = state,
                        onUpdate = {},
                        onDismiss = {},
                        // The banner's REAL placement on the FolderList screen: a
                        // bottom-edge overlay with 12dp padding, ABOVE the system
                        // nav bar (the production content Box carries the nav
                        // inset). Adding it here makes the acceptance screenshot
                        // faithful and keeps the controls off the nav bar.
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(12.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        SystemClock.sleep(150)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/cli-version-banner-update")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create screenshot directory: ${dir.absolutePath}"
        }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(dir, name)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("CLI_VERSION_BANNER_UPDATE_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
