package com.pocketshell.uikit.components

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Deterministic avatar colour per host name.
 *
 * The HostCard avatar circle used to render a flat `SurfaceElev` square
 * with a single uppercase letter ("T" for "test"). When several hosts
 * begin with the same letter the avatars became visually
 * indistinguishable in the list. This helper pulls a hue from a hash of
 * the host name and renders a colour with fixed saturation / lightness so
 * different names produce visibly different avatars while keeping the
 * dark-theme contrast budget intact.
 *
 * The mapping is `hash(name) -> H` with `S` and `L` pinned. We use the
 * standard JVM `String.hashCode()` and reduce it modulo 360 so the
 * function is:
 *
 * - **Deterministic** — the same input always yields the same output.
 *   This is unit-tested in `HostAvatarColorTest`.
 * - **Stable across processes** — `String.hashCode()` is contract-
 *   specified by the JLS, not JVM-specific.
 * - **Cheap** — no hashing library, no allocation.
 *
 * Saturation is `0.55f` and lightness is `0.42f`. Those values were
 * picked to:
 *
 * - Sit on a dark surface (`#161B22`) with enough chroma to read as
 *   "coloured" without competing with the accent (cyan, `#22D3EE`).
 * - Keep a white "T" / "P" / "G" overlay legible at the 15sp label size
 *   used by `HostCard`.
 *
 * Empty / blank names default to a neutral hue (180°) so the avatar
 * still draws something rather than vanishing.
 */
object HostAvatarColor {

    private const val SATURATION: Float = 0.55f
    private const val LIGHTNESS: Float = 0.42f

    /**
     * Map a host name to a stable [Color]. See class docs for the
     * mapping recipe.
     */
    fun colorFor(name: String): Color {
        val hue = hueFor(name)
        return hslToColor(hue = hue, saturation = SATURATION, lightness = LIGHTNESS)
    }

    /**
     * The hue (`0..359`) derived from [name]. Exposed for tests so the
     * deterministic mapping can be asserted without going through the
     * full HSL -> RGB conversion.
     */
    fun hueFor(name: String): Float {
        if (name.isEmpty()) return 180f
        // `String.hashCode()` is contract-specified by the JLS so it is
        // stable across JVMs and processes. We take `abs` to side-step
        // `Int.MIN_VALUE.absoluteValue == Int.MIN_VALUE` (signed wrap)
        // and modulo to 360 hue degrees.
        val raw = name.hashCode()
        // `Int.MIN_VALUE` cannot be made positive by `-x`; fall back to
        // a value `abs` can handle.
        val safe = if (raw == Int.MIN_VALUE) Int.MAX_VALUE else raw
        return (abs(safe) % 360).toFloat()
    }

    /**
     * Convert HSL (`hue` in degrees `0..360`, `saturation` and
     * `lightness` in `0f..1f`) to an sRGB [Color] with full alpha.
     *
     * Standard HSL -> RGB formula (Wikipedia, "HSL and HSV"). Kept
     * inline to avoid pulling a colour library for a one-shot
     * conversion.
     */
    private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
        val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
        val hPrime = (hue % 360f) / 60f
        val x = c * (1f - kotlin.math.abs(hPrime % 2f - 1f))
        val (r1, g1, b1) = when {
            hPrime < 1f -> Triple(c, x, 0f)
            hPrime < 2f -> Triple(x, c, 0f)
            hPrime < 3f -> Triple(0f, c, x)
            hPrime < 4f -> Triple(0f, x, c)
            hPrime < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = lightness - c / 2f
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }
}
