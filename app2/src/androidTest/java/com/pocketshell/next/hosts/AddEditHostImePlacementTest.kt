package com.pocketshell.next.hosts

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The host form's footer owns the IME inset, so its actions stay reachable while
 * the independently scrolling fields remain behind it. This uses the same
 * synthetic-inset model as the terminal keyboard geometry proof (#780).
 */
@RunWith(AndroidJUnit4::class)
class AddEditHostImePlacementTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)

    @Test
    fun actionsStayAboveTheImeInset() {
        setUpEdgeToEdge()
        compose.setContent {
            PocketShellTheme {
                ObserveInsets()
                AddEditHostScreen(
                    state = HostFormState(
                        name = "hetzner",
                        hostname = "dev.example.test",
                        username = "alexey",
                        selectedKeyId = 1,
                    ),
                    keys = listOf(
                        SshKeyEntity(
                            id = 1,
                            name = "device-key",
                            privateKeyPath = "/data/data/keys/device-key",
                        ),
                    ),
                    onChange = { it },
                    onSave = {},
                    onTestConnection = {},
                    onCancel = {},
                    onAddKey = {},
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(ROOT_TAG),
                )
            }
        }
        compose.waitForIdle()

        val actionsDown = boundsOf(HOST_FORM_ACTIONS_TAG).bottom
        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * density()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * density()).toInt(),
        )
        compose.waitForIdle()

        val imeBottomPx = observedImeBottomPx.value
        assertTrue(
            "Synthetic ime() inset did not reach Compose; observedImeBottomPx=$imeBottomPx.",
            imeBottomPx > 0,
        )

        val rootBottom = boundsOf(ROOT_TAG).bottom
        val keyboardIntrusionPx = (imeBottomPx - observedNavBottomPx.value).coerceAtLeast(0)
        val keyboardTopPx = rootBottom - keyboardIntrusionPx
        val actionsUp = boundsOf(HOST_FORM_ACTIONS_TAG)

        assertTrue(
            "The action area did not move above the synthetic IME: " +
                "downBottom=$actionsDown up=$actionsUp keyboardTopPx=$keyboardTopPx",
            actionsUp.bottom < actionsDown - BOUNDS_SLOP_PX,
        )
        assertNodeFullyAboveKeyboard(HOST_FORM_ACTIONS_TAG, keyboardTopPx)
        assertNodeFullyAboveKeyboard(HOST_FORM_TEST_TAG, keyboardTopPx)
        assertNodeFullyAboveKeyboard(HOST_FORM_SAVE_TAG, keyboardTopPx)
    }

    @Composable
    private fun ObserveInsets() {
        val density = LocalDensity.current
        observedImeBottomPx.value = WindowInsets.ime.getBottom(density)
        observedNavBottomPx.value = WindowInsets.navigationBars.getBottom(density)
    }

    private fun setUpEdgeToEdge() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }

    private fun applySyntheticInsets(imeBottomPx: Int, navBarBottomPx: Int) {
        compose.activityRule.scenario.onActivity { activity ->
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, navBarBottomPx),
                )
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(0, 0, 0, navBarBottomPx),
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        }
    }

    private data class Bounds(
        val bottom: Float,
    )

    private fun boundsOf(tag: String): Bounds = Bounds(
        bottom = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom,
    )

    private fun assertNodeFullyAboveKeyboard(tag: String, keyboardTopPx: Float) {
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val slopPx = compose.density.density
        assertTrue(
            "Node '$tag' is not fully above the synthetic keyboard: " +
                "bottom=${bounds.bottom} keyboardTopPx=$keyboardTopPx",
            bounds.bottom <= keyboardTopPx + slopPx,
        )
    }

    private fun density(): Float = compose.density.density

    private companion object {
        const val ROOT_TAG = "host-form-ime-root"
        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val BOUNDS_SLOP_PX = 1.5f
    }
}
