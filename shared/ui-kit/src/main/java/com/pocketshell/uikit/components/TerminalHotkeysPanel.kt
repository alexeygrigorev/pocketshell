package com.pocketshell.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity

/**
 * A labelled group of hotkeys.
 *
 * [rows] normally follows [columns], while the Ctrl picker supplies explicit
 * QWERTY rows so `HJKL` and `NM\` do not flow into neighbouring rows.
 */
data class HotkeySection(
    val title: String,
    val keys: List<KeyBinding>,
    val columns: Int,
    val rows: List<List<KeyBinding>> = keys.chunked(columns),
)

/** Transient page selected inside the terminal-hotkeys sheet. */
enum class TerminalHotkeysPage {
    Main,
    Ctrl,
}

/** A discoverable alternate action attached to one key target. */
data class HotkeyLongPressAction(
    val cue: String,
    val accessibilityLabel: String,
)

const val TERMINAL_HOTKEYS_PANEL_TAG: String = "terminal:hotkeys-panel"
const val TERMINAL_HOTKEYS_PANEL_CLOSE_TAG: String = "terminal:hotkeys-panel-close"
const val TERMINAL_HOTKEYS_PANEL_BACK_TAG: String = "terminal:hotkeys-panel-back"
const val TERMINAL_HOTKEYS_CTRL_FLOW_TAG: String = "terminal:hotkeys-ctrl-flow"

/**
 * Test-readable overflow signal. Compose otherwise clips a label while its
 * semantics bounds still appear contained.
 */
val HotkeyLabelTruncatedKey: SemanticsPropertyKey<Boolean> =
    SemanticsPropertyKey("HotkeyLabelTruncated")
var SemanticsPropertyReceiver.hotkeyLabelTruncated: Boolean by HotkeyLabelTruncatedKey

/**
 * Issue #1662 two-page hotkey renderer.
 *
 * Page state deliberately lives in the sheet host. This renderer has no sticky
 * modifier or expander state: a Ctrl-page key sends immediately, and the page
 * remains visible until Back/close/dismiss.
 */
@Composable
fun TerminalHotkeysPanel(
    sections: List<HotkeySection>,
    page: TerminalHotkeysPage = TerminalHotkeysPage.Main,
    onKey: (KeyBinding) -> Unit,
    onLongKey: (KeyBinding) -> Unit = {},
    onOpenCtrlPage: () -> Unit = {},
    onBackToMain: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ctrlFlowLabel: String = "Ctrl+…",
    longPressActions: Map<String, HotkeyLongPressAction> = emptyMap(),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 8.dp)
            .semantics {
                contentDescription = if (page == TerminalHotkeysPage.Ctrl) {
                    "Ctrl + key picker"
                } else {
                    "Terminal hotkeys"
                }
            },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HotkeysHeader(
            page = page,
            enabled = enabled,
            onBackToMain = onBackToMain,
            onClose = onClose,
            modifier = Modifier.padding(top = 6.dp),
        )

        sections.forEach { section ->
            HotkeySectionGrid(
                section = section,
                onKey = onKey,
                onLongKey = onLongKey,
                longPressActions = longPressActions,
                enabled = enabled,
            )
        }

        if (page == TerminalHotkeysPage.Main) {
            HotkeyPageAction(
                label = ctrlFlowLabel,
                enabled = enabled,
                onClick = onOpenCtrlPage,
            )
        }
    }
}

@Composable
private fun HotkeysHeader(
    page: TerminalHotkeysPage,
    enabled: Boolean,
    onBackToMain: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page == TerminalHotkeysPage.Ctrl) {
            Box(
                modifier = Modifier
                    .heightIn(min = PocketShellDensity.tapTargetMin)
                    .widthIn(min = PocketShellDensity.tapTargetMin)
                    .combinedClickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClickLabel = "Back to common keys",
                        onClick = onBackToMain,
                    )
                    .testTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "‹ keys",
                    color = if (enabled) PocketShellColors.Accent else PocketShellColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Text(
            text = if (page == TerminalHotkeysPage.Ctrl) "Ctrl + …" else "Terminal hotkeys",
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )

        Box(
            modifier = Modifier
                .size(PocketShellDensity.tapTargetMin)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Close terminal hotkeys",
                    onClick = onClose,
                )
                .testTag(TERMINAL_HOTKEYS_PANEL_CLOSE_TAG)
                .semantics { contentDescription = "Close terminal hotkeys" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                color = PocketShellColors.TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HotkeyPageAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PocketShellDensity.tapTargetMin)
            .background(PocketShellColors.AccentSoft, RoundedCornerShape(8.dp))
            .border(
                BorderStroke(1.dp, PocketShellColors.AccentDim),
                RoundedCornerShape(8.dp),
            )
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .testTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) PocketShellColors.Accent else PocketShellColors.TextMuted,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HotkeySectionGrid(
    section: HotkeySection,
    onKey: (KeyBinding) -> Unit,
    onLongKey: (KeyBinding) -> Unit,
    longPressActions: Map<String, HotkeyLongPressAction>,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = section.title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
            color = PocketShellColors.TextMuted,
        )
        section.rows.forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowKeys.forEach { binding ->
                    HotkeySlot(
                        binding = binding,
                        enabled = enabled,
                        longPressAction = longPressActions[binding.label],
                        onTap = { onKey(binding) },
                        onLongPress = { onLongKey(binding) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(section.columns - rowKeys.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HotkeySlot(
    binding: KeyBinding,
    enabled: Boolean,
    longPressAction: HotkeyLongPressAction?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val textColor: Color = when {
        !enabled -> PocketShellColors.TextMuted
        binding.kind == KeyKind.Arrow -> PocketShellColors.TextSecondary
        else -> PocketShellColors.Text
    }
    var labelTruncated by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .heightIn(min = PocketShellDensity.tapTargetMin)
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
            .border(
                border = BorderStroke(1.dp, PocketShellColors.Border),
                shape = RoundedCornerShape(8.dp),
            )
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTap()
                },
                onLongClickLabel = longPressAction?.accessibilityLabel,
                onLongClick = longPressAction?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                },
            )
            .semantics(mergeDescendants = true) {
                hotkeyLabelTruncated = labelTruncated
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = binding.label,
                color = textColor,
                fontFamily = if (binding.kind == KeyKind.Arrow) null else JetBrainsMonoFamily,
                fontSize = if (binding.kind == KeyKind.Arrow) 18.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { result -> labelTruncated = result.hasVisualOverflow },
            )
            if (longPressAction != null) {
                Text(
                    text = longPressAction.cue,
                    color = if (enabled) PocketShellColors.TextMuted else PocketShellColors.Border,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 8.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
