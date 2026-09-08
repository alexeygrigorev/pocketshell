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
import org.junit.Assert.assertEquals
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
