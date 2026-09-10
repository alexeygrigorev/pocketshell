package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #2635 §5 and 2a: the shared primitives the audit found missing or
 * mis-shaped, pinned at the kit level so every screen inherits the fix.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class QuietPrimitivesTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * #2635 2a, reproduce-first: `ListRow` padded the ROW, so a row whose
     * trailing slot is a 48dp control could not be the 56dp standard row —
     * 48 + 2×8 = 64. The host list therefore read as "56dp rows, except the
     * ones with a menu, which are 64".
     *
     * The pair below differ ONLY in whether the trailing slot holds a 48dp
     * control, so the assertion is that they land on the SAME height, which is
     * the actual complaint. It fails on the row-level padding (64 vs 56).
     */
    @Test
    fun `a row with a 48dp trailing control is the same height as one without`() {
        composeRule.setContent {
            PocketShellTheme {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                    ListRow(
                        title = "plain",
                        subtitle = "alexey@host",
                        onClick = {},
                        modifier = Modifier.testTag("plain"),
                    )
                    ListRow(
                        title = "with kebab",
                        subtitle = "alexey@host",
                        trailing = {
                            Kebab(
                                items = listOf(KebabItem(label = "Edit", onClick = {})),
                                triggerTestTag = "kebab",
                            )
                        },
                        onClick = {},
                        modifier = Modifier.testTag("menu"),
                    )
                }
            }
        }

        val density = composeRule.density.density
        val plain = composeRule.onNodeWithTag("plain")
            .fetchSemanticsNode().boundsInRoot.height / density
        val menu = composeRule.onNodeWithTag("menu")
            .fetchSemanticsNode().boundsInRoot.height / density
        val standard = PocketShellDensity.rowMinHeight.value

        assertTrue("a plain row is the standard row, was ${plain}dp", plain <= standard + 1f)
        assertTrue(
            "a row carrying a 48dp control must be the SAME height as one " +
                "without: plain ${plain}dp vs menu ${menu}dp",
            menu <= plain + 0.5f,
        )
        assertTrue(
            "the row must still clear the 48dp tap floor, was ${menu}dp",
            menu >= PocketShellDensity.tapTargetMin.value,
        )
    }

    /**
     * #2635 T2: `ScreenHeader` carries the STEADY transport state as a dot, so
     * the subtitle line is free for the states that need words.
     */
    @Test
    fun `the screen header can carry a status dot beside its title`() {
        composeRule.setContent {
            PocketShellTheme {
                ScreenHeader(
                    title = "hetzner",
                    status = ConnectionStatus.Connected,
                    statusDescription = "Connected",
                    statusTestTag = "dot",
                    titleTestTag = "title",
                )
            }
        }

        val dot = composeRule.onNodeWithTag("dot").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithTag("title")
            .fetchSemanticsNode().boundsInRoot

        assertTrue("the dot leads the title", dot.right <= title.left)
        assertTrue(
            "the dot shares the title's line rather than stacking above it",
            dot.top >= title.top - 8f && dot.bottom <= title.bottom + 8f,
        )
        // No text label: that is the whole point of the steady state.
        composeRule.onNodeWithText("Connected").assertDoesNotExist()
    }

    /**
     * #2635 §5: `QuietTextField` exists so a screen stops copying an
     * eight-colour `OutlinedTextFieldDefaults.colors(...)` block. It is on the
     * kit's field geometry — `size.fieldMin` and `radius.field` — not on
     * Material's defaults, which are not Quiet's colours at all.
     */
    @Test
    fun `the shared text field is on the kit's field geometry`() {
        composeRule.setContent {
            PocketShellTheme {
                QuietTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = "Find a workspace",
                    testTag = "field",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        composeRule.onNodeWithTag("field").assertIsDisplayed()
        composeRule.onNodeWithText("Find a workspace").assertIsDisplayed()

        // NOT a height assertion: `OutlinedTextField`'s own Material minimum is
        // already 56dp, so `heightIn(min = fieldMin)` is unobservable from the
        // rendered node — a measured assertion here would pass whatever the
        // component did with the token, which is a vacuous green (G6).
        //
        // What IS load-bearing is that the field's geometry comes from the
        // token file rather than a per-screen literal, so this asserts the
        // wiring: the kit rung and `tokens.json` § `size.fieldMin` agree, and
        // the corner is the ladder's field radius.
        assertEquals(
            "the field height rung must be tokens.json's size.fieldMin",
            56f,
            PocketShellDensity.fieldMinHeight.value,
            0f,
        )
        assertEquals(
            "the field corner must be the ladder's field radius",
            12f,
            topStartRadiusOf(PocketShellShapes.medium),
            0f,
        )
    }

    /**
     * #2635 C3: the composer's idle row is FOUR controls, and it fits the
     * narrowest phone the app supports. The audit measured the five-control row
     * at ~330dp of the 372dp available at 412dp; at 360dp (320dp usable) the
     * trailing mic was clipped, which is a hard failure.
     *
     * Rendered at 360dp: every control must be inside the viewport.
     */
    @Test
    @Config(qualifiers = "w360dp-h800dp-night-xxhdpi")
    fun `the composer idle row fits a 360dp phone`() {
        composeRule.setContent {
            PocketShellTheme {
                ComposerIdleControls(
                    onAttach = {},
                    onOpenTools = {},
                    onSend = {},
                    onPaste = {},
                    onMicTap = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val root = composeRule.onRootBounds()
        listOf(
            COMPOSER_ATTACH_TAG,
            COMPOSER_TOOLS_TRIGGER_TAG,
            COMPOSER_SEND_TAG,
            COMPOSER_MIC_TAG,
        ).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$tag is clipped at 360dp (right ${bounds.right}px, viewport " +
                    "${root.second}px)",
                bounds.right <= root.second + 0.5f,
            )
            assertTrue("$tag has no width at 360dp", bounds.width > 0f)
        }

        // The fifth control is what did not fit.
        composeRule.onNodeWithText("Paste").assertDoesNotExist()
    }

    private fun topStartRadiusOf(shape: androidx.compose.ui.graphics.Shape): Float {
        val outline = shape.createOutline(
            size = androidx.compose.ui.geometry.Size(100f, 100f),
            layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
            density = androidx.compose.ui.unit.Density(1f),
        )
        return (outline as androidx.compose.ui.graphics.Outline.Rounded)
            .roundRect.topLeftCornerRadius.x
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onRootBounds(): Pair<Float, Float> {
        val bounds = onNodeWithTag(COMPOSER_SEND_TAG).fetchSemanticsNode().root!!.semanticsOwner
            .rootSemanticsNode.boundsInRoot
        return bounds.left to bounds.right
    }
}
