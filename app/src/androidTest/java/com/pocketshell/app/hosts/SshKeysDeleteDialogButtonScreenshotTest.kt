package com.pocketshell.app.hosts

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #756 (Batch B) — emulator screenshot evidence that the SSH-keys
 * delete-confirm dialog now uses the canonical [com.pocketshell.uikit.components.PocketShellButton]:
 * the affirmative confirm is `ButtonVariant.Destructive` (red TEXT, not a filled
 * red slab) and the dismiss is `ButtonVariant.Text` (muted). This drives the real
 * [SshKeysManagementPane] on the emulator, opens the delete dialog through the
 * kebab, and writes a device screenshot of the migrated dialog for the reviewer.
 */
@RunWith(AndroidJUnit4::class)
class SshKeysDeleteDialogButtonScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: SshKeysViewModel

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        viewModel = SshKeysViewModel(db.sshKeyDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deleteConfirmDialog_usesCanonicalDestructiveAndTextButtons() {
        runBlocking {
            val keyId = db.sshKeyDao().insert(
                SshKeyEntity(
                    name = "deploy-key",
                    privateKeyPath = "/tmp/pocketshell/deploy-key",
                ),
            )

            compose.setContent {
                PocketShellTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SshKeysManagementPane(
                            viewModel = viewModel,
                            requiresUnlock = { false },
                        )
                    }
                }
            }

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(sshKeyRowTestTag(keyId))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            compose.onNodeWithTag(sshKeyActionsTestTag(keyId)).performClick()
            compose.onNodeWithTag(sshKeyDeleteActionTestTag(keyId)).assertIsDisplayed().performClick()

            // The migrated dialog: title + canonical Destructive "Delete" confirm
            // and Text "Cancel" dismiss, all reachable/visible.
            compose.onNodeWithText("Delete this key?").assertIsDisplayed()
            compose.onNodeWithText("Delete").assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsDisplayed()

            compose.waitForIdle()
            SystemClock.sleep(200)
            capture("issue-756b-sshkeys-delete-dialog.png")
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-756b-buttons")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create screenshot directory: ${dir.absolutePath}"
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(dir, name)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_756B_BUTTON_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
