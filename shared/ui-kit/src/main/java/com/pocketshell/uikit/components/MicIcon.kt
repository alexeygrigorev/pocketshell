package com.pocketshell.uikit.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * Issue #453: the canonical filled-microphone [ImageVector] for PocketShell.
 *
 * This lives in `:shared:ui-kit` so EVERY mic affordance — the shared
 * [MicButton] used by the session band, and the app-side composer / dictation
 * surfaces — renders the exact same glyph. Previously the band's [MicButton]
 * drew `Text("●")` (a black dot in a cyan disc that read as a record/power
 * button, the maintainer's #1 complaint) while the app-side composer had its
 * own inline mic vector. Centralising it here means one shared component = one
 * look: there is no longer any `●` mic anywhere the user can see it.
 *
 * Material's bundled `material-icons-core` (transitively from material3) does
 * not ship a standalone `Filled.Mic`, so the silhouette is traced inline: a
 * rounded-rect capsule body centred at x=12, a U-shaped stand cradle below it,
 * a vertical stem, and a horizontal base bar — readable as a microphone from
 * ~14dp up through 24dp.
 *
 * Tint at the call site with [androidx.compose.material3.Icon]'s `tint`; the
 * path itself is filled white so an un-tinted render stays visible.
 */
val MicGlyphIcon: ImageVector = ImageVector.Builder(
    name = "MicGlyph",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addMicPath(
    fill = SolidColor(Color.White),
).build()

/**
 * Trace a Material-style filled microphone into this [ImageVector.Builder].
 *
 * The path has four sub-shapes, all rendered as one filled path:
 * 1. Capsule body: a 6x9 rounded rectangle (corner radius 3) centred at x=12,
 *    spanning y=2..11.
 * 2. Stand cradle: an open arc from (5, 11) → (19, 11) curving downward,
 *    closed back along the top via a thinner inner arc so the cradle reads as
 *    a U rather than a filled bowl.
 * 3. Stem: a short vertical stem from (12, 18) down to the base bar.
 * 4. Base bar: a horizontal bar centred on x=12 at y≈21.
 *
 * Coordinates are absolute for readability — the icon is small enough that a
 * few extra moveTo / lineTo calls cost nothing at runtime.
 */
internal fun ImageVector.Builder.addMicPath(fill: SolidColor): ImageVector.Builder {
    val builder = PathBuilder()

    // Mic body: rounded rectangle, x in [9, 15], y in [2, 11], radius 3.
    builder.moveTo(12f, 2f)
    builder.arcToRelative(3f, 3f, 0f, false, false, -3f, 3f)
    builder.lineToRelative(0f, 6f)
    builder.arcToRelative(3f, 3f, 0f, false, false, 6f, 0f)
    builder.lineToRelative(0f, -6f)
    builder.arcToRelative(3f, 3f, 0f, false, false, -3f, -3f)
    builder.close()

    // Stand cradle (U-shape) — outer arc down, inner arc back up so the
    // result is a 1.5-unit-thick curve, not a filled bowl.
    builder.moveTo(19f, 11f)
    builder.arcToRelative(7f, 7f, 0f, false, true, -14f, 0f)
    builder.lineToRelative(1.5f, 0f)
    builder.arcToRelative(5.5f, 5.5f, 0f, false, false, 11f, 0f)
    builder.close()

    // Stem from cradle bottom (12, 18) down to base bar at y=21.
    builder.moveTo(11.25f, 18f)
    builder.lineToRelative(1.5f, 0f)
    builder.lineToRelative(0f, 3f)
    builder.lineToRelative(-1.5f, 0f)
    builder.close()

    // Base bar centred on x=12.
    builder.moveTo(8f, 20.25f)
    builder.lineToRelative(8f, 0f)
    builder.lineToRelative(0f, 1.5f)
    builder.lineToRelative(-8f, 0f)
    builder.close()

    addPath(pathData = builder.nodes, fill = fill)
    return this
}
