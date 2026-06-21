package com.pocketshell.app.conversation

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationImage
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Issue #842 — rendered-UI proof that a transcript image is SURFACED inline
 * (the bug: an image content block / tool-result image / pasted-image-by-path
 * was dropped or shown as a raw path, never displayed).
 *
 * The image loader is injected via [LocalConversationImageLoader] so this is a
 * deterministic render proof (no SSH/host). It exercises the real production
 * composables ([ConversationImageContent] and a [ConversationEvent.Message]
 * turn carrying an image):
 *  - loader succeeds → the inline image renders ([CONVERSATION_IMAGE_TAG]);
 *  - loader fails → the path-text FALLBACK renders ([CONVERSATION_IMAGE_FALLBACK_TAG])
 *    and NO image node exists (the reference is preserved, not dropped);
 *  - load in flight → the loading placeholder renders.
 *
 * The load-bearing (green) assertion is the FALLBACK on failure — that is the
 * #842 user-visible behaviour (a referenced image that can't be fetched still
 * shows its path, never silently vanishing).
 */
@RunWith(AndroidJUnit4::class)
class ConversationImageContentRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val image = ConversationImage(path = "/home/me/shot.png")

    @Test
    fun loaderSuccessRendersInlineImage() {
        val loader = ConversationImageLoader { Result.success(tinyPngBytes()) }
        compose.setContent {
            PocketShellTheme {
                CompositionLocalProvider(LocalConversationImageLoader provides loader) {
                    ConversationImageContent(image = image)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(CONVERSATION_IMAGE_TAG).assertIsDisplayed()
    }

    @Test
    fun loaderFailureRendersPathTextFallbackAndNoImage() {
        val loader = ConversationImageLoader { Result.failure(RuntimeException("host unreachable")) }
        compose.setContent {
            PocketShellTheme {
                CompositionLocalProvider(LocalConversationImageLoader provides loader) {
                    ConversationImageContent(image = image)
                }
            }
        }
        compose.waitForIdle()
        // The reference is preserved (fallback shown) and the image is absent.
        compose.onNodeWithTag(CONVERSATION_IMAGE_FALLBACK_TAG).assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodes(hasTestTag(CONVERSATION_IMAGE_TAG)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun undecodableBytesDegradeToFallback() {
        val loader = ConversationImageLoader { Result.success(byteArrayOf(1, 2, 3, 4)) }
        compose.setContent {
            PocketShellTheme {
                CompositionLocalProvider(LocalConversationImageLoader provides loader) {
                    ConversationImageContent(image = image)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(CONVERSATION_IMAGE_FALLBACK_TAG).assertIsDisplayed()
    }

    @Test
    fun inFlightLoadShowsPlaceholder() {
        val pending = CompletableDeferred<Result<ByteArray>>()
        val loader = ConversationImageLoader { pending.await() }
        compose.setContent {
            PocketShellTheme {
                CompositionLocalProvider(LocalConversationImageLoader provides loader) {
                    ConversationImageContent(image = image)
                }
            }
        }
        compose.onNodeWithTag(CONVERSATION_IMAGE_PLACEHOLDER_TAG).assertIsDisplayed()
    }

    @Test
    fun messageTurnWithImageRendersTheImageInline() {
        // The whole production message turn (text + image) — proves the image is
        // surfaced where the user actually sees the transcript, not just in the
        // isolated content composable.
        val loader = ConversationImageLoader { Result.success(tinyPngBytes()) }
        val event = ConversationEvent.Message(
            id = "m1",
            agent = com.pocketshell.core.agents.AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "look at this",
            images = listOf(image),
        )
        compose.setContent {
            PocketShellTheme {
                CompositionLocalProvider(LocalConversationImageLoader provides loader) {
                    ConversationMessageTurn(event = event)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(CONVERSATION_IMAGE_TAG).assertIsDisplayed()
    }

    private fun tinyPngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
