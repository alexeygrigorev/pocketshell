package com.pocketshell.uikit.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
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
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class DisclosureIconTest {

    private val iconSize = 48.dp // larger than default so rotation pixels are crisp

    @OptIn(ExperimentalRoborazziApi::class)
    private fun renderIconBitmap(expanded: Boolean, name: String): Bitmap {
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
                    modifier = Modifier.size(iconSize),
                    size = iconSize,
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
        val collapsed = renderIconBitmap(expanded = false, name = "disclosure-collapsed")
        val expanded = renderIconBitmap(expanded = true, name = "disclosure-expanded")

        // Sanity: both states actually paint a triangle (non-empty coverage).
        assertTrue("Collapsed disclosure icon painted nothing", coverage(collapsed) > 0.02)
        assertTrue("Expanded disclosure icon painted nothing", coverage(expanded) > 0.02)

        // Core property: rotating the expanded triangle back by -90° must
        // recover the collapsed triangle. A glyph swap ('›' -> 'v') would NOT
        // satisfy this — a rotated '›' is not a 'v'.
        val expandedRotatedBack = rotate(expanded, -90f)
        val agreement = inkAgreement(collapsed, expandedRotatedBack)
        assertTrue(
            "Expanded icon is not the collapsed icon rotated 90° " +
                "(ink agreement=$agreement); the two states are different shapes, " +
                "which is the #840 bug.",
            agreement > 0.80,
        )

        // Guard against a vacuous pass: the UNrotated expanded bitmap must
        // clearly DISAGREE with the collapsed one (they point different
        // directions), otherwise the rotation comparison proves nothing.
        val agreementUnrotated = inkAgreement(collapsed, expanded)
        assertTrue(
            "Collapsed and expanded look identical without rotation " +
                "(agreement=$agreementUnrotated); the icon isn't actually rotating.",
            agreementUnrotated < 0.80,
        )
    }
}
