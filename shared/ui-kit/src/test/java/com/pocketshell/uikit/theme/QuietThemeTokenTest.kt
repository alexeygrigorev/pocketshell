package com.pocketshell.uikit.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Contract checks for the production side of Quiet A1.
 *
 * The values mirror `docs/design-kit/design-system/tokens.json` and the slot
 * mapping in `docs/design-kit/android/PocketShellTheme.kt`. Keeping these
 * assertions next to the production theme makes a later token drift fail in
 * the normal shared ui-kit JVM gate instead of only in a visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class QuietThemeTokenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun quietColorTokensMatchTheDesignKit() {
        assertEquals(Color(0xFF10171E), PocketShellColors.Background)
        assertEquals(Color(0xFF19222B), PocketShellColors.Surface)
        assertEquals(Color(0xFF222D38), PocketShellColors.SurfaceElev)
        assertEquals(Color(0xFFF0F3F7), PocketShellColors.Text)
        assertEquals(Color(0xFFA6B2C1), PocketShellColors.TextSecondary)
        assertEquals(Color(0xFF92A0B0), PocketShellColors.TextMuted)
        assertEquals(Color(0xFF2B3946), PocketShellColors.BorderSoft)
        assertEquals(Color(0xFF64778A), PocketShellColors.Border)
        assertEquals(Color(0xFF53D8EC), PocketShellColors.Accent)
        assertEquals(Color(0xFF082027), PocketShellColors.OnAccent)
        assertEquals(Color(0xFF5CDF89), PocketShellColors.Green)
        assertEquals(Color(0xFFE6BC78), PocketShellColors.Amber)
        assertEquals(Color(0xFFF3A1A1), PocketShellColors.Red)
        assertEquals(Color(0xFF0B1117), PocketShellColors.TermBg)
        assertEquals(PocketShellColors.Text, PocketShellColors.TermText)
        assertEquals(PocketShellColors.Accent, PocketShellColors.TermPrompt)
        assertEquals(PocketShellColors.TextMuted, PocketShellColors.TermComment)
        assertEquals(Color(0x99000000), PocketShellColors.Scrim)
    }

    /**
     * #2630 reproduce-first: `Type.kt`'s own doc comment claimed to be "pinned
     * to `docs/design-language.md`'s restrained scale" while every rung shipped
     * one step LARGER than that document specifies — 28/20/18/16 against the
     * documented 20/16/14/11 — which is what made the app read as oversized on
     * a phone. Nothing failed, because nothing had ever asserted the scale.
     *
     * The numbers below are `docs/design-language.md` § Type verbatim
     * ("11sp captions, 14sp body, 16sp titles, 20sp screen headings"), mirrored
     * by `docs/design-system.md`'s token table. Change the doc first.
     */
    @Test
    fun quietTypeScaleMatchesTheDocumentedDesignLanguageScale() {
        assertEquals("screen heading", 20.sp, PocketShellType.screen.fontSize)
        assertEquals(26.sp, PocketShellType.screen.lineHeight)
        assertEquals("title", 16.sp, PocketShellType.title.fontSize)
        assertEquals(22.sp, PocketShellType.title.lineHeight)
        assertEquals("body", 14.sp, PocketShellType.body.fontSize)
        assertEquals(20.sp, PocketShellType.body.lineHeight)
        assertEquals("caption/metadata", 11.sp, PocketShellType.metadata.fontSize)
        assertEquals(16.sp, PocketShellType.metadata.lineHeight)
        assertEquals("label", 11.sp, PocketShellType.label.fontSize)

        // The M3 slot overrides are the same four rungs, so a component reading
        // `MaterialTheme.typography` cannot land on a different scale.
        assertEquals(PocketShellType.screen.fontSize, PocketShellTypography.headlineSmall.fontSize)
        assertEquals(PocketShellType.title.fontSize, PocketShellTypography.titleMedium.fontSize)
        assertEquals(PocketShellType.body.fontSize, PocketShellTypography.bodyMedium.fontSize)
        assertEquals(PocketShellType.metadata.fontSize, PocketShellTypography.labelSmall.fontSize)

        // …and so are the `quiet*` spellings: two independent copies of one
        // scale drifting apart is exactly how #2630 shipped.
        assertEquals(PocketShellType.screen, PocketShellType.quietScreen)
        assertEquals(PocketShellType.title, PocketShellType.quietTitle)
        assertEquals(PocketShellType.body, PocketShellType.quietBody)
        assertEquals(PocketShellType.metadata, PocketShellType.quietMetadata)
        assertEquals(PocketShellType.label, PocketShellType.quietLabel)
        assertEquals(PocketShellType.title, PocketShellType.workspace)

        // The dense/mono rungs sit deliberately outside the four-rung scale and
        // were already correct; #2630 must not have moved them.
        assertEquals(13.sp, PocketShellType.bodyDense.fontSize)
        assertEquals(13.sp, PocketShellType.bodyMono.fontSize)
        assertEquals(11.sp, PocketShellType.labelMono.fontSize)
    }

    /**
     * #2630: rows were 72dp/88dp tall because the Quiet redesign read the 48dp
     * *tap* floor as a *row* target. Visual density may shrink; the hit area
     * may not go below [PocketShellDensity.tapTargetMin].
     */
    @Test
    fun quietRowHeightsAreCompactButNeverBelowTheTapFloor() {
        assertEquals(48.dp, PocketShellDensity.tapTargetMin)
        assertEquals(56.dp, PocketShellDensity.rowMinHeight)
        assertEquals(64.dp, PocketShellDensity.workspaceRowMinHeight)
        assertEquals(8.dp, PocketShellDensity.rowPadV)

        // The alias must never become a second, drifting value.
        assertEquals(PocketShellDensity.rowMinHeight, PocketShellDensity.standardRowMinHeight)

        assertTrue(
            "a standard row must still clear the 48dp touch floor",
            PocketShellDensity.rowMinHeight >= PocketShellDensity.tapTargetMin,
        )
        assertTrue(
            "the workspace row is the taller navigation target",
            PocketShellDensity.workspaceRowMinHeight > PocketShellDensity.rowMinHeight,
        )
        assertTrue(
            "72dp rows are what #2630 reported; stay well under that",
            PocketShellDensity.rowMinHeight < 72.dp,
        )
    }

    @Test
    fun quietShapeTokensMatchTheDesignKit() {
        assertEquals(12f, topStartRadius(PocketShellShapes.small), 0f)
        assertEquals(12f, topStartRadius(PocketShellShapes.medium), 0f)
        assertEquals(24f, topStartRadius(PocketShellShapes.large), 0f)
    }

    @Test
    fun materialThemeMapsQuietRolesAndPreservesTheTerminalInputs() {
        var scheme: ColorScheme? = null
        composeRule.setContent {
            PocketShellTheme {
                scheme = MaterialTheme.colorScheme
            }
        }

        composeRule.runOnIdle {
            val mapped = requireNotNull(scheme)
            assertEquals(PocketShellColors.Background, mapped.background)
            assertEquals(PocketShellColors.Surface, mapped.surface)
            assertEquals(PocketShellColors.SurfaceElev, mapped.surfaceVariant)
            assertEquals(PocketShellColors.Accent, mapped.primary)
            assertEquals(PocketShellColors.OnAccent, mapped.onPrimary)
            assertEquals(PocketShellColors.SurfaceElev, mapped.primaryContainer)
            assertEquals(PocketShellColors.TextSecondary, mapped.secondary)
            assertEquals(PocketShellColors.Border, mapped.outline)
            assertEquals(PocketShellColors.BorderSoft, mapped.outlineVariant)
            assertEquals(PocketShellColors.Red, mapped.error)
            assertEquals(PocketShellColors.Background, mapped.onError)
            assertEquals(PocketShellColors.Scrim, mapped.scrim)
        }
    }

    private fun topStartRadius(shape: Shape): Float {
        val outline = shape.createOutline(
            size = Size(100f, 100f),
            layoutDirection = LayoutDirection.Ltr,
            density = Density(1f),
        )
        return (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x
    }
}
