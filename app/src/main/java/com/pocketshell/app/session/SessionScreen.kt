package com.pocketshell.app.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.session.SessionViewModel.ConnectionStatus
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.uikit.components.Breadcrumb
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Phase 1 session screen — the visual target is `docs/mockups/session.html`.
 *
 * Stacks four bands top-to-bottom:
 *
 * 1. **Breadcrumb** (ui-kit's `Breadcrumb`) — back arrow signals "detach",
 *    `host > session > pane` chain, `⋮` for the (future) more menu.
 * 2. **Tabs row** — `Terminal` is the only tab in Phase 1 (Conversation
 *    is hidden until Phase 3 / #23). Rendered inline here rather than
 *    pulling in a `Tabs` ui-kit component for one entry.
 * 3. **`TerminalSurface`** filling the remaining vertical space.
 * 4. **Input strip** — either the [KeyBar] (when the IME is showing) or
 *    the [CommandChip] row (when it is hidden), plus the mic FAB anchored
 *    bottom-right per `docs/input-methods.md` §"Screen real estate".
 *
 * The screen does not own any business state — everything lives in
 * [SessionViewModel]. The composable is a thin renderer that wires its
 * callbacks to the ViewModel's intent functions.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
public fun SessionScreen(
    viewModel: SessionViewModel,
    modifier: Modifier = Modifier,
    host: String = SessionDefaults.HOST,
    port: Int = SessionDefaults.PORT,
    user: String = SessionDefaults.USER,
    keyPath: String? = null,
    onBack: () -> Unit = {},
) {
    LaunchedEffect(host, port, user, keyPath) {
        viewModel.connect(host, port, user, keyPath)
    }

    val status by viewModel.connectionStatus.collectAsState()
    val armed by viewModel.armedModifiers.collectAsState()

    var showMicSheet by remember { mutableStateOf(false) }

    val isImeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Breadcrumb(
                crumbs = breadcrumbCrumbs(host, user),
                onBack = onBack,
                onMore = { /* More menu — wiring lands with #23. */ },
            )

            TabsRow()

            // Optional one-line status above the terminal until the
            // breadcrumb's live dot covers it post-#18.
            (status as? ConnectionStatus.Connecting)?.let {
                StatusLine("connecting to ${it.user}@${it.host}:${it.port}")
            }
            (status as? ConnectionStatus.Failed)?.let {
                StatusLine(it.message)
            }

            // The terminal surface owns the rest of the vertical band. The
            // weight pushes the bottom strip (key bar / chips / FAB) right
            // up against either the IME or the system nav, whichever is
            // present.
            Box(modifier = Modifier.weight(1f)) {
                TerminalSurface(
                    state = viewModel.terminalState,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (armed.isNotEmpty()) {
                ArmedModifierStrip(armed)
            }

            if (isImeVisible) {
                KeyBar(
                    keys = KeyBarLayout,
                    onKey = { binding -> viewModel.onKeyBarKey(binding.label) },
                )
            } else {
                ChipRow(
                    chips = DefaultChips,
                    onChipTap = viewModel::onChipTap,
                    onDictateTap = { showMicSheet = true },
                )
            }
        }

        // Bottom-right mic FAB. Always visible (per `docs/input-methods.md`
        // §"Screen real estate" → keyboard down) — the keyboard-up path
        // surfaces the dictate icon-chip via the chip row instead.
        if (!isImeVisible) {
            MicButton(
                state = MicButtonState.Idle,
                onClick = { showMicSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 24.dp),
            )
        }
    }

    if (showMicSheet) {
        // Wires the issue #15 prompt composer. `onSend` is the contract
        // the composer drives:
        //  - `withEnter = false` -> write the prompt bytes only (Send)
        //  - `withEnter = true`  -> write the prompt bytes + '\n' (Send + ↵)
        // Either way we dismiss the sheet so the user lands back on the
        // live terminal with their submission visible.
        PromptComposerSheet(
            onDismiss = { showMicSheet = false },
            onSend = { text, withEnter ->
                val payload = if (withEnter) text + "\n" else text
                viewModel.terminalState.writeInput(payload.toByteArray(Charsets.UTF_8))
                showMicSheet = false
            },
        )
    }
}

/**
 * Build the breadcrumb segments. v1 has a flat `host > session > pane`
 * triplet; #18 / Phase 2 fill in real session + pane names once tmux
 * awareness lands.
 */
private fun breadcrumbCrumbs(host: String, user: String): List<Crumb> = listOf(
    Crumb(label = host, isCurrent = false, onClick = { /* host root — #18 */ }),
    Crumb(label = "$user@$host", isCurrent = true, onClick = { /* current — no-op */ }),
    Crumb(label = "pane 1", isCurrent = false, onClick = { /* pane switcher — Phase 2 */ }),
)

/**
 * Inline tabs row. Only `Terminal` is active in Phase 1; `Conversation`
 * is hidden entirely until #23 wires the agent-awareness side panel
 * (`docs/agent-awareness.md`). Once Tabs exists in the ui-kit (a future
 * issue), this gets swapped for it.
 */
@Composable
private fun TabsRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.Border)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Terminal",
            color = PocketShellColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = PocketShellColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Small accent strip surfaced while one or more sticky modifiers are
 * armed. The ui-kit `KeyBar` does not visually surface armed state for
 * `KeyKind.Regular` slots (see the [SessionViewModel] class-level docs on
 * why we register Ctrl / Alt as `Regular`), so this strip keeps the user
 * informed that the next bar tap will be wrapped.
 */
@Composable
private fun ArmedModifierStrip(armed: Set<SessionViewModel.Modifier>) {
    val label = armed.joinToString(" + ") { it.name }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.AccentSoft)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "$label armed — tap a bar key to send it wrapped",
            color = PocketShellColors.Accent,
            fontSize = 11.sp,
        )
    }
}

/**
 * Always-visible chip row (only when the IME is hidden, per
 * `docs/input-methods.md` §"Screen real estate"). The first chip is the
 * `dictate` icon chip — tapping it opens the composer placeholder; the
 * rest write their literal text + `\n` into the terminal.
 */
@Composable
private fun ChipRow(
    chips: List<String>,
    onChipTap: (String) -> Unit,
    onDictateTap: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.Border)
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dictate chip uses the accent treatment via the ui-kit's
        // `icon-chip` mode. We pass a small filled-circle marker so the
        // ui-kit picks the accent style without us having to ship a real
        // icon set yet (the ui-kit's CommandChip swaps `ImageVector` once
        // the design language gets a proper icon ramp).
        CommandChip(
            label = "dictate",
            onClick = onDictateTap,
            icon = DictateDotIcon,
        )
        chips.forEach { chip ->
            CommandChip(
                label = chip,
                onClick = { onChipTap(chip) },
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
    }
}

/**
 * The 8 bar slots from `docs/mockups/session.html`:
 *
 * - Esc, Tab, Ctrl, Alt, then four arrows.
 *
 * `Ctrl` and `Alt` are declared as `Regular` (rather than `Modifier`) so
 * the ui-kit forwards their taps through `onKey`. The [SessionViewModel]
 * owns the sticky FSM — see its class-level documentation for the
 * rationale.
 */
private val KeyBarLayout: List<KeyBinding> = listOf(
    KeyBinding(label = "Esc", kind = KeyKind.Regular),
    KeyBinding(label = "Tab", kind = KeyKind.Regular),
    KeyBinding(label = "Ctrl", kind = KeyKind.Regular),
    KeyBinding(label = "Alt", kind = KeyKind.Regular),
    KeyBinding(label = "‹", kind = KeyKind.Arrow),
    KeyBinding(label = "⌃", kind = KeyKind.Arrow),
    KeyBinding(label = "⌄", kind = KeyKind.Arrow),
    KeyBinding(label = "›", kind = KeyKind.Arrow),
)

/**
 * A 24x24 filled dot used as the dictate chip's leading icon. We build
 * the [ImageVector] inline rather than pull in `material-icons-extended`
 * for one glyph — the icon set already in the classpath
 * (`material-icons-core`, transitively from material3) does not ship a
 * standalone `Filled.Circle`.
 */
private val DictateDotIcon: ImageVector = ImageVector.Builder(
    name = "DictateDot",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addCirclePath(
    fill = SolidColor(androidx.compose.ui.graphics.Color.White),
).build()

/**
 * Build a filled circle centred at (12, 12) with radius 6 in the path
 * data, then append it to this [ImageVector.Builder]. Kept as a private
 * extension so the inline icon definition stays declarative.
 *
 * Uses two relative arcs (the standard SVG idiom for a circle: a half-arc
 * down, then a half-arc back up) so we do not need an `Oval` primitive in
 * the path-node vocabulary.
 */
private fun ImageVector.Builder.addCirclePath(fill: SolidColor): ImageVector.Builder {
    val builder = PathBuilder()
    builder.moveTo(12f, 6f)
    // arcToRelative(a, b, theta, isMoreThanHalf, isPositiveArc, dx1, dy1)
    builder.arcToRelative(6f, 6f, 0f, true, true, 0f, 12f)
    builder.arcToRelative(6f, 6f, 0f, true, true, 0f, -12f)
    builder.close()
    addPath(pathData = builder.nodes, fill = fill)
    return this
}

/**
 * v1 chip set — matches `docs/mockups/session.html`'s `.chip-row`
 * (without the dictate entry, which the screen renders separately as the
 * icon chip). Phase 2 / #18 will source these from per-host storage.
 */
private val DefaultChips: List<String> = listOf(
    "git status",
    "tmux ls",
    "k logs",
    "clear",
)
