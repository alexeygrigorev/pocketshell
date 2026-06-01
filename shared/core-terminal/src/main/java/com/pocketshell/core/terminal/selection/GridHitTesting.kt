package com.pocketshell.core.terminal.selection

import com.termux.view.TerminalView

internal fun <T> hitTestGridRegion(
    view: TerminalView,
    regions: List<T>,
    tapX: Float,
    tapY: Float,
    rowOf: (T) -> Int,
    startColOf: (T) -> Int,
    endColExclusiveOf: (T) -> Int,
): T? {
    val renderer = view.mRenderer ?: return null
    val emulator = view.mEmulator ?: return null
    if (emulator.mColumns <= 0 || emulator.mRows <= 0) return null
    val fontWidth = renderer.fontWidth
    val lineSpacing = renderer.fontLineSpacing.toFloat()
    val rowOffsetPx = renderer.fontLineSpacingAndAscent.toFloat()
    val topRow = view.topRow
    for (region in regions) {
        val rowOnScreen = rowOf(region) - topRow
        if (rowOnScreen < 0) continue
        val left = startColOf(region) * fontWidth
        val right = endColExclusiveOf(region) * fontWidth
        val top = rowOffsetPx + rowOnScreen * lineSpacing
        val bottom = top + lineSpacing
        if (tapX >= left && tapX < right && tapY >= top && tapY < bottom) {
            return region
        }
    }
    return null
}
