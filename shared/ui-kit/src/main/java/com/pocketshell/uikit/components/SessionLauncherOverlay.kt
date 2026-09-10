package com.pocketshell.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

const val SESSION_LAUNCHER_OVERLAY_TAG: String = "session-launcher-overlay"
const val SESSION_COMPOSER_LAUNCHER_TAG: String = "session-composer-launcher"
const val SESSION_HOTKEYS_LAUNCHER_TAG: String = "session-hotkeys-launcher"

const val SESSION_COMPOSER_LAUNCHER_LABEL: String = "Prompt Composer"
const val SESSION_HOTKEYS_LAUNCHER_LABEL: String = "Terminal hotkeys"

/** Diameter of the primary (Prompt Composer) floating button. */
private val PrimaryDiameter: Dp = 56.dp

/** Diameter of the secondary (hotkeys) floating button. */
private val SecondaryDiameter: Dp = 44.dp

/**
 * Inset from the bottom and end edges of the terminal — the standard Android
 * floating-action corner inset, and on the 4dp grid ([PocketShellSpacing.lg]).
 */
private val CornerInset: Dp = PocketShellSpacing.lg

/**
 * Floating in-session launcher (#2631): two round overlay buttons drawn
 * *over* the terminal instead of the old docked full-width chip row.
 *
 * The previous `SessionLauncherBar` was a permanent `fillMaxWidth()` strip
 * below the terminal, so its height was subtracted from the terminal's cell
 * grid forever. This control is laid out inside the terminal's own `Box`
 * (`Modifier.align(Alignment.BottomEnd)` at the call site), which means the
 * terminal keeps every row it can measure and the launcher simply paints on
 * top of the bottom-right corner — the same treatment the Electron desktop
 * PocketShell uses.
 *
 * - Primary, accent-filled 56dp circle: opens the Prompt Composer sheet
 *   ([onOpenComposer]). The sheet itself already floats over the terminal as
 *   a `ModalBottomSheet`, so opening it still never resizes the cell grid.
 * - Secondary, quiet 44dp circle stacked above it: opens the terminal
 *   hotkeys panel ([onOpenHotkeys]). Omitted when [onOpenHotkeys] is null
 *   (no pane to receive control bytes). The composer sheet keeps its own
 *   hotkeys entry point, so the capability has two routes.
 *
 * Both buttons are icon-only, so their labels are carried as semantics
 * content descriptions rather than visible text — the whole point of the
 * change is to give the terminal its pixels back.
 */
@Composable
fun SessionLauncherOverlay(
    onOpenComposer: () -> Unit,
    onOpenHotkeys: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        // The inset lives INSIDE this control, not on the parent: the call site
        // aligns the padded column flush to the container's bottom-end corner,
        // so nothing upstream can push the buttons toward the middle without
        // moving the whole terminal with them. 16dp is the standard Android
        // floating-action inset from both edges.
        modifier = modifier
            .padding(CornerInset)
            .testTag(SESSION_LAUNCHER_OVERLAY_TAG),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        if (onOpenHotkeys != null) {
            LauncherButton(
                icon = PocketShellIcons.Keyboard,
                contentDescription = SESSION_HOTKEYS_LAUNCHER_LABEL,
                onClick = onOpenHotkeys,
                diameter = SecondaryDiameter,
                background = PocketShellColors.SurfaceElev,
                foreground = PocketShellColors.Text,
                border = PocketShellColors.Border,
                modifier = Modifier.testTag(SESSION_HOTKEYS_LAUNCHER_TAG),
            )
        }
        LauncherButton(
            icon = PocketShellIcons.Edit,
            contentDescription = SESSION_COMPOSER_LAUNCHER_LABEL,
            onClick = onOpenComposer,
            diameter = PrimaryDiameter,
            background = PocketShellColors.Accent,
            foreground = PocketShellColors.OnAccent,
            border = null,
            modifier = Modifier.testTag(SESSION_COMPOSER_LAUNCHER_TAG),
        )
    }
}

@Composable
private fun LauncherButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    diameter: Dp,
    background: Color,
    foreground: Color,
    border: Color?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(diameter)
            // The button floats over live terminal text, so it needs a real
            // elevation cue to stay legible against arbitrary scrollback.
            .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
            .background(color = background, shape = CircleShape)
            .then(
                if (border != null) {
                    Modifier.border(BorderStroke(1.dp, border), CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(diameter / 2.6f),
        )
    }
}
