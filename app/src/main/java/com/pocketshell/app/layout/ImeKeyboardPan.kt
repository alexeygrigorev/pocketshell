package com.pocketshell.app.layout

/**
 * How far (in pixels) to pan a terminal column up so its bottom controls stay
 * visible above the soft keyboard without remeasuring the terminal viewport.
 *
 * The IME bottom inset is the obstruction that must be cleared. We
 * intentionally do not subtract the navigation bar inset: some devices report
 * IME and nav-bar insets separately, and subtracting nav leaves the accessory
 * row partly covered by the keyboard.
 */
internal fun imeKeyboardPanOffsetPx(
    imeBottomPx: Int,
    @Suppress("UNUSED_PARAMETER") navBarBottomPx: Int,
): Int =
    imeBottomPx.coerceAtLeast(0)
