package com.pocketshell.app.projects

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected smoke coverage for issue #206 — per-host watched-folders
 * config screen + new-session chip integration.
 *
 * The user journeys under test:
 *
 *  1. From an empty per-host watched-folders screen, the user adds a
 *     folder via the dialog and the row appears persisted in the
 *     [com.pocketshell.core.storage.dao.ProjectRootDao].
 *  2. With one or more watched folders configured for a host, the
 *     chip row composable renders the folders and tapping a chip
 *     invokes the `onChipTap` callback with the configured path —
 *     which the new-session sheet wires to its start-folder field.
 *  3. With zero watched folders configured, the chip-row composable
 *     surfaces the empty-state nudge directing the user to Settings.
 *
 * The test does not need Docker — it exercises the local DAO + the
 * Compose surface only. The cwd-to-tmux integration is implicit in
 * #204's `new-session -c '<dir>'` wiring; verifying the chip writes
 * the right value into the start-folder field is the relevant guard
 * for #206 specifically.
 */
@RunWith(AndroidJUnit4::class)
class WatchedFoldersE2eTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase

    @Before
    fun openDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Use an in-memory DB so the test is independent of the app's
        // shared `pocketshell.db` file — keeps the assertion focused on
        // the round-trip behaviour and avoids leaking rows into a future
        // test in the same suite.
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // ProjectRootEntity has a FK to HostEntity which has a FK to
        // SshKeyEntity. Seed both so insert() doesn't trip the foreign
        // key check.
        runBlocking {
            val keyId = db.sshKeyDao().insert(
                SshKeyEntity(name = "test-key", privateKeyPath = "/tmp/test"),
            )
            // Insert hosts on multiple ids so each @Test can use its own
            // hostId without clashing.
            listOf(7L, 8L, 17L, 33L, 99L, 1L).forEach { id ->
                db.hostDao().insert(
                    HostEntity(
                        id = id,
                        name = "h$id",
                        hostname = "h$id.example",
                        port = 22,
                        username = "u$id",
                        keyId = keyId,
                    ),
                )
            }
        }
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun addFolderRoundTripsThroughDao(): Unit = runBlocking {
        val dao = db.projectRootDao()
        val hostId = 17L

        val vm = WatchedFoldersViewModel(
            projectRootDao = dao,
            hostDao = db.hostDao(),
        )

        compose.setContent {
            PocketShellTheme {
                WatchedFoldersScreen(
                    hostId = hostId,
                    hostName = "ci-host",
                    sshCredentials = null,
                    onBack = {},
                    viewModel = vm,
                )
            }
        }

        // Empty state hint must render before any folder exists.
        compose.onNodeWithTag(WATCHED_FOLDERS_EMPTY_HINT_TAG).assertExists()

        // Add a folder via the dialog.
        compose.onNodeWithTag(WATCHED_FOLDERS_ADD_TAG).performClick()
        compose.onNodeWithTag(WATCHED_FOLDERS_DIALOG_LABEL_TAG)
            .performTextInput("dotfiles")
        compose.onNodeWithTag(WATCHED_FOLDERS_DIALOG_PATH_TAG)
            .performTextInput("~/git/dotfiles")
        compose.onNodeWithTag(WATCHED_FOLDERS_DIALOG_CONFIRM_TAG).performClick()

        // Wait for the row to materialise in the DAO snapshot. Polling
        // through the view model state flow keeps the test deterministic
        // without coupling to Compose recomposition.
        compose.waitUntil(timeoutMillis = 5_000L) {
            vm.state.value.roots.isNotEmpty()
        }

        val saved = vm.state.value.roots.first()
        assertEquals("dotfiles", WatchedFoldersViewModel.stripOrderPrefix(saved.label))
        assertEquals("~/git/dotfiles", saved.path)
        assertEquals(hostId, saved.hostId)

        // The empty-state hint is replaced by the row.
        compose.onNodeWithTag(WATCHED_FOLDERS_EMPTY_HINT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(watchedFolderRowTestTag(saved.id)).assertExists()
    }

    @Test
    fun chipRowRendersConfiguredFoldersAndCallsBack(): Unit = runBlocking {
        val dao = db.projectRootDao()
        val hostId = 33L
        dao.insert(
            ProjectRootEntity(hostId = hostId, label = "site", path = "~/code/site"),
        )

        val chipsVm = WatchedFoldersChipsViewModel(projectRootDao = dao)
        var lastChip: String? = null

        compose.setContent {
            PocketShellTheme {
                WatchedFoldersChipRow(
                    hostId = hostId,
                    onChipTap = { path -> lastChip = path },
                    viewModel = chipsVm,
                )
            }
        }

        // Chip row appears once the DAO Flow emits the seeded row.
        compose.waitUntil(timeoutMillis = 5_000L) {
            chipsVm.roots.value.isNotEmpty()
        }
        compose.onNodeWithTag(WATCHED_FOLDERS_CHIP_ROW_TAG).assertExists()
        compose.onNodeWithTag(watchedFoldersChipTestTag("~/code/site"))
            .assertExists()
            .performClick()

        assertEquals("~/code/site", lastChip)
    }

    @Test
    fun chipRowShowsEmptyNudgeWhenNoFolders(): Unit = runBlocking {
        val chipsVm = WatchedFoldersChipsViewModel(projectRootDao = db.projectRootDao())
        compose.setContent {
            PocketShellTheme {
                WatchedFoldersChipRow(
                    hostId = 99L,
                    onChipTap = { /* unused — empty path */ },
                    viewModel = chipsVm,
                )
            }
        }
        compose.onNodeWithTag(WATCHED_FOLDERS_CHIP_EMPTY_NUDGE_TAG).assertExists()
    }

    @Test
    fun deletingFolderDropsItFromDao(): Unit = runBlocking {
        val dao = db.projectRootDao()
        val hostId = 8L
        val insertedId = dao.insert(
            ProjectRootEntity(hostId = hostId, label = "doomed", path = "~/doomed"),
        )

        val vm = WatchedFoldersViewModel(
            projectRootDao = dao,
            hostDao = db.hostDao(),
        )

        compose.setContent {
            PocketShellTheme {
                WatchedFoldersScreen(
                    hostId = hostId,
                    hostName = "h",
                    sshCredentials = null,
                    onBack = {},
                    viewModel = vm,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000L) {
            vm.state.value.roots.any { it.id == insertedId }
        }

        compose.onNodeWithTag(watchedFolderDeleteTestTag(insertedId)).performClick()

        compose.waitUntil(timeoutMillis = 5_000L) {
            vm.state.value.roots.none { it.id == insertedId }
        }
        compose.onNodeWithTag(WATCHED_FOLDERS_EMPTY_HINT_TAG).assertExists()
    }
}
