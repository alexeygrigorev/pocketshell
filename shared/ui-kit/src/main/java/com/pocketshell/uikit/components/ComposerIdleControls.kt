package com.pocketshell.uikit.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

/** Stable tags, shared by production and the render harness. */
const val COMPOSER_ATTACH_TAG: String = "composer-attach"
const val COMPOSER_TOOLS_TRIGGER_TAG: String = "composer-tools-trigger"
const val COMPOSER_SEND_TAG: String = "composer-send"
const val COMPOSER_MIC_TAG: String = "composer-mic"

/** The one name for paste-without-Enter (#2635 C3). */
const val COMPOSER_PASTE_LABEL: String = "Paste without sending"

/**
 * The composer's idle controls row — the REAL component, in the kit.
 *
 * ```
 * [📎] [+] ....................... [Send ➤] (MIC)
 * ```
 *
 * #2635: this used to be app2-private, with a hand-written MIRROR of it in
 * `ComposerRenderFixtures.kt` so `scripts/render.sh` had something to draw. The
 * audit found the mirror had drifted twice in one session — it still titled the
 * sheet "Prompt Composer" while production says "Input to <target>", and still
 * described an attachment destination line production had already deleted. A
 * render of a copy proves nothing about the screen; the fix is not a better
 * copy, it is no copy.
 *
 * The parameters are deliberately primitive (booleans and lambdas): the kit
 * cannot see app2's `ComposerUiState`, and it should not — a shared control row
 * that knows about a specific screen's state class is not shared.
 *
 * #2635 C3: there are four controls, not five. Paste was a sixth ~70dp target
 * that made the row overflow at 360dp; it is now the long-press of [onSend]
 * (and a named row in the "+" sheet). Send is the primary verb, so it keeps the
 * tap.
 */
@Composable
fun ComposerIdleControls(
    onAttach: () -> Unit,
    onOpenTools: () -> Unit,
    onSend: () -> Unit,
    onPaste: () -> Unit,
    onMicTap: () -> Unit,
    modifier: Modifier = Modifier,
    attachEnabled: Boolean = true,
    toolsEnabled: Boolean = true,
    sendEnabled: Boolean = true,
    micEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(COMPOSER_CONTROL_GAP),
    ) {
        // Attach is a one-tap paperclip (#2630). It was the app's most-used
        // composer action and #2529 buried it two taps deep behind the "+"
        // sheet; the sheet keeps the genuinely occasional tools.
        ComposerGlyphButton(
            icon = PocketShellIcons.Paperclip,
            contentDescription = "Attach files",
            onClick = onAttach,
            enabled = attachEnabled,
            testTag = COMPOSER_ATTACH_TAG,
        )
        ComposerGlyphButton(
            icon = PocketShellIcons.Plus,
            contentDescription = "Add to input",
            onClick = onOpenTools,
            enabled = toolsEnabled,
            testTag = COMPOSER_TOOLS_TRIGGER_TAG,
        )
        Spacer(modifier = Modifier.weight(1f))
        ComposerSendButton(
            onClick = onSend,
            onLongClick = onPaste,
            enabled = sendEnabled,
            modifier = Modifier.testTag(COMPOSER_SEND_TAG),
        )
        MicButton(
            state = if (micEnabled) MicButtonState.Idle else MicButtonState.Disabled,
            onClick = onMicTap,
            modifier = Modifier
                .size(PocketShellDensity.tapTargetMin)
                .testTag(COMPOSER_MIC_TAG),
        )
    }
}

/**
 * The accent Send pill. [onLongClick] is paste-without-Enter (#2635 C3) — it is
 * published as a long-click label AND as a named accessibility action, because
 * TalkBack cannot long-press a custom target.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerSendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val containerColor = if (enabled) PocketShellColors.Accent else PocketShellColors.SurfaceElev
    val contentColor = if (enabled) PocketShellColors.OnAccent else PocketShellColors.TextMuted
    Row(
        modifier = modifier
            .height(PocketShellDensity.tapTargetMin)
            .clip(PocketShellShapes.medium)
            .background(color = containerColor, shape = PocketShellShapes.medium)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClick?.let { COMPOSER_PASTE_LABEL },
            )
            .semantics {
                if (onLongClick != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(COMPOSER_PASTE_LABEL) {
                            onLongClick()
                            true
                        },
                    )
                }
            }
            .padding(horizontal = COMPOSER_SEND_PAD_H),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(COMPOSER_SEND_GAP),
    ) {
        Text(
            text = "Send",
            color = contentColor,
            style = PocketShellType.button,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            imageVector = PocketShellIcons.Send,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(COMPOSER_GLYPH_SIZE_LARGE),
        )
    }
}

/** A 48dp icon target with a small glyph — shrink the ink, not the hit area. */
@Composable
fun ComposerGlyphButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(PocketShellDensity.tapTargetMin)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) PocketShellColors.TextSecondary else PocketShellColors.TextMuted,
            modifier = Modifier.size(COMPOSER_GLYPH_SIZE),
        )
    }
}

private val COMPOSER_CONTROL_GAP = 8.dp
private val COMPOSER_SEND_PAD_H = 18.dp
private val COMPOSER_SEND_GAP = 7.dp
private val COMPOSER_GLYPH_SIZE = 18.dp
private val COMPOSER_GLYPH_SIZE_LARGE = 18.dp
