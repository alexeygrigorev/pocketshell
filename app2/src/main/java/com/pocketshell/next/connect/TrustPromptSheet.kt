package com.pocketshell.next.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Stable test tags for the trust prompt. Instrumentation asserts on these
 * rather than on user-visible copy, so a wording change cannot silently
 * disarm the journey that proves the prompt appeared at all.
 */
const val TRUST_SHEET_TAG: String = "trust-sheet"
const val TRUST_SHEET_TITLE_TAG: String = "trust-sheet-title"
const val TRUST_SHEET_EXPLANATION_TAG: String = "trust-sheet-explanation"
const val TRUST_SHEET_FINGERPRINT_TAG: String = "trust-sheet-fingerprint"
const val TRUST_SHEET_PREVIOUS_FINGERPRINT_TAG: String = "trust-sheet-previous-fingerprint"
const val TRUST_SHEET_TRUST_TAG: String = "trust-sheet-trust"
const val TRUST_SHEET_REJECT_TAG: String = "trust-sheet-reject"

/**
 * Copy for the two prompt shapes, kept next to the component so the
 * first-contact / key-changed distinction is one table rather than scattered
 * `if (isMismatch)` branches through the layout. `internal` so the render test
 * asserts on the SAME strings the screen paints instead of a hand-copied
 * duplicate that can silently drift.
 */
internal const val UNKNOWN_TITLE = "Trust this host?"
internal const val MISMATCH_TITLE = "Host key CHANGED"

internal const val UNKNOWN_EXPLANATION =
    "First connection to this host. Check the fingerprint below matches the " +
        "server before trusting it."
internal const val MISMATCH_EXPLANATION =
    "This host is presenting a DIFFERENT key than the one you trusted before. " +
        "That happens after a legitimate server rebuild — and it is also exactly " +
        "what an interception attack looks like. Only trust the new key if you " +
        "know why it changed."

internal const val UNKNOWN_TRUST_LABEL = "Trust"
internal const val MISMATCH_TRUST_LABEL = "Trust the new key"

/**
 * The host-key confirmation bottom sheet (rewrite task U-2).
 *
 * Raised when a dial comes back [com.pocketshell.core.transport.ConnectResult.NeedsTrust].
 * It is the ONLY place a host key can be accepted, so it deliberately renders
 * the presented fingerprint verbatim rather than a summarised "unknown host"
 * line: a user who cannot read the fingerprint cannot make the decision the
 * prompt is asking for.
 *
 * ## Why a mismatch does not look like first contact
 *
 * [TrustPromptState.isMismatch] is not a cosmetic flag. First contact is a
 * routine trust-on-first-use step; a CHANGED key means the identity behind an
 * address you already verified is not the one you verified. The two therefore
 * get a different title, a different explanation, an error-role (red) banner
 * instead of an info-role (accent) one, the previously-trusted fingerprint
 * rendered alongside the new one so the user can see the two differ, and a
 * destructive-variant confirm button whose label spells out what is being
 * accepted. Collapsing them into one generic "accept?" is the failure this
 * component exists to prevent.
 *
 * Dismissing the sheet (scrim tap / back / drag-down) routes to [onReject], so
 * "get out of this prompt" can never be mistaken for consent — nothing is
 * recorded unless the trust button is actually pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustPromptSheet(
    prompt: TrustPromptState,
    hostLabel: String,
    onTrust: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(
        onDismissRequest = onReject,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        shape = PocketShellShapes.large,
    ) {
        TrustPromptSheetContent(
            prompt = prompt,
            hostLabel = hostLabel,
            onTrust = onTrust,
            onReject = onReject,
        )
    }
}

/**
 * The sheet's body, split out from the [ModalBottomSheet] container so it can
 * be composed directly by a host-JVM test (and a design render) without the
 * sheet's window/animation machinery. Everything the user reads and taps lives
 * here; the wrapper above only supplies the surface.
 */
@Composable
fun TrustPromptSheetContent(
    prompt: TrustPromptState,
    hostLabel: String,
    onTrust: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TRUST_SHEET_TAG)
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(bottom = PocketShellSpacing.lg)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        SheetHeader(
            title = if (prompt.isMismatch) MISMATCH_TITLE else UNKNOWN_TITLE,
            subtitle = hostLabel,
            titleTestTag = TRUST_SHEET_TITLE_TAG,
        )

        Banner(
            text = if (prompt.isMismatch) MISMATCH_EXPLANATION else UNKNOWN_EXPLANATION,
            role = if (prompt.isMismatch) BannerRole.Error else BannerRole.Info,
            modifier = Modifier.testTag(TRUST_SHEET_EXPLANATION_TAG),
        )

        Fingerprint(
            label = if (prompt.isMismatch) "New key presented now" else "Key fingerprint",
            value = prompt.fingerprintSha256,
            testTag = TRUST_SHEET_FINGERPRINT_TAG,
        )

        // Rendered ONLY for a mismatch, and only because the state carries it:
        // seeing the old and the new fingerprint together is what lets a user
        // tell a rebuild from an interception.
        prompt.previousFingerprintSha256?.let { previous ->
            Fingerprint(
                label = "Key you trusted before",
                value = previous,
                testTag = TRUST_SHEET_PREVIOUS_FINGERPRINT_TAG,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                space = PocketShellSpacing.sm,
                alignment = Alignment.End,
            ),
        ) {
            PocketShellButton(
                text = "Reject",
                onClick = onReject,
                variant = ButtonVariant.Text,
                modifier = Modifier.testTag(TRUST_SHEET_REJECT_TAG),
            )
            PocketShellButton(
                text = if (prompt.isMismatch) MISMATCH_TRUST_LABEL else UNKNOWN_TRUST_LABEL,
                onClick = onTrust,
                // Destructive (red text, no filled slab) for a changed key: the
                // design system reserves that treatment for the confirm action
                // of a flow the user can regret, which is precisely this one.
                variant = if (prompt.isMismatch) {
                    ButtonVariant.Destructive
                } else {
                    ButtonVariant.Primary
                },
                modifier = Modifier.testTag(TRUST_SHEET_TRUST_TAG),
            )
        }
    }
}

/** One labelled fingerprint block: muted caption over the mono digest itself. */
@Composable
private fun Fingerprint(label: String, value: String, testTag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
        Text(
            text = value,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyMono,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag(testTag),
        )
    }
}
