package com.pocketshell.app.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/**
 * Issue #491: the styled draft-entry box for PocketShell's composer surface.
 *
 * Production now has exactly one composer renderer — the bottom-sheet
 * dictation composer ([com.pocketshell.app.composer.PromptComposerSheet]) —
 * which writes bytes straight into the focused tmux/SSH pane. This file holds
 * the shared draft-box building block that surface consumes:
 *
 *  - [ComposerDraftField] — the styled draft entry box (surface-elev fill,
 *    accent cursor, muted placeholder) backed by a [TextFieldValue] so the
 *    composer always sees the IME's composing region (the #491 Send-no-op
 *    fix), rendered through the shared [DraftFieldBox] chrome.
 */

/**
 * Issue #491: the [TextFieldValue]-backed draft field.
 *
 * The terminal composer ([PromptComposerSheet]) drives its Send button
 * straight off the live editor state. With the legacy `String` overload of
 * [BasicTextField] the composer never sees the IME's *composing region*
 * (predictive-text / autocorrect underline) until the IME decides to commit
 * it — which, on a short prompt, can be never until the user manually hits
 * Enter on the soft keyboard. That is the exact "Send is a no-op, I had to
 * raise the keyboard and press Enter" bug the maintainer reported: the typed
 * text was still sitting in an uncommitted composing region, so the
 * ViewModel's draft (and therefore the Send button's enabled gate) read
 * empty.
 *
 * Holding the [TextFieldValue] ourselves means the composer always sees the
 * full visible text — composing region included — so Send can read and
 * dispatch it without waiting for the IME to commit. A [focusRequester] is
 * exposed so a keyboard affordance can raise the IME on demand.
 */
@Composable
internal fun ComposerDraftField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fieldTag: String? = null,
    minHeight: Dp = 110.dp,
    maxHeight: Dp? = null,
    singleLine: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    DraftFieldBox(
        isEmpty = value.text.isEmpty(),
        placeholder = placeholder,
        modifier = modifier,
        fieldTag = fieldTag,
        minHeight = minHeight,
        maxHeight = maxHeight,
        singleLine = singleLine,
        focusRequester = focusRequester,
    ) { editableModifier, textStyle, cursorBrush, decorationBox ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            modifier = editableModifier,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            decorationBox = decorationBox,
        )
    }
}

/**
 * Issue #491: the shared styled chrome (surface-elev fill, accent cursor,
 * muted placeholder, scroll) hosting the [ComposerDraftField] editor.
 */
@Composable
private fun DraftFieldBox(
    isEmpty: Boolean,
    placeholder: String,
    modifier: Modifier,
    fieldTag: String?,
    minHeight: Dp,
    maxHeight: Dp?,
    singleLine: Boolean,
    focusRequester: FocusRequester? = null,
    editor: @Composable (
        editableModifier: Modifier,
        textStyle: TextStyle,
        cursorBrush: SolidColor,
        decorationBox: @Composable (@Composable () -> Unit) -> Unit,
    ) -> Unit,
) {
    // Issue #873: the height bound (`heightIn(min, max)`) lives on the EDITOR, not
    // on the surrounding box, so the box WRAPS to the editor's content. Previously
    // the box carried `heightIn(min, max)` and the editor `fillMaxHeight()`'d it —
    // which made the editor greedily fill whatever height the parent offered. With
    // the composer's keyboard-up `weight(1f)` scroll region that meant a one-line
    // draft box inflated toward its 220dp max (≈155dp on a resized sheet), centring
    // the text in a sea of empty space: the ~1cm dead band the maintainer circled.
    //
    // Binding the EDITOR to `heightIn(min, max)` instead makes a short draft wrap
    // to ~its `min` (compact, no dead space) while a multi-line `BasicTextField`
    // still self-scrolls to the caret once the content exceeds `max` (the #765
    // caret-follow invariant — a bounded BasicTextField owns its own scroll). The
    // box then wraps to the editor + padding, so the parent `weight(1f, fill =
    // false)` genuinely wraps to content with no reserved void.
    val editorSizeModifier = if (maxHeight != null) {
        Modifier.heightIn(min = minHeight, max = maxHeight)
    } else {
        Modifier.heightIn(min = minHeight)
    }
    // 12dp draft-box radius — the same value as the `md` spacing rung; reused as a
    // local Shape so the box corner stays on the token grid (no shape-scale token
    // exists between small(8) and medium(14)). 14dp vertical padding is off the
    // 4dp grid, so it stays a literal until the composer is re-spec'd in #453.
    val draftShape = RoundedCornerShape(PocketShellSpacing.md)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = draftShape,
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = draftShape,
            )
            .padding(horizontal = PocketShellSpacing.lg, vertical = 14.dp),
        // Issue #873: top-align the editor within the box. The editor carries the
        // `min` height, so for a one-line draft the box is exactly the editor's
        // min — there is no extra box height to centre the text in, and as the
        // user types the caret grows downward from the top (never floating in the
        // middle of an over-tall box).
        contentAlignment = Alignment.TopStart,
    ) {
        var fieldModifier = Modifier.fillMaxWidth()
        if (focusRequester != null) {
            fieldModifier = fieldModifier.focusRequester(focusRequester)
        }
        if (fieldTag != null) {
            fieldModifier = fieldModifier.testTag(fieldTag)
        }
        // Issue #765/#873: do NOT wrap the editor in an external `Modifier.
        // verticalScroll(...)`. A multi-line `BasicTextField` scrolls ITSELF to
        // keep the caret in view when it is given a bounded height and owns its
        // own scroll. An external `verticalScroll` modifier OVERRIDES that
        // built-in caret-follow ("it starts cutting, I don't see anything").
        // Instead the editor carries the `heightIn(min, max)` bound directly
        // (#873) — it wraps to content for a short draft (no dead space) and
        // self-scrolls to the caret natively once a long draft exceeds `max`.
        val editableModifier = if (singleLine) {
            // A single-line field keeps only the `min` floor so the box never
            // collapses below it; it has no `max` because it cannot grow taller
            // than one line anyway.
            fieldModifier.heightIn(min = minHeight)
        } else {
            fieldModifier.then(editorSizeModifier)
        }
        val textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize, // 14sp body rung
        )
        val cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
        val decorationBox: @Composable (@Composable () -> Unit) -> Unit = { inner ->
            if (isEmpty) {
                Text(
                    // Placeholder uses the muted text token (no M3 slot maps to
                    // TextMuted; it stays a centralized raw token).
                    text = placeholder,
                    color = PocketShellColors.TextMuted,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize, // 14sp
                )
            }
            inner()
        }
        editor(editableModifier, textStyle, cursorBrush, decorationBox)
    }
}
