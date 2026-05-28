package com.pocketshell.app.composer

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #196: pure-logic coverage for the shared composer building blocks
 * in [UnifiedComposer]. The visual rendering of both composer surfaces is
 * exercised by the connected androidTest journeys; this unit test pins the
 * deterministic, Compose-free logic the shared surface relies on:
 *
 *  - The long-press tooltip popup positioning math shared by every Send
 *    button across both composer surfaces.
 *  - The stable tooltip test-tag derivation (so terminal + agent Send
 *    tooltips get distinct, reproducible tags).
 *  - The per-target distinction that drives the only intentional behaviour
 *    difference between the two surfaces.
 */
class UnifiedComposerTest {

    private val provider = ComposerAboveAnchorPopupPositionProvider

    @Test
    fun tooltipCentresHorizontallyAboveTheAnchorWhenThereIsRoom() {
        val anchor = IntRect(left = 400, top = 600, right = 600, bottom = 644)
        val popup = IntSize(width = 120, height = 40)
        val window = IntSize(width = 1080, height = 2400)

        val offset = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, popup)

        // Centred on the anchor: anchorLeft + (anchorWidth - popupWidth)/2
        // = 400 + (200 - 120)/2 = 440.
        assertEquals(440, offset.x)
        // Above the anchor with the 24px gap: anchorTop - popupHeight - 24
        // = 600 - 40 - 24 = 536.
        assertEquals(536, offset.y)
    }

    @Test
    fun tooltipClampsToTheLeftWindowEdge() {
        // Anchor hugs the left edge — a centred popup would spill negative.
        val anchor = IntRect(left = 0, top = 600, right = 80, bottom = 644)
        val popup = IntSize(width = 200, height = 40)
        val window = IntSize(width = 1080, height = 2400)

        val offset = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, popup)

        assertEquals("popup must not spill off the left edge", 0, offset.x)
    }

    @Test
    fun tooltipClampsToTheRightWindowEdge() {
        // Anchor hugs the right edge — a centred popup would spill past it.
        val anchor = IntRect(left = 1000, top = 600, right = 1080, bottom = 644)
        val popup = IntSize(width = 200, height = 40)
        val window = IntSize(width = 1080, height = 2400)

        val offset = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, popup)

        assertEquals(
            "popup right edge must stay inside the window",
            window.width - popup.width,
            offset.x,
        )
    }

    @Test
    fun tooltipFallsBelowTheAnchorWhenThereIsNoRoomAbove() {
        // Anchor sits at the very top — there is no room for the popup
        // above it, so the provider must drop it below the anchor instead.
        val anchor = IntRect(left = 400, top = 0, right = 600, bottom = 44)
        val popup = IntSize(width = 120, height = 40)
        val window = IntSize(width = 1080, height = 2400)

        val offset = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, popup)

        // Below the anchor: anchorBottom + 24 = 44 + 24 = 68.
        assertEquals(68, offset.y)
        assertTrue("y must be non-negative", offset.y >= 0)
    }

    @Test
    fun tooltipTagsAreStableAndDistinctPerCopy() {
        val terminalSend = composerSendTooltipTestTag(SEND_TOOLTIP_LABEL)
        val terminalSendEnter = composerSendTooltipTestTag(SEND_ENTER_TOOLTIP_LABEL)
        val agentSend = composerSendTooltipTestTag(AGENT_SEND_TOOLTIP_LABEL)

        // Stable: same input → same tag.
        assertEquals(terminalSend, composerSendTooltipTestTag(SEND_TOOLTIP_LABEL))
        // Distinct: each composer Send affordance gets its own tag.
        assertNotEquals(terminalSend, terminalSendEnter)
        assertNotEquals(terminalSend, agentSend)
        assertNotEquals(terminalSendEnter, agentSend)
    }

    @Test
    fun composerTargetEnumerationCoversBothSurfaces() {
        // Both call sites must be representable; the agent pane and the
        // terminal shell are the only two surfaces by design.
        val targets = ComposerTarget.entries.toSet()
        assertEquals(
            setOf(ComposerTarget.TerminalShell, ComposerTarget.AgentPane),
            targets,
        )
    }
}
