package com.pocketshell.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * Dedicated terminal-hotkeys panel (issue #784).
 *
 * Replaces the cramped, `…`-overflowing key bar that #755 had wedged into the
 * Prompt Composer. This is its OWN surface — toggled from the terminal view,
 * NOT part of the soft keyboard and NOT inside the composer. It shows EVERY key
 * at once in a tidy multi-row grid with NO `…` overflow and NO horizontal
 * scroll: keys flow row-by-row, each row a fixed count of equal-flex slots, so
 * a short row is left-aligned and never stretched into giant keys.
 *
 * The panel is a pure renderer. It emits exactly one [onKey] callback per tap,
 * carrying the tapped [KeyBinding]; the host screen owns the wire mapping
 * (control byte / named key) so the ui-kit stays render-only — the same
 * contract [KeyBar] used.
 *
 * Layout (top → bottom):
 *  - A title row ("Terminal hotkeys") + a close affordance.
 *  - One or more labelled [HotkeySection]s, each a header + a grid of keys.
 *
 * Visual recipe mirrors the existing [KeyBar] key slot (SurfaceElev fill, 1dp
 * Border, 8dp radius, mono label) so the two read as one vocabulary, but here
 * every key is laid out at full size — nothing is hidden behind an expander.
 */
data class HotkeySection(
    val title: String,
    val keys: List<KeyBinding>,
    /**
     * How many equal-width columns this section's grid uses. The key set is
     * curated so each section fits a phone width at this column count without
     * clipping; a section with fewer keys than [columns] simply leaves the
     * trailing slots empty (keys stay full-size, never stretched).
     */
    val columns: Int,
)

const val TERMINAL_HOTKEYS_PANEL_TAG: String = "terminal:hotkeys-panel"
const val TERMINAL_HOTKEYS_PANEL_CLOSE_TAG: String = "terminal:hotkeys-panel-close"

/**
 * Test-only semantics flag (issue #755): `true` on a key slot whose label
 * visually overflowed its slot, i.e. the glyph was **truncated** — the exact
 * "keys cut off / `…`" symptom the maintainer reported. The panel sets this from
 * the slot `Text`'s `onTextLayout` (`hasVisualOverflow`). A regression test reads
 * it to hard-fail on truncation, which a `boundsInRoot` containment check cannot
 * catch (Compose clamps a node's reported rect to its slot, so an overflowing
 * label still reports as "contained"). Render-only; no behavioural effect.
 */
val HotkeyLabelTruncatedKey: SemanticsPropertyKey<Boolean> =
    SemanticsPropertyKey("HotkeyLabelTruncated")
var SemanticsPropertyReceiver.hotkeyLabelTruncated: Boolean by HotkeyLabelTruncatedKey

@Composable
fun TerminalHotkeysPanel(
    sections: List<HotkeySection>,
    onKey: (KeyBinding) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .padding(horizontal = 18.dp)
            .padding(bottom = 8.dp)
            .semantics { contentDescription = "Terminal hotkeys" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Terminal hotkeys",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                color = PocketShellColors.Text,
            )
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .background(
                        PocketShellColors.SurfaceElev,
                        androidx.compose.foundation.shape.CircleShape,
                    )
                    .padding(horizontal = 9.dp)
                    .clickable(role = Role.Button, onClick = onClose)
                    .testTag(TERMINAL_HOTKEYS_PANEL_CLOSE_TAG)
                    .semantics { contentDescription = "Close terminal hotkeys" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 20.sp,
                )
            }
        }

        sections.forEach { section ->
            HotkeySectionGrid(
                section = section,
                onKey = onKey,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun HotkeySectionGrid(
    section: HotkeySection,
    onKey: (KeyBinding) -> Unit,
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
        section.keys.chunked(section.columns).forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowKeys.forEach { binding ->
                    HotkeySlot(
                        binding = binding,
                        enabled = enabled,
                        onTap = { onKey(binding) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad the final short row so keys keep their natural width
                // instead of stretching to fill the row.
                val padding = section.columns - rowKeys.size
                repeat(padding) {
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
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val textColor: Color = when {
        !enabled -> PocketShellColors.TextMuted
        binding.kind == KeyKind.Arrow -> PocketShellColors.TextSecondary
        else -> PocketShellColors.Text
    }
    // Tracks whether this key's label was truncated (glyph clipped) at the slot's
    // measured width — exposed via [hotkeyLabelTruncated] for the #755 regression
    // guard. Render-only.
    var labelTruncated by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(44.dp)
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
            .border(
                border = BorderStroke(1.dp, PocketShellColors.Border),
                shape = RoundedCornerShape(8.dp),
            )
            .let { if (enabled) it.clickable(role = Role.Button, onClick = onTap) else it }
            // Merge the label Text into this clickable node so a test can match
            // a key by "label text + click action" (disambiguating the panel key
            // from identically-labelled terminal content). Also publish the
            // truncation flag so the #755 guard can read it off this node.
            .semantics(mergeDescendants = true) { hotkeyLabelTruncated = labelTruncated }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = binding.label,
            color = textColor,
            fontFamily = if (binding.kind == KeyKind.Arrow) null else JetBrainsMonoFamily,
            fontSize = if (binding.kind == KeyKind.Arrow) 18.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            // `Clip` (the default for a single line) silently cuts the glyph;
            // capture that as the truncation signal the #755 guard asserts on.
            overflow = TextOverflow.Clip,
            onTextLayout = { result -> labelTruncated = result.hasVisualOverflow },
        )
    }
}
