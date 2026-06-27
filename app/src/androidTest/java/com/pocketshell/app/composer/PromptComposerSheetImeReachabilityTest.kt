package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyAboveImeOrKeyboard
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #615 — Send must stay reachable when the composer is in a keyboard-up
 * layout.
 *
 * This used to raise the REAL soft IME, then `assumeTrue`-skip when the shared
 * CI emulator failed to surface it. That made the reachability assertion
 * environment-dependent and allowed a vacuous green run. The newer composer IME
 * proofs use a deterministic #780 model instead: render the production
 * [SheetContent] in a fixed host, dispatch a synthetic `Type.ime()` inset, read
 * the inset back from inside Compose, and HARD-assert it applied before judging
 * geometry. No real keyboard, no skip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerSheetImeReachabilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)
    private val observedStatusTopPx = mutableStateOf(0)

    @Test
    fun sendStaysAboveKeyboardUnderSyntheticImeInset() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        compose.setContent {
            PocketShellTheme {
                val density = LocalDensity.current
                observedImeBottomPx.value = WindowInsets.ime.getBottom(density)
                observedNavBottomPx.value = WindowInsets.navigationBars.getBottom(density)
                observedStatusTopPx.value = WindowInsets.statusBars.getTop(density)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    FauxTerminalBackdrop()
                    Box(
                        modifier = Modifier
                            .width(HOST_WIDTH_DP.dp)
                            .height(HOST_HEIGHT_DP.dp)
                            .testTag(HOST_TAG),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SheetContent(
                            state = keyboardUpDraftState(),
                            onClose = {},
                            onDraftChange = {},
                            onMicTap = {},
                            onSend = {},
                            onAttachFiles = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * displayDensity()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * displayDensity()).toInt(),
            statusBarTopPx = (STATUS_BAR_DP * displayDensity()).toInt(),
        )
        compose.waitForIdle()

        val density = displayDensity()
        val imeBottomPx = observedImeBottomPx.value
        val navBottomPx = observedNavBottomPx.value
        val statusTopPx = observedStatusTopPx.value
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()

        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate issue " +
                "#615 keyboard-up Send reachability. observedImeBottomPx=" +
                "$imeBottomPx (expected ~$expectedImePx).",
            imeBottomPx > 0,
        )

        val hostBounds = compose.onNodeWithTag(HOST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        // Model the resized-sheet case: the host height is already the visible
        // room above the keyboard, so the synthetic keyboard starts at its bottom.
        val keyboardTopPx = hostBounds.bottom

        println(
            "ISSUE615_SYNTHETIC_GEOMETRY sendTop=${sendBounds.top} " +
                "sendBottom=${sendBounds.bottom} keyboardTopPx=$keyboardTopPx " +
                "hostBottom=${hostBounds.bottom} imeBottomPx=$imeBottomPx " +
                "navBottomPx=$navBottomPx statusTopPx=$statusTopPx density=$density",
        )

        compose.assertNodeFullyAboveImeOrKeyboard(
            COMPOSER_SEND_ENTER_TAG,
            keyboardTopPx = keyboardTopPx,
            slopDp = SLOP_DP,
            useUnmergedTree = true,
        )
    }

    private fun keyboardUpDraftState(): PromptComposerViewModel.UiState =
        PromptComposerViewModel.UiState(
            draft = "printf issue615 send must be reachable",
            recording = PromptComposerViewModel.RecordingState.Idle,
            attachments = emptyList(),
        )

    private fun applySyntheticInsets(
        imeBottomPx: Int,
        navBarBottomPx: Int,
        statusBarTopPx: Int,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, navBarBottomPx),
                )
                .setInsets(
                    WindowInsetsCompat.Type.statusBars(),
                    Insets.of(0, statusBarTopPx, 0, 0),
                )
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(0, statusBarTopPx, 0, navBarBottomPx),
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
        }
    }

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    @androidx.compose.runtime.Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
            color = PocketShellColors.Text,
        )
    }

    private companion object {
        const val HOST_TAG = "issue615-synthetic-ime-host"
        const val HOST_HEIGHT_DP = 470f
        const val HOST_WIDTH_DP = 392f
        const val IME_HEIGHT_DP = 343f
        const val NAV_BAR_DP = 48f
        const val STATUS_BAR_DP = 52f
        const val SLOP_DP = 4f
    }
}
