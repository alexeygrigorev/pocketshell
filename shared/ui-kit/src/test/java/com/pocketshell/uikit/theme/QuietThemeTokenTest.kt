package com.pocketshell.uikit.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * Every number below is READ from `docs/design-kit/design-system/tokens.json`
 * through [DesignKitTokens] rather than restated here (issue #2635, T1). The
 * previous version of this file hard-coded the same values, which pinned the
 * code to a *copy* of one of the four disagreeing token sources: regenerating
 * the kit's `android/PocketShellTheme.kt` from the JSON would have put the
 * 28/20/18/16 scale back and no test would have noticed. Now the JSON is the
 * source of truth and `Type.kt`/`Spacing.kt`/`Shape.kt`/`Color.kt` — plus the
 * kit's own generated handoff theme — are all pinned to the same file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class QuietThemeTokenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun quietColorTokensMatchTheDesignKit() {
        assertKitColor("background", PocketShellColors.Background)
        assertKitColor("surface", PocketShellColors.Surface)
        assertKitColor("surfaceRaised", PocketShellColors.SurfaceElev)
        assertKitColor("text", PocketShellColors.Text)
        assertKitColor("secondary", PocketShellColors.TextSecondary)
        assertKitColor("muted", PocketShellColors.TextMuted)
        assertKitColor("divider", PocketShellColors.BorderSoft)
        assertKitColor("inputBorder", PocketShellColors.Border)
        assertKitColor("accent", PocketShellColors.Accent)
        assertKitColor("onAccent", PocketShellColors.OnAccent)
        assertKitColor("positive", PocketShellColors.Green)
        assertKitColor("warning", PocketShellColors.Amber)
        assertKitColor("error", PocketShellColors.Red)
        assertKitColor("terminal", PocketShellColors.TermBg)
        assertKitColor("scrim", PocketShellColors.Scrim)

        assertEquals(PocketShellColors.Text, PocketShellColors.TermText)
        assertEquals(PocketShellColors.Accent, PocketShellColors.TermPrompt)
        assertEquals(PocketShellColors.TextMuted, PocketShellColors.TermComment)
    }

    /**
     * #2630 reproduce-first: `Type.kt`'s own doc comment claimed to be "pinned
     * to `docs/design-language.md`'s restrained scale" while every rung shipped
     * one step LARGER than that document specifies — 28/20/18/16 against the
     * documented 20/16/14/11 — which is what made the app read as oversized on
     * a phone. Nothing failed, because nothing had ever asserted the scale.
     *
     * #2635 closes the second half of that hole: the rungs are read from
     * `tokens.json`, so the code cannot drift from the kit and the kit cannot
     * drift from the code.
     */
    @Test
    fun quietTypeScaleMatchesTheDesignKitTokens() {
        assertKitType("screen", PocketShellType.screen)
        assertKitType("workspace", PocketShellType.workspace)
        assertKitType("title", PocketShellType.title)
        assertKitType("body", PocketShellType.body)
        assertKitType("metadata", PocketShellType.metadata)
        assertKitType("label", PocketShellType.label)
        assertKitType("button", PocketShellType.button)
        assertKitType("terminal", PocketShellType.terminal)
        assertKitType("bodyDense", PocketShellType.bodyDense)
        assertKitType("bodyMono", PocketShellType.bodyMono)
        assertKitType("labelMono", PocketShellType.labelMono)

        // Every rung the kit declares has to be asserted above; a rung added to
        // the JSON with no production style is drift the moment it is written.
        assertEquals(
            "tokens.json type rungs must all be pinned to a PocketShellType style",
            listOf(
                "body", "bodyDense", "bodyMono", "button", "label", "labelMono",
                "metadata", "screen", "terminal", "title", "workspace",
            ),
            DesignKitTokens.typeRungNames(),
        )

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
    }

    /**
     * #2630: rows were 72dp/88dp tall because the Quiet redesign read the 48dp
     * *tap* floor as a *row* target. Visual density may shrink; the hit area
     * may not go below [PocketShellDensity.tapTargetMin].
     */
    @Test
    fun quietRowHeightsAreCompactButNeverBelowTheTapFloor() {
        assertEquals(DesignKitTokens.sizeDp("touchMin").dp, PocketShellDensity.tapTargetMin)
        assertEquals(DesignKitTokens.sizeDp("listRowMin").dp, PocketShellDensity.rowMinHeight)
        assertEquals(
            DesignKitTokens.sizeDp("workspaceRowMin").dp,
            PocketShellDensity.workspaceRowMinHeight,
        )
        assertEquals(DesignKitTokens.sizeDp("rowPadV").dp, PocketShellDensity.rowPadV)
        assertEquals(DesignKitTokens.sizeDp("rowPadH").dp, PocketShellDensity.rowPadH)
        assertEquals(DesignKitTokens.sizeDp("chipPadV").dp, PocketShellDensity.chipPadV)
        assertEquals(DesignKitTokens.sizeDp("chipPadH").dp, PocketShellDensity.chipPadH)
        assertEquals(DesignKitTokens.sizeDp("sectionGap").dp, PocketShellDensity.sectionGap)
        assertEquals(DesignKitTokens.sizeDp("treeIndent").dp, PocketShellDensity.treeIndent)
        assertEquals(DesignKitTokens.sizeDp("fieldMin").dp, PocketShellDensity.fieldMinHeight)

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

    /**
     * #2635 T3: the spacing rungs are the kit's, and there are exactly six of
     * them. `section` (32dp) existed only because the kit's prose asked for
     * 32dp section separation; `PocketShellDensity.sectionGap` (24dp) is the
     * rung the app actually uses, and the last `section` consumer moved to it.
     */
    @Test
    fun quietSpacingRungsMatchTheDesignKitTokens() {
        assertEquals(DesignKitTokens.spaceDp("xs").dp, PocketShellSpacing.xs)
        assertEquals(DesignKitTokens.spaceDp("sm").dp, PocketShellSpacing.sm)
        assertEquals(DesignKitTokens.spaceDp("md").dp, PocketShellSpacing.md)
        assertEquals(DesignKitTokens.spaceDp("lg").dp, PocketShellSpacing.lg)
        assertEquals(DesignKitTokens.spaceDp("xl").dp, PocketShellSpacing.xl)
        assertEquals(DesignKitTokens.spaceDp("xxl").dp, PocketShellSpacing.xxl)

        assertEquals(
            "the retired 32dp `section` rung must not come back through the kit",
            listOf("lg", "md", "sm", "xl", "xs", "xxl"),
            DesignKitTokens.space.keys().asSequence().toList().sorted(),
        )
    }

    /**
     * The radius ladder is `{4 badge, 8 chip/tile, 12 field/button/card,
     * 24 sheet}`. Before #2635 the kit named only three radii, the code shipped
     * 12/12/24, and `check-design-tokens.sh` allow-listed 8/14/20/28 — a ladder
     * no shipped file used, so `RoundedCornerShape(12.dp)` was flagged while
     * `RoundedCornerShape(28.dp)` passed.
     */
    @Test
    fun quietShapeTokensMatchTheDesignKit() {
        assertEquals(
            DesignKitTokens.radiusDp("field").toFloat(),
            topStartRadius(PocketShellShapes.small),
            0f,
        )
        assertEquals(
            DesignKitTokens.radiusDp("button").toFloat(),
            topStartRadius(PocketShellShapes.medium),
            0f,
        )
        assertEquals(
            DesignKitTokens.radiusDp("sheet").toFloat(),
            topStartRadius(PocketShellShapes.large),
            0f,
        )
        assertEquals(
            "field, button and card share one 12dp rung",
            DesignKitTokens.radiusDp("field"),
            DesignKitTokens.radiusDp("card"),
        )
        assertEquals(
            "the guardrail's allow-list is exactly this ladder",
            listOf(4, 8, 12, 12, 12, 24),
            listOf("badge", "chip", "field", "button", "card", "sheet")
                .map(DesignKitTokens::radiusDp)
                .sorted(),
        )
    }

    /**
     * The kit's `android/PocketShellTheme.kt` is generated from `tokens.json`
     * and is what a future implementer would regenerate a theme from. #2635's
     * audit named this the top risk of making the JSON authoritative: the
     * generated file still carried 28/20/18/16 and 72/88dp rows, so a
     * regeneration would have silently reverted #2630. Pin it to the same file.
     */
    @Test
    fun theGeneratedKitThemeIsStillInSyncWithTokensJson() {
        val source = DesignKitTokens.generatedAndroidTheme.readText()

        assertEquals(
            "the generated kit theme must declare the kit's row sizes",
            "${DesignKitTokens.sizeDp("listRowMin")}.dp",
            kitTokenLiteral(source, "listRowMin"),
        )
        assertEquals(
            "${DesignKitTokens.sizeDp("workspaceRowMin")}.dp",
            kitTokenLiteral(source, "workspaceRowMin"),
        )
        assertEquals(
            "${DesignKitTokens.radiusDp("field")}.dp",
            kitTokenLiteral(source, "fieldRadius"),
        )
        assertEquals(
            "${DesignKitTokens.radiusDp("sheet")}.dp",
            kitTokenLiteral(source, "sheetRadius"),
        )

        for (rung in DesignKitTokens.typeRungNames()) {
            val expected = DesignKitTokens.typeRung(rung)
            val block = Regex(
                "val ${rung}Type = TextStyle\\((.*?)\\n    \\)",
                RegexOption.DOT_MATCHES_ALL,
            ).find(source)?.groupValues?.get(1)
                ?: error("generated kit theme has no ${rung}Type")
            assertTrue(
                "generated ${rung}Type must be ${expected.sizeSp}sp/${expected.lineHeightSp}sp, was: $block",
                block.contains("fontSize = ${expected.sizeSp}.sp") &&
                    block.contains("lineHeight = ${expected.lineHeightSp}.sp") &&
                    block.contains("FontWeight(${expected.weight})"),
            )
        }
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

    private fun assertKitColor(role: String, actual: Color) {
        assertEquals(role, Color(DesignKitTokens.colorArgb(role)), actual)
    }

    private fun assertKitType(rung: String, actual: TextStyle) {
        val expected = DesignKitTokens.typeRung(rung)
        assertEquals("$rung size", expected.sizeSp.sp, actual.fontSize)
        assertEquals("$rung line height", expected.lineHeightSp.sp, actual.lineHeight)
        assertEquals("$rung weight", FontWeight(expected.weight), actual.fontWeight)
        assertEquals(
            "$rung family",
            if (expected.mono) JetBrainsMonoFamily else FontFamily.SansSerif,
            actual.fontFamily,
        )
    }

    /** `val <name> = <literal>` out of the generated kit theme. */
    private fun kitTokenLiteral(source: String, name: String): String =
        Regex("val $name = ([0-9]+\\.dp)").find(source)?.groupValues?.get(1)
            ?: error("generated kit theme has no `val $name`")

    private fun topStartRadius(shape: Shape): Float {
        val outline = shape.createOutline(
            size = Size(100f, 100f),
            layoutDirection = LayoutDirection.Ltr,
            density = Density(1f),
        )
        return (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x
    }
}
