package com.pocketshell.core.terminal.selection

import com.termux.view.TerminalView

/**
 * A thread-safe snapshot of the live terminal viewport: the visible rows (with
 * their soft-wrap flags) plus the grid width and current scroll offset. This is
 * the small, immutable hand-off object that lets the **expensive** affordance
 * regex scan run OFF the main thread (issue #871).
 *
 * The vendored [TerminalView] / `TerminalEmulator` / `TerminalBuffer` are NOT
 * thread-safe and must only be touched on the UI thread. [extractVisibleViewportRows]
 * reads them on the main thread and copies the result into this plain-data
 * snapshot; the regex passes ([urlRegionsForRows] / [filePathRegionsForRows])
 * then run against the snapshot on any dispatcher.
 *
 * @property rows the visible [VisualRow]s, top-to-bottom, each a pure string
 *   copy with its `wrapsToNext` flag — no live emulator reference.
 * @property columns the live grid width at extraction time, used to clip
 *   per-row spans.
 */
public data class ViewportRowsSnapshot(
    val rows: List<VisualRow>,
    val columns: Int,
) {
    public val isEmpty: Boolean get() = columns <= 0 || rows.isEmpty()

    public companion object {
        public val EMPTY: ViewportRowsSnapshot = ViewportRowsSnapshot(emptyList(), 0)
    }
}

/**
 * Reads the currently visible viewport of [view] into a thread-safe
 * [ViewportRowsSnapshot] (issue #871). MUST be called on the UI thread — it
 * touches the live (non-thread-safe) emulator/screen — but does only the cheap
 * work: one `getSelectedText` per visible row (typically <30 on a phone) plus
 * the soft-wrap flags. The heavy regex/reassembly work is left to the pure
 * [urlRegionsForRows] / [filePathRegionsForRows] functions, which the caller may
 * then run OFF the main thread against the returned snapshot.
 *
 * This is the extraction half of [findVisibleUrls] / [findVisibleFilePaths]; the
 * two scanners share the exact same row-read loop, so it is hoisted here once.
 *
 * Returns [ViewportRowsSnapshot.EMPTY] when no emulator is attached yet or the
 * grid size is zero.
 */
public fun extractVisibleViewportRows(view: TerminalView): ViewportRowsSnapshot {
    val emulator = view.mEmulator ?: return ViewportRowsSnapshot.EMPTY
    val screen = emulator.screen ?: return ViewportRowsSnapshot.EMPTY
    val columns = emulator.mColumns
    val rows = emulator.mRows
    if (columns <= 0 || rows <= 0) return ViewportRowsSnapshot.EMPTY

    val topRow = view.topRow
    val firstRow = topRow
    val lastRowExclusive = topRow + rows

    val visualRows = ArrayList<VisualRow>(rows)
    for (row in firstRow until lastRowExclusive) {
        val line: String = try {
            screen.getSelectedText(0, row, columns, row)
        } catch (_: Throwable) {
            // Mid-resize the vendored emulator occasionally throws AIOOBE.
            visualRows += VisualRow(row = row, text = "", wrapsToNext = false)
            continue
        }
        val wraps = try {
            row + 1 < lastRowExclusive && screen.getLineWrap(row)
        } catch (_: Throwable) {
            false
        }
        visualRows += VisualRow(row = row, text = line, wrapsToNext = wraps)
    }
    return ViewportRowsSnapshot(rows = visualRows, columns = columns)
}
