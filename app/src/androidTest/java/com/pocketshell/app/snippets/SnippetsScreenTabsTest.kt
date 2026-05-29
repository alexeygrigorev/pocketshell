package com.pocketshell.app.snippets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
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
    private var hostId: Long = 0L

    @Before
    fun setUp() = runBlocking {
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
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun managerTabs_filterPromptsAndCommands() = runBlocking {
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
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetsScreen(
                    hostId = hostId,
                    onBack = {},
                    viewModel = viewModel,
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
    }

    @Test
    fun addSnippet_defaultsToSelectedTabKind() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetsScreen(
                    hostId = hostId,
                    onBack = {},
                    viewModel = viewModel,
                )
            }
        }

        compose.onNodeWithText("Add prompt").performClick()
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
}
