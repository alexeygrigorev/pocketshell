package com.pocketshell.app.snippets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.CommandTemplateEntity
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnippetsScreenTabsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: SnippetsViewModel
    private lateinit var commandTemplatesViewModel: CommandTemplatesViewModel
    private var hostId: Long = 0L

    @Before
    fun setUp() { runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "tabs-key", privateKeyPath = "/tmp/tabs-key"),
        )
        hostId = db.hostDao().insert(
            HostEntity(
                name = "tabs-host",
                hostname = "tabs.example.com",
                port = 22,
                username = "deploy",
                keyId = keyId,
            ),
        )
        viewModel = SnippetsViewModel(db.snippetDao())
        commandTemplatesViewModel = CommandTemplatesViewModel(db.commandTemplateDao())
    } }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun managerTabs_filterPromptsAndCommands() { runBlocking {
        db.snippetDao().insert(
            SnippetEntity(
                hostId = hostId,
                label = "List files",
                body = "ls -la",
                kind = SnippetKind.Command.storageValue,
            ),
        )
        db.snippetDao().insert(
            SnippetEntity(
                hostId = hostId,
                label = "Continue task",
                body = "Continue from the last plan.",
                kind = SnippetKind.Prompt.storageValue,
            ),
        )

        compose.setContent {
            PocketShellTheme {
                SnippetsScreen(
                    hostId = hostId,
                    onBack = {},
                    viewModel = viewModel,
                    commandTemplatesViewModel = commandTemplatesViewModel,
                )
            }
        }

        compose.onNodeWithTag(snippetKindTabTag(SnippetKind.Prompt)).assertIsDisplayed()
        compose.onNodeWithTag(snippetKindTabTag(SnippetKind.Command)).assertIsDisplayed()
        compose.onNodeWithText("Continue task").assertIsDisplayed()
        compose.onNodeWithText("List files").assertDoesNotExist()

        compose.onNodeWithTag(snippetKindTabTag(SnippetKind.Command)).performClick()

        compose.onNodeWithText("List files").assertIsDisplayed()
        compose.onNodeWithText("Continue task").assertDoesNotExist()
    } }

    @Test
    fun addSnippet_defaultsToSelectedTabKind() {
        compose.setContent {
            PocketShellTheme {
                SnippetsScreen(
                    hostId = hostId,
                    onBack = {},
                    viewModel = viewModel,
                    commandTemplatesViewModel = commandTemplatesViewModel,
                )
            }
        }

        compose.onNodeWithText("Add prompt").performClick()
        compose.onNodeWithText(
            "We'll use the first line as the label. Use the row menu to rename it.",
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "We'll use the first line as the label. Long-press a snippet later to rename it.",
        ).assertDoesNotExist()
        compose.onNodeWithText("Snippet text").performTextInput("Continue from the last plan.")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                db.snippetDao().getByHostId(hostId).first().any {
                    it.body == "Continue from the last plan." && it.kind == SnippetKind.Prompt.storageValue
                }
            }
        }

        compose.onNodeWithTag(snippetKindTabTag(SnippetKind.Command)).performClick()
        compose.onNodeWithText("Add command").performClick()
        compose.onNodeWithText("Snippet text").performTextInput("ls -la")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                db.snippetDao().getByHostId(hostId).first().any {
                    it.body == "ls -la" && it.kind == SnippetKind.Command.storageValue
                }
            }
        }

        val rows = runBlocking { db.snippetDao().getByHostId(hostId).first() }
        assertEquals(
            listOf(SnippetKind.Prompt.storageValue, SnippetKind.Command.storageValue),
            rows.sortedBy { it.id }.map { it.kind },
        )
    }

    @Test
    fun snippetRowActions_areReachableThroughKebab() {
        runBlocking {
            val snippetId = db.snippetDao().insert(
                SnippetEntity(
                    hostId = hostId,
                    label = "Deploy checklist",
                    body = "Run tests\nShip build",
                    kind = SnippetKind.Prompt.storageValue,
                ),
            )

            compose.setContent {
                PocketShellTheme {
                    SnippetsScreen(
                        hostId = hostId,
                        onBack = {},
                        viewModel = viewModel,
                        commandTemplatesViewModel = commandTemplatesViewModel,
                    )
                }
            }

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(snippetRowTestTag(snippetId))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            compose.onNodeWithTag(snippetActionsTestTag(snippetId)).performClick()
            compose.onNodeWithTag(snippetEditActionTestTag(snippetId)).assertIsDisplayed().performClick()
            compose.onNodeWithText("Edit snippet").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()

            compose.onNodeWithTag(snippetActionsTestTag(snippetId)).performClick()
            compose.onNodeWithTag(snippetRenameActionTestTag(snippetId)).assertIsDisplayed().performClick()
            compose.onNodeWithText("Rename snippet").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()

            compose.onNodeWithTag(snippetActionsTestTag(snippetId)).performClick()
            compose.onNodeWithTag(snippetDeleteActionTestTag(snippetId)).assertIsDisplayed().performClick()
            compose.onNodeWithText("Delete this snippet?").assertIsDisplayed()
        }
    }

    @Test
    fun snippetDeleteDialog_showsSharedConfirmAndConfirmDeletes() {
        runBlocking {
            val snippetId = db.snippetDao().insert(
                SnippetEntity(
                    hostId = hostId,
                    label = "Doomed prompt",
                    body = "Body",
                    kind = SnippetKind.Prompt.storageValue,
                ),
            )

            compose.setContent {
                PocketShellTheme {
                    SnippetsScreen(
                        hostId = hostId,
                        onBack = {},
                        viewModel = viewModel,
                        commandTemplatesViewModel = commandTemplatesViewModel,
                    )
                }
            }

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(snippetRowTestTag(snippetId))
                    .fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithTag(snippetActionsTestTag(snippetId)).performClick()
            compose.onNodeWithTag(snippetDeleteActionTestTag(snippetId)).performClick()

            // Migrated shared ConfirmDialog: title + body + confirm + cancel.
            compose.onNodeWithTag(SNIPPET_DELETE_DIALOG_TAG).assertIsDisplayed()
            compose.onNodeWithText("Delete this snippet?").assertIsDisplayed()
            compose.onNodeWithText("“Doomed prompt” will be removed permanently.")
                .assertIsDisplayed()
            compose.onNodeWithTag(SNIPPET_DELETE_CANCEL_TAG).assertIsDisplayed()

            // Cancel dismisses without deleting.
            compose.onNodeWithTag(SNIPPET_DELETE_CANCEL_TAG).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(SNIPPET_DELETE_DIALOG_TAG)
                    .fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag(snippetRowTestTag(snippetId)).assertIsDisplayed()

            // Re-open and confirm → onConfirm fires and the row disappears.
            compose.onNodeWithTag(snippetActionsTestTag(snippetId)).performClick()
            compose.onNodeWithTag(snippetDeleteActionTestTag(snippetId)).performClick()
            compose.onNodeWithTag(SNIPPET_DELETE_CONFIRM_TAG).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(snippetRowTestTag(snippetId))
                    .fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun macroDeleteDialog_showsSharedConfirmAndConfirmDeletes() {
        runBlocking {
            val macroId = db.commandTemplateDao().insert(
                CommandTemplateEntity(
                    hostId = hostId,
                    label = "Doomed macro",
                    commands = "echo {{x}}",
                ),
            )

            compose.setContent {
                PocketShellTheme {
                    SnippetsScreen(
                        hostId = hostId,
                        onBack = {},
                        viewModel = viewModel,
                        commandTemplatesViewModel = commandTemplatesViewModel,
                    )
                }
            }

            // Switch to the Macros tab.
            compose.onNodeWithText("Macros").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(commandTemplateRowTestTag(macroId))
                    .fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithTag(commandTemplateActionsTestTag(macroId)).performClick()
            compose.onNodeWithTag(commandTemplateDeleteActionTestTag(macroId)).performClick()

            compose.onNodeWithTag(MACRO_DELETE_DIALOG_TAG).assertIsDisplayed()
            compose.onNodeWithText("Delete this macro?").assertIsDisplayed()
            compose.onNodeWithText("\"Doomed macro\" will be removed permanently.")
                .assertIsDisplayed()
            compose.onNodeWithTag(MACRO_DELETE_CANCEL_TAG).assertIsDisplayed()

            compose.onNodeWithTag(MACRO_DELETE_CONFIRM_TAG).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(commandTemplateRowTestTag(macroId))
                    .fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun longSnippetContent_keepsTrailingKebabUsable() {
        runBlocking {
            val snippetId = db.snippetDao().insert(
                SnippetEntity(
                    hostId = hostId,
                    label = "Very long prompt label that should ellipsize before the " +
                        "trailing menu button",
                    body = "A very long command or prompt preview that should not push " +
                        "the actions off screen",
                    kind = SnippetKind.Prompt.storageValue,
                ),
            )

            compose.setContent {
                PocketShellTheme {
                    Box(
                        modifier = Modifier
                            .width(260.dp)
                            .height(520.dp),
                    ) {
                        SnippetsScreen(
                            hostId = hostId,
                            onBack = {},
                            viewModel = viewModel,
                            commandTemplatesViewModel = commandTemplatesViewModel,
                        )
                    }
                }
            }

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(snippetRowTestTag(snippetId))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            compose.onNodeWithTag(snippetActionsTestTag(snippetId)).assertIsDisplayed().performClick()
            compose.onNodeWithTag(snippetEditActionTestTag(snippetId)).assertIsDisplayed()
        }
    }
}
