package com.pocketshell.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.KeyModifierState
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Sticky-modifier state for one key. Internal to [KeyBar].
 *
 * - [Off] — not armed
 * - [OneShot] — armed for the next non-modifier key press, then auto-clears
 * - [Locked] — sticky on until tapped again
 *
 * Both `OneShot` and `Locked` render with the active (accent-soft)
 * visual; only `Locked` survives the next non-modifier tap.
 */
private typealias ModifierState = KeyModifierState

/**
 * Bottom-of-screen key strip — the 8-slot row above the system
 * keyboard in `docs/mockups/session.html`. Matches `.keybar` and `.key`
 * (with `.key.active` for armed modifiers and `.key.arrow` for the
 * directional keys) in `docs/mockups/styles.css`.
 *
 * Visual recipe (per the CSS):
 * - `surface` background, 1dp `border` top edge
 * - 8dp horizontal + vertical padding, 5dp gap between keys
 * - Each key: 38dp tall, equal-flex width, `surface-elev` background,
 *   8dp corner radius, 1dp `border` outline
 * - Active modifier: `accent-soft` background, `accent-dim` border,
 *   accent foreground
 *
 * Sticky modifier behaviour:
 *
 * - First tap on a `KeyKind.Modifier` arms it for one shot — the next
 *   non-modifier key fires through with the modifier set, then clears
 *   it. Same as ChromeOS / xterm sticky keys.
 * - Second consecutive tap (a "double tap") flips the modifier to
 *   *locked*: it stays on until tapped a third time. The component
 *   detects double-tap by measuring the gap between consecutive taps
 *   on the same modifier — under [DoubleTapWindowMs]ms = lock toggle,
 *   otherwise it's a fresh one-shot.
 * - Tapping a non-modifier key fires the binding through [onKey] and
 *   clears any one-shot modifiers. Locked modifiers persist.
 * - Arrow keys (`KeyKind.Arrow`) are non-modifier and always one-shot;
 *   they fire through immediately and clear armed one-shot modifiers.
 *
 * ## Modifier-event contract for screen-level glue
 *
 * `KeyBar` emits exactly **one** [onKey] callback per non-modifier tap.
 * Modifier taps never trigger [onKey] — they only mutate internal sticky
 * state and recompose the affected key for the visual accent. The screen-
 * level integration is therefore responsible for *its own* view of "what
 * modifiers are currently armed" if it wants to decorate the synthesised
 * terminal key event; the ui-kit does not surface modifier state.
 *
 * **Auto-clear timing:** one-shot modifiers clear immediately on the
 * same tap that fires the triggering [onKey] — after the callback
 * returns. The clear happens *inside* the same tap handler, not on a
 * delay, so by the time control returns to the caller the bar already
 * reflects the cleared state on the next recomposition. Locked modifiers
 * never auto-clear; only an explicit tap on the same locked modifier
 * resets it.
 *
 * **Order of events** when the user taps a sticky modifier and then a
 * regular key inside the same gesture window:
 *
 * ```
 * t=0ms  : user taps "Ctrl"
 *          -> KeyBar arms Ctrl as one-shot, recomposes Ctrl slot active.
 *          -> No onKey() callback. Caller sees nothing.
 *
 * t=120ms: user taps "C"
 *          -> KeyBar fires onKey(KeyBinding("C", Regular)).
 *          -> KeyBar then clears all one-shot modifiers (Ctrl -> Off).
 *          -> Caller's onKey handler runs while Ctrl is still
 *             conceptually "armed for this event" — but the modifier
 *             state is invisible to the caller; if the caller wants to
 *             decorate the key with Ctrl, it must observe modifier taps
 *             via a separate UI surface (or mirror the FSM screen-side).
 * ```
 *
 * Double-tap-to-lock followed by a regular tap:
 *
 * ```
 * t=0ms  : user taps "Ctrl"     -> Off -> OneShot, no onKey()
 * t=180ms: user taps "Ctrl"     -> OneShot -> Locked (within 350ms),
 *                                  no onKey()
 * t=900ms: user taps "X"        -> onKey(X), Ctrl stays Locked
 * t=1500ms: user taps "Y"       -> onKey(Y), Ctrl stays Locked
 * t=4000ms: user taps "Ctrl"    -> Locked -> Off (gap > 350ms but
 *                                  Locked behaviour is "single tap
 *                                  while armed disarms"), no onKey()
 * ```
 *
 * In short: `onKey` fires only on regular / arrow taps; modifier state
 * is private to the bar and is auto-cleared *after* the triggering
 * [onKey] returns for one-shots, *never* for locked. If the screen-
 * level glue needs to ship Ctrl/Alt key codes to the terminal, mirror
 * modifier changes from [onModifierStateChange].
 */
@Composable
fun KeyBar(
    keys: List<KeyBinding>,
    onKey: (KeyBinding) -> Unit,
    modifier: Modifier = Modifier,
    modifierStates: Map<String, KeyModifierState>? = null,
    onModifierStateChange: (KeyBinding, KeyModifierState) -> Unit = { _, _ -> },
) {
    // Per-key modifier state, indexed by the binding's `label` (the
    // bar's "ID" of a key). A `SnapshotStateMap` so Compose sees state
    // changes and recomposes the affected key.
    val internalModifierStates: SnapshotStateMap<String, ModifierState> = remember { androidx.compose.runtime.mutableStateMapOf() }

    // Tracks the last tap time per modifier label, used to detect the
    // "consecutive taps under 350ms" double-tap gesture.
    val lastTapMillis: SnapshotStateMap<String, Long> = remember { androidx.compose.runtime.mutableStateMapOf() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(border = BorderStroke(1.dp, PocketShellColors.Border))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        keys.forEach { binding ->
            val state: ModifierState = if (binding.kind == KeyKind.Modifier) {
                modifierStates?.get(binding.label)
                    ?: internalModifierStates[binding.label]
                    ?: ModifierState.Off
            } else {
                ModifierState.Off
            }

            KeySlot(
                binding = binding,
                isActive = state != ModifierState.Off,
                modifier = Modifier.weight(1f),
                onTap = {
                    when (binding.kind) {
                        KeyKind.Modifier -> {
                            // Detect double-tap (lock toggle) vs first tap
                            // (one-shot arm) vs tap-while-armed (disarm).
                            val now = System.currentTimeMillis()
                            val previous = lastTapMillis[binding.label] ?: 0L
                            val isDoubleTap = (now - previous) <= DoubleTapWindowMs

                            val current = modifierStates?.get(binding.label)
                                ?: internalModifierStates[binding.label]
                                ?: ModifierState.Off
                            val next = when {
                                // Double-tap on a one-shot promotes it to locked.
                                isDoubleTap && current == ModifierState.OneShot -> ModifierState.Locked
                                // Double-tap on locked toggles off.
                                isDoubleTap && current == ModifierState.Locked -> ModifierState.Off
                                // Single tap on off arms one-shot.
                                current == ModifierState.Off -> ModifierState.OneShot
                                // Single tap on already-armed (one-shot or locked) clears.
                                else -> ModifierState.Off
                            }
                            internalModifierStates[binding.label] = next
                            lastTapMillis[binding.label] = now
                            onModifierStateChange(binding, next)
                            // We deliberately do NOT call onKey() for
                            // modifiers — modifiers don't "fire" by
                            // themselves; they decorate the next key.
                        }

                        KeyKind.Arrow, KeyKind.Regular -> {
                            // Fire the binding, then clear all one-shot
                            // modifiers (locked modifiers persist).
                            onKey(binding)
                            clearOneShotModifiers(
                                externalStates = modifierStates,
                                internalStates = internalModifierStates,
                                keys = keys,
                                onModifierStateChange = onModifierStateChange,
                            )
                        }
                    }
                },
            )
        }
    }

    // Safety: if the bar's `keys` list shrinks between recompositions,
    // drop any stale modifier state so we don't accumulate ghosts.
    LaunchedEffect(keys) {
        val labels = keys.map { it.label }.toSet()
        val bindingsByLabel = keys.associateBy { it.label }
        val staleInternal = internalModifierStates.keys - labels
        staleInternal.forEach { label ->
            val binding = bindingsByLabel[label] ?: KeyBinding(label = label, kind = KeyKind.Modifier)
            onModifierStateChange(binding, ModifierState.Off)
        }
        internalModifierStates.keys.retainAll(labels)
        lastTapMillis.keys.retainAll(labels)
    }

    LaunchedEffect(keys, modifierStates) {
        val external = modifierStates ?: return@LaunchedEffect
        val labels = keys.map { it.label }.toSet()
        val staleActive = external.filterKeys { it !in labels }.filterValues { it != ModifierState.Off }
        staleActive.keys.forEach { label ->
            onModifierStateChange(KeyBinding(label = label, kind = KeyKind.Modifier), ModifierState.Off)
        }
    }
}

/**
 * One slot in the [KeyBar]. Renders the binding's label, picks the
 * right colour / font / weight per [KeyKind], and reflects the active
 * state for modifiers (`.key.active` in the CSS).
 *
 * Kept private to the file because the visual recipe is tightly
 * coupled to the bar's padding / sizing decisions — extracting it
 * would just be ceremony.
 */
@Composable
private fun KeySlot(
    binding: KeyBinding,
    isActive: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val (textColor: Color, bgColor: Color, borderColor: Color) = when {
        isActive -> Triple(
            PocketShellColors.Accent,
            PocketShellColors.AccentSoft,
            PocketShellColors.AccentDim,
        )

        binding.kind == KeyKind.Arrow -> Triple(
            PocketShellColors.TextSecondary,
            PocketShellColors.SurfaceElev,
            PocketShellColors.Border,
        )

        else -> Triple(
            PocketShellColors.Text,
            PocketShellColors.SurfaceElev,
            PocketShellColors.Border,
        )
    }

    Box(
        modifier = modifier
            .widthIn(min = 30.dp)
            .height(38.dp)
            .background(color = bgColor, shape = RoundedCornerShape(8.dp))
            .border(
                border = BorderStroke(1.dp, borderColor),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.Button, onClick = onTap)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = binding.label,
            color = textColor,
            // Arrow keys use the UI sans-serif and a larger glyph
            // (`.key.arrow` rule); everything else uses mono.
            fontFamily = if (binding.kind == KeyKind.Arrow) null else JetBrainsMonoFamily,
            fontSize = when {
                binding.kind == KeyKind.Arrow -> 16.sp
                binding.label.length >= 6 -> 9.sp
                else -> 12.sp
            },
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun clearOneShotModifiers(
    externalStates: Map<String, ModifierState>?,
    internalStates: SnapshotStateMap<String, ModifierState>,
    keys: List<KeyBinding>,
    onModifierStateChange: (KeyBinding, KeyModifierState) -> Unit,
) {
    val bindingsByLabel = keys.associateBy { it.label }
    val activeStates = externalStates ?: internalStates
    val toClear: List<String> = activeStates.entries.filter { it.value == ModifierState.OneShot }.map { it.key }
    toClear.forEach { label ->
        internalStates[label] = ModifierState.Off
        bindingsByLabel[label]?.let { binding ->
            onModifierStateChange(binding, ModifierState.Off)
        }
    }
}

/**
 * Maximum interval between two taps to count as a double-tap (sticky
 * lock toggle). 350ms matches Compose's default `combinedClickable`
 * window — picked for muscle-memory consistency rather than measuring
 * a specific user research result.
 */
private const val DoubleTapWindowMs: Long = 350L
