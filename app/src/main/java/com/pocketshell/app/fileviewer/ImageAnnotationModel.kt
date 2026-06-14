package com.pocketshell.app.fileviewer

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

/**
 * Image-annotation model for the file viewer (issue #764, MVP).
 *
 * The maintainer opens a remote image, taps the markup toggle, draws **freehand
 * (Pen)** strokes and **Arrows** on top of it, and submits. On submit the
 * annotations are flattened onto the source bitmap and the annotated PNG is
 * written to the host inbox over the SAME warm SSH lease the viewer used to
 * fetch (D21, no new dial) — mirroring the #714 review-submit flow.
 *
 * Everything here is pure and Android-free so it unit-tests with no emulator:
 *  - [ImagePoint] / [Annotation] — the vector ops, stored in **source-bitmap
 *    pixel space** (NOT screen space) so the flattened PNG matches what the user
 *    drew regardless of the `ContentScale.Fit` letterbox.
 *  - [ImageFitMapping] — the screen↔source coordinate transform. This is the
 *    top risk the design concept flagged; it has its own focused unit test.
 *  - [ImageAnnotationState] — the VM-owned mode/tool/annotation list with pure
 *    `withTool` / `withAnnotation` / `undone` / `cleared` ops.
 *  - [AnnotationExport] — the `pocketshell_annotation` YAML sidecar builder.
 */

/** A point in **source-bitmap pixel space** (origin top-left, x→right, y→down). */
data class ImagePoint(val x: Float, val y: Float)

/** The drawing tools the viewer offers. [Pan] re-enables pinch/pan (no drawing). */
enum class AnnotationTool {
    /** Reposition/zoom the image; drawing is disabled. The default. */
    Pan,

    /** Freehand stroke — drag accumulates a polyline. */
    Pen,

    /** A straight arrow from the drag start to the drag end. */
    Arrow,

    /** A rectangle outline — drag defines the two opposite corners (#764 v2). */
    Rect,

    /** An ellipse/circle outline — drag defines the bounding box (#764 v2). */
    Circle,

    /** A text label — tap to place an anchor, then enter the text (#764 v2). */
    Text,
}

/**
 * One vector annotation, stored in source-bitmap pixel space. [colorArgb] is the
 * packed ARGB the stroke renders with; [strokeWidthPx] is the stroke width in
 * **source pixels** (so it scales with the image, not the screen).
 */
sealed interface Annotation {
    val colorArgb: Int
    val strokeWidthPx: Float

    /** A freehand polyline — [points] are the captured drag samples. */
    data class Freehand(
        val points: List<ImagePoint>,
        override val colorArgb: Int,
        override val strokeWidthPx: Float,
    ) : Annotation

    /** A straight arrow from [start] to [end] (arrowhead drawn at [end]). */
    data class Arrow(
        val start: ImagePoint,
        val end: ImagePoint,
        override val colorArgb: Int,
        override val strokeWidthPx: Float,
    ) : Annotation

    /**
     * A rectangle outline (#764 v2). [start]/[end] are any two opposite corners
     * of the drag; the renderer normalises them to a top-left/bottom-right rect
     * so a drag in any direction produces the same box.
     */
    data class Rectangle(
        val start: ImagePoint,
        val end: ImagePoint,
        override val colorArgb: Int,
        override val strokeWidthPx: Float,
    ) : Annotation

    /**
     * An ellipse outline (#764 v2) inscribed in the bounding box defined by the
     * two opposite corners [start]/[end] (a square box draws a circle).
     */
    data class Circle(
        val start: ImagePoint,
        val end: ImagePoint,
        override val colorArgb: Int,
        override val strokeWidthPx: Float,
    ) : Annotation

    /**
     * A text label (#764 v2) anchored at [anchor] (its top-left baseline box).
     * [textSizePx] is the font size in **source pixels** so it scales with the
     * image, exactly like [strokeWidthPx]. [strokeWidthPx] is unused for fill
     * text but kept on the interface; the glyph fill uses [colorArgb].
     */
    data class Text(
        val text: String,
        val anchor: ImagePoint,
        val textSizePx: Float,
        override val colorArgb: Int,
        override val strokeWidthPx: Float = 0f,
    ) : Annotation
}

/**
 * The pure screen↔source coordinate transform for an image drawn with
 * `ContentScale.Fit` (letterbox) inside a [viewportWidth] × [viewportHeight]
 * box. In annotate mode the viewer pins zoom=1 / pan=0 so the mapping is just
 * the fit-scale + centering offset — the one non-trivial bit, unit-tested.
 *
 * `Fit` scales the source uniformly by [scale] = min(viewW/srcW, viewH/srcH) so
 * the whole image is visible, then centers it, leaving letterbox bars on the
 * axis with slack. A screen point maps to source pixels by subtracting the
 * centering offset and dividing by the scale; the inverse multiplies and adds.
 *
 * Construct via [of]; the source/viewport dims must be positive.
 */
data class ImageFitMapping(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val viewportWidth: Float,
    val viewportHeight: Float,
) {
    /** Uniform fit scale (source-pixels → screen-pixels). */
    val scale: Float = minOf(
        viewportWidth / sourceWidth,
        viewportHeight / sourceHeight,
    )

    /** Displayed image width/height in screen pixels. */
    val displayedWidth: Float = sourceWidth * scale
    val displayedHeight: Float = sourceHeight * scale

    /** Centering letterbox offset (screen pixels) of the displayed image. */
    val offsetX: Float = (viewportWidth - displayedWidth) / 2f
    val offsetY: Float = (viewportHeight - displayedHeight) / 2f

    /**
     * Map a screen point (relative to the viewport top-left) to source-bitmap
     * pixel space. Points outside the displayed image clamp to its edges so a
     * stroke that strays into the letterbox bar still lands on the image rather
     * than off-canvas.
     */
    fun screenToSource(screenX: Float, screenY: Float): ImagePoint {
        val sx = ((screenX - offsetX) / scale).coerceIn(0f, sourceWidth.toFloat())
        val sy = ((screenY - offsetY) / scale).coerceIn(0f, sourceHeight.toFloat())
        return ImagePoint(sx, sy)
    }

    /** Map a source-pixel point back to a screen point (the inverse). */
    fun sourceToScreen(point: ImagePoint): Pair<Float, Float> =
        (point.x * scale + offsetX) to (point.y * scale + offsetY)

    /** Convert a screen-space stroke width to the equivalent source-pixel width. */
    fun screenToSourceLength(screenLength: Float): Float = screenLength / scale

    companion object {
        fun of(
            sourceWidth: Int,
            sourceHeight: Int,
            viewportWidth: Float,
            viewportHeight: Float,
        ): ImageFitMapping {
            require(sourceWidth > 0 && sourceHeight > 0) { "source dims must be positive" }
            require(viewportWidth > 0f && viewportHeight > 0f) { "viewport dims must be positive" }
            return ImageFitMapping(sourceWidth, sourceHeight, viewportWidth, viewportHeight)
        }
    }
}

/**
 * VM-owned annotation state (issue #764). Held in [FileViewerViewModel] — not
 * Compose `remember` — so it survives scroll/config-change, exactly like
 * [ReviewState]. All ops are pure and unit-tested.
 *
 *  - [active]      — annotate mode on/off. When on, the image overlay captures
 *                    drawing and pinch/pan is gated by the selected [tool].
 *  - [tool]        — the active tool (Pan default; Pen/Arrow draw).
 *  - [colorArgb]   — the stroke colour new annotations get.
 *  - [annotations] — the ordered op list; undo pops the last.
 *  - [note]        — an optional one-line caption written into the YAML sidecar.
 *  - [submitting]  — true while a flatten+upload is in flight.
 */
data class ImageAnnotationState(
    val active: Boolean = false,
    val tool: AnnotationTool = AnnotationTool.Pan,
    val colorArgb: Int = DEFAULT_COLOR_ARGB,
    val annotations: List<Annotation> = emptyList(),
    val note: String? = null,
    val submitting: Boolean = false,
) {
    /** True when there is at least one annotation to flatten + submit. */
    val hasAnnotations: Boolean get() = annotations.isNotEmpty()

    /** Toggle annotate mode; leaving resets the tool to Pan. */
    fun toggledActive(): ImageAnnotationState =
        if (active) copy(active = false, tool = AnnotationTool.Pan) else copy(active = true)

    /** Select a drawing tool. */
    fun withTool(next: AnnotationTool): ImageAnnotationState = copy(tool = next)

    /** Select the stroke colour for new annotations. */
    fun withColor(argb: Int): ImageAnnotationState = copy(colorArgb = argb)

    /** Append a finished annotation to the op list. */
    fun withAnnotation(annotation: Annotation): ImageAnnotationState =
        copy(annotations = annotations + annotation)

    /** Undo — drop the last annotation (a no-op when the list is empty). */
    fun undone(): ImageAnnotationState =
        if (annotations.isEmpty()) this else copy(annotations = annotations.dropLast(1))

    /** Set/overwrite the caption note; a blank value clears it. */
    fun withNote(text: String): ImageAnnotationState =
        copy(note = text.trim().ifEmpty { null })

    /**
     * Reset to an empty annotate session but keep mode + tool + colour — used
     * after a successful submit so the maintainer can keep marking up.
     */
    fun cleared(): ImageAnnotationState =
        copy(annotations = emptyList(), note = null, submitting = false)

    companion object {
        /** Default stroke colour: PocketShell semantic Red (`0xFFEF4444`). */
        const val DEFAULT_COLOR_ARGB: Int = 0xFFEF4444.toInt()

        /**
         * The fixed swatch row offered in annotate mode — PocketShell accent +
         * semantic colours plus white. ARGB ints so the pure model has no
         * Compose dependency.
         */
        val SWATCHES: List<Int> = listOf(
            0xFFEF4444.toInt(), // Red
            0xFFF59E0B.toInt(), // Amber
            0xFF22C55E.toInt(), // Green
            0xFF22D3EE.toInt(), // Accent (cyan)
            0xFFFFFFFF.toInt(), // White
        )
    }
}

/**
 * One-shot result of an annotation submit (issue #764) — the file viewer screen
 * collects these from [FileViewerViewModel.annotationEvents] and surfaces a
 * confirmation sheet (saved path) / a calm one-line error toast. Mirrors
 * [ReviewSubmitEvent].
 */
sealed interface AnnotationSubmitEvent {
    /** The flattened PNG was written to [remotePath] on [host]. */
    data class Success(val host: String, val remotePath: String) : AnnotationSubmitEvent

    /** The submit failed; [message] is a short user-facing reason. */
    data class Failure(val message: String) : AnnotationSubmitEvent
}

/**
 * Pure assembly + serialization of the `pocketshell_annotation` YAML sidecar
 * (issue #764). The PNG is the primary artifact; this YAML is the machine-
 * readable wrapper (host + source provenance + the flattened-image path + an
 * optional note) so the orchestrator's inbox watcher extends naturally from the
 * `pocketshell_review` convention. Kept Android/SSH-free so it is unit-testable.
 */
object AnnotationExport {

    const val TYPE: String = "pocketshell_annotation"
    const val SCHEMA: Int = 1

    /**
     * Build the canonical `pocketshell_annotation` YAML. [submittedAt] is
     * supplied by the caller (ISO-8601 UTC) so this builder is pure and never
     * reads the clock. A multi-line [note] emits as a literal block scalar (`|`)
     * via the shared snakeyaml representer (mirrors [ReviewExport]); a blank
     * note is omitted entirely.
     */
    fun buildAnnotationYaml(
        host: String,
        sourceFile: String,
        image: String,
        submittedAt: String,
        note: String?,
    ): String {
        val root = LinkedHashMap<String, Any>()
        root["type"] = TYPE
        root["schema"] = SCHEMA
        root["host"] = host
        root["source_file"] = sourceFile
        root["image"] = image
        root["submitted_at"] = submittedAt
        note?.takeIf { it.isNotBlank() }?.let { root["note"] = it }
        return yaml().dump(root)
    }

    /**
     * A snakeyaml [Yaml] tuned exactly like [ReviewExport]'s: block style, no
     * document-start marker, multi-line strings as literal block scalars (`|`).
     */
    private fun yaml(): Yaml {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = false
            isAllowUnicode = true
            width = Int.MAX_VALUE
        }
        return Yaml(LiteralBlockRepresenter(options), options)
    }

    private class LiteralBlockRepresenter(options: DumperOptions) : Representer(options) {
        init {
            representers[String::class.java] = RepresentString()
        }

        private inner class RepresentString : org.yaml.snakeyaml.representer.Represent {
            override fun representData(data: Any): Node {
                val value = data as String
                return if (value.contains('\n')) {
                    representScalar(Tag.STR, value, DumperOptions.ScalarStyle.LITERAL)
                } else {
                    representScalar(Tag.STR, value)
                }
            }
        }
    }
}
