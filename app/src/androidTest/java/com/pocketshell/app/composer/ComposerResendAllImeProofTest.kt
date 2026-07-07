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
 * Issue #1308 — the batch "Resend all" button must stay REACHABLE above the soft
 * keyboard, not occluded, when the maintainer opens the composer with a queued
 * backlog and the keyboard is up. This is the composer/bottom-chrome
 * keyboard-up-occlusion failure class (process.md "Visual / composer / keyboard
 * / layout regressions", #641/#567/#615) applied to the NEW control.
 *
 * It reuses the #780 CI-DETERMINISTIC synthetic-inset model: compose the
 * PRODUCTION [SheetContent] with the outbound queue expanded and >= 2 Failed
 * rows (so the "Resend all" button renders), host it in a fixed-height container,
 * dispatch a SYNTHETIC `Type.ime()` inset (no real soft keyboard, so it is
 * identical on the dev-box AVD and CI swiftshader), HARD-assert the inset applied
 * (no `assumeTrue` skip — F3), then assert the button is fully above the keyboard
 * top via [assertNodeFullyAboveImeOrKeyboard] (viewport containment, not a bare
 * `assertIsDisplayed()` which a below-the-keyboard control still passes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class ComposerResendAllImeProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)
    private val observedStatusTopPx = mutableStateOf(0)

    private fun failedRow(id: String, text: String, createdAtMs: Long) = OutboundItem(
        id = id,
        sessionKey = "1/a",
        cleanText = text,
        state = OutboundState.Failed,
        lastError = "connection lost",
        createdAtMs = createdAtMs,
    )

    @Test
    fun resendAllButtonStaysAboveKeyboardWhenImeUp() {
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
                    Box(
                        modifier = Modifier
                            .width(CONTAINER_WIDTH_DP.dp)
                            .height(CONTAINER_HEIGHT_DP.dp)
                            .testTag(CONTAINER_TAG),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SheetContent(
                            state = PromptComposerViewModel.UiState(),
                            onClose = {},
                            onDraftChange = {},
                            onMicTap = {},
                            onSend = {},
                            outboundQueueItems = listOf(
                                failedRow("f-1", "first prompt", 1L),
                                failedRow("f-2", "second prompt", 2L),
                            ),
                            outboundQueueExpanded = true,
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
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()
        // HARD-assert the synthetic IME actually reached Compose — otherwise we
        // would validate a keyboard-DOWN layout and pass vacuously (#780/F3).
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "issue #1308 keyboard-up reachability of Resend all. " +
                "observedImeBottomPx=$imeBottomPx (expected ~$expectedImePx).",
            imeBottomPx > 0,
        )

        val containerBottom = compose.onNodeWithTag(CONTAINER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val keyboardIntrusionPx = (imeBottomPx - navBottomPx).coerceAtLeast(0)
        val keyboardTopPx = containerBottom - keyboardIntrusionPx

        // Containment: the button's bottom edge must sit at or above the keyboard
        // top (and within the window horizontally + at the top). A control shoved
        // under the keyboard still passes assertIsDisplayed(); this fails it.
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = COMPOSER_OUTBOUND_QUEUE_RESEND_ALL_TAG,
            keyboardTopPx = keyboardTopPx,
            slopDp = SLOP_DP,
        )
    }

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

    private companion object {
        const val CONTAINER_TAG = "issue1308-resend-all-host"

        // Roomy fixed host so a short draft + the expanded 2-row banner (with the
        // Resend all button) fit above the synthetic keyboard without scrolling;
        // the layout math is then deterministic on every AVD (#780 model).
        const val CONTAINER_HEIGHT_DP = 740f
        const val CONTAINER_WIDTH_DP = 392f

        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val STATUS_BAR_DP = 28f

        const val SLOP_DP = 4f
    }
}
