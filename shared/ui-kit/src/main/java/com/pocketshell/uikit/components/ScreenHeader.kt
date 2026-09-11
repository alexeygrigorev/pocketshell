package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import com.pocketshell.uikit.icons.PocketShellIcons

/**
 * Shared screen header — the title block that sits atop the tree, host list,
 * sessions dashboard, settings, etc. Encodes the issue #489 header pattern and
 * the design language locked on #479:
 *
 * ```
 * ┌───────────────────────────────────────────────────────┐
 * │ Hosts                                      [⟳]  [+]    │
 * │ 4 hosts · 7 sessions                                   │
 * └───────────────────────────────────────────────────────┘
 * ```
 *
 * - **Title** uses the Quiet screen style (28sp/34sp) and the optional subtitle
 *   uses the 16sp metadata rung.
 * - **Subtitle** (optional) is the `N x · M y` facet line — muted, dense — the
 *   same count-subtitle vocabulary `ListRow`/`SectionHeader` use. Callers build
 *   the string (e.g. `"4 hosts · 7 sessions"`); this component does not invent
 *   pluralisation.
 * - **[onBack]** renders the shared 48dp navigation affordance. The legacy
 *   [leading] slot remains for non-navigation context.
 * - **[trailing]** is one meaningful secondary action, normally a kebab that
 *   owns occasional page actions.
 *
 * Density follows [PocketShellDensity] horizontal/vertical row padding so the
 * header lines up with the rows beneath it. Colours stay on the always-dark raw
 * tokens (#477 single dark scheme) so the header never flips with the system
 * light setting.
 *
 * - **[titleTestTag] / [subtitleTestTag]** (optional) tag the title / subtitle
 *   text nodes so screens that previously hand-rolled a tagged header (e.g. the
 *   folder tree's `FOLDER_LIST_TITLE_TAG` / counts tag) keep their existing
 *   instrumentation hooks after migrating onto [ScreenHeader].
 *
 * This is presentational only — wire navigation through [onBack] and the one
 * secondary affordance through [trailing].
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMaxLines: Int = 2,
    titleMaxLines: Int = 2,
    titleStyle: TextStyle = PocketShellType.screen,
    titleTestTag: String? = null,
    subtitleTestTag: String? = null,
    onBack: (() -> Unit)? = null,
    backTestTag: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack != null || leading != null) PocketShellSpacing.md else PocketShellSpacing.xl,
                end = PocketShellSpacing.md,
                top = PocketShellSpacing.lg,
                bottom = PocketShellSpacing.xl,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            onBack != null -> {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(PocketShellDensity.tapTargetMin)
                        .let { base -> if (backTestTag == null) base else base.testTag(backTestTag) },
                ) {
                    Icon(
                        imageVector = PocketShellIcons.Back,
                        contentDescription = "Back",
                        tint = PocketShellColors.TextSecondary,
                    )
                }
            }
            leading != null -> {
                leading()
                Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = PocketShellSpacing.xs),
        ) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = titleStyle,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .semantics { heading() }
                    .let { base -> if (titleTestTag == null) base else base.testTag(titleTestTag) },
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.size(2.dp))
                Text(
                    text = subtitle,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (subtitleTestTag != null) Modifier.testTag(subtitleTestTag) else Modifier,
                )
            }
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
            Row(
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing()
            }
        }
    }
}
