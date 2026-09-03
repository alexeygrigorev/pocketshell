package com.pocketshell.next.tree

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
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
 * Stable test tags for the create-session sheet. Instrumentation asserts on
 * these rather than on user-visible copy, so a wording change cannot silently
 * disarm the journey that proves the sheet works.
 */
const val CREATE_SESSION_SHEET_TAG: String = "create-session-sheet"
const val CREATE_SESSION_TITLE_TAG: String = "create-session-title"
const val CREATE_SESSION_FOLDER_TAG: String = "create-session-folder"
const val CREATE_SESSION_NAME_TAG: String = "create-session-name"
const val CREATE_SESSION_SUBMIT_TAG: String = "create-session-submit"
const val CREATE_SESSION_CANCEL_TAG: String = "create-session-cancel"
const val CREATE_SESSION_ERROR_TAG: String = "create-session-error"

internal const val CREATE_SESSION_TITLE = "New session"
internal const val CREATE_SESSION_FOLDER_LABEL = "Folder"
internal const val CREATE_SESSION_NAME_LABEL = "Session name"
internal const val CREATE_SESSION_SUBMIT_LABEL = "Create"
internal const val CREATE_SESSION_CANCEL_LABEL = "Cancel"
internal const val CREATE_SESSION_HINT =
    "The session starts detached in this folder. Leave the folder blank to use " +
        "the host's default."

/**
 * The session name derived from a folder path — the LAST path segment.
 *
 * The host CLI's `sessions create NAME` takes the name as a REQUIRED positional
 * argument and derives nothing from `--cwd` (see
 * [com.pocketshell.core.hostapi.HostCliClient.createSession]), so a blank name
 * is a usage error on the host, not a generated one. Rather than making the
 * user type a name they almost always want to be "the folder", the sheet
 * prefills this and lets them overwrite it — the create button stays disabled
 * while the field is blank, so the host can never be asked to do the deriving.
 *
 * `:` and `.` are rewritten to `-` because tmux rejects both in a session name
 * (`:` separates a session from its window, `.` a window from its pane), and a
 * folder called `agent.v2` would otherwise prefill a name the host must refuse.
 * Only the DERIVED default is rewritten; a name the user types is sent verbatim
 * and the host's own validation answers for it.
 *
 * Pure: no Android, no Compose, no clock.
 */
fun defaultSessionName(folder: String): String {
    val trimmed = folder.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val segment = trimmed.substringAfterLast('/').trim()
    // `~`, `.` and `..` name a location, not a project; there is nothing
    // useful to derive from them.
    if (segment == "~" || segment == "." || segment == "..") return ""
    return segment.map { char -> if (char == ':' || char == '.') '-' else char }
        .joinToString("")
        .trim('-')
}

/**
 * The sheet's editable form, hoisted out of the composition.
 *
 * A plain state holder rather than `remember { mutableStateOf(...) }` pairs
 * inside the composable, for two reasons: the name-follows-folder rule is real
 * behaviour that deserves a test of its own (it is unit-tested directly, with
 * no composition at all), and a test of the sheet can hand in a pre-filled form
 * instead of driving an `OutlinedTextField`, whose focused cursor animation
 * wedges Robolectric's idle wait.
 *
 * [name] tracks [folder] only until the user edits the name themselves; after
 * that the field is theirs and a later folder edit leaves it alone. Silently
 * overwriting a typed name would be the worse behaviour of the two.
 */
@Stable
class CreateSessionFormState(initialFolder: String = "") {

    var folder: String by mutableStateOf(initialFolder)
        private set

    var name: String by mutableStateOf(defaultSessionName(initialFolder))
        private set

    /** True once the user typed in the name field, which freezes the tracking. */
    var nameEdited: Boolean by mutableStateOf(false)
        private set

    fun onFolderChange(value: String) {
        folder = value
        if (!nameEdited) name = defaultSessionName(value)
    }

    fun onNameChange(value: String) {
        name = value
        nameEdited = true
    }

    /** The host requires a name, so a blank one can never be submitted. */
    val canSubmit: Boolean get() = name.isNotBlank()

    /** What `sessions create` is asked for: `NAME`. */
    val submittedName: String get() = name.trim()

    /** `--cwd`, or `null` so the host's own default working directory applies. */
    val submittedCwd: String? get() = folder.trim().ifBlank { null }
}

/**
 * The create-session bottom sheet (rewrite task U-6, journey J04).
 *
 * Folder + name only. There is deliberately no engine/profile picker: the
 * rewrite's scope amendment (docs/rewrite-implementation-plan.md, "Scope
 * amendment") cut agent launching from the client entirely, so this sheet
 * creates a plain shell session and `createSession`'s `engine`/`profile`
 * parameters are always `null` here.
 *
 * Dismissing the sheet (scrim tap / back / drag-down) routes to [onCancel],
 * so backing out can never be mistaken for a create.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSessionSheet(
    state: CreateSessionState,
    defaultFolder: String,
    onSubmit: (name: String, cwd: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // `skipPartiallyExpanded` is load-bearing, not a style choice: a modal sheet
    // otherwise opens at its PARTIAL detent (about half the screen) and anything
    // below that line is off-screen. With the keyboard up — which is the normal
    // state of this sheet, since it is two text fields — the Create button lands
    // exactly there and the user cannot reach it (observed on the emulator in
    // J04). Opening fully expanded, plus the `imePadding` below, keeps the
    // action row above the IME.
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        shape = PocketShellShapes.large,
    ) {
        CreateSessionSheetContent(
            state = state,
            defaultFolder = defaultFolder,
            onSubmit = onSubmit,
            onCancel = onCancel,
        )
    }
}

/**
 * The sheet's body, split out from the [ModalBottomSheet] container so it can
 * be composed directly by a host-JVM test (and a design render) without the
 * sheet's window/animation machinery — the same split [
 * com.pocketshell.next.connect.TrustPromptSheet] uses.
 */
@Composable
fun CreateSessionSheetContent(
    state: CreateSessionState,
    defaultFolder: String,
    onSubmit: (name: String, cwd: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    form: CreateSessionFormState = remember(defaultFolder) {
        CreateSessionFormState(defaultFolder)
    },
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = PocketShellColors.Text,
        unfocusedTextColor = PocketShellColors.Text,
        focusedBorderColor = PocketShellColors.Accent,
        unfocusedBorderColor = PocketShellColors.BorderSoft,
        focusedLabelColor = PocketShellColors.Accent,
        unfocusedLabelColor = PocketShellColors.TextSecondary,
        cursorColor = PocketShellColors.Accent,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CREATE_SESSION_SHEET_TAG)
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(bottom = PocketShellSpacing.lg)
            .navigationBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        SheetHeader(
            title = CREATE_SESSION_TITLE,
            subtitle = CREATE_SESSION_HINT,
            titleTestTag = CREATE_SESSION_TITLE_TAG,
        )

        // A failed create keeps the sheet open with the user's own text still
        // in the fields: the fix for "that folder does not exist" is an edit,
        // and a sheet that closed on failure would throw the edit away.
        state.failure?.let { failure ->
            Banner(
                text = failure,
                role = BannerRole.Error,
                maxLines = 4,
                modifier = Modifier.testTag(CREATE_SESSION_ERROR_TAG),
            )
        }

        OutlinedTextField(
            value = form.folder,
            onValueChange = form::onFolderChange,
            singleLine = true,
            enabled = !state.submitting,
            label = { Text(CREATE_SESSION_FOLDER_LABEL) },
            placeholder = { Text("/home/you/git/project") },
            colors = fieldColors,
            textStyle = PocketShellType.bodyMono,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CREATE_SESSION_FOLDER_TAG),
        )

        OutlinedTextField(
            value = form.name,
            onValueChange = form::onNameChange,
            singleLine = true,
            enabled = !state.submitting,
            label = { Text(CREATE_SESSION_NAME_LABEL) },
            colors = fieldColors,
            textStyle = PocketShellType.bodyMono,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CREATE_SESSION_NAME_TAG),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                space = PocketShellSpacing.sm,
                alignment = Alignment.End,
            ),
        ) {
            PocketShellButton(
                text = CREATE_SESSION_CANCEL_LABEL,
                onClick = onCancel,
                variant = ButtonVariant.Text,
                enabled = !state.submitting,
                modifier = Modifier.testTag(CREATE_SESSION_CANCEL_TAG),
            )
            PocketShellButton(
                text = if (state.submitting) "Creating…" else CREATE_SESSION_SUBMIT_LABEL,
                onClick = { onSubmit(form.submittedName, form.submittedCwd) },
                variant = ButtonVariant.Primary,
                enabled = form.canSubmit && !state.submitting,
                modifier = Modifier.testTag(CREATE_SESSION_SUBMIT_TAG),
            )
        }
    }
}
