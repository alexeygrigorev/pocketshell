package com.pocketshell.app.insets

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.cards.SESSION_CARD_FEED_SHEET_TAG
import com.pocketshell.app.cards.SESSION_CHECKLIST_ITEM_TAG_PREFIX
import com.pocketshell.app.cards.SessionCardFeedSheet
import com.pocketshell.app.cards.SessionCardInteractions
import com.pocketshell.app.cards.SessionCardsRemoteSource
import com.pocketshell.app.fileviewer.FILE_VIEWER_ANNOTATE_ATTACH_TAG
import com.pocketshell.app.fileviewer.FILE_VIEWER_ANNOTATE_SAVED_SHEET_TAG
import com.pocketshell.app.fileviewer.FILE_VIEWER_REVIEW_FILE_SHEET_TAG
import com.pocketshell.app.fileviewer.FILE_VIEWER_REVIEW_LINE_SHEET_TAG
import com.pocketshell.app.fileviewer.FILE_VIEWER_REVIEW_SAVED_DONE_TAG
import com.pocketshell.app.fileviewer.FILE_VIEWER_REVIEW_SAVED_SHEET_TAG
import com.pocketshell.app.fileviewer.FILE_VIEWER_REVIEW_SAVE_TAG
import com.pocketshell.app.fileviewer.FILE_VIEWER_REVIEW_SUBMIT_TAG
import com.pocketshell.app.fileviewer.FILE_VIEWER_REVIEW_TRAY_SHEET_TAG
import com.pocketshell.app.fileviewer.AnnotationSavedSheet
import com.pocketshell.app.fileviewer.ReviewSheet
import com.pocketshell.app.fileviewer.ReviewSheets
import com.pocketshell.app.fileviewer.ReviewState
import com.pocketshell.app.fileviewer.ReviewSubmittedSheet
import com.pocketshell.app.git.GIT_CREATE_ISSUE_SHEET_TAG
import com.pocketshell.app.git.GIT_CREATE_ISSUE_SUBMIT_TAG
import com.pocketshell.app.git.GIT_ISSUES_TAB_TAG
import com.pocketshell.app.git.GIT_NEW_ISSUE_TAG
import com.pocketshell.app.git.GitBranch
import com.pocketshell.app.git.GitCommit
import com.pocketshell.app.git.GitHistoryScaffold
import com.pocketshell.app.git.GitHistoryUiState
import com.pocketshell.app.git.GitRepoOverview
import com.pocketshell.app.git.GitRepoStatus
import com.pocketshell.app.git.GitWorktree
import com.pocketshell.app.projects.FOLDER_CONTEXT_EMPTY_PROJECT_TAG
import com.pocketshell.app.projects.FOLDER_CONTEXT_SHEET_TAG
import com.pocketshell.app.projects.FolderContextActionSheet
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CREATE_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_SHEET_TAG
import com.pocketshell.app.projects.SessionTypePickerSheet
import com.pocketshell.app.snippets.SnippetPickerContent
import com.pocketshell.app.snippets.snippetSendChipTag
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1821 — the per-site keyboard-geometry oracle for every remaining
 * nested `.navigationBarsPadding().imePadding()` site that lives inside a
 * Material3 `ModalBottomSheet`.
 *
 * ## What this proves, and why the issue needed it
 *
 * #1812 established the mechanism: `ModalBottomSheet` wraps its window content
 * in `Box(Modifier.fillMaxSize().imePadding())` (`ModalBottomSheet.kt:169`, and
 * again via `contentWindowInsets = safeDrawing.only(Bottom)` at `:291`), and
 * `windowInsetsPadding` **consumes** the inset for its subtree. So a
 * content-level `imePadding()` inside the sheet resolves to exactly zero. That
 * argument is Compose common code and cannot vary by API level — but #1812
 * deliberately refused to sweep the remaining sites on the strength of a source
 * reading, because **none of them had a keyboard-geometry oracle to measure
 * against**. This class is that oracle.
 *
 * Each test mounts the PRODUCTION sheet (the real `ModalBottomSheet`, its own
 * window), dispatches a synthetic `Type.ime()` inset to every attached app
 * window root, and records the sheet's bottom-most control geometry with the
 * keyboard down and up. The recorded `ISSUE1821_SHEET_GEOMETRY` line is the
 * before/after evidence used to classify each site inert (byte-identical with
 * and without the modifier) or live.
 *
 * ## What it durably asserts
 *
 * Two hard assertions per site, neither of which the surfaces had before:
 *
 *  1. **The sheet lifts by essentially the whole keyboard.** This is what makes
 *     the measurement non-vacuous: it fails if the synthetic inset never
 *     reached the modal window, and it fails if Material3 ever stops handling
 *     the ime inset for sheet content. With the duplicate content-level
 *     `imePadding()` deleted, that second regression can no longer be masked —
 *     the lift can only come from Material's own handling.
 *
 *     The first half is mutation-proven: in #1821 round 2 (run R2E) narrowing
 *     `SyntheticImeStage.dispatch` to the activity decor only turned **all 10**
 *     of these cases red at `liftPx=0.0`, while the 5 standalone cases stayed
 *     green; the #1821 reviewer reproduced it (run V5).
 *
 *     The second half — the Material3-upgrade unmasking — is **mechanism, not a
 *     measured claim**: nothing here simulates a Material3 that stops consuming
 *     the ime inset, so it rests on #1812's source reading of
 *     `ModalBottomSheet.kt:169` plus the fact that this file's own measurements
 *     show the sheet lifting with no content-level `imePadding()` present.
 *  2. **The bottom-most control stays fully ABOVE the keyboard**, asserted as
 *     `boundsInRoot` containment in the sheet's OWN Compose root, not
 *     `assertIsDisplayed()` (F2/F3 — a control under the keyboard still reports
 *     "displayed"). `GitHistoryScaffoldTest`'s "create-issue form (issue #650)"
 *     block records exactly this gap ("we keep `assertIsDisplayed()` for the
 *     sheet-internal controls because `assertNodeFullyWithinRoot` reads a single
 *     `onRoot()`"); binding against the owning modal root closes it.
 *
 * ## Why no `file:line` in the site labels
 *
 * They used to carry one (`SessionChecklistUi.kt:168-169`), and #1821's own
 * round-2 comments in those production files shifted every one of them out of
 * date within the same change — the site labels pointed at the comment block
 * above the modifier instead of the modifier. A citation that rots the moment
 * anyone edits the file is the same defect this issue is about, one layer up, so
 * the labels name the composable instead, which does not rot. Library
 * references (`ModalBottomSheet.kt:169`) keep their line numbers: that file is
 * pinned by the dependency version.
 *
 * No `assumeTrue` / `assumeFalse`: an environment that cannot reach the state
 * fails hard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class NestedImePaddingInSheetGeometryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stage by lazy { SyntheticImeStage(compose) }

    @After
    fun releaseSyntheticInsets() {
        runCatching { stage.dispatch(imeBottomPx = 0, requireExtraWindowRoot = false) }
        runCatching { stage.hideRealImeBestEffort() }
    }

    // -- SessionChecklistUi.kt — SessionCardFeedContent ----------------------------

    @Test
    fun sessionCardFeedSheetLiftsAndKeepsLastCardAboveKeyboard() {
        measureSheet(
            site = "SessionChecklistUi.SessionCardFeedContent",
            sheetTag = SESSION_CARD_FEED_SHEET_TAG,
            probeTag = SESSION_CHECKLIST_ITEM_TAG_PREFIX + "cl:build-0",
        ) {
            SessionCardFeedSheet(
                cards = listOf(checklistCard()),
                interactions = NoopInteractions,
                onDismiss = {},
            )
        }
    }

    // -- FileViewerReviewUi.kt — CommentSheet (line) -------------------------------

    @Test
    fun fileViewerLineCommentSheetLiftsAndKeepsSaveAboveKeyboard() {
        measureSheet(
            site = "FileViewerReviewUi.CommentSheet(line)",
            sheetTag = FILE_VIEWER_REVIEW_LINE_SHEET_TAG,
            probeTag = FILE_VIEWER_REVIEW_SAVE_TAG,
        ) {
            ReviewSheetsHost(ReviewSheet.Line(2))
        }
    }

    // -- FileViewerReviewUi.kt — CommentSheet (whole file) -------------------------

    @Test
    fun fileViewerFileCommentSheetLiftsAndKeepsSaveAboveKeyboard() {
        measureSheet(
            site = "FileViewerReviewUi.CommentSheet(file)",
            sheetTag = FILE_VIEWER_REVIEW_FILE_SHEET_TAG,
            probeTag = FILE_VIEWER_REVIEW_SAVE_TAG,
        ) {
            ReviewSheetsHost(ReviewSheet.File)
        }
    }

    // -- FileViewerReviewUi.kt — PendingTraySheet ----------------------------------

    @Test
    fun fileViewerReviewTraySheetLiftsAndKeepsSubmitAboveKeyboard() {
        measureSheet(
            site = "FileViewerReviewUi.PendingTraySheet",
            sheetTag = FILE_VIEWER_REVIEW_TRAY_SHEET_TAG,
            probeTag = FILE_VIEWER_REVIEW_SUBMIT_TAG,
        ) {
            ReviewSheetsHost(ReviewSheet.Tray)
        }
    }

    // -- FileViewerReviewUi.kt — ReviewSubmittedSheet ------------------------------

    @Test
    fun fileViewerReviewSubmittedSheetLiftsAndKeepsDoneAboveKeyboard() {
        measureSheet(
            site = "FileViewerReviewUi.ReviewSubmittedSheet",
            sheetTag = FILE_VIEWER_REVIEW_SAVED_SHEET_TAG,
            probeTag = FILE_VIEWER_REVIEW_SAVED_DONE_TAG,
        ) {
            ReviewSubmittedSheet(
                host = "agents",
                count = 2,
                savedPath = "/root/inbox/pocketshell/reviews/main-kt-20260729.yaml",
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                onCopyPath = {},
                onAttach = {},
                onDismiss = {},
            )
        }
    }

    // -- FileViewerReviewUi.kt — AnnotationSavedSheet ------------------------------

    @Test
    fun fileViewerAnnotationSavedSheetLiftsAndKeepsAttachAboveKeyboard() {
        measureSheet(
            site = "FileViewerReviewUi.AnnotationSavedSheet",
            sheetTag = FILE_VIEWER_ANNOTATE_SAVED_SHEET_TAG,
            probeTag = FILE_VIEWER_ANNOTATE_ATTACH_TAG,
        ) {
            AnnotationSavedSheet(
                host = "agents",
                savedPath = "/root/inbox/pocketshell/annotations/shot-20260729.png",
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                onCopyPath = {},
                onAttach = {},
                onDismiss = {},
            )
        }
    }

    // -- GitHistoryScreen.kt — CreateIssueSheet ------------------------------------

    @Test
    fun gitCreateIssueSheetLiftsAndKeepsSubmitAboveKeyboard() {
        measureSheet(
            site = "GitHistoryScreen.CreateIssueSheet",
            sheetTag = GIT_CREATE_ISSUE_SHEET_TAG,
            probeTag = GIT_CREATE_ISSUE_SUBMIT_TAG,
            openSheet = {
                compose.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
                compose.onNodeWithTag(GIT_NEW_ISSUE_TAG).performClick()
            },
        ) {
            GitHistoryScaffold(
                hostName = "agents",
                state = gitReadyState(),
                onBack = {},
                onRetry = {},
            )
        }
    }

    // -- FolderContextActionSheet.kt — FolderContextActionContent ------------------

    @Test
    fun folderContextSheetLiftsAndKeepsLastRowAboveKeyboard() {
        measureSheet(
            site = "FolderContextActionSheet.FolderContextActionContent",
            sheetTag = FOLDER_CONTEXT_SHEET_TAG,
            probeTag = FOLDER_CONTEXT_EMPTY_PROJECT_TAG,
        ) {
            FolderContextActionSheet(
                folderLabel = "projects",
                folderPath = "/root/projects",
                onDismiss = {},
                onNewSession = {},
                onImport = {},
                onCloneGitProject = {},
                onEmptyProject = {},
            )
        }
    }

    // -- SessionTypePickerSheet.kt — SessionTypePickerContent ----------------------

    @Test
    fun sessionTypePickerSheetLiftsAndKeepsCreateAboveKeyboard() {
        measureSheet(
            site = "SessionTypePickerSheet.SessionTypePickerContent",
            sheetTag = SESSION_TYPE_PICKER_SHEET_TAG,
            probeTag = SESSION_TYPE_PICKER_CREATE_TAG,
        ) {
            SessionTypePickerSheet(
                folderPath = "/root/projects/demo",
                folderLabel = "demo",
                onDismiss = {},
                onCreate = {},
            )
        }
    }

    // -- SnippetPickerSheet.kt — SnippetPickerContent ------------------------------

    /**
     * `SnippetPickerSheet` itself resolves two `hiltViewModel()`s, and this
     * module's `androidTest` variant has no Hilt test application, so the sheet
     * wrapper cannot be composed here. This mounts the PRODUCTION content
     * composable inside a real `ModalBottomSheet` — the identical composition
     * shape the wrapper produces — `SnippetPickerSheet`'s own
     * `ModalBottomSheet { SnippetPickerContent(...) }` — with only
     * the view-model plumbing replaced. The property under test is purely the
     * inset relationship between the sheet window and its content, which that
     * plumbing does not participate in.
     */
    @Test
    fun snippetPickerSheetLiftsAndKeepsSendChipAboveKeyboard() {
        measureSheet(
            site = "SnippetPickerSheet.SnippetPickerContent",
            sheetTag = SNIPPET_PICKER_PROBE_SHEET_TAG,
            probeTag = snippetSendChipTag(SNIPPET_FIXTURE.id, withEnter = true),
        ) {
            SnippetPickerContentInSheet()
        }
    }

    // ------------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------------

    /**
     * Mounts [content], settles it, then samples [probeTag]'s geometry with the
     * synthetic keyboard down and up and asserts the two load-bearing
     * properties. [openSheet] runs after the content is composed for surfaces
     * whose sheet is opened by a user action rather than mounted directly.
     */
    private fun measureSheet(
        site: String,
        sheetTag: String,
        probeTag: String,
        openSheet: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        stage.startEdgeToEdge()
        compose.setContent { PocketShellTheme { content() } }
        openSheet?.invoke()

        assertTrue(
            "The production sheet '$sheetTag' must mount and settle for site $site.",
            stage.awaitStable(sheetTag),
        )
        // A focus-requesting field inside a sheet can raise the real keyboard;
        // the synthetic boundary is only meaningful with it provably gone.
        stage.hideRealImeAndAssertHidden(
            "Issue #1821 synthetic sheet-geometry proof cannot start while a real IME is up " +
                "(site=$site).",
        )

        stage.dispatch(imeBottomPx = 0, requireExtraWindowRoot = true)
        assertTrue(
            "Keyboard-down geometry must settle before it is captured (site=$site).",
            stage.awaitStable(probeTag),
        )
        val down = stage.boundsOf(probeTag)

        val imeBottomPx = stage.imeBottomPx
        stage.dispatch(imeBottomPx = imeBottomPx, requireExtraWindowRoot = true)
        assertTrue(
            "Keyboard-up geometry must settle before it is captured (site=$site).",
            stage.awaitStable(probeTag),
        )
        val up = stage.boundsOf(probeTag)
        val modalRoot = stage.owningRootOf(probeTag)
        val liftPx = down.bottom - up.bottom
        val keyboardTopPx = modalRoot.boundsInRoot.bottom - imeBottomPx

        // Publish BEFORE asserting so even a red run carries the evidence — every
        // assertion, including the nonzero-keyboard guard below, which used to
        // sit above this line and would have cost a red run its geometry line
        // (the same wrinkle the #1821 reviewer found in the standalone proof).
        Log.i(
            GEOMETRY_LOG_TAG,
            "ISSUE1821_SHEET_GEOMETRY site=$site imeBottomPx=$imeBottomPx liftPx=$liftPx " +
                "keyboardDownProbe=$down keyboardUpProbe=$up " +
                "modalRoot=${modalRoot.boundsInRoot} keyboardTopPx=$keyboardTopPx " +
                "density=${stage.density}",
        )

        assertTrue("The synthetic keyboard must be nonzero.", imeBottomPx > 0)
        assertTrue(
            "The production sheet must lift its content by essentially the whole keyboard; " +
                "a zero lift means the synthetic inset never reached the modal window, or " +
                "Material3 stopped handling the ime inset for sheet content. " +
                "site=$site liftPx=$liftPx imeBottomPx=$imeBottomPx down=$down up=$up",
            liftPx >= imeBottomPx * SyntheticImeStage.MINIMUM_LIFT_FRACTION,
        )
        stage.assertFullyAboveKeyboard(probeTag, modalRoot, keyboardTopPx)
    }

    private object NoopInteractions : SessionCardInteractions {
        override fun onToggleChecklistItem(cardId: String, itemId: String, checked: Boolean) = Unit
        override fun onSetNoteRead(cardId: String, read: Boolean) = Unit
    }

    @Composable
    private fun ReviewSheetsHost(activeSheet: ReviewSheet) {
        ReviewSheets(
            activeSheet = activeSheet,
            reviewState = ReviewState(
                active = true,
                lineComments = mapOf(2 to "needs a null check"),
                fileComment = "overall looks fine",
            ),
            lines = listOf("package demo", "fun main() {", "}"),
            onDismiss = {},
            onSetLineComment = { _, _ -> },
            onDeleteLineComment = {},
            onSetFileComment = {},
            onDeleteFileComment = {},
            onOpenLine = {},
            onSubmitReview = {},
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SnippetPickerContentInSheet() {
        ModalBottomSheet(
            onDismissRequest = {},
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PocketShellColors.Surface,
            contentColor = PocketShellColors.Text,
        ) {
            Column(modifier = Modifier.testTag(SNIPPET_PICKER_PROBE_SHEET_TAG)) {
                SnippetPickerContent(
                    snippets = listOf(SNIPPET_FIXTURE),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetSend = { _, _ -> },
                    onManageTap = {},
                    onClose = {},
                )
            }
        }
    }

    private companion object {
        /**
         * Instrumentation `println` never reaches the Gradle console for a
         * connected run, so the per-site geometry evidence is logged and read
         * back from logcat.
         */
        const val GEOMETRY_LOG_TAG = "Issue1821Geometry"

        const val SNIPPET_PICKER_PROBE_SHEET_TAG = "issue1821:snippet-picker:sheet"

        val SNIPPET_FIXTURE = SnippetEntity(
            id = 1,
            hostId = 1,
            label = "deploy api",
            body = "kubectl rollout restart deploy/api",
            kind = "command",
        )

        fun checklistCard() = SessionCardsRemoteSource.ChecklistCard(
            id = "cl",
            title = "Deploy",
            createdAt = null,
            updatedAt = null,
            items = listOf(SessionCardsRemoteSource.ChecklistItem("build-0", "Build")),
            checkedIds = emptySet(),
        )

        fun gitReadyState() = GitHistoryUiState.Ready(
            dir = "/root/git/demo",
            commits = listOf(GitCommit("a1b2c3d", "Ada Lovelace", "2 hours ago", "Add timeline")),
            truncated = false,
            overview = GitRepoOverview(
                status = GitRepoStatus(
                    currentBranch = "main",
                    upstream = "origin/main",
                    ahead = 0,
                    behind = 0,
                    dirty = false,
                    changedFiles = 0,
                    lastCommit = "a1b2c3d Add timeline",
                    hasNoCommits = false,
                ),
                branches = listOf(
                    GitBranch("main", current = true, upstream = "origin/main", subject = "Add timeline"),
                ),
                worktrees = listOf(
                    GitWorktree(
                        "/root/git/demo",
                        branch = "main",
                        head = "a1b2c3d",
                        bare = false,
                        detached = false,
                    ),
                ),
            ),
            issues = emptyList(),
            ghHint = null,
        )
    }
}
