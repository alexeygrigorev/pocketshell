package com.pocketshell.app.insets

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.cards.ChecklistCardsContent
import com.pocketshell.app.cards.SESSION_CHECKLIST_ITEM_TAG_PREFIX
import com.pocketshell.app.cards.SessionCardFeedContent
import com.pocketshell.app.cards.SessionCardInteractions
import com.pocketshell.app.cards.SessionCardsRemoteSource
import com.pocketshell.app.projects.FOLDER_CONTEXT_EMPTY_PROJECT_TAG
import com.pocketshell.app.projects.FolderContextActionContent
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CREATE_TAG
import com.pocketshell.app.projects.SessionTypePickerContent
import com.pocketshell.app.snippets.SnippetPickerContent
import com.pocketshell.app.snippets.snippetSendChipTag
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1821 — the LIVE half of the nested-`imePadding()` sweep.
 *
 * ## Why these five sites are not deleted with the others
 *
 * Ten sites carried the copy-pasted `.navigationBarsPadding().imePadding()`
 * idiom. Five of them are written inline inside a `ModalBottomSheet` content
 * lambda, so the sheet is their ONLY possible composition and the modifier is
 * structurally unreachable — those are deleted (D22 hard cut).
 *
 * The other five are **extracted content composables** that are also composed
 * **standalone**, outside any `ModalBottomSheet`:
 *
 *  - `SessionCardFeedContent`  — `SessionCardFeedRegistryTest`
 *  - `ChecklistCardsContent`   — `SessionChecklistUiInteractionTest`,
 *                                `SessionChecklistPushJourneyDockerTest`
 *  - `FolderContextActionContent` — `FolderContextActionSheetScreenshotTest`
 *  - `SessionTypePickerContent` — `SessionTypePickerNameFieldUiTest` and three
 *                                 sibling picker tests
 *                                 (`SessionTypePickerSkipPermissionsUiTest`,
 *                                 `SessionTypeProfilePickerUiTest`,
 *                                 `RepoBrowserSessionPickerTest`)
 *  - `SnippetPickerContent`    — `SnippetPickerSendButtonsTest`,
 *                                `SnippetPickerBodyPreviewTest`
 *
 * Outside a sheet nothing has consumed the ime or navigation-bar insets, so for
 * these composables `.imePadding()` and `.navigationBarsPadding()` are the ONLY
 * things keeping their bottom controls clear of the keyboard and the nav bar.
 * Deleting them "by analogy with #1812" would have been wrong — which is the
 * entire reason #1821 exists as a per-site sweep rather than a blanket one.
 *
 * ## What each test asserts
 *
 * The content is mounted **bottom-anchored** and a synthetic inset set (a 295 dp
 * `Type.ime()` and a 48 dp `Type.navigationBars()`) is dispatched. Three hard
 * assertions per site, deliberately split across BOTH keyboard states because
 * the two modifiers are only visible in different ones:
 *
 *  1. keyboard **UP** — the bottom-most control **lifts by essentially the whole
 *     keyboard**, and
 *  2. keyboard **UP** — it is **fully contained above the keyboard boundary**
 *     (`boundsInRoot` containment, not `assertIsDisplayed()` — F2/F3).
 *     1 and 2 go RED when the site's `.imePadding()` is removed (lift 648 → 0).
 *  3. keyboard **DOWN** — it is **fully contained above the navigation-bar
 *     boundary**. This one goes RED when the site's `.navigationBarsPadding()`
 *     is removed (the control drops 126 px, straight under the nav bar).
 *
 * Assertion 3 is not decoration. With the keyboard up the ime inset subsumes the
 * nav bar, so 1 and 2 **structurally cannot see** a missing
 * `navigationBarsPadding()` — the #1821 reviewer removed exactly that modifier
 * from a kept site and this suite came back 15/15 green while the bottom row sat
 * under the nav bar. Only the keyboard-down state, where the ime inset is zero,
 * has bite. #1812 and #1742 could say nothing about `navigationBarsPadding()`
 * for precisely this reason: their oracle dispatched a ZERO nav-bar inset.
 *
 * Together the three make the "keep" decision durable for BOTH modifiers: before
 * this class, nothing in the tree distinguished a live site from an inert one,
 * so the next reader had no way to tell them apart.
 *
 * ## One honest qualifier: no EXISTING caller depends on these modifiers
 *
 * "LIVE" here is a **precautionary invariant** about what these modifiers do
 * when the content is composed outside a sheet — not a claim that some existing
 * on-device journey depends on them today. That was checked, not assumed:
 *
 *  - Most standalone callers mount the content **top-anchored with no IME**,
 *    where a missing bottom inset is geometrically invisible.
 *  - The one exception is `SessionTypePickerSkipPermissionsUiTest`
 *    `folderSuggestionStaysAboveImeAndCanBeSelected` (#613), which DOES mount
 *    `SessionTypePickerContent` bottom-anchored (`Alignment.BottomCenter` in a
 *    `fillMaxSize` Box) with the REAL soft IME raised — the same shape this
 *    proof uses. Even so, it does not depend on the modifier: deleting
 *    `.imePadding()` from `SessionTypePickerContent` left all 6 of that class's
 *    cases green (#1821 round 2, runs R2F0/R2F1). Its assertions do not read
 *    the geometry the modifier moves.
 *
 * So the bottom-anchored mount below is the faithful standalone analogue of a
 * bottom sheet and the arrangement in which the modifier is observable at all —
 * which is why the proof uses it — but it is this class, and only this class,
 * that turns "keep it" into something a gate can enforce.
 *
 * The `site=` labels name `File.Composable` and deliberately carry no line
 * number: #1821's own round-2 comments shifted every line number these labels
 * used to cite, inside the same change (see
 * [NestedImePaddingInSheetGeometryTest], "Why no `file:line` in the site
 * labels").
 *
 * No `assumeTrue` / `assumeFalse`: an environment that cannot reach the state
 * fails hard.
 */
@RunWith(AndroidJUnit4::class)
class StandaloneContentImePaddingLivenessTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stage by lazy { SyntheticImeStage(compose) }

    @After
    fun releaseSyntheticInsets() {
        runCatching { stage.dispatch(imeBottomPx = 0, requireExtraWindowRoot = false) }
        runCatching { stage.hideRealImeBestEffort() }
    }

    @Test
    fun sessionCardFeedContentLiftsItsLastCardAboveTheKeyboard() {
        measureStandalone(
            site = "SessionChecklistUi.SessionCardFeedContent",
            probeTag = SESSION_CHECKLIST_ITEM_TAG_PREFIX + "cl:build-0",
        ) {
            SessionCardFeedContent(
                cards = listOf(checklistCard()),
                interactions = NoopInteractions,
                onClose = {},
            )
        }
    }

    @Test
    fun checklistCardsContentLiftsItsLastItemAboveTheKeyboard() {
        measureStandalone(
            site = "SessionChecklistUi.ChecklistCardsContent",
            probeTag = SESSION_CHECKLIST_ITEM_TAG_PREFIX + "cl:build-0",
        ) {
            ChecklistCardsContent(
                cards = listOf(checklistCard()),
                onToggle = { _, _, _ -> },
                onClose = {},
            )
        }
    }

    @Test
    fun folderContextActionContentLiftsItsLastRowAboveTheKeyboard() {
        measureStandalone(
            site = "FolderContextActionSheet.FolderContextActionContent",
            probeTag = FOLDER_CONTEXT_EMPTY_PROJECT_TAG,
        ) {
            FolderContextActionContent(
                folderLabel = "projects",
                folderPath = "/root/projects",
                onNewSession = {},
                onImport = {},
                onCloneGitProject = {},
                onEmptyProject = {},
            )
        }
    }

    @Test
    fun sessionTypePickerContentLiftsCreateAboveTheKeyboard() {
        measureStandalone(
            site = "SessionTypePickerSheet.SessionTypePickerContent",
            probeTag = SESSION_TYPE_PICKER_CREATE_TAG,
        ) {
            SessionTypePickerContent(
                folderPath = "/root/projects/demo",
                folderLabel = "demo",
                onCancel = {},
                onCreate = {},
            )
        }
    }

    @Test
    fun snippetPickerContentLiftsItsSendChipAboveTheKeyboard() {
        measureStandalone(
            site = "SnippetPickerSheet.SnippetPickerContent",
            probeTag = snippetSendChipTag(SNIPPET_FIXTURE.id, withEnter = true),
        ) {
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

    // ------------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------------

    private fun measureStandalone(
        site: String,
        probeTag: String,
        content: @Composable () -> Unit,
    ) {
        stage.startEdgeToEdge()
        compose.setContent {
            PocketShellTheme {
                // Bottom-anchored: the standalone analogue of a bottom sheet,
                // and the arrangement where a missing bottom inset actually
                // pushes controls under the keyboard.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    content()
                }
            }
        }

        assertTrue(
            "The standalone content must mount and settle for site $site.",
            stage.awaitStable(probeTag),
        )
        stage.hideRealImeAndAssertHidden(
            "Issue #1821 standalone liveness proof cannot start while a real IME is up " +
                "(site=$site).",
        )

        stage.dispatch(imeBottomPx = 0, requireExtraWindowRoot = false)
        assertTrue(
            "Keyboard-down geometry must settle before it is captured (site=$site).",
            stage.awaitStable(probeTag),
        )
        val down = stage.boundsOf(probeTag)

        val imeBottomPx = stage.imeBottomPx
        val navBarBottomPx = stage.navBarBottomPx
        stage.dispatch(imeBottomPx = imeBottomPx, requireExtraWindowRoot = false)
        assertTrue(
            "Keyboard-up geometry must settle before it is captured (site=$site).",
            stage.awaitStable(probeTag),
        )
        val up = stage.boundsOf(probeTag)
        val root = stage.owningRootOf(probeTag)
        val liftPx = down.bottom - up.bottom
        val keyboardTopPx = root.boundsInRoot.bottom - imeBottomPx
        val navBarTopPx = root.boundsInRoot.bottom - navBarBottomPx

        // Publish BEFORE asserting so even a red run carries the evidence. That
        // is why the two non-vacuity guards below sit AFTER this line and not
        // before it: in #1821 round 2 the nav-bar guard preceded the log, so the
        // run that mutated the nav bar to zero shipped 10 geometry lines instead
        // of 15 — a red run that dropped a third of its own evidence.
        Log.i(
            GEOMETRY_LOG_TAG,
            "ISSUE1821_STANDALONE_GEOMETRY site=$site imeBottomPx=$imeBottomPx " +
                "navBarBottomPx=$navBarBottomPx liftPx=$liftPx " +
                "keyboardDownProbe=$down keyboardUpProbe=$up root=${root.boundsInRoot} " +
                "keyboardTopPx=$keyboardTopPx navBarTopPx=$navBarTopPx " +
                "density=${stage.density}",
        )

        assertTrue("The synthetic keyboard must be nonzero.", imeBottomPx > 0)
        // A zero nav-bar inset is what made #1812/#1742 unable to say anything
        // about `navigationBarsPadding()`: the boundary assertion below would be
        // satisfied by the root's own bottom edge and would prove nothing.
        assertTrue(
            "The synthetic navigation bar must be nonzero, or the keyboard-down " +
                "containment assertion below is vacuous.",
            navBarBottomPx > 0,
        )

        // ---- what `.imePadding()` protects: the keyboard-UP state ------------
        assertTrue(
            "Composed OUTSIDE a ModalBottomSheet nothing has consumed the ime inset, so this " +
                "content's own imePadding() is the only thing lifting it off the keyboard. " +
                "A zero lift means that modifier is gone. " +
                "site=$site liftPx=$liftPx imeBottomPx=$imeBottomPx down=$down up=$up",
            liftPx >= imeBottomPx * SyntheticImeStage.MINIMUM_LIFT_FRACTION,
        )
        stage.assertFullyAboveKeyboard(probeTag, root, keyboardTopPx)

        // ---- what `.navigationBarsPadding()` protects: the keyboard-DOWN state
        // Re-enter the keyboard-down state and assert LIVE geometry there. This
        // half cannot be folded into the assertions above: with the keyboard up
        // the 295 dp ime inset subsumes the 48 dp nav bar, so removing
        // `navigationBarsPadding()` leaves every keyboard-up assertion green
        // while the bottom control drops a full nav-bar height out of reach.
        // (Found by the #1821 reviewer: 15/15 green with the modifier deleted.)
        stage.dispatch(imeBottomPx = 0, requireExtraWindowRoot = false)
        assertTrue(
            "Keyboard-down geometry must settle again before the navigation-bar " +
                "containment assertion (site=$site).",
            stage.awaitStable(probeTag),
        )
        val downAgain = stage.boundsOf(probeTag)
        assertTrue(
            "Re-entering the keyboard-down state must reproduce the geometry captured " +
                "at the start of this measurement, or the two halves of this proof are " +
                "reading different layouts. site=$site first=$down second=$downAgain",
            kotlin.math.abs(downAgain.bottom - down.bottom) <= 1f,
        )
        val downRoot = stage.owningRootOf(probeTag)
        stage.assertFullyAboveNavigationBar(
            probeTag,
            downRoot,
            downRoot.boundsInRoot.bottom - navBarBottomPx,
        )
    }

    private object NoopInteractions : SessionCardInteractions {
        override fun onToggleChecklistItem(cardId: String, itemId: String, checked: Boolean) = Unit
        override fun onSetNoteRead(cardId: String, read: Boolean) = Unit
    }

    private companion object {
        /**
         * Instrumentation `println` never reaches the Gradle console for a
         * connected run, so the per-site geometry evidence is logged and read
         * back from logcat.
         */
        const val GEOMETRY_LOG_TAG = "Issue1821Geometry"

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
    }
}
