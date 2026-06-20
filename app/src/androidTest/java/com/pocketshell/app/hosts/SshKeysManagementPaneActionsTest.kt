package com.pocketshell.app.hosts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshKeysManagementPaneActionsTest {

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
    fun keyActionArea_keepsImportAndGenerateActionsReachable() {
        renderPane(
            modifier = Modifier
                .width(260.dp)
                .height(520.dp),
        )

        compose.onNodeWithTag(SSH_KEYS_IMPORT_ACTION_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SSH_KEYS_GENERATE_ACTION_TAG).assertIsDisplayed()
    }

    @Test
    fun keyDeleteAction_isReachableThroughKebab() {
        runBlocking {
            val keyId = db.sshKeyDao().insert(
                SshKeyEntity(
                    name = "deploy-key",
                    privateKeyPath = "/tmp/pocketshell/deploy-key",
                ),
            )

            renderPane()

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(sshKeyRowTestTag(keyId))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            compose.onNodeWithTag(sshKeyActionsTestTag(keyId)).performClick()
            compose.onNodeWithTag(sshKeyDeleteActionTestTag(keyId)).assertIsDisplayed().performClick()
            compose.onNodeWithText("Delete this key?").assertIsDisplayed()
        }
    }

    @Test
    fun deleteKeyDialog_showsTitleBodyConfirmCancel_andCancelKeepsKey() {
        runBlocking {
            val keyId = db.sshKeyDao().insert(
                SshKeyEntity(name = "keep-key", privateKeyPath = "/tmp/keep-key"),
            )

            renderPane()

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(sshKeyRowTestTag(keyId))
                    .fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithTag(sshKeyActionsTestTag(keyId)).performClick()
            compose.onNodeWithTag(sshKeyDeleteActionTestTag(keyId)).performClick()

            // The migrated shared ConfirmDialog: title + body + confirm + cancel.
            compose.onNodeWithTag(SSH_KEYS_DELETE_DIALOG_TAG).assertIsDisplayed()
            compose.onNodeWithText("Delete this key?").assertIsDisplayed()
            compose.onNodeWithText(
                "“keep-key” will be removed. Any hosts that reference this key " +
                    "are deleted too (foreign-key cascade).",
            ).assertIsDisplayed()
            compose.onNodeWithTag(SSH_KEYS_DELETE_CONFIRM_TAG).assertIsDisplayed()
            compose.onNodeWithTag(SSH_KEYS_DELETE_CANCEL_TAG).assertIsDisplayed()

            // Cancel dismisses without deleting (onDismiss callback wired).
            compose.onNodeWithTag(SSH_KEYS_DELETE_CANCEL_TAG).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(SSH_KEYS_DELETE_DIALOG_TAG)
                    .fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag(sshKeyRowTestTag(keyId)).assertIsDisplayed()
        }
    }

    @Test
    fun deleteKeyDialog_confirmDeletesTheKey() {
        runBlocking {
            val keyId = db.sshKeyDao().insert(
                SshKeyEntity(name = "doomed-key", privateKeyPath = "/tmp/doomed-key"),
            )

            renderPane()

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(sshKeyRowTestTag(keyId))
                    .fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithTag(sshKeyActionsTestTag(keyId)).performClick()
            compose.onNodeWithTag(sshKeyDeleteActionTestTag(keyId)).performClick()
            compose.onNodeWithTag(SSH_KEYS_DELETE_DIALOG_TAG).assertIsDisplayed()

            // Confirm fires onConfirm → deleteKey: the row disappears.
            compose.onNodeWithTag(SSH_KEYS_DELETE_CONFIRM_TAG).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(sshKeyRowTestTag(keyId))
                    .fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun screenHeaderAndEmptyState_render() {
        // No keys inserted → the migrated shared EmptyState shows, and the
        // shared ScreenHeader is present (#864).
        renderPane()

        compose.onNodeWithTag(SSH_KEYS_HEADER_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("SSH keys").assertIsDisplayed()
        compose.onNodeWithTag(SSH_KEYS_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithText("No keys yet").assertIsDisplayed()
        compose.onNodeWithText("Import a key file or generate one on-device.")
            .assertIsDisplayed()
    }

    @Test
    fun screenHeaderSubtitle_countsKeys() {
        runBlocking {
            db.sshKeyDao().insert(SshKeyEntity(name = "k1", privateKeyPath = "/tmp/k1"))
            db.sshKeyDao().insert(SshKeyEntity(name = "k2", privateKeyPath = "/tmp/k2"))

            renderPane()

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(SSH_KEYS_HEADER_SUBTITLE_TAG)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("2 keys").assertIsDisplayed()
        }
    }

    @Test
    fun longKeyNameAndPath_keepTrailingKebabUsable() {
        runBlocking {
            val keyId = db.sshKeyDao().insert(
                SshKeyEntity(
                    name = "very-long-ssh-key-name-that-should-ellipsize-before-actions",
                    privateKeyPath = "/very/long/path/to/a/private/key/location/that/" +
                        "should/ellipsize/id_ed25519",
                    hasPassphrase = true,
                ),
            )

            renderPane(
                modifier = Modifier
                    .width(260.dp)
                    .height(520.dp),
            )

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(sshKeyRowTestTag(keyId))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            compose.onNodeWithTag(sshKeyActionsTestTag(keyId)).assertIsDisplayed().performClick()
            compose.onNodeWithTag(sshKeyDeleteActionTestTag(keyId)).assertIsDisplayed()
        }
    }

    private fun renderPane(modifier: Modifier = Modifier.fillMaxSize()) {
        compose.setContent {
            PocketShellTheme {
                Box(modifier = modifier) {
                    SshKeysManagementPane(
                        viewModel = viewModel,
                        requiresUnlock = { false },
                    )
                }
            }
        }
    }
}
