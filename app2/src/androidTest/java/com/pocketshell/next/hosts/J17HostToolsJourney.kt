package com.pocketshell.next.hosts

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * J17 — current-window evidence for the host tools added by the Quiet
 * redesign: SSH-key detail/generation/import/copy and the Add host sheet.
 *
 * The assertions deliberately drive the real navigation graph on an emulator.
 * JVM screen tests cover the state tables; this journey proves that the tools
 * are reachable from Hosts and that the system clipboard surface exists in the
 * shipping Activity chrome.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J17HostToolsJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var fixtureKeyId: Long = 0L

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val pem = AgentsFixture.privateKeyPem()
        val keyPath = AgentsFixture.installPrivateKey("j17_fixture_key")
        fixtureKeyId = graph.sshKeyDao().insert(
            SshKeyEntity(
                name = "fixture-key",
                privateKeyPath = keyPath,
                fingerprint = SshKeyMaterial.fingerprint(pem),
            ),
        )
        graph.hostDao().insert(
            HostEntity(
                id = 9_701L,
                name = "tools-host",
                hostname = "example.invalid",
                username = "testuser",
                keyId = fixtureKeyId,
            ),
        )

        println("J17_SEED ${description.methodName}")
    }

    @Test
    fun hostToolsReachRealKeySurfaces() {
        awaitTag(hostRowTag(9_701L))

        openTools()
        compose.onNodeWithTag(HOST_LIST_KEYS_TAG).performClick()
        awaitTag(sshKeyRowTag(fixtureKeyId))
        capture("01-ssh-keys")

        compose.onNodeWithTag(sshKeyRowTag(fixtureKeyId)).performScrollTo().performClick()
        awaitTag(SSH_KEYS_DETAIL_TAG)
        compose.onNodeWithTag(SSH_KEYS_DETAIL_TAG).performTouchInput {
            swipeUp()
            swipeUp()
        }
        awaitScrollableTag(SSH_KEYS_COPY_PUBLIC_KEY_TAG)
        capture("02-ssh-key-detail")
        copyKeyAndAwaitClipboard(SshKeyMaterial.publicKeyLine(AgentsFixture.privateKeyPem()))

        pressBackToHosts()
        openTools()
        compose.onNodeWithTag(HOST_LIST_KEYS_TAG).performClick()
        awaitTag(sshKeyRowTag(fixtureKeyId))
        compose.onNodeWithTag(SSH_KEYS_GENERATE_TAG).performClick()
        awaitTag(SSH_KEYS_GENERATE_CONFIRM_TAG)
        capture("03-ssh-key-generate")
        compose.onNodeWithTag(SSH_KEYS_GENERATE_CONFIRM_TAG).performClick()
        awaitText("Generated", substring = true)
        capture("04-ssh-key-generated")

        compose.onNodeWithTag(SSH_KEYS_IMPORT_TAG).performClick()
        awaitTag(SSH_KEYS_PASTE_FIELD_TAG)
        compose.onNodeWithTag(SSH_KEYS_PASTE_FIELD_TAG)
            .performTextInput(AgentsFixture.privateKeyPem())
        compose.onNodeWithTag(SSH_KEYS_IMPORT_CONFIRM_TAG).performClick()
        awaitTag(SSH_KEYS_IMPORT_REVIEW_TAG)
        compose.onNodeWithTag(SSH_KEYS_IMPORT_REVIEW_CONFIRM_TAG).performClick()
        awaitText("Added", substring = true)
        capture("05-ssh-key-imported")

        pressBackToHosts()
        awaitTag(HOST_LIST_ADD_TAG)
        compose.onNodeWithTag(HOST_LIST_ADD_TAG).performClick()
        awaitTag(HOST_LIST_ADD_METHODS_TAG)
        capture("06-host-add-methods")
    }

    /**
     * #2630: SSH keys and Settings left the host page for a sheet behind the
     * header cog. One tap on the cog, then the rows are on screen.
     */
    private fun openTools() {
        awaitTag(HOST_LIST_TOOLS_TAG)
        compose.onNodeWithTag(HOST_LIST_TOOLS_TAG).performClick()
        awaitTag(HOST_LIST_KEYS_TAG)
    }

    private fun pressBackToHosts() {
        if (compose.onAllNodesWithTag(SSH_KEYS_COPY_PUBLIC_KEY_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            pressBack()
            // The detail page owns the first back event. Wait for its
            // in-route page state to settle before sending the route-level
            // back event; otherwise both events can be consumed by the same
            // BackHandler while Compose is still recomposing.
            compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
                compose.onAllNodesWithTag(SSH_KEYS_DETAIL_TAG)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
        }
        pressBack()
        awaitTag(HOST_LIST_ADD_TAG)
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun clipboardText(): String? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
    }

    /**
     * Copy is verified against the real system clipboard, which on API 29+
     * only answers a read when the calling app holds input focus. On loaded
     * CI emulators the launcher ANRs mid-suite ("Pixel Launcher isn't
     * responding") and the modal dialog holds focus for minutes — reads
     * answer null the whole time (runs 34382677524, 34409039305). The WRITE
     * always lands, so: tap once, then poll reads while DISMISSING the ANR
     * dialog's "Wait" button through UiAutomator, which Compose test rules
     * cannot reach. Re-tapping is wrong twice over — the write already
     * landed, and it refreshes the clipboard-preview overlay into the
     * journey's later steps (run 34404229281). The oracle stays the exact
     * key text.
     */
    private fun copyKeyAndAwaitClipboard(expected: String) {
        compose.onNodeWithTag(SSH_KEYS_COPY_PUBLIC_KEY_TAG).performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var lastDismissAt = 0L
        compose.waitUntil(timeoutMillis = CLIPBOARD_RETRY_MS) {
            if (SystemClock.elapsedRealtime() - lastDismissAt >= 5_000) {
                lastDismissAt = SystemClock.elapsedRealtime()
                runCatching {
                    device.findObject(By.textContains("Wait"))?.click()
                }
            }
            expected == clipboardText()
        }
        assertEquals(expected, clipboardText())
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun awaitScrollableTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
                false
            } else {
                runCatching {
                    compose.onNodeWithTag(tag).performScrollTo()
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                }.isSuccess
            }
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** Keep real-device captures after the connected-test app is removed. */
    private fun capture(name: String): File {
        val file = JourneyScreenshots.capture(name, JOURNEY)
        val outputDir = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }
            ?: return file
        runCatching {
            val targetDir = File(outputDir, JOURNEY).apply { mkdirs() }
            file.parentFile?.listFiles()
                ?.filter { it.isFile && it.name.startsWith("${file.nameWithoutExtension}") }
                ?.forEach { artifact ->
                    val target = File(targetDir, artifact.name)
                    artifact.copyTo(target, overwrite = true)
                    println("J17_SCREENSHOT ${target.absolutePath}")
                }
        }
        return file
    }

    private companion object {
        const val JOURNEY = "j17-host-tools"
        const val TIMEOUT_MS = 30_000L
        const val CLIPBOARD_RETRY_MS = 90_000L
    }
}
