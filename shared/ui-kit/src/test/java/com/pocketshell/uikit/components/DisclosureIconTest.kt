package com.pocketshell.uikit.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Issue #840: regression net for the single canonical disclosure affordance.
 *
 * The bug: every expandable surface drew a DIFFERENT glyph for collapsed vs
 * expanded (`›`/`v`, `>`/`v`, two distinct triangle Paths), so the two states
 * read as two different icons. The fix is that [DisclosureIcon] is ONE filled
 * triangle drawn once and *rotated* 90° between states.
 *
 * The load-bearing assertion is exactly that property: the expanded render is
 * the collapsed render **rotated 90°** (and therefore the same shape), proven
 * here by rendering both states to bitmaps, rotating the expanded bitmap back by
 * -90°, and asserting it matches the collapsed bitmap within a small tolerance.
 * A glyph-swap implementation (the old bug) would FAIL this because a rotated
 * `›` is not a `v`. This is JVM/Robolectric (NATIVE graphics), so it runs in the
 * plain Unit CI job (`testDebugUnitTest`), not only the emulator and not only
 * under recordRoborazzi.
 *
 * Issue #2113 folded the former `DisclosureIconSlice2Test` in as two extra
 * [Styling] rows — see that class's KDoc below for why it was a duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class DisclosureIconTest {

    /**
     * One styling the icon is used at. Issue #2113 folded `DisclosureIconSlice2Test`
     * in here as the two extra rows: it re-asserted this exact rotation property on
     * the SAME bare composable with ~90 lines of copy-pasted bitmap-diff helpers.
     * Its KDoc claimed it tested the icon "as composed inside each row", but its
     * bodies only varied `tint`/`size` — and rotation invariance does not depend on
     * tint. Proven vacuous: reverting the folder-tree row to a bespoke two-Paths
     * triangle (the #840 bug itself) left both of its tests green. Row-level
     * protection is `app`'s `DisclosureIconAdoptionTest`, which goes RED under that
     * same mutation.
     *
     * @param coverageFloor minimum painted-pixel fraction; smaller icons paint a
     *   smaller share of their bitmap, so this is per-case rather than shared.
     * @param agreementFloor minimum rotated-ink agreement; likewise coarser at
     *   16dp than at 48dp.
     */
    private data class Styling(
        val label: String,
        val tint: Color,
        val size: Dp,
        val coverageFloor: Double,
        val agreementFloor: Double,
    )

    private val stylings = listOf(
        // The canonical case: larger than default so rotation pixels are crisp.
        Styling("default-48dp", PocketShellColors.TextSecondary, 48.dp, 0.02, 0.80),
        // Folder/session tree row styling (was DisclosureIconSlice2Test).
        Styling("folder-tree-16dp", PocketShellColors.TextSecondary, 16.dp, 0.01, 0.78),
        // Conversation system-note row styling (was DisclosureIconSlice2Test).
        Styling("system-note-16dp", PocketShellColors.TextMuted, 16.dp, 0.01, 0.78),
    )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun renderIconBitmap(expanded: Boolean, name: String, styling: Styling): Bitmap {
        val path = "build/test-renders/$name.png"
        File(path).delete()
        // Force Record so the PNG is written even in the plain `testDebugUnitTest`
        // Unit CI job (default taskType is None, which would write nothing).
        // Roborazzi advances the compose clock to settle the rotation animation
        // before snapshotting, so this captures the fully-rotated triangle.
        captureRoboImage(
            filePath = path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        ) {
            PocketShellTheme {
                DisclosureIcon(
                    expanded = expanded,
                    modifier = Modifier.size(styling.size),
                    tint = styling.tint,
                    size = styling.size,
                )
            }
        }
        val file = File(path)
        assertTrue("Render did not produce $path", file.exists())
        return BitmapFactory.decodeFile(path)
            ?: error("Could not decode rendered bitmap at $path")
    }

    /** Rotate [src] by [degrees] about its center into a same-size bitmap. */
    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val matrix = Matrix().apply {
            postRotate(degrees, src.width / 2f, src.height / 2f)
        }
        canvas.drawBitmap(
            src,
            matrix,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }

    /**
     * A pixel is "ink" when it differs materially from the background. The
     * compose capture is transparent where nothing was drawn, so we threshold
     * on alpha; if the surface is opaque we also accept a luminance delta from
     * the top-left corner pixel (background).
     */
    private fun isInk(bmp: Bitmap, bg: Int, x: Int, y: Int): Boolean {
        val p = bmp.getPixel(x, y)
        if (android.graphics.Color.alpha(p) < 40) return false
        if (android.graphics.Color.alpha(bg) < 40) return true // transparent bg, any opaque pixel is ink
        val dr = android.graphics.Color.red(p) - android.graphics.Color.red(bg)
        val dg = android.graphics.Color.green(p) - android.graphics.Color.green(bg)
        val db = android.graphics.Color.blue(p) - android.graphics.Color.blue(bg)
        return (dr * dr + dg * dg + db * db) > 900 // ~30/channel
    }

    private fun coverage(bmp: Bitmap): Double {
        val bg = bmp.getPixel(0, 0)
        var ink = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (isInk(bmp, bg, x, y)) ink++
        return ink.toDouble() / (bmp.width * bmp.height)
    }

    /** Fraction of ink-union pixels whose ink-presence agrees between a and b. */
    private fun inkAgreement(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height)
        val bgA = a.getPixel(0, 0)
        val bgB = b.getPixel(0, 0)
        var agree = 0
        var total = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val pa = isInk(a, bgA, x, y)
                val pb = isInk(b, bgB, x, y)
                if (pa || pb) {
                    total++
                    if (pa == pb) agree++
                }
            }
        }
        return if (total == 0) 0.0 else agree.toDouble() / total
    }

    @Test
    fun expandedIsCollapsedRotated90() {
        stylings.forEach { styling ->
            val collapsed = renderIconBitmap(
                expanded = false,
                name = "disclosure-collapsed-${styling.label}",
                styling = styling,
            )
            val expanded = renderIconBitmap(
                expanded = true,
                name = "disclosure-expanded-${styling.label}",
                styling = styling,
            )

            // Sanity: both states actually paint a triangle (non-empty coverage).
            assertTrue(
                "[${styling.label}] Collapsed disclosure icon painted nothing",
                coverage(collapsed) > styling.coverageFloor,
            )
            assertTrue(
                "[${styling.label}] Expanded disclosure icon painted nothing",
                coverage(expanded) > styling.coverageFloor,
            )

            // Core property: rotating the expanded triangle back by -90° must
            // recover the collapsed triangle. A glyph swap ('›' -> 'v') would NOT
            // satisfy this — a rotated '›' is not a 'v'.
            val expandedRotatedBack = rotate(expanded, -90f)
            val agreement = inkAgreement(collapsed, expandedRotatedBack)
            assertTrue(
                "[${styling.label}] Expanded icon is not the collapsed icon rotated 90° " +
                    "(ink agreement=$agreement); the two states are different shapes, " +
                    "which is the #840 bug.",
                agreement > styling.agreementFloor,
            )

            // Guard against a vacuous pass: the UNrotated expanded bitmap must
            // clearly DISAGREE with the collapsed one (they point different
            // directions), otherwise the rotation comparison proves nothing.
            val agreementUnrotated = inkAgreement(collapsed, expanded)
            assertTrue(
                "[${styling.label}] Collapsed and expanded look identical without rotation " +
                    "(agreement=$agreementUnrotated); the icon isn't actually rotating.",
                agreementUnrotated < 0.80,
            )
        }
    }
}
