package com.pocketshell.app.hosts

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.projects.FOLDER_LIST_BACK_TAG
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.EntryPointAccessors
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

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var hostId: Long? = null
    private var keyId: Long? = null

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        val entry = testAccess()
        entry.settingsRepository().setDefaultHostId(null)
        val db = entry.appDatabase()
        runBlocking {
            hostId?.let { db.hostDao().deleteById(it) }
            keyId?.let { db.sshKeyDao().deleteById(it) }
        }
    }

    @Test
    fun selectedDefaultHostLaunchesFolderListAndBackReturnsToHosts() {
        val hostName = "Default launch ${System.nanoTime()}"
        seedDefaultHost(hostName)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        launchedActivity!!.moveToState(Lifecycle.State.RESUMED)

        // Issue #951: the default host is now resolved OFF the Main thread, so
        // the host list may flash before the FolderList appears. Wait for the
        // FolderList SCREEN and its host-name breadcrumb (the screen identity,
        // rendered immediately from the destination args), not just the screen
        // tag in the same frame, so the contract check doesn't race the async
        // arrival. The host-name crumb is the load-bearing identity assertion:
        // it proves the default host's FolderList opened (the #305 contract).
        // We deliberately do NOT also wait on the dynamic "Folders" section
        // label here — that content appears only after the SSH folder-structure
        // probe returns, which is connection/fixture-timing dependent and not
        // part of the launch-routing contract this test owns.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(hostName, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
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

    // Issue #951: seed the host + default-host preference through the SAME Hilt
    // singletons MainActivity reads (DB via TestAccessEntryPoint.appDatabase,
    // default-host id via the singleton SettingsRepository). The default-host
    // resolution now runs OFF the Main thread reading the singleton's snapshot;
    // a throwaway `SettingsRepository(context)` would write SharedPreferences
    // but leave the singleton's construction-time snapshot stale, so the resolve
    // could read a null default host (flaky). Going through the singleton is
    // deterministic.
    private fun seedDefaultHost(hostName: String) {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val entry = testAccess()
        val db = entry.appDatabase()
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
        entry.settingsRepository().setDefaultHostId(requireNotNull(hostId))
    }

    private fun testAccess(): TestAccessEntryPoint =
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            TestAccessEntryPoint::class.java,
        )

    private companion object {
        const val DEFAULT_HOST: String = "10.0.2.2"
        const val DEFAULT_PORT: Int = 2222
        const val DEFAULT_USER: String = "testuser"
    }
}
