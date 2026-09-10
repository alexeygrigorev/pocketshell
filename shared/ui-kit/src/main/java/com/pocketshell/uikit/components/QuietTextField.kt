package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

/**
 * The one text input in the app.
 *
 * #2635 §5: the text field was the last un-tokenised primitive. Three screens
 * hand-declared the SAME eight-colour `OutlinedTextFieldDefaults.colors(...)`
 * block plus `PocketShellShapes.medium` and a `heightIn(min = 56.dp)`, and
 * everything else fell back to Material's defaults — which are not Quiet's
 * colours at all. Copying a colour block is how a design system stops being
 * one; this component is what `PocketShellButton` is for buttons.
 *
 * Geometry comes from the token file: `size.fieldMin` for the height and
 * `radius.field` (via [PocketShellShapes] `medium`) for the corner, so the
 * field cannot drift from `tokens.json` (T1).
 */
@Composable
fun QuietTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    focusRequester: FocusRequester? = null,
    testTag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        isError = isError,
        singleLine = singleLine,
        label = label?.let { { Text(text = it, style = PocketShellType.label) } },
        placeholder = placeholder?.let {
            {
                Text(
                    text = it,
                    style = PocketShellType.body,
                    color = PocketShellColors.TextMuted,
                )
            }
        },
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    // Decorative: the placeholder/label already names the field,
                    // and a duplicated label is noise for TalkBack.
                    contentDescription = null,
                    tint = PocketShellColors.TextMuted,
                )
            }
        },
        trailingIcon = trailing,
        textStyle = PocketShellType.body,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        shape = PocketShellShapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = PocketShellColors.Surface,
            unfocusedContainerColor = PocketShellColors.Surface,
            disabledContainerColor = PocketShellColors.Surface,
            focusedTextColor = PocketShellColors.Text,
            unfocusedTextColor = PocketShellColors.Text,
            disabledTextColor = PocketShellColors.TextMuted,
            focusedBorderColor = PocketShellColors.Accent,
            unfocusedBorderColor = PocketShellColors.Border,
            disabledBorderColor = PocketShellColors.BorderSoft,
            errorBorderColor = PocketShellColors.Red,
            focusedLabelColor = PocketShellColors.Accent,
            unfocusedLabelColor = PocketShellColors.TextMuted,
            cursorColor = PocketShellColors.Accent,
        ),
        modifier = modifier
            .heightIn(min = PocketShellDensity.fieldMinHeight)
            .let { base -> if (focusRequester == null) base else base.focusRequester(focusRequester) }
            .let { base -> if (testTag == null) base else base.testTag(testTag) },
    )
}
