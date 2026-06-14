package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Flattens a list of [Annotation]s (stored in source-bitmap pixel space) onto a
 * copy of the source bitmap and encodes it to PNG bytes (issue #764).
 *
 * Annotations are drawn directly in source-pixel coordinates onto a full-
 * resolution canvas, so the exported PNG matches what the user drew on screen
 * regardless of the `ContentScale.Fit` letterbox / zoom of the live view (the
 * coordinate mapping into source space already happened at capture time via
 * [ImageFitMapping]). Pure raster work — call it off the main thread.
 */
internal object AnnotationRenderer {

    /** Half-angle of the arrowhead wings, in radians (~28°). */
    private const val ARROWHEAD_ANGLE = 0.5f

    /** Arrowhead wing length as a multiple of the stroke width. */
    private const val ARROWHEAD_LENGTH_FACTOR = 6f

    /** Minimum arrowhead wing length in source pixels (so a thin arrow still reads). */
    private const val ARROWHEAD_MIN_LENGTH = 24f

    /**
     * Draw [annotations] onto a mutable copy of [source] in source-pixel space.
     * Returns a NEW bitmap; [source] is not mutated. The caller owns recycling
     * the returned bitmap.
     */
    fun flatten(source: Bitmap, annotations: List<Annotation>): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        annotations.forEach { drawAnnotation(canvas, it) }
        return out
    }

    /**
     * Flatten [annotations] onto [source] and encode the result to PNG bytes.
     * The intermediate flattened bitmap is recycled before returning.
     */
    fun flattenToPng(source: Bitmap, annotations: List<Annotation>): ByteArray {
        val flattened = flatten(source, annotations)
        return try {
            ByteArrayOutputStream().use { out ->
                flattened.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } finally {
            if (flattened != source) flattened.recycle()
        }
    }

    private fun drawAnnotation(canvas: Canvas, annotation: Annotation) {
        val paint = strokePaint(annotation.colorArgb, annotation.strokeWidthPx)
        when (annotation) {
            is Annotation.Freehand -> drawFreehand(canvas, annotation, paint)
            is Annotation.Arrow -> drawArrow(canvas, annotation, paint)
        }
    }

    private fun drawFreehand(canvas: Canvas, freehand: Annotation.Freehand, paint: Paint) {
        val points = freehand.points
        if (points.isEmpty()) return
        if (points.size == 1) {
            // A single tap → a dot the width of the stroke.
            val dot = Paint(paint).apply { style = Paint.Style.FILL }
            canvas.drawCircle(points[0].x, points[0].y, freehand.strokeWidthPx / 2f, dot)
            return
        }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawArrow(canvas: Canvas, arrow: Annotation.Arrow, paint: Paint) {
        val sx = arrow.start.x
        val sy = arrow.start.y
        val ex = arrow.end.x
        val ey = arrow.end.y
        canvas.drawLine(sx, sy, ex, ey, paint)

        // Two wings at +/- ARROWHEAD_ANGLE from the reverse shaft direction.
        val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
        val wing = maxOf(arrow.strokeWidthPx * ARROWHEAD_LENGTH_FACTOR, ARROWHEAD_MIN_LENGTH)
        val left = angle + Math.PI - ARROWHEAD_ANGLE
        val right = angle + Math.PI + ARROWHEAD_ANGLE
        canvas.drawLine(ex, ey, ex + (wing * cos(left)).toFloat(), ey + (wing * sin(left)).toFloat(), paint)
        canvas.drawLine(ex, ey, ex + (wing * cos(right)).toFloat(), ey + (wing * sin(right)).toFloat(), paint)
    }

    private fun strokePaint(colorArgb: Int, strokeWidthPx: Float): Paint = Paint().apply {
        isAntiAlias = true
        color = colorArgb
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}
