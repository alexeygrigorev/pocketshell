package com.pocketshell.next.composer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The rendered composer on the host JVM (Robolectric).
 *
 * `J07ComposerSendJourney` proves the send really reaches a real host; this
 * suite pins the chrome rules around it — the ones a device journey would only
 * notice by screenshot: that the undelivered chip is a distinct, visible thing,
 * that Send is gated on having something to send, that the slash sheet opens
 * only when it should, and that a staged attachment is visible with a way to
 * remove it.
 */
@RunWith(AndroidJUnit4::class)
class ComposerBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an empty composer cannot send`() {
        setContent(ComposerUiState())

        composeRule.onNodeWithTag(COMPOSER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertIsNotEnabled()
    }

    /**
     * The editor reports what it holds, IME composing region included.
     *
     * The old client shipped a Send that read a stale `String`-backed draft and
     * therefore did nothing for text the IME had not committed yet; the field
     * here is `TextFieldValue`-backed so the composer always sees the visible
     * text.
     */
    @Test
    fun `typing reports the field's text`() {
        val typed = mutableListOf<String>()
        setContent(ComposerUiState(), onDraftChange = { typed += it })

        composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("hi")

        assertEquals("hi", typed.last())
    }

    @Test
    fun `a draft enables send`() {
        setContent(ComposerUiState(draft = "something"))

        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertIsEnabled()
    }

    @Test
    fun `insert and send are separate taps`() {
        var inserts = 0
        var sends = 0
        setContent(
            ComposerUiState(draft = "something"),
            onInsert = { inserts += 1 },
            onSend = { sends += 1 },
        )

        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).performClick()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).performClick()

        assertEquals(1, inserts)
        assertEquals(1, sends)
    }

    /** An attachment on its own is a complete message. */
    @Test
    fun `a staged attachment alone enables send and renders a tile`() {
        setContent(ComposerUiState(attachments = listOf(attachment())))

        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(COMPOSER_ATTACHMENTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(composerAttachmentTileTag(REMOTE_PATH)).assertIsDisplayed()
    }

    @Test
    fun `removing a tile reports its remote path`() {
        var removed: String? = null
        setContent(
            ComposerUiState(attachments = listOf(attachment())),
            onRemoveAttachment = { removed = it },
        )

        composeRule.onNodeWithTag(composerAttachmentRemoveTag(REMOTE_PATH)).performClick()

        assertEquals(REMOTE_PATH, removed)
    }

    /**
     * The whole delivery story has to be VISIBLE. A draft silently kept with no
     * chip is indistinguishable from a message that was sent.
     */
    @Test
    fun `the undelivered chip renders with the draft still in the field`() {
        setContent(ComposerUiState(draft = "kept text", notice = ComposerNotice.Undelivered))

        composeRule.onNodeWithTag(COMPOSER_UNDELIVERED_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(COMPOSER_UNDELIVERED_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText("kept text").assertIsDisplayed()
    }

    @Test
    fun `a problem notice is not the undelivered chip`() {
        setContent(ComposerUiState(notice = ComposerNotice.Problem("upload failed")))

        composeRule.onNodeWithTag(COMPOSER_NOTICE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_UNDELIVERED_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("upload failed").assertIsDisplayed()
    }

    @Test
    fun `an upload in flight shows its progress and blocks send`() {
        setContent(
            ComposerUiState(draft = "text", staging = StagingProgress(2, 3, "shot.png")),
        )

        composeRule.onNodeWithTag(COMPOSER_STAGING_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Uploading 2 of 3 · shot.png").assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSER_TOOLS_TRIGGER_TAG).assertIsNotEnabled()
    }

    @Test
    fun `the mic is disabled while no recognizer is wired`() {
        setContent(ComposerUiState(micAvailable = false))

        composeRule.onNodeWithTag(COMPOSER_MIC_TAG).assertIsNotEnabled()
    }

    @Test
    fun `recording swaps the editing tools for a discard action`() {
        setContent(ComposerUiState(recording = RecordingState.Recording, micAvailable = true))

        composeRule.onNodeWithTag(COMPOSER_DISCARD_RECORDING_TAG).assertIsDisplayed()
        // Attach / history / slash / mic are text-composition tools, not
        // usable mid-dictation. Insert and Send stay on the recording row.
        composeRule.onNodeWithTag(COMPOSER_ATTACH_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_HISTORY_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_SLASH_TRIGGER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_TOOLS_TRIGGER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_MIC_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsDisplayed()
    }

    @Test
    fun `typing a slash does not render an inline command dropdown`() {
        setContent(ComposerUiState())

        composeRule.onNodeWithTag(COMPOSER_SLASH_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("/")

        composeRule.onNodeWithTag(COMPOSER_SLASH_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(composerSlashRowTag("/clear")).assertDoesNotExist()
    }

    @Test
    fun `the slash tool opens the native command sheet`() {
        var draft = ""
        setContent(ComposerUiState(), onDraftChange = { draft = it })
        composeRule.onNodeWithTag(COMPOSER_TOOLS_TRIGGER_TAG).performClick()
        composeRule.onNodeWithTag(COMPOSER_SLASH_TRIGGER_TAG).performClick()

        composeRule.onNodeWithTag(COMPOSER_SLASH_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SLASH_SEARCH_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(composerSlashRowTag("/clear")).assertIsDisplayed()
        assertEquals("/", draft)
    }

    @Test
    fun `preview renders the draft as markdown instead of the editor`() {
        setContent(ComposerUiState(draft = "# Heading", previewing = true))

        composeRule.onNodeWithTag(COMPOSER_PREVIEW_VIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Heading").assertIsDisplayed()
    }

    @Test
    fun `preview is not on the idle control row`() {
        setContent(ComposerUiState())

        composeRule.onNodeWithTag(COMPOSER_PREVIEW_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Preview").assertDoesNotExist()
    }

    @Test
    fun `the history control is always reachable`() {
        var toggled = 0
        setContent(ComposerUiState(), onToggleHistory = { toggled += 1 })

        composeRule.onNodeWithTag(COMPOSER_TOOLS_TRIGGER_TAG).performClick()
        composeRule.onNodeWithTag(COMPOSER_HISTORY_TAG).performClick()

        assertEquals(1, toggled)
    }

    /**
     * #2529 reproduce-first: idle chrome is ONE control row. The rewrite
     * shipped Insert/Send on a second row with Recent/Preview/Clear on the
     * mic row. This fails on that two-row occupancy and passes when Insert,
     * Send, and the mic share a row and the rewrite text tools are gone.
     */
    @Test
    fun `idle controls sit on one row with grouped tools insert send and mic`() {
        setContent(ComposerUiState(draft = "hello", micAvailable = true))

        composeRule.onNodeWithTag(COMPOSER_TOOLS_TRIGGER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_HISTORY_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_SLASH_TRIGGER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed()

        composeRule.onNodeWithText("Recent").assertDoesNotExist()
        composeRule.onNodeWithText("Preview").assertDoesNotExist()
        composeRule.onNodeWithText("Clear").assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_PREVIEW_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_DISCARD_TAG).assertDoesNotExist()

        assertSameRow(COMPOSER_INSERT_TAG, COMPOSER_SEND_TAG, COMPOSER_MIC_TAG)
        assertSameRow(COMPOSER_TOOLS_TRIGGER_TAG, COMPOSER_INSERT_TAG, COMPOSER_SEND_TAG, COMPOSER_MIC_TAG)
    }

    /**
     * #2630 reproduce-first: "now the attach button is hidden under plus".
     *
     * #2529 folded Attach into the "+" sheet, so staging a screenshot went from
     * one tap to two-plus-a-modal. This fails on that shape — the tag does not
     * exist until the sheet is opened — and passes only when the paperclip is a
     * real control on the idle row next to Send and the mic.
     */
    @Test
    fun `attach is one tap on the controls row not a row inside the plus sheet`() {
        var attaches = 0
        setContent(ComposerUiState(draft = "hello", micAvailable = true), onAttach = { attaches += 1 })

        composeRule.onNodeWithTag(COMPOSER_ATTACH_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Attach files").assertIsDisplayed()
        assertSameRow(
            COMPOSER_ATTACH_TAG,
            COMPOSER_TOOLS_TRIGGER_TAG,
            COMPOSER_SEND_TAG,
            COMPOSER_MIC_TAG,
        )

        // One tap, from the closed composer, reaches the document picker.
        composeRule.onNodeWithTag(COMPOSER_ATTACH_TAG).performClick()
        assertEquals(1, attaches)

        // And it is no longer duplicated inside the sheet: the "+" panel keeps
        // only the genuinely occasional tools.
        composeRule.onNodeWithTag(COMPOSER_TOOLS_TRIGGER_TAG).performClick()
        composeRule.onNodeWithTag(COMPOSER_TOOLS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Attach file").assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_HISTORY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SLASH_TRIGGER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_DISCARD_TAG).assertIsDisplayed()
    }

    /**
     * #2630 reproduce-first: the maintainer's annotated screenshot arrows the
     * stacked attachment info, and their follow-up review asked for the
     * pre-0.5.0 shape back.
     *
     * A single staged file announced itself three times — a dismissable
     * "Attached 1 file." banner, the tile, and a full-width "Uploaded to
     * <remote path>" line per file. The tiles are now the whole signal, and
     * they sit BELOW the draft field the way the old client had them
     * (`Issue2057AttachmentTilesBelowDraftProofTest`) rather than shoving the
     * editor down.
     *
     * This fails on the shipped shape (a destination line exists, and the tiles
     * are above the field) and passes on tiles-only, below the draft.
     */
    @Test
    fun `staged attachments are just tiles and they sit below the draft field`() {
        val second = StagedAttachment(SECOND_REMOTE_PATH, "log.txt", "text/plain")
        setContent(ComposerUiState(draft = "look", attachments = listOf(attachment(), second)))

        composeRule.onNodeWithTag(composerAttachmentTileTag(REMOTE_PATH)).assertIsDisplayed()
        composeRule.onNodeWithTag(composerAttachmentTileTag(SECOND_REMOTE_PATH)).assertIsDisplayed()

        // No destination caption survives, in any spelling: not per file, not
        // merged for the batch.
        composeRule.onNodeWithText("Uploaded to $REMOTE_PATH").assertDoesNotExist()
        composeRule.onNodeWithText("Uploaded to $SECOND_REMOTE_PATH").assertDoesNotExist()
        composeRule.onNodeWithText("Uploaded to $REMOTE_DIR").assertDoesNotExist()
        assertEquals(
            "no 'Uploaded to' caption may remain under the tiles",
            0,
            composeRule.onAllNodesWithText("Uploaded to", substring = true)
                .fetchSemanticsNodes().size,
        )

        // Order: draft field, THEN the tiles, THEN the controls row.
        val draft = composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG).fetchSemanticsNode().boundsInRoot
        val tiles = composeRule.onNodeWithTag(COMPOSER_ATTACHMENTS_TAG).fetchSemanticsNode().boundsInRoot
        val controls = composeRule.onNodeWithTag(COMPOSER_CONTROLS_ROW_TAG)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "tiles must sit BELOW the draft field (draft ended at ${draft.bottom}, " +
                "tiles started at ${tiles.top})",
            tiles.top >= draft.bottom,
        )
        assertTrue(
            "tiles must sit above the controls row (tiles ended at ${tiles.bottom}, " +
                "controls started at ${controls.top})",
            tiles.bottom <= controls.top,
        )
    }

    /**
     * #2529 reproduce-first: recording chrome is timer+waveform plus one
     * right-aligned [Discard · Insert · Send] row. The rewrite hid Insert/Send
     * and left the mic on the listening row.
     */
    @Test
    fun `recording shows timer waveform and discard insert send on one row`() {
        setContent(ComposerUiState(recording = RecordingState.Recording, micAvailable = true))

        composeRule.onNodeWithTag(COMPOSER_TIMER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_WAVEFORM_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_DISCARD_RECORDING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(COMPOSER_ATTACH_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_HISTORY_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_SLASH_TRIGGER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_MIC_TAG).assertDoesNotExist()

        assertSameRow(
            COMPOSER_DISCARD_RECORDING_TAG,
            COMPOSER_INSERT_TAG,
            COMPOSER_SEND_TAG,
            COMPOSER_STOP_RECORDING_TAG,
        )
    }

    /**
     * #2598: "there is no way to stop it". The recording row shipped
     * Discard / Insert / Send and no stop control, so a dictation could only
     * be ended by throwing the text away or committing it — never by handing
     * it back as an editable draft.
     */
    @Test
    fun `the recording row has a stop control that is not discard`() {
        var micTaps = 0
        var discards = 0
        setContent(
            ComposerUiState(recording = RecordingState.Recording, micAvailable = true),
            onMicTap = { micTaps += 1 },
            onCancelRecording = { discards += 1 },
        )

        composeRule.onNodeWithTag(COMPOSER_STOP_RECORDING_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(COMPOSER_STOP_RECORDING_DESCRIPTION)
            .assertIsDisplayed()

        composeRule.onNodeWithTag(COMPOSER_STOP_RECORDING_TAG).performClick()

        assertEquals("stop ends the dictation", 1, micTaps)
        assertEquals("stop is not discard", 0, discards)

        composeRule.onNodeWithTag(COMPOSER_DISCARD_RECORDING_TAG).performClick()
        assertEquals(1, discards)
        assertEquals(1, micTaps)
    }

    /**
     * Transcribing is its own surface, and the recording controls that only
     * make sense while the mic is live are gone from it — Cancel is the way
     * out, not a second stop.
     */
    @Test
    fun `transcribing shows its own surface without the recording controls`() {
        setContent(ComposerUiState(recording = RecordingState.Transcribing, micAvailable = true))

        composeRule.onNodeWithTag(COMPOSER_TRANSCRIBING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_WAVEFORM_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_STOP_RECORDING_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_DISCARD_RECORDING_TAG).assertIsDisplayed()
    }

    /**
     * The stop control has to FIT: a `Row` does not overflow, it SQUASHES —
     * a recording row one button too wide for a phone silently shrinks its
     * last child instead of pushing it off-screen, which would be the same
     * "no way out" bug with a stop button too small to hit.
     *
     * Stop is measured last, so its full 44dp square is the canary for the
     * whole row having room.
     */
    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `the recording row fits a phone width`() {
        setContent(ComposerUiState(recording = RecordingState.Recording, micAvailable = true))

        val row = composeRule.onNodeWithTag(COMPOSER_CONTROLS_ROW_TAG)
            .fetchSemanticsNode().boundsInRoot
        val density = composeRule.density.density
        val controls = listOf(
            COMPOSER_DISCARD_RECORDING_TAG,
            COMPOSER_INSERT_TAG,
            COMPOSER_SEND_TAG,
            COMPOSER_STOP_RECORDING_TAG,
        ).associateWith { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        }

        controls.forEach { (tag, bounds) ->
            assertTrue(
                "$tag is outside the controls row: $bounds vs $row",
                bounds.left >= row.left - 0.5f && bounds.right <= row.right + 0.5f,
            )
        }
        val stop = controls.getValue(COMPOSER_STOP_RECORDING_TAG)
        assertEquals(
            "the stop control keeps the 48dp touch target",
            48f,
            stop.width / density,
            0.5f,
        )
        assertEquals(48f, stop.height / density, 0.5f)
    }

    @Test
    fun `the slash trigger seeds a leading slash and opens the native sheet`() {
        var draft = ""
        setContent(ComposerUiState(), onDraftChange = { draft = it })

        composeRule.onNodeWithTag(COMPOSER_TOOLS_TRIGGER_TAG).performClick()
        composeRule.onNodeWithTag(COMPOSER_SLASH_TRIGGER_TAG).performClick()

        assertEquals("/", draft)
        composeRule.onNodeWithTag(COMPOSER_SLASH_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SLASH_SEARCH_TAG).assertIsDisplayed()
    }

    @Test
    fun `remote delivery controls are disabled while the draft remains editable`() {
        val drafts = mutableListOf<String>()
        setContent(
            ComposerUiState(draft = "local draft"),
            onDraftChange = { drafts += it },
            deliveryEnabled = false,
        )

        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(" more")

        assertEquals("local draft more", drafts.last())
    }

    // --------------------------------------------------------------- helpers

    private fun attachment() = StagedAttachment(REMOTE_PATH, "shot.png", "image/png")

    private fun setContent(
        state: ComposerUiState,
        onDraftChange: (String) -> Unit = {},
        onSend: () -> Unit = {},
        onInsert: () -> Unit = {},
        onAttach: () -> Unit = {},
        onRemoveAttachment: (String) -> Unit = {},
        onToggleHistory: () -> Unit = {},
        onMicTap: () -> Unit = {},
        onCancelRecording: () -> Unit = {},
        deliveryEnabled: Boolean = true,
    ) {
        composeRule.setContent {
            PocketShellTheme {
                ComposerBar(
                    state = state,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onInsert = onInsert,
                    onAttach = onAttach,
                    onMicTap = onMicTap,
                    onCancelRecording = onCancelRecording,
                    onToggleHistory = onToggleHistory,
                    onTogglePreview = {},
                    onRemoveAttachment = onRemoveAttachment,
                    onDismissNotice = {},
                    onDiscard = {},
                    deliveryEnabled = deliveryEnabled,
                )
            }
        }
    }

    /**
     * Two nodes share a row when their bounds overlap vertically. Tops can
     * disagree when heights differ (a 44dp mic next to a 48dp pill) as long
     * as they sit in the same [androidx.compose.foundation.layout.Row].
     */
    private fun assertSameRow(vararg tags: String) {
        require(tags.size >= 2)
        val bounds = tags.associateWith { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        }
        val firstTag = tags.first()
        val first = bounds.getValue(firstTag)
        tags.drop(1).forEach { tag ->
            val other = bounds.getValue(tag)
            assertTrue(
                "$tag (top=${other.top} bottom=${other.bottom}) must share a row with " +
                    "$firstTag (top=${first.top} bottom=${first.bottom})",
                first.top < other.bottom && other.top < first.bottom,
            )
        }
    }

    private companion object {
        const val REMOTE_DIR = "~/.pocketshell/attachments/7-devbox"
        const val REMOTE_PATH = "$REMOTE_DIR/shot.png"
        const val SECOND_REMOTE_PATH = "$REMOTE_DIR/log.txt"
    }
}
