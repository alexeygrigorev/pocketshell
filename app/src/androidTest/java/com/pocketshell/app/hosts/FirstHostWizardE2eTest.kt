package com.pocketshell.app.hosts

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SHEET_TAG
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.entity.SshKeyEntity
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1243 — end-to-end journey for the guided first-run wizard.
 *
 * This is the last open acceptance criterion for #1243: the guided
 * "Add your first host" wizard (empty-state primary action → guided host
 * details → real SSH test-connect → setup handoff) must complete against a
 * Docker fixture host END-TO-END, INCLUDING the failure path (a bad host so
 * test-connect fails → an actionable error with a working recovery affordance,
 * NOT a dead end / silent failure / stuck spinner).
 *
 * Both tests drive the REAL production wizard on the real path:
 *   empty state (HostListScreen) → tap the labelled primary
 *   "Add your first host" action (NOT the FAB) → the firstRunGuided
 *   AddEditHostScreen → fill the four required fields + pick the seeded key →
 *   "Add host" saves and (via the firstRun onFirstRunHostSaved route) REPLACES
 *   to FirstHostTestConnect → the ViewModel runs a REAL `SshConnection.connect`
 *   against the fixture.
 *
 * Nothing is faked: there is no state-injection seam (no `force*ForTest` /
 * `set*ForTest`); the Success / Failed status is produced by the real Docker
 * connect. The happy path points at the deterministic `agents` fixture
 * (`10.0.2.2:2222`, testuser + the shared test_key that every host Docker test
 * uses); the failure path points at a CLOSED port on the same host so the
 * connect is refused fast.
 *
 * Mirrors the fixture/host-setup helpers of `DefaultHostLaunchE2eTest`,
 * `HostEditFromKebabE2eTest`, and the empty-state add-host driving of
 * `com.pocketshell.app.proof.ColdInstallE2eTest`.
 */
@RunWith(AndroidJUnit4::class)
class FirstHostWizardE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus from
    // the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @Before
    fun resetToEmptyHostList() {
        // The wizard's entry point is the EMPTY state, so the app must start
        // with zero hosts. Clear all Room state (hosts + keys) through the Hilt
        // singleton so the in-app reactive flows snap to empty, then clear the
        // default-host preference + fast-resume snapshot so launch lands on the
        // host list (not an auto-opened folder list from sibling state).
        val entry = testAccess()
        entry.appDatabase().clearAllTables()
        entry.settingsRepository().setDefaultHostId(null)
        clearLastSessionPrefs()
    }

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        val entry = testAccess()
        entry.appDatabase().clearAllTables()
        entry.settingsRepository().setDefaultHostId(null)
        clearLastSessionPrefs()
    }

    /**
     * HAPPY PATH — the guided wizard completes against the live Docker fixture
     * and hands the user off to the working set-up step (NOT a dead end).
     */
    @Test
    fun guidedFirstHostWizard_realConnectSucceeds_reachesSetupHandoff() { runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        val storedKey = seedFixtureKeyViaHilt(key, "first-host-wizard-key-${System.nanoTime()}")

        launchOnEmptyState()

        // 1. Empty state exposes the labelled primary action (issue AC 1).
        compose.onNodeWithText("No hosts yet", useUnmergedTree = true).assertExists()
        openGuidedWizardFromEmptyState()

        // 2. The guided host-details step (firstRunGuided AddEditHostScreen).
        val hostName = "First host ${System.nanoTime().toString(36)}"
        fillHostForm(
            name = hostName,
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyName = storedKey.name,
        )
        capture("01-guided-host-details")

        // 3. "Add host" saves and (firstRun route) REPLACES to test-connect.
        compose.onNodeWithTag(ADD_HOST_CTA_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(FIRST_HOST_TEST_CONNECT_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 4. The REAL Docker connect SUCCEEDS → the wizard advances to the
        //    Setup step with the success copy + the "Finish setup" handoff.
        //    This is the load-bearing assertion: it is driven by the real
        //    SshConnection.connect against agents:2222, not an injected state.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Connection works.", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Connection works.", useUnmergedTree = true).assertExists()
        // Wizard progress shows the 4th "Setup" step is now active.
        compose.onNodeWithText("4. Setup", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FIRST_HOST_TEST_CONNECT_OPEN_TAG, useUnmergedTree = true).assertExists()
        capture("02-test-connect-success")

        // 5. Tap "Finish setup" → the existing bootstrap/setup path runs a real
        //    probe against the fixture. A working host either surfaces the
        //    HostBootstrapSheet (setup step) or navigates straight to the folder
        //    list — BOTH are a reachable working host, NOT a dead end. Assert we
        //    leave the test-connect screen for one of those setup/working states.
        compose.onNodeWithTag(FIRST_HOST_TEST_CONNECT_OPEN_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            reachedSetupOrWorkingHost()
        }
        assertTrue(
            "expected the guided wizard to reach the setup step (bootstrap sheet) " +
                "or the working host (folder list) after Finish setup, not a dead end",
            reachedSetupOrWorkingHost(),
        )
        capture("03-setup-handoff")
    } }

    /**
     * FAILURE PATH — a bad host makes the REAL test-connect fail; the user must
     * see an actionable error AND a working recovery affordance (Retry + Edit),
     * NOT a dead end / silent failure / stuck spinner. This is the load-bearing
     * "no dead end" guarantee.
     */
    @Test
    fun guidedFirstHostWizard_realConnectFails_showsActionableErrorAndRecovery() { runBlocking {
        val key = readFixtureKey()
        // Confirm the fixture is up (so the failure is attributable to the bad
        // PORT we point at, not to a missing fixture).
        waitForSshFixtureReady(SshKey.Pem(key))
        val storedKey = seedFixtureKeyViaHilt(key, "first-host-wizard-badport-key-${System.nanoTime()}")

        launchOnEmptyState()
        openGuidedWizardFromEmptyState()

        // Valid key/user/host but a CLOSED port → the real connect is refused.
        val hostName = "Bad port host ${System.nanoTime().toString(36)}"
        fillHostForm(
            name = hostName,
            hostname = DEFAULT_HOST,
            port = CLOSED_PORT,
            username = DEFAULT_USER,
            keyName = storedKey.name,
        )

        compose.onNodeWithTag(ADD_HOST_CTA_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(FIRST_HOST_TEST_CONNECT_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The REAL connect FAILS → an actionable error + BOTH recovery
        // affordances are shown. The error text is the friendly guidance the
        // user can act on; the buttons are the recovery paths (no dead end).
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(FIRST_HOST_TEST_CONNECT_RETRY_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            "Could not connect. Check the hostname, port, username, and SSH key, then retry.",
            substring = true,
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(FIRST_HOST_TEST_CONNECT_RETRY_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FIRST_HOST_TEST_CONNECT_EDIT_TAG, useUnmergedTree = true).assertExists()
        capture("04-test-connect-failure")

        // The recovery affordance actually works: tapping "Edit" routes back to
        // the (guided) host form (EditFirstHost) so the user can fix the details
        // and retry — proving it is a live recovery path, not a dead end. The
        // route replace loads the host from Room, so give it a generous window
        // (the second test runs after the heavier happy-path journey and the
        // emulator can be under load).
        compose.waitForIdle()
        compose.onNodeWithTag(FIRST_HOST_TEST_CONNECT_EDIT_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(ADD_HOST_NAME_FIELD_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(hostName, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(hostName, useUnmergedTree = true).assertExists()
        capture("05-recovery-edit-form")
    } }

    // ---------------------------------------------------------------------
    // Journey steps
    // ---------------------------------------------------------------------

    private fun launchOnEmptyState() {
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(HOST_LIST_EMPTY_STATE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openGuidedWizardFromEmptyState() {
        compose.onNodeWithTag(HOST_LIST_EMPTY_ADD_HOST_TAG, useUnmergedTree = true).performClick()
        // The firstRunGuided AddEditHostScreen shows the wizard step strip and
        // the four host-detail fields.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(ADD_HOST_NAME_FIELD_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FIRST_HOST_WIZARD_STEPS_TAG, useUnmergedTree = true).assertExists()
    }

    private fun fillHostForm(
        name: String,
        hostname: String,
        port: Int,
        username: String,
        keyName: String,
    ) {
        compose.onNodeWithTag(ADD_HOST_NAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput(name)
        compose.onNodeWithTag(ADD_HOST_HOSTNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput(hostname)
        // The port field is prefilled with "22" — REPLACE it.
        compose.onNodeWithTag(ADD_HOST_PORT_FIELD_TAG, useUnmergedTree = true)
            .performTextReplacement("$port")
        compose.onNodeWithTag(ADD_HOST_USERNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput(username)
        // Open the key dropdown and pick the seeded key by its stored name.
        compose.onNodeWithTag(ADD_HOST_KEY_FIELD_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(keyName, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(keyName, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun reachedSetupOrWorkingHost(): Boolean {
        val sheet = compose.onAllNodesWithTag(HOST_BOOTSTRAP_SHEET_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        val folderList = compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        return sheet || folderList
    }

    // ---------------------------------------------------------------------
    // Fixture + DB helpers (mirroring ColdInstallE2eTest / DefaultHostLaunchE2eTest)
    // ---------------------------------------------------------------------

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedFixtureKeyViaHilt(key: String, name: String): SshKeyEntity {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val sshKeyDao = testAccess().sshKeyDao()
        return SshKeyStorage.persistKey(
            context = ctx,
            sshKeyDao = sshKeyDao,
            name = name,
            content = key,
        )
    }

    private fun testAccess(): TestAccessEntryPoint =
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            TestAccessEntryPoint::class.java,
        )

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = artifactDir()
        val file = File(dir, "$name.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("FIRST_HOST_WIZARD_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/first-host-wizard")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private companion object {
        // A CLOSED port on the emulator's host loopback (10.0.2.2). The `agents`
        // fixture pool uses 2222/2243/2244/2245 and the network-fault proxy uses
        // 2228; 59999 collides with none of them, so the connect is refused fast
        // and the failure is attributable to this deliberately-bad port.
        const val CLOSED_PORT: Int = 59999
    }
}
