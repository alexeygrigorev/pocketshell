package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #2631: the in-session launcher is a floating overlay control, not the old
 * docked full-width chip row.
 */
@RunWith(RobolectricTestRunner::class)
class SessionLauncherOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the overlay is a composer button plus a hotkeys button`() {
        var composer = 0
        var hotkeys = 0
        setContent(onOpenComposer = { composer += 1 }, onOpenHotkeys = { hotkeys += 1 })

        compose.onNodeWithTag(SESSION_LAUNCHER_OVERLAY_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(SESSION_COMPOSER_LAUNCHER_LABEL).assertIsDisplayed()
        compose.onNodeWithContentDescription(SESSION_HOTKEYS_LAUNCHER_LABEL).assertIsDisplayed()
        compose.onNodeWithText("Ctrl").assertDoesNotExist()
        compose.onNodeWithText("Enter").assertDoesNotExist()

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        compose.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).performClick()
        assertEquals(1, composer)
        assertEquals(1, hotkeys)
    }

    @Test
    fun `the hotkeys button is omitted when there is no pane`() {
        setContent(onOpenHotkeys = null)

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).assertDoesNotExist()
    }

    /**
     * The regression this component exists to prevent: the old
     * `SessionLauncherBar` was `fillMaxWidth()` chrome that permanently ate a
     * strip of the session column. The replacement must stay a small corner
     * control that leaves the rest of the surface to the terminal.
     */
    @Test
    fun `the overlay is a corner control, not a full-width bar`() {
        setContent()

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val overlay = compose.onNodeWithTag(SESSION_LAUNCHER_OVERLAY_TAG)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the launcher must not span the surface: ${overlay.width} of ${root.width}",
            overlay.width < root.width / 2,
        )
        assertTrue(
            "the launcher must not span the surface: ${overlay.height} of ${root.height}",
            overlay.height < root.height / 2,
        )
    }

    /**
     * #2631 follow-up — the maintainer read the first render as "middle, not
     * right bottom corner". That turned out to be a render-fixture artifact
     * rather than a layout bug, but nothing pinned the anchor, so nothing
     * would have caught it if it HAD been real.
     *
     * The control's inset is inside its own bounds, so the assertion is an
     * exact edge distance: one standard 16dp inset from the container's end
     * and bottom edges, and no more. A padded wrapper, a centred alignment, or
     * an inset moved onto the parent all push these numbers up and fail here.
     */
    @Test
    fun `the overlay hugs the bottom-end corner at one standard inset`() {
        setContent()

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val overlay = compose.onNodeWithTag(SESSION_LAUNCHER_OVERLAY_TAG)
            .getUnclippedBoundsInRoot()

        assertEquals(
            "expected a 16dp end inset, got ${root.right - overlay.right} ($overlay in $root)",
            CORNER_INSET_DP,
            (root.right - overlay.right).value,
            1f,
        )
        assertEquals(
            "expected a 16dp bottom inset, got ${root.bottom - overlay.bottom} ($overlay in $root)",
            CORNER_INSET_DP,
            (root.bottom - overlay.bottom).value,
            1f,
        )
    }

    /**
     * The primary button owns the corner and the secondary stacks directly
     * above it on the same right edge — a staggered stack would read as
     * "floating" rather than corner-anchored.
     */
    @Test
    fun `the button stack is right-aligned with the composer button in the corner`() {
        setContent()

        val overlay = compose.onNodeWithTag(SESSION_LAUNCHER_OVERLAY_TAG)
            .getUnclippedBoundsInRoot()
        val composer = compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .getUnclippedBoundsInRoot()
        val hotkeys = compose.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG)
            .getUnclippedBoundsInRoot()

        assertEquals(
            "the composer button must own the corner, got $composer in $overlay",
            overlay.right.value,
            composer.right.value,
            1f,
        )
        assertEquals(
            "the composer button must own the bottom edge, got $composer in $overlay",
            overlay.bottom.value,
            composer.bottom.value,
            1f,
        )
        assertEquals(
            "the stack must share one right edge, got $hotkeys vs $composer",
            composer.right.value,
            hotkeys.right.value,
            1f,
        )
        assertTrue(
            "the hotkeys button must stack above the composer, got $hotkeys vs $composer",
            hotkeys.bottom <= composer.top,
        )
    }

    private fun setContent(
        onOpenComposer: () -> Unit = {},
        onOpenHotkeys: (() -> Unit)? = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SessionLauncherOverlay(
                        onOpenComposer = onOpenComposer,
                        onOpenHotkeys = onOpenHotkeys,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        /** The standard floating-action corner inset the control paints itself with. */
        const val CORNER_INSET_DP: Float = 16f
    }
}
