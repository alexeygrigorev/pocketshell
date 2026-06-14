package com.pocketshell.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

/**
 * The enumerated variant set for [PocketShellButton] (#756).
 *
 * Deliberately a SMALL closed enum rather than free `ButtonColors`: the audit
 * (#756) found ~142 raw Material buttons, and the **same** accent
 * `ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent,
 * disabled… )` block was hand-re-declared in 9 different files, with
 * `fontWeight = SemiBold` re-applied per call. Forcing callers to pick a named
 * variant is what stops that drift from coming back. If a genuinely new button
 * treatment is needed, add a variant here (and justify it) instead of passing a
 * raw `colors`/`shape` at the call site.
 *
 * - [Primary] — the page's main affordance (Add host, Save, Start, Create).
 *   Filled accent container, [PocketShellColors.OnAccent] label, SemiBold. There
 *   should be ONE primary per screen / dialog action row.
 * - [Secondary] — a lower-emphasis affirmative action sitting next to a Primary
 *   (e.g. a "Browse" beside "Save"). Outlined accent — accent label + accent
 *   border, no fill — so it reads as actionable but yields visual weight to the
 *   Primary.
 * - [Text] — the muted, chrome-less action: Cancel / Retry / inline links and
 *   the dialog dismiss action. No container, no border, muted label.
 * - [Destructive] — the confirm action of a delete/reset/stop flow. Red label
 *   (text-only, NOT a filled red slab — per `docs/design-system.md`: "destructive
 *   confirmation uses red text only on the confirm action").
 */
enum class ButtonVariant {
    Primary,
    Secondary,
    Text,
    Destructive,
}

/**
 * The canonical PocketShell button (#756).
 *
 * Before this component there were **zero** ui-kit buttons and ~142 raw Material
 * `Button`/`TextButton`/`IconButton` call sites; the accent primary-CTA colour
 * block was copy-pasted across 9 files and the muted Cancel/Retry treatment was
 * whatever Material defaulted to per theme. [PocketShellButton] is the single
 * shared surface those sites converge onto: pick a [ButtonVariant], pass a
 * label, done — colour, shape, typography, and disabled treatment all come from
 * the theme tokens, never from a per-call `colors` block.
 *
 * All four variants share:
 *  - **Shape** — [PocketShellColors] / the design-system chip radius (8dp,
 *    `PocketShellShapes.small`), so every button corner matches the chip/key
 *    vocabulary.
 *  - **Colour** — entirely from [LocalPocketShellSemantic] (accent /
 *    accentDim) + the [PocketShellColors] surface ramp. No raw hex per call.
 *  - **Disabled** — a single muted treatment (`Border` container /
 *    `TextMuted` label for filled, `TextMuted` label for the rest), so a
 *    disabled button reads the same everywhere.
 *
 * The label is a `String` for the common case; [content] is the escape hatch for
 * a button that needs an icon + label or other custom row content (it still
 * inherits the variant container/shape/disabled treatment).
 *
 * [compact] is the dense affordance (#756, compact-variant batch): an inline
 * action sitting inside a banner / dialog / dense card row — `Dismiss`, `Retry`,
 * `Update`, `Copy keys from…` — where the default ~14sp button label and tall
 * Material touch padding would dominate the dense block. It paints the SAME
 * variant colour / shape / disabled grammar, only at the [PocketShellType.bodyDense]
 * (13sp) rung with tighter content padding, so the 6 screens that hand-rolled
 * raw `TextButton`s at `labelSmall`/`bodyDense`/`12.sp` converge onto the canonical
 * component without changing their look. Existing (non-compact) call sites are
 * untouched: [compact] defaults to false.
 *
 * @param text the button label (the common case).
 * @param onClick invoked on tap when [enabled].
 * @param variant which enumerated [ButtonVariant] treatment to paint.
 * @param enabled when false, the button is non-interactive and dimmed.
 * @param compact when true, render the dense inline treatment (smaller label +
 *   tighter padding) for banner/dialog/dense-row actions.
 */
@Composable
fun PocketShellButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    PocketShellButton(
        onClick = onClick,
        modifier = modifier,
        variant = variant,
        enabled = enabled,
        compact = compact,
    ) {
        Text(
            text = text,
            style = if (compact) PocketShellType.bodyDense else LocalTextStyle.current,
            fontWeight = if (variant == ButtonVariant.Primary) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * [PocketShellButton] with a custom [content] slot (icon + label, etc.). Use the
 * `text` overload above for the common label-only case. The variant's container,
 * shape, and disabled treatment still apply. [compact] applies the same dense
 * padding/height as the `text` overload; supply a [PocketShellType.bodyDense]-sized
 * label inside [content] to match.
 */
@Composable
fun PocketShellButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val semantic = LocalPocketShellSemantic.current
    // Compact actions trim the tall default touch padding so the dense inline
    // action sits flush in a banner/dialog row; the standard variants keep
    // Material's default content padding untouched.
    val contentPadding = if (compact) CompactContentPadding else null
    when (variant) {
        ButtonVariant.Primary -> {
            Button(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = ButtonShape,
                contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = semantic.accent,
                    contentColor = PocketShellColors.OnAccent,
                    disabledContainerColor = PocketShellColors.Border,
                    disabledContentColor = PocketShellColors.TextMuted,
                ),
                content = content,
            )
        }

        ButtonVariant.Secondary -> {
            // Outlined accent: accent label + accent border, no fill. We use a
            // filled `Button` with a transparent container (instead of M3's
            // OutlinedButton) so the SAME shape/padding/disabled grammar as the
            // other variants applies; the accent border is the only differentiator.
            Button(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = ButtonShape,
                contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
                border = if (enabled) {
                    BorderStroke(1.dp, semantic.accentDim)
                } else {
                    BorderStroke(1.dp, PocketShellColors.Border)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = semantic.accent,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = PocketShellColors.TextMuted,
                ),
                content = content,
            )
        }

        ButtonVariant.Text -> {
            TextButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = ButtonShape,
                contentPadding = contentPadding ?: ButtonDefaults.TextButtonContentPadding,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = PocketShellColors.TextSecondary,
                    disabledContentColor = PocketShellColors.TextMuted,
                ),
                content = content,
            )
        }

        ButtonVariant.Destructive -> {
            // Red TEXT confirm — NOT a filled red slab (design-system.md:
            // "destructive confirmation uses red text only on the confirm action").
            TextButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = ButtonShape,
                contentPadding = contentPadding ?: ButtonDefaults.TextButtonContentPadding,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = semantic.statusError,
                    disabledContentColor = PocketShellColors.TextMuted,
                ),
                content = content,
            )
        }
    }
}

/**
 * The one canonical button corner radius — `PocketShellShapes.small` (8dp), the
 * shared chip/key slot vocabulary. Kept private so callers can't pass a
 * per-button shape, and sourced from the shape token (not a duplicated literal)
 * so there is a single source of truth for the radius.
 */
private val ButtonShape = PocketShellShapes.small

/**
 * Tighter content padding for the [compact] affordance. Trims Material's default
 * tall touch padding so a dense inline action (banner Dismiss/Retry, dialog
 * Update, "Copy keys from…") sits flush in its row instead of dominating it,
 * matching the look of the raw `TextButton`s the 6 dense screens previously
 * hand-rolled. Horizontal stays modest so the label still has breathing room.
 */
private val CompactContentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
