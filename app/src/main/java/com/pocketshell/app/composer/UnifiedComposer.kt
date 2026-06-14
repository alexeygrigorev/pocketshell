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
    val sizeModifier = if (maxHeight != null) {
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
            .then(sizeModifier)
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
        contentAlignment = Alignment.CenterStart,
    ) {
        var fieldModifier = Modifier.fillMaxWidth()
        if (focusRequester != null) {
            fieldModifier = fieldModifier.focusRequester(focusRequester)
        }
        if (fieldTag != null) {
            fieldModifier = fieldModifier.testTag(fieldTag)
        }
        // Issue #765: do NOT wrap the editor in an external `Modifier.
        // verticalScroll(...)`. A multi-line `BasicTextField` scrolls ITSELF to
        // keep the caret in view when it is given a bounded height and owns its
        // own scroll. An external `verticalScroll` modifier OVERRIDES that
        // built-in caret-follow, so on a long draft with the keyboard up the
        // editor stayed pinned at the top and the line being typed scrolled out
        // of view ("it starts cutting, I don't see anything"). Instead we let the
        // editor `fillMaxHeight()` of the height-bounded [DraftFieldBox] so the
        // framework scrolls it to the caret natively.
        val editableModifier = if (singleLine) {
            fieldModifier
        } else {
            fieldModifier.fillMaxHeight()
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
