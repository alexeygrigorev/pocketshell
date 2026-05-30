package com.pocketshell.app.hosts

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.projects.FOLDER_LIST_BACK_TAG
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #305: connected smoke for "default host opens on launch".
 * Seeds a saved host + key, selects it in SettingsRepository, launches
 * MainActivity normally, and verifies the first visible route is the
 * per-host FolderList screen. Back must still return to the host list.
 */
@RunWith(AndroidJUnit4::class)
class DefaultHostLaunchE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var hostId: Long? = null
    private var keyId: Long? = null

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        SettingsRepository(appContext).setDefaultHostId(null)
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            runBlocking {
                hostId?.let { db.hostDao().deleteById(it) }
                keyId?.let { db.sshKeyDao().deleteById(it) }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun selectedDefaultHostLaunchesFolderListAndBackReturnsToHosts() {
        val hostName = "Default launch ${System.nanoTime()}"
        seedDefaultHost(hostName)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        launchedActivity!!.moveToState(Lifecycle.State.RESUMED)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Folders", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(hostName, useUnmergedTree = true).assertExists()

        compose.onNodeWithTag(FOLDER_LIST_BACK_TAG, useUnmergedTree = true).performClick()

        val rowTag = HOST_ROW_TAG_PREFIX + requireNotNull(hostId)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(rowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(rowTag, useUnmergedTree = true).assertExists()
    }

    private fun seedDefaultHost(hostName: String) {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            runBlocking {
                val storedKey = SshKeyStorage.persistKey(
                    context = appContext,
                    sshKeyDao = db.sshKeyDao(),
                    name = "default-launch-key-${System.nanoTime()}",
                    content = key,
                )
                keyId = storedKey.id
                hostId = db.hostDao().insert(
                    HostEntity(
                        name = hostName,
                        hostname = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        username = DEFAULT_USER,
                        keyId = storedKey.id,
                        tmuxInstalled = true,
                        pocketshellInstalled = true,
                        lastBootstrapAt = System.currentTimeMillis(),
                        pocketshellLastDetectedAt = System.currentTimeMillis(),
                    ),
                )
            }
        } finally {
            db.close()
        }
        SettingsRepository(appContext).setDefaultHostId(requireNotNull(hostId))
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEFAULT_HOST: String = "10.0.2.2"
        const val DEFAULT_PORT: Int = 2222
        const val DEFAULT_USER: String = "testuser"
    }
}
