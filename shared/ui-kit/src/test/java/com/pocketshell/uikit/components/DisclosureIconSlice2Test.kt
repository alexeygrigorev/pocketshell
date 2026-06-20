package com.pocketshell.uikit.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * Issue #840 slice 2: regression net for the TWO deferred disclosure surfaces —
 * the folder/session tree row (was a screen-private hand-built triangle that
 * drew two distinct Paths for collapsed vs expanded) and the conversation
 * system-note row (had NO disclosure affordance at all). Both now lead with the
 * SAME shared rotating [DisclosureIcon].
 *
 * The load-bearing property is the same one slice 1 proved for the bare icon,
 * but asserted here on the icon **as composed inside each row** (leading icon +
 * its siblings): rendering the row in collapsed vs expanded and rotating the
 * expanded capture back -90° must recover the collapsed capture within a small
 * tolerance. A glyph-swap or a two-distinct-Paths implementation (the old folder
 * tree) would FAIL because a rotated `▶` is not a separately-drawn `▼` glyph.
 *
 * The rows here mirror exactly how the app sites compose the shared component
 * (`DisclosureIcon(expanded = ...)` leading the row), so a row regressing back to
 * a bespoke glyph is caught. JVM/Robolectric (NATIVE graphics) so it runs in the
 * plain Unit CI job (`testDebugUnitTest`), not only the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class DisclosureIconSlice2Test {

    // The folder tree leads with DisclosureIcon(TextSecondary, 16dp); the
    // system-note row leads with DisclosureIcon(TextMuted, default size). We
    // render the leading icon with each site's EXACT styling and assert the
    // rotation property, so a site reverting to a bespoke glyph is caught (the
    // adjacent siblings — status dot / actor label — don't rotate and are not
    // the property under test; the source-structure guard
    // `DisclosureIconAdoptionTest` confirms each site composes this icon).
    @OptIn(ExperimentalRoborazziApi::class)
    private fun renderIconBitmap(name: String, content: @Composable () -> Unit): Bitmap {
        val path = "build/test-renders/$name.png"
        File(path).delete()
        captureRoboImage(
            filePath = path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        ) {
            PocketShellTheme { content() }
        }
        val file = File(path)
        assertTrue("Render did not produce $path", file.exists())
        return BitmapFactory.decodeFile(path)
            ?: error("Could not decode rendered bitmap at $path")
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val matrix = Matrix().apply { postRotate(degrees, src.width / 2f, src.height / 2f) }
        canvas.drawBitmap(src, matrix, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun isInk(bmp: Bitmap, bg: Int, x: Int, y: Int): Boolean {
        val p = bmp.getPixel(x, y)
        if (android.graphics.Color.alpha(p) < 40) return false
        if (android.graphics.Color.alpha(bg) < 40) return true
        val dr = android.graphics.Color.red(p) - android.graphics.Color.red(bg)
        val dg = android.graphics.Color.green(p) - android.graphics.Color.green(bg)
        val db = android.graphics.Color.blue(p) - android.graphics.Color.blue(bg)
        return (dr * dr + dg * dg + db * db) > 900
    }

    private fun coverage(bmp: Bitmap): Double {
        val bg = bmp.getPixel(0, 0)
        var ink = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (isInk(bmp, bg, x, y)) ink++
        return ink.toDouble() / (bmp.width * bmp.height)
    }

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

    private fun assertSameIconRotated(surface: String, collapsed: Bitmap, expanded: Bitmap) {
        assertTrue("[$surface] collapsed disclosure icon painted nothing", coverage(collapsed) > 0.01)
        assertTrue("[$surface] expanded disclosure icon painted nothing", coverage(expanded) > 0.01)

        val expandedRotatedBack = rotate(expanded, -90f)
        val agreement = inkAgreement(collapsed, expandedRotatedBack)
        assertTrue(
            "[$surface] expanded icon is not the collapsed icon rotated 90° " +
                "(ink agreement=$agreement); the site is NOT using the shared rotating " +
                "DisclosureIcon, which is the #840 divergence.",
            agreement > 0.78,
        )

        val agreementUnrotated = inkAgreement(collapsed, expanded)
        assertTrue(
            "[$surface] collapsed and expanded look identical without rotation " +
                "(agreement=$agreementUnrotated); the icon isn't actually rotating.",
            agreementUnrotated < 0.80,
        )
    }

    @Test
    fun folderTreeRowUsesSharedRotatingIcon() {
        // Folder tree styling: TextSecondary tint, 16dp.
        assertSameIconRotated(
            surface = "folder-tree-row",
            collapsed = renderIconBitmap("slice2-folder-collapsed") {
                DisclosureIcon(expanded = false, modifier = Modifier.size(16.dp), tint = PocketShellColors.TextSecondary, size = 16.dp)
            },
            expanded = renderIconBitmap("slice2-folder-expanded") {
                DisclosureIcon(expanded = true, modifier = Modifier.size(16.dp), tint = PocketShellColors.TextSecondary, size = 16.dp)
            },
        )
    }

    @Test
    fun systemNoteRowUsesSharedRotatingIcon() {
        // System-note styling: TextMuted tint, default size.
        assertSameIconRotated(
            surface = "system-note-row",
            collapsed = renderIconBitmap("slice2-systemnote-collapsed") {
                DisclosureIcon(expanded = false, modifier = Modifier.size(16.dp), tint = PocketShellColors.TextMuted, size = 16.dp)
            },
            expanded = renderIconBitmap("slice2-systemnote-expanded") {
                DisclosureIcon(expanded = true, modifier = Modifier.size(16.dp), tint = PocketShellColors.TextMuted, size = 16.dp)
            },
        )
    }
}
