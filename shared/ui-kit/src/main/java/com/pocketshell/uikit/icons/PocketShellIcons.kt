package com.pocketshell.uikit.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The production Quiet icon registry.
 *
 * These paths are kept in the same 24dp coordinate space as the design-kit
 * source files. The registry owns the vectors so production callers share the
 * exact same shape, stroke width and line treatment instead of importing a
 * vendor icon or tracing a one-off path at the call site.
 */
object PocketShellIcons {
    val Back: ImageVector get() = vectors.getValue("back")
    val Chevron: ImageVector get() = vectors.getValue("chevron")
    val Down: ImageVector get() = vectors.getValue("down")
    val Up: ImageVector get() = vectors.getValue("up")
    val Close: ImageVector get() = vectors.getValue("close")
    val Plus: ImageVector get() = vectors.getValue("plus")
    val Minus: ImageVector get() = vectors.getValue("minus")
    val Search: ImageVector get() = vectors.getValue("search")
    val More: ImageVector get() = vectors.getValue("more")
    val Check: ImageVector get() = vectors.getValue("check")
    val Hexagon: ImageVector get() = vectors.getValue("hexagon")
    val Code: ImageVector get() = vectors.getValue("code")
    val Terminal: ImageVector get() = vectors.getValue("terminal")
    val Zap: ImageVector get() = vectors.getValue("zap")
    val Folder: ImageVector get() = vectors.getValue("folder")
    val File: ImageVector get() = vectors.getValue("file")
    val Image: ImageVector get() = vectors.getValue("image")
    val Key: ImageVector get() = vectors.getValue("key")
    val Lock: ImageVector get() = vectors.getValue("lock")
    val Shield: ImageVector get() = vectors.getValue("shield")
    val Server: ImageVector get() = vectors.getValue("server")
    val Mic: ImageVector get() = vectors.getValue("mic")
    val Paperclip: ImageVector get() = vectors.getValue("paperclip")
    val Send: ImageVector get() = vectors.getValue("send")
    val History: ImageVector get() = vectors.getValue("history")
    val Settings: ImageVector get() = vectors.getValue("settings")
    val Sliders: ImageVector get() = vectors.getValue("sliders")
    val Keyboard: ImageVector get() = vectors.getValue("keyboard")
    val Info: ImageVector get() = vectors.getValue("info")
    val Warning: ImageVector get() = vectors.getValue("warning")
    val Refresh: ImageVector get() = vectors.getValue("refresh")
    val Download: ImageVector get() = vectors.getValue("download")
    val Upload: ImageVector get() = vectors.getValue("upload")
    val External: ImageVector get() = vectors.getValue("external")
    val Copy: ImageVector get() = vectors.getValue("copy")
    val Edit: ImageVector get() = vectors.getValue("edit")
    val Trash: ImageVector get() = vectors.getValue("trash")
    val Ports: ImageVector get() = vectors.getValue("ports")
    val Chart: ImageVector get() = vectors.getValue("chart")
    val Flash: ImageVector get() = vectors.getValue("flash")
    val Eye: ImageVector get() = vectors.getValue("eye")
    val Pause: ImageVector get() = vectors.getValue("pause")
    val Stop: ImageVector get() = vectors.getValue("stop")
    val Fingerprint: ImageVector get() = vectors.getValue("fingerprint")
    val Wifi: ImageVector get() = vectors.getValue("wifi")

    /** Resolve a design-kit name for callers that receive an icon identifier. */
    fun byName(name: String): ImageVector? = vectors[name]

    private val vectors: Map<String, ImageVector> by lazy {
        paths.mapValues { (name, pathData) -> vector(name, pathData) }
    }

    private val paths: Map<String, List<String>> = linkedMapOf(
        "back" to listOf("M19 12H5", "M12 19l-7-7 7-7"),
        "chevron" to listOf("M9 5l7 7-7 7"),
        "down" to listOf("M5 9l7 7 7-7"),
        "up" to listOf("M12 20V4", "M5 11l7-7 7 7"),
        "close" to listOf("M6 6l12 12", "M18 6L6 18"),
        "plus" to listOf("M12 5v14", "M5 12h14"),
        "minus" to listOf("M5 12h14"),
        "search" to listOf(
            "M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0-16",
            "M17 17l5 5",
        ),
        "more" to listOf("M12 3v.1", "M12 12v.1", "M12 21v.1"),
        "check" to listOf("M4 12l5 5L20 6"),
        "hexagon" to listOf("M12 2l9 5v10l-9 5-9-5V7z"),
        "code" to listOf("M8 6l-6 6 6 6", "M16 6l6 6-6 6"),
        "terminal" to listOf("M4 6l6 6-6 6", "M12 18h8"),
        "zap" to listOf("M13 2L3 14h8l-1 8 11-12h-8z"),
        "folder" to listOf("M3 5h7l2 3h9v12H3z"),
        "file" to listOf("M4 2h10l6 6v14H4z", "M14 2v6h6"),
        "image" to listOf(
            "M3 3h18v18H3z",
            "M3 16l6-6 4 4 3-3 5 5",
            "M8 7h.1",
        ),
        "key" to listOf(
            "M9 4a5 5 0 1 0 0 10a5 5 0 1 0 0-10",
            "M13 12l8 8",
            "M17 16l3-3",
            "M19 18l3-3",
        ),
        "lock" to listOf("M6 10h12v11H6z", "M8 10V6a4 4 0 0 1 8 0v4"),
        "shield" to listOf(
            "M12 2l8 3v7c0 5-8 10-8 10S4 17 4 12V5z",
            "M8 11l3 3 5-5",
        ),
        "server" to listOf(
            "M3 3h18v7H3z",
            "M3 14h18v7H3z",
            "M7 6.5h.1",
            "M7 17.5h.1",
        ),
        "mic" to listOf(
            "M9 5a3 3 0 0 1 6 0v8a3 3 0 0 1-6 0z",
            "M5 11v2a7 7 0 0 0 14 0v-2",
            "M12 20v2",
        ),
        "paperclip" to listOf(
            "M8 14l8-8a3 3 0 0 1 4 4L9 21a5 5 0 0 1-7-7L13 3a2 2 0 0 1 3 3L5 17",
        ),
        "send" to listOf("M12 21V3", "M5 10l7-7 7 7"),
        "history" to listOf(
            "M3 4v6h6",
            "M3 10a9 9 0 1 1 1 9",
            "M12 7v5l4 2",
        ),
        "settings" to listOf(
            "M10 3h4l1 3 3 1 3 3v4l-3 1-1 3-3 3h-4l-1-3-3-1-3-3v-4l3-1 1-3z",
            "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8",
        ),
        "sliders" to listOf(
            "M4 5h16",
            "M4 12h16",
            "M4 19h16",
            "M8 2v6",
            "M16 9v6",
            "M10 16v6",
        ),
        "keyboard" to listOf(
            "M2 5h20v14H2z",
            "M5 9h.1",
            "M9 9h.1",
            "M13 9h.1",
            "M17 9h.1",
            "M5 13h.1",
            "M9 13h.1",
            "M13 13h.1",
            "M17 13h.1",
            "M8 16h8",
        ),
        "info" to listOf(
            "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20",
            "M12 11v6",
            "M12 7h.1",
        ),
        "warning" to listOf(
            "M12 2L1 21h22z",
            "M12 9v5",
            "M12 18h.1",
        ),
        "refresh" to listOf("M20 3v6h-6", "M20 9a9 9 0 1 0 0 7"),
        "download" to listOf(
            "M12 3v12",
            "M6 9l6 6 6-6",
            "M3 16v5h18v-5",
        ),
        "upload" to listOf(
            "M12 16V3",
            "M6 9l6-6 6 6",
            "M3 16v5h18v-5",
        ),
        "external" to listOf(
            "M14 3h7v7",
            "M21 3l-11 11",
            "M10 3H3v18h18v-7",
        ),
        "copy" to listOf("M8 8h13v13H8z", "M16 8V3H3v13h5"),
        "edit" to listOf("M4 17l-1 5 5-1L21 8l-4-4z", "M14 7l4 4"),
        "trash" to listOf(
            "M3 6h18",
            "M6 6v15h12V6",
            "M9 6V3h6v3",
            "M10 10v7",
            "M14 10v7",
        ),
        "ports" to listOf(
            "M3 7h18",
            "M16 3l5 4-5 4",
            "M21 17H3",
            "M8 13l-5 4 5 4",
        ),
        "chart" to listOf("M5 21V10", "M12 21V3", "M19 21V7"),
        "flash" to listOf("M13 2L4 14h7v8L21 9h-8z"),
        "eye" to listOf(
            "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6",
        ),
        "pause" to listOf("M8 4v16", "M16 4v16"),
        "stop" to listOf("M5 5h14v14H5z"),
        "fingerprint" to listOf(
            "M5 19V10a7 7 0 0 1 14 0v5",
            "M8 21V10a4 4 0 0 1 8 0v11",
            "M12 10v12",
        ),
        "wifi" to listOf(
            "M2 8a16 16 0 0 1 20 0",
            "M5 12a11 11 0 0 1 14 0",
            "M9 16a5 5 0 0 1 6 0",
            "M12 20h.1",
        ),
    )
}

private fun vector(name: String, pathData: List<String>): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        pathData.forEach { data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
