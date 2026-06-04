package com.pocketshell.uikit.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Instrumentation tests for the Slice S1 shared primitives (#480). Each test
 * renders one component inside [PocketShellTheme] and asserts it actually paints
 * + behaves — these are the regression net the screen slices (A–E of #479) rely
 * on when they swap their bespoke rows for these.
 */
@RunWith(AndroidJUnit4::class)
class UiKitPrimitivesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun screenHeader_rendersTitleSubtitleAndTrailing() {
        composeRule.setContent {
            PocketShellTheme {
                ScreenHeader(
                    title = "Hosts",
                    subtitle = "4 hosts · 7 sessions",
                    trailing = { Badge(label = "live", role = BadgeRole.Active, mono = false) },
                )
            }
        }
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
        composeRule.onNodeWithText("4 hosts · 7 sessions").assertIsDisplayed()
        composeRule.onNodeWithText("live").assertIsDisplayed()
    }

    @Test
    fun listRow_rendersTitleSubtitleLeadingAndTrailing() {
        composeRule.setContent {
            PocketShellTheme {
                ListRow(
                    title = "agent-main",
                    subtitle = "~/proj/agent",
                    leading = { StatusDot(status = ConnectionStatus.Connected) },
                    trailing = { Badge(label = "Claude", role = BadgeRole.Agent) },
                    onClick = {},
                )
            }
        }
        composeRule.onNodeWithText("agent-main").assertIsDisplayed()
        composeRule.onNodeWithText("~/proj/agent").assertIsDisplayed()
        composeRule.onNodeWithText("Claude").assertIsDisplayed()
    }

    @Test
    fun listRow_invokesOnClick() {
        var clicks = 0
        composeRule.setContent {
            PocketShellTheme {
                ListRow(
                    title = "tap me",
                    modifier = Modifier.testTag(ROW_TAG),
                    onClick = { clicks++ },
                )
            }
        }
        composeRule.onNodeWithTag(ROW_TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    /**
     * The headline contract of #480: a clickable [ListRow] paints compact (44/8)
     * but the whole-row hit area is held at the 48dp a11y touch floor, baked in
     * so screens can't regress it. We render a single-line clickable row (which
     * at 44dp paint + 8dp pad would otherwise be under 48dp) and assert the node
     * is at least 48dp tall.
     */
    @Test
    fun listRow_clickable_meetsTouchFloor() {
        composeRule.setContent {
            PocketShellTheme {
                ListRow(
                    title = "x",
                    modifier = Modifier.testTag(ROW_TAG),
                    onClick = {},
                )
            }
        }
        composeRule.onNodeWithTag(ROW_TAG).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun badge_rendersLabelForEachRole() {
        composeRule.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Badge(label = "Claude", role = BadgeRole.Agent)
                    Badge(label = "shell", role = BadgeRole.Shell)
                    Badge(label = "active", role = BadgeRole.Active)
                    Badge(label = "idle", role = BadgeRole.Idle)
                    Badge(label = "error", role = BadgeRole.Error)
                }
            }
        }
        composeRule.onNodeWithText("Claude").assertIsDisplayed()
        composeRule.onNodeWithText("shell").assertIsDisplayed()
        composeRule.onNodeWithText("active").assertIsDisplayed()
        composeRule.onNodeWithText("idle").assertIsDisplayed()
        composeRule.onNodeWithText("error").assertIsDisplayed()
    }

    @Test
    fun kebab_opensMenuAndInvokesItem() {
        var ports = 0
        composeRule.setContent {
            PocketShellTheme {
                Kebab(
                    items = listOf(
                        KebabItem("Ports", onClick = { ports++ }),
                        KebabItem("Share", onClick = {}),
                    ),
                )
            }
        }
        // Menu closed initially.
        composeRule.onNodeWithText("Ports").assertDoesNotExistOrHidden()
        composeRule.onNodeWithTag(KEBAB_BUTTON_TAG).performClick()
        composeRule.onNodeWithText("Ports").assertIsDisplayed()
        composeRule.onNodeWithText("Ports").performClick()
        composeRule.runOnIdle { assertEquals(1, ports) }
    }

    @Test
    fun sectionHeader_rendersLabelUppercaseWithCount() {
        composeRule.setContent {
            PocketShellTheme {
                SectionHeader(label = "Sessions", count = 3)
            }
        }
        composeRule.onNodeWithText("SESSIONS").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
    }

    /**
     * Renders the full primitive stack the way a real screen assembles it
     * (header → section → rows with leading dot / badge / kebab) and captures a
     * viewport PNG to the device, then copies it to external files so the
     * reviewer can pull authoritative visual evidence of the design language.
     */
    @Test
    fun capturePrimitivesScreenshot() {
        composeRule.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .testTag(CAPTURE_TAG)
                        .fillMaxWidth()
                        .background(PocketShellColors.Background),
                ) {
                    ScreenHeader(
                        title = "Hosts",
                        subtitle = "4 hosts · 7 sessions",
                        trailing = { Badge(label = "live", role = BadgeRole.Active, mono = false) },
                    )
                    SectionHeader(label = "Sessions", count = 3)
                    ListRow(
                        title = "agent-main",
                        subtitle = "~/proj/agent",
                        leading = { StatusDot(status = ConnectionStatus.Connected) },
                        trailing = { Badge(label = "Claude", role = BadgeRole.Agent) },
                        onClick = {},
                    )
                    ListRow(
                        title = "training",
                        subtitle = "~/ml/run",
                        leading = { StatusDot(status = ConnectionStatus.Idle) },
                        trailing = { Badge(label = "shell", role = BadgeRole.Shell) },
                        onClick = {},
                    )
                    ListRow(
                        title = "Appearance",
                        onClick = {},
                    )
                }
            }
        }

        val bitmap: Bitmap = composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureToImage()
            .asAndroidBitmap()

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(ctx.getExternalFilesDir(null), "uikit-primitives")
        outDir.mkdirs()
        val out = File(outDir, "primitives-viewport.png")
        FileOutputStream(out).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertTrue("screenshot file should exist and be non-empty", out.length() > 0)
    }

    private companion object {
        const val ROW_TAG = "test:list-row"
        const val CAPTURE_TAG = "test:primitives-capture"
    }
}

/**
 * Small helper: a node that isn't part of the tree (menu closed) should not be
 * displayed. `assertDoesNotExist` is too strict because the dropdown can keep a
 * collapsed node; this checks "not currently shown to the user".
 */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrHidden() {
    val exists = try {
        fetchSemanticsNode()
        true
    } catch (e: AssertionError) {
        false
    }
    assertTrue("expected node to be absent or not displayed", !exists)
}
