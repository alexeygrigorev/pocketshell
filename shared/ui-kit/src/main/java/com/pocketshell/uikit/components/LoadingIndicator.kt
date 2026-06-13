package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * The enumerated size set for [LoadingIndicator.Spinner] (#756).
 *
 * Deliberately a SMALL closed enum rather than free `dp`: the audit found 8
 * different hand-picked spinner diameters across 21+ call sites (16, 18, 20,
 * 24, 28, 32 dp, plus two bare Material defaults). Forcing callers to pick from
 * a named rung is what stops the drift from coming back. If a genuinely new
 * geometry is needed, add a rung here (and justify it) instead of passing a raw
 * value at the call site.
 *
 * - [Small] — inline / on-row / in-button affordance (a row reveal, a trailing
 *   cell, a pending list item). Folds the 16/18/20 dp inline spinners.
 * - [Medium] — the centered "whole area is loading" affordance (a full-screen
 *   or full-section loader). Folds the 24/28/32 dp and bare-default spinners.
 */
enum class SpinnerSize {
    Small,
    Medium,
}

/**
 * The canonical **indeterminate** loading affordance for PocketShell (#756).
 *
 * The maintainer's #1 UI complaint is that loading is inconsistent — "sometimes
 * a bar, sometimes a spinning thing". The structural cause (audit on #756): the
 * only ui-kit progress component, [ProgressBar], is **determinate-only**
 * (`progress: Float`), so every "something is happening, no known percentage"
 * site fell back to a raw Material 3 indicator, each hand-configured with its
 * own size, stroke, colour, and track. [LoadingIndicator] is the single shared
 * surface those sites converge onto.
 *
 * Two variants, both token-driven (colour from [LocalPocketShellSemantic],
 * geometry from named rungs — never a raw hex / free spinner diameter):
 *
 * - [Bar] — indeterminate **linear** progress. The standard top-of-screen /
 *   inline "in flight" affordance (first-connect, reconnecting, refresh).
 *   Replaces the 2/3/4 dp ad-hoc bar heights with ONE height + accent fill on a
 *   `surface-elev` track.
 * - [Spinner] — indeterminate **circular** progress. The standard centered
 *   "something is happening" affordance, with an optional label slot
 *   ("Attaching…", "waiting for tmux panes…"). Sizes come from the enumerated
 *   [SpinnerSize] rung set, not free `dp`.
 *
 * For a KNOWN percentage (usage quota, download progress) keep using the
 * determinate [ProgressBar] — these three together are the complete progress
 * vocabulary.
 *
 * Decorative + label only — no `onClick`. A loading affordance states "busy";
 * any cancel/retry action lives in a button or row beside it.
 */
object LoadingIndicator {

    /** The one canonical indeterminate-bar height. Folds the ad-hoc 2/3/4 dp. */
    private val BarHeight: Dp = 4.dp

    /** The bar track/fill corner radius — matches [ProgressBar]'s thin-track radius. */
    private val BarRadius: Dp = 4.dp

    /** Stroke for the [SpinnerSize.Small] spinner. */
    private val SmallStroke: Dp = 2.dp

    /** Stroke for the [SpinnerSize.Medium] spinner. */
    private val MediumStroke: Dp = 3.dp

    /**
     * Indeterminate linear progress bar — the canonical "in flight" strip.
     *
     * Full-width by default, [BarHeight] tall, accent fill on a `surface-elev`
     * track, both rounded to [BarRadius]. Colours come from
     * [LocalPocketShellSemantic] so the bar can't drift to a raw palette hex per
     * call site.
     */
    @Composable
    fun Bar(modifier: Modifier = Modifier) {
        val semantic = LocalPocketShellSemantic.current
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(BarHeight),
            color = semantic.accent,
            trackColor = semantic.statusIdle.copy(alpha = 0.24f),
            strokeCap = StrokeCap.Round,
        )
    }

    /**
     * Indeterminate circular spinner — the canonical "something is happening"
     * affordance.
     *
     * Centered in its available width. The diameter + stroke come from the
     * enumerated [size] rung (never a raw `dp`). When [label] is non-null it
     * renders below the spinner in [PocketShellType.bodyDense] muted text
     * ("Attaching…", "waiting for tmux panes…").
     *
     * For a spinner shown ON an accent-filled surface (e.g. the in-button
     * progress on a primary CTA while it submits), set [onAccent] = true: the
     * arc then paints with the canonical on-accent content colour
     * ([PocketShellColors.Background]) — the same colour an accent button uses
     * for its label — so the spinner is visible against the accent fill instead
     * of the accent-on-accent invisibility the default would produce. Use this
     * only inside an accent-coloured container; the default accent arc remains
     * the affordance for ordinary (background-coloured) surfaces.
     *
     * @param size which enumerated rung to paint ([SpinnerSize.Small] inline,
     *   [SpinnerSize.Medium] centered/full-area).
     * @param label optional caption rendered below the spinner.
     * @param onAccent paint the inverted on-accent colour for spinners shown on
     *   an accent-filled button/surface (default `false` = accent arc).
     */
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
            // Bare spinner — caller positions it (inline, trailing cell,
            // in-button on an accent CTA when onAccent = true, etc.).
            CircularProgressIndicator(
                modifier = modifier.size(diameter),
                color = arcColor,
                strokeWidth = stroke,
            )
        } else {
            // Labelled spinner — the centered "whole area is loading" affordance.
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(diameter),
                    color = arcColor,
                    strokeWidth = stroke,
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
}
