package com.pocketshell.uikit.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * A deterministic snapshot of the canonical loading vocabulary for Roborazzi
 * design fixtures only (#1772).
 *
 * Production deliberately keeps the live indeterminate Material animations in
 * `LoadingIndicator`. Roborazzi, however, drains Robolectric's paused looper
 * before capture; an infinite animation continuously posts frames and prevents
 * that drain from reaching quiescence. These test-source-only painters preserve
 * one recognizable in-flight frame without scheduling any animation work.
 */
internal object StaticLoadingIndicator {
    private val BarHeight: Dp = 4.dp
    private val SmallStroke: Dp = 2.dp
    private val MediumStroke: Dp = 3.dp

    // Two separated segments read as indeterminate rather than "42% complete".
    private const val FirstBarStartFraction = 0.08f
    private const val FirstBarEndFraction = 0.46f
    private const val SecondBarStartFraction = 0.66f
    private const val SecondBarEndFraction = 0.84f

    // A fixed frame matching the production spinner's rounded arc and left gap.
    private const val StaticSpinnerStartDegrees = -130f
    private const val StaticSpinnerSweepDegrees = 270f

    @Composable
    fun Bar(modifier: Modifier = Modifier) {
        val semantic = LocalPocketShellSemantic.current
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(BarHeight),
        ) {
            val strokeWidth = size.height
            val centerY = size.height / 2f
            val trackColor = semantic.statusIdle.copy(alpha = 0.24f)

            drawLine(
                color = trackColor,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = semantic.accent,
                start = Offset(size.width * FirstBarStartFraction, centerY),
                end = Offset(size.width * FirstBarEndFraction, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = semantic.accent,
                start = Offset(size.width * SecondBarStartFraction, centerY),
                end = Offset(size.width * SecondBarEndFraction, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }

    @Composable
    fun Spinner(
        modifier: Modifier = Modifier,
        size: SpinnerSize = SpinnerSize.Medium,
        label: String? = null,
        onAccent: Boolean = false,
    ) {
        val semantic = LocalPocketShellSemantic.current
        val arcColor = if (onAccent) PocketShellColors.Background else semantic.accent
        val diameter: Dp = when (size) {
            SpinnerSize.Small -> 18.dp
            SpinnerSize.Medium -> 28.dp
        }
        val stroke: Dp = when (size) {
            SpinnerSize.Small -> SmallStroke
            SpinnerSize.Medium -> MediumStroke
        }

        if (label == null) {
            SpinnerArc(
                modifier = modifier,
                diameter = diameter,
                stroke = stroke,
                color = arcColor,
            )
        } else {
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            ) {
                SpinnerArc(
                    diameter = diameter,
                    stroke = stroke,
                    color = arcColor,
                )
                Text(
                    text = label,
                    color = semantic.statusIdle,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    @Composable
    private fun SpinnerArc(
        diameter: Dp,
        stroke: Dp,
        color: Color,
        modifier: Modifier = Modifier,
    ) {
        Canvas(modifier = modifier.size(diameter)) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2f
            drawArc(
                color = color,
                startAngle = StaticSpinnerStartDegrees,
                sweepAngle = StaticSpinnerSweepDegrees,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(
                    width = size.width - strokePx,
                    height = size.height - strokePx,
                ),
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}
