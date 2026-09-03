package com.pocketshell.next.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Test tags for the text surfaces. */
const val VIEWER_TEXT_TAG: String = "viewer-text"
const val VIEWER_EDITOR_TAG: String = "viewer-editor"

/**
 * Read-only text rendering (rewrite task P-3b).
 *
 * Monospaced on the terminal background, because everything a developer opens
 * here — source, config, a log, a diff — is column-aligned, and the rest of the
 * app already speaks that vocabulary ([PocketShellType.bodyMono]).
 *
 * Deliberately a plain scrolling [Text], not a lazy list of lines: a lazy list
 * would need a line index, which means splitting the whole file eagerly anyway,
 * and it breaks text selection across the split. The read cap in
 * [ViewerViewModel] is what keeps this bounded.
 */
@Composable
internal fun TextContent(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = text.ifEmpty { "(empty file)" },
            color = if (text.isEmpty()) PocketShellColors.TextMuted else PocketShellColors.TermText,
            style = PocketShellType.bodyMono,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketShellSpacing.md)
                .testTag(VIEWER_TEXT_TAG),
        )
    }
}

/**
 * The editor (rewrite task P-3b).
 *
 * A [BasicTextField] on the terminal surface rather than a Material
 * `OutlinedTextField`: the field IS the screen here, so a floating label,
 * container tint and outline would be chrome around a full-bleed editor. A
 * syntax-highlighting editor is explicitly out of scope — plain text is the
 * contract.
 */
@Composable
internal fun TextEditor(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .verticalScroll(rememberScrollState()),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = enabled,
            textStyle = LocalTextStyle.current.merge(
                PocketShellType.bodyMono.copy(color = PocketShellColors.TermText),
            ),
            cursorBrush = SolidColor(PocketShellColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketShellSpacing.md)
                .testTag(VIEWER_EDITOR_TAG),
        )
    }
}
