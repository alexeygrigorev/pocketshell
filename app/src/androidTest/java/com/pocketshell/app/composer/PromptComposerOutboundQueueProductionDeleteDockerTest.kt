package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1589 AC8/AC9: the production composer Delete action reaches the real,
 * Hilt-provided durable queue lifecycle coordinator. This deliberately mounts
 * [PromptComposerSheet] through [ComposerHiltHostActivity]; unlike
 * [PromptComposerOutboundQueueTest], it does not construct a ViewModel or
 * forward Delete into a test-owned list.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerOutboundQueueProductionDeleteDockerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComposerHiltHostActivity>()

    private var vmUnderTest: PromptComposerViewModel? = null
    private var sourceFile: File? = null

    @After
    fun clearDurableProofState() {
        vmUnderTest?.outboundQueueStore?.clearSession(TARGET)
        targetContext().getSharedPreferences(
            OutboundAttachmentSidecarStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        File(
            targetContext().filesDir,
            OutboundAttachmentSidecarStore.DIRECTORY_NAME,
        ).deleteRecursively()
        sourceFile?.delete()
    }

    @Test
    fun productionDeleteRemovesDurableRowAndLeavesRemoteCleanupTombstone() {
        val vm = activityScopedComposerVm().also { vmUnderTest = it }
        val queue = vm.outboundQueueStore
        assertNotNull(
            "production Hilt graph must provide the real attachment sidecar store",
            vm.outboundAttachmentSidecarStore,
        )
        val sidecars = requireNotNull(vm.outboundAttachmentSidecarStore)
        assertTrue(
            "production Hilt graph must provide SharedPrefsOutboundQueueStore, was ${queue::class.java.name}",
            queue is SharedPrefsOutboundQueueStore,
        )
        assertNotNull(
            "production Hilt graph must provide OutboundQueueLifecycleCoordinator",
            vm.outboundQueueLifecycleCoordinator,
        )

        // Start clean even after an aborted prior instrumentation process.
        queue.clearSession(TARGET)
        targetContext().getSharedPreferences(
            OutboundAttachmentSidecarStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        File(
            targetContext().filesDir,
            OutboundAttachmentSidecarStore.DIRECTORY_NAME,
        ).deleteRecursively()

        val id = "issue1589-production-delete-${System.currentTimeMillis()}"
        val source = File(targetContext().cacheDir, "$id.txt").also {
            sourceFile = it
            it.writeText("durable attachment bytes for production Delete proof\n")
        }
        val sidecar = runBlocking {
            sidecars.stage(
                outboundItemId = id,
                uris = listOf(Uri.fromFile(source)),
                attachmentIndices = listOf(0),
            ).single()
        }
        runBlocking { sidecars.markUploaded(mapOf(sidecar.id to REMOTE_PATH)) }
        val persistedSidecarPath = File(sidecar.localPath)
        assertTrue("real staged sidecar bytes must exist before Delete", persistedSidecarPath.isFile)

        val failed = OutboundItem(
            id = id,
            sessionKey = TARGET,
            cleanText = "delete this failed production queue row",
            attachments = listOf(
                DurableAttachmentRef(
                    remotePath = REMOTE_PATH,
                    displayName = sidecar.displayName,
                    mimeType = sidecar.mimeType,
                ),
            ),
            state = OutboundState.Failed,
            lastError = "connection lost",
            createdAtMs = System.currentTimeMillis(),
        )
        assertEquals(id, queue.enqueueExisting(failed).id)
        assertEquals(id, queue.item(id)?.id)

        compose.runOnUiThread {
            // ComposerHiltHostActivity's null target effect has already settled;
            // drive the same activity-scoped VM used by the production sheet.
            vm.onComposerTargetChanged(TARGET)
            vm.refreshOutboundQueueItemsFor(TARGET)
        }

        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(
                COMPOSER_OUTBOUND_QUEUE_BANNER_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(
            COMPOSER_OUTBOUND_QUEUE_BANNER_TAG,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithTag(
            COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG,
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(
                composerOutboundQueueItemRowTestTag(id),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }

        val deleteTag = composerOutboundQueueDeleteTestTag(id)
        compose.onNodeWithTag(deleteTag, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        // MUTATION ANCHOR: replace PromptComposerSheet's production
        // `onDeleteOutboundItem = viewModel::discardOutboundItem` callback with
        // `{}`. This wait then hard-times-out because only the real callback can
        // acquire OutboundDisposalPermit and remove the SharedPreferences row.
        compose.waitUntil(TIMEOUT_MS) { queue.item(id) == null }
        assertEquals("production Delete must durably remove the exact row", null, queue.item(id))

        compose.waitUntil(TIMEOUT_MS) {
            vm.outboundQueueItems.value.none { it.id == id } &&
                runBlocking { sidecars.refsFor(id).isEmpty() } &&
                !persistedSidecarPath.exists()
        }
        compose.onNodeWithTag(
            composerOutboundQueueItemRowTestTag(id),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        compose.onNodeWithTag(
            COMPOSER_OUTBOUND_QUEUE_BANNER_TAG,
            useUnmergedTree = true,
        ).assertDoesNotExist()
        assertFalse("production Delete must remove local sidecar bytes", persistedSidecarPath.exists())
        assertTrue(
            "production Delete must remove persisted local sidecar refs",
            runBlocking { sidecars.refsFor(id) }.isEmpty(),
        )

        val tombstones = sidecars.pendingTombstonesBlocking().filter { it.outboundItemId == id }
        assertEquals("remote cleanup must retain exactly one durable tombstone", 1, tombstones.size)
        assertEquals(sidecar.id, tombstones.single().sidecarId)
        assertEquals(REMOTE_PATH, tombstones.single().remotePath)
        assertEquals(sidecar.id, tombstones.single().stableToken)
    }

    private fun activityScopedComposerVm(): PromptComposerViewModel {
        lateinit var vm: PromptComposerViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[PromptComposerViewModel::class.java]
        }
        compose.waitForIdle()
        return vm
    }

    private fun targetContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val TARGET = "tmux:1589:\$42:1700000000"
        const val REMOTE_PATH = ".pocketshell/attachments/issue1589-sidecar.txt"
        const val TIMEOUT_MS = 10_000L
    }
}
