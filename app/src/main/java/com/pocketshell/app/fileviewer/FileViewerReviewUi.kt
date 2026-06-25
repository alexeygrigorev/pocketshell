package com.pocketshell.app.fileviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Which review comment sheet is currently open (issue #714). This is transient
 * UI affordance state; the comments themselves live in the ViewModel's
 * [ReviewState] and survive config change.
 */
internal sealed interface ReviewSheet {
    /** Per-line comment sheet for the given 1-based [line]. */
    data class Line(val line: Int) : ReviewSheet

    /** Whole-file ("File note") comment sheet. */
    data object File : ReviewSheet

    /** Pending-comments tray. */
    data object Tray : ReviewSheet
}

/**
 * The per-line commentable text panel (issue #714). Unlike the default blob
 * [TextPanel], this splits [content] into a `LazyColumn` of rows so each line is
 * tappable: a row is a gutter (1-based line number + a comment dot when the line
 * carries a comment) plus the monospace line text. Tapping the gutter opens the
 * line's comment sheet via [onLineTap]. Word-wrap behavior is preserved per row.
 *
 * Markdown rendering is intentionally NOT line-addressable, so review mode shows
 * the raw source rows here (the file-level note still works from the header).
 */
@Composable
internal fun CommentableTextPanel(
    content: String,
    wordWrap: Boolean,
    lineComments: Map<Int, String>,
    onLineTap: (Int) -> Unit,
) {
    // Split once and remember — a 20 MB file is the worst case but only the
    // visible rows compose (LazyColumn), so the only cost is this one list.
    val lines = remember(content) { content.split("\n") }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .testTag(FILE_VIEWER_COMMENTABLE_TEXT_TAG),
    ) {
        itemsIndexed(lines) { index, lineText ->
            val lineNo = index + 1
            CommentableLineRow(
                lineNo = lineNo,
                lineText = lineText,
                hasComment = lineComments.containsKey(lineNo),
                wordWrap = wordWrap,
                onTap = { onLineTap(lineNo) },
            )
        }
    }
}

@Composable
private fun CommentableLineRow(
    lineNo: Int,
    lineText: String,
    hasComment: Boolean,
    wordWrap: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasComment) {
                    Modifier.background(PocketShellColors.SurfaceElev.copy(alpha = 0.4f))
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.Top,
    ) {
        // Gutter: the tap target (a full-height left column — an easy edge
        // target). Shows the 1-based line number and, when commented, a dot.
        Row(
            modifier = Modifier
                .width(56.dp)
                .clickable(role = Role.Button, onClick = onTap)
                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp, end = 6.dp)
                .testTag(fileViewerLineGutterTag(lineNo)),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top,
        ) {
            if (hasComment) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp, end = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(PocketShellColors.Accent)
                        .testTag(fileViewerLineDotTag(lineNo)),
                )
            }
            Text(
                text = lineNo.toString(),
                color = if (hasComment) PocketShellColors.Accent else PocketShellColors.TextMuted,
                style = PocketShellType.bodyMono,
            )
        }
        Text(
            text = lineText,
            color = PocketShellColors.TermText,
            style = PocketShellType.bodyMono,
            softWrap = wordWrap,
            overflow = if (wordWrap) TextOverflow.Clip else TextOverflow.Ellipsis,
            maxLines = if (wordWrap) Int.MAX_VALUE else 1,
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onTap)
                .padding(end = 12.dp, top = 2.dp, bottom = 2.dp),
        )
    }
}

/**
 * Renders whichever review sheet is open (issue #714). Split out so the scaffold
 * stays readable; each sheet is a `ModalBottomSheet` that floats above the soft
 * keyboard (`imePadding` + `navigationBarsPadding`) so the input is reachable
 * with the keyboard up (#641/#567 IME-occlusion rigor).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewSheets(
    activeSheet: ReviewSheet?,
    reviewState: ReviewState,
    lines: List<String>,
    onDismiss: () -> Unit,
    onSetLineComment: (Int, String) -> Unit,
    onDeleteLineComment: (Int) -> Unit,
    onSetFileComment: (String) -> Unit,
    onDeleteFileComment: () -> Unit,
    onOpenLine: (Int) -> Unit,
    onSubmitReview: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    when (activeSheet) {
        null -> Unit
        is ReviewSheet.Line -> {
            val line = activeSheet.line
            val lineText = lines.getOrNull(line - 1).orEmpty()
            CommentSheet(
                sheetState = sheetState,
                onDismiss = onDismiss,
                title = "L$line",
                subtitle = lineText,
                testTag = FILE_VIEWER_REVIEW_LINE_SHEET_TAG,
                initialText = reviewState.lineComments[line].orEmpty(),
                onSave = { text -> onSetLineComment(line, text); onDismiss() },
                onDelete = if (reviewState.hasLineComment(line)) {
                    { onDeleteLineComment(line); onDismiss() }
                } else {
                    null
                },
            )
        }
        ReviewSheet.File -> CommentSheet(
            sheetState = sheetState,
            onDismiss = onDismiss,
            title = "File note",
            subtitle = "Comment on the whole file",
            testTag = FILE_VIEWER_REVIEW_FILE_SHEET_TAG,
            initialText = reviewState.fileComment.orEmpty(),
            onSave = { text -> onSetFileComment(text); onDismiss() },
            onDelete = if (!reviewState.fileComment.isNullOrEmpty()) {
                { onDeleteFileComment(); onDismiss() }
            } else {
                null
            },
        )
        ReviewSheet.Tray -> PendingTraySheet(
            sheetState = sheetState,
            onDismiss = onDismiss,
            reviewState = reviewState,
            lines = lines,
            onEditLine = onOpenLine,
            onDeleteLine = onDeleteLineComment,
            onDeleteFile = onDeleteFileComment,
            onSubmit = onSubmitReview,
        )
    }
}

/**
 * The line / file comment editor sheet: a header (`L<n>  <line text>` or
 * `File note`), a multi-line text field, and Save / Delete actions. Floats above
 * the IME so the field is reachable with the keyboard up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    title: String,
    subtitle: String,
    testTag: String,
    initialText: String,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var draft by remember(initialText) { mutableStateOf(initialText) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .testTag(testTag),
        ) {
            SheetHeader(
                title = title,
                subtitle = subtitle.takeIf { it.isNotEmpty() },
                subtitleMaxLines = 2,
                subtitleStyle = PocketShellType.bodyMono,
            )
            Spacer(modifier = Modifier.height(PocketShellSpacing.md))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .background(
                        color = PocketShellColors.SurfaceElev,
                        shape = PocketShellShapes.small,
                    )
                    .border(
                        width = 1.dp,
                        color = PocketShellColors.Border,
                        shape = PocketShellShapes.small,
                    )
                    .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.md),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FILE_VIEWER_REVIEW_COMMENT_FIELD_TAG),
                    textStyle = PocketShellType.bodyDense.copy(color = PocketShellColors.Text),
                    cursorBrush = SolidColor(PocketShellColors.Accent),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) {
                            Text(
                                text = "Add a comment...",
                                color = PocketShellColors.TextMuted,
                                style = PocketShellType.bodyDense,
                            )
                        }
                        inner()
                    },
                )
            }
            Spacer(modifier = Modifier.height(PocketShellSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            ) {
                if (onDelete != null) {
                    ReviewSheetButton(
                        label = "Delete",
                        primary = false,
                        testTag = FILE_VIEWER_REVIEW_DELETE_TAG,
                        modifier = Modifier.weight(1f),
                        onClick = onDelete,
                    )
                }
                ReviewSheetButton(
                    label = "Save",
                    primary = true,
                    enabled = draft.isNotBlank(),
                    testTag = FILE_VIEWER_REVIEW_SAVE_TAG,
                    modifier = Modifier.weight(1f),
                    onClick = { onSave(draft) },
                )
            }
        }
    }
}

/**
 * The pending-comments tray (issue #714): lists every pending comment (line +
 * snippet + text, then the file note) with edit/delete, and a primary Submit
 * action enabled when there is at least one comment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingTraySheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    reviewState: ReviewState,
    lines: List<String>,
    onEditLine: (Int) -> Unit,
    onDeleteLine: (Int) -> Unit,
    onDeleteFile: () -> Unit,
    onSubmit: () -> Unit,
) {
    val orderedLines = remember(reviewState.lineComments) {
        reviewState.lineComments.entries.sortedBy { it.key }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .testTag(FILE_VIEWER_REVIEW_TRAY_SHEET_TAG),
        ) {
            SheetHeader(title = "Review (${reviewState.pendingCount})")
            Spacer(modifier = Modifier.height(PocketShellSpacing.md))

            if (!reviewState.hasPending) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No comments yet — tap a line number to add one.",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                ) {
                    items(orderedLines, key = { it.key }) { (line, text) ->
                        TrayRow(
                            label = "L$line",
                            snippet = lines.getOrNull(line - 1).orEmpty(),
                            text = text,
                            testTag = fileViewerTrayRowTag(line),
                            onEdit = { onEditLine(line) },
                            onDelete = { onDeleteLine(line) },
                        )
                    }
                    reviewState.fileComment?.takeIf { it.isNotEmpty() }?.let { fileComment ->
                        items(listOf(fileComment), key = { "file-note" }) { note ->
                            TrayRow(
                                label = "File note",
                                snippet = "",
                                text = note,
                                testTag = fileViewerTrayRowTag(-1),
                                onEdit = null,
                                onDelete = onDeleteFile,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(PocketShellSpacing.md))
            ReviewSheetButton(
                label = "Submit",
                primary = true,
                enabled = reviewState.hasPending && !reviewState.submitting,
                testTag = FILE_VIEWER_REVIEW_SUBMIT_TAG,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSubmit,
            )
        }
    }
}

/**
 * The post-Submit confirmation surface (issue #763). After a review is written
 * to `~/inbox/pocketshell/reviews/<file>-<ts>.yaml`, this sheet replaces the
 * old fire-and-forget toast: it shows where the YAML landed, lets the maintainer
 * **copy the exact saved path** (tap the path or the Copy button), and offers an
 * **"Attach to current session"** action that drops a ready prompt referencing
 * the path into the active session composer (`onAttach`).
 *
 * [savedPath] is the absolute remote path the ViewModel returned; [host] is the
 * host alias the review was filed against; [count] is how many comments landed.
 * The inbox-drop pickup path (#714) is untouched — this is purely additive in-
 * session routing, so both coexist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewSubmittedSheet(
    host: String,
    count: Int,
    savedPath: String,
    sheetState: SheetState,
    onCopyPath: () -> Unit,
    onAttach: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .testTag(FILE_VIEWER_REVIEW_SAVED_SHEET_TAG),
        ) {
            SheetHeader(
                title = "Review saved",
                subtitle = "Sent $count ${if (count == 1) "comment" else "comments"} to $host. " +
                    "It's in the reviews inbox and you can route it into this session.",
            )
            Spacer(modifier = Modifier.height(PocketShellSpacing.md))

            // The exact saved path — tap the path (or the Copy button) to copy.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = PocketShellColors.SurfaceElev,
                        shape = PocketShellShapes.small,
                    )
                    .border(
                        width = 1.dp,
                        color = PocketShellColors.BorderSoft,
                        shape = PocketShellShapes.small,
                    )
                    .clickable(role = Role.Button, onClick = onCopyPath)
                    .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = savedPath,
                    style = PocketShellType.bodyMono,
                    color = PocketShellColors.Text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(FILE_VIEWER_REVIEW_SAVED_PATH_TAG),
                )
                Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
                Text(
                    text = "Copy",
                    style = PocketShellType.labelMono,
                    fontWeight = FontWeight.SemiBold,
                    color = PocketShellColors.Accent,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onCopyPath)
                        .padding(vertical = PocketShellSpacing.xs)
                        .testTag(FILE_VIEWER_REVIEW_COPY_PATH_TAG),
                )
            }

            Spacer(modifier = Modifier.height(PocketShellSpacing.md))
            PocketShellButton(
                text = "Attach to current session",
                onClick = onAttach,
                variant = ButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FILE_VIEWER_REVIEW_ATTACH_TAG),
            )
            Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
            PocketShellButton(
                text = "Done",
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FILE_VIEWER_REVIEW_SAVED_DONE_TAG),
            )
        }
    }
}

/**
 * Post-Submit confirmation surface for an annotated image (issue #764). After
 * the flattened PNG is written to `~/inbox/pocketshell/annotations/<file>-<ts>.png`
 * (plus its `pocketshell_annotation` YAML sidecar), this sheet shows where the
 * PNG landed, lets the maintainer **copy the exact saved path**, and — #764 v2 —
 * **attach it to the current session** (seeds the composer with a prompt that
 * references the saved PNG, reusing the #763 attach-to-session path). Mirrors
 * [ReviewSubmittedSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnnotationSavedSheet(
    host: String,
    savedPath: String,
    sheetState: SheetState,
    onCopyPath: () -> Unit,
    onAttach: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .testTag(FILE_VIEWER_ANNOTATE_SAVED_SHEET_TAG),
        ) {
            SheetHeader(
                title = "Annotated image saved",
                subtitle = "Sent the marked-up image to $host. " +
                    "It's in the annotations inbox (with a YAML sidecar).",
            )
            Spacer(modifier = Modifier.height(PocketShellSpacing.md))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = PocketShellColors.SurfaceElev,
                        shape = PocketShellShapes.small,
                    )
                    .border(
                        width = 1.dp,
                        color = PocketShellColors.BorderSoft,
                        shape = PocketShellShapes.small,
                    )
                    .clickable(role = Role.Button, onClick = onCopyPath)
                    .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = savedPath,
                    style = PocketShellType.bodyMono,
                    color = PocketShellColors.Text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(FILE_VIEWER_ANNOTATE_SAVED_PATH_TAG),
                )
                Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
                Text(
                    text = "Copy",
                    style = PocketShellType.labelMono,
                    fontWeight = FontWeight.SemiBold,
                    color = PocketShellColors.Accent,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onCopyPath)
                        .padding(vertical = PocketShellSpacing.xs),
                )
            }

            Spacer(modifier = Modifier.height(PocketShellSpacing.md))
            PocketShellButton(
                text = "Attach to current session",
                onClick = onAttach,
                variant = ButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FILE_VIEWER_ANNOTATE_ATTACH_TAG),
            )
            Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
            PocketShellButton(
                text = "Done",
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrayRow(
    label: String,
    snippet: String,
    text: String,
    testTag: String,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = PocketShellShapes.small)
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm)
            .testTag(testTag),
    ) {
        Text(
            text = label,
            color = PocketShellColors.Accent,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
        )
        if (snippet.isNotEmpty()) {
            Text(
                text = snippet,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.bodyMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = text,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.md)) {
            if (onEdit != null) {
                Text(
                    text = "Edit",
                    color = PocketShellColors.Accent,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onEdit)
                        .padding(vertical = PocketShellSpacing.xs),
                )
            }
            Text(
                text = "Delete",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onDelete)
                    .padding(vertical = PocketShellSpacing.xs),
            )
        }
    }
}

@Composable
private fun ReviewSheetButton(
    label: String,
    primary: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val containerColor = when {
        !enabled -> PocketShellColors.SurfaceElev
        primary -> PocketShellColors.Accent
        else -> PocketShellColors.Surface
    }
    val labelColor = when {
        !enabled -> PocketShellColors.TextMuted
        primary -> PocketShellColors.OnAccent
        else -> PocketShellColors.Text
    }
    val borderColor = if (primary && enabled) PocketShellColors.Accent else PocketShellColors.Border
    Box(
        modifier = modifier
            .height(PocketShellDensity.tapTargetMin)
            .background(containerColor, PocketShellShapes.small)
            .border(width = 1.dp, color = borderColor, shape = PocketShellShapes.small)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = PocketShellSpacing.md)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = labelColor,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
