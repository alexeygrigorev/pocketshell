package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class NavigationChevronTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersWithDefaultFootprint() {
        composeRule.setContent {
            PocketShellTheme {
                NavigationChevron(modifier = Modifier.testTag("nav-chevron"))
            }
        }

        composeRule.onNodeWithTag("nav-chevron")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(NavigationChevronDefaultSize)
            .assertHeightIsEqualTo(NavigationChevronDefaultSize)
    }

    /**
     * Issue #2113 trimmed this test. It used to also assert that the test's OWN
     * `Box(Modifier.size(48.dp))` measures 48dp — the test's constant compared with
     * itself, which no production change can redden: making the chevron ignore its
     * declared size (`Canvas(modifier.size(size * 2))`) failed this method at the
     * `nav-chevron` assertion below, i.e. the two touch-target lines had already
     * passed. The 48dp wrapper stays because it is the SCENARIO (a row's touch
     * target); only the assertions that read it back are gone.
     */
    @Test
    fun callerCanWrapInRowTouchTargetWithoutChangingIconSize() {
        composeRule.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.size(48.dp)) {
                    NavigationChevron(modifier = Modifier.testTag("nav-chevron"))
                }
            }
        }

        composeRule.onNodeWithTag("nav-chevron")
            .assertWidthIsEqualTo(NavigationChevronDefaultSize)
            .assertHeightIsEqualTo(NavigationChevronDefaultSize)
    }
}
