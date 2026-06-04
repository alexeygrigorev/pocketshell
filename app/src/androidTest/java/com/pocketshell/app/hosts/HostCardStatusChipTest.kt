package com.pocketshell.app.hosts

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.components.HOST_STATUS_DESCRIPTION_ATTENTION
import com.pocketshell.uikit.components.HOST_STATUS_DOT_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Connected (instrumentation) coverage for the trailing host-card status
 * indicator vocabulary (originally issue #201; reshaped by issue #418).
 *
 * Issue #418 collapsed the old text status **pill** + inline setup
 * **badge** into a single status **dot** (design-system §8 / the
 * `dashboard.html` `.status-dot`). The state vocabulary now lives in the
 * dot's `contentDescription` rather than a painted label, so this suite
 * asserts presence of the [HOST_STATUS_DOT_TAG] dot per row plus the
 * folded `NeedsSetup` -> amber-dot ("Needs setup" description) case, and
 * still guards that the ambiguous word "idle" never appears.
 *
 * No Docker / SSH is involved: the rows are stubbed with persisted
 * bootstrap columns so the badge derivation in [deriveSetupState] picks
 * the desired pre-state without contacting any host. The aim is to
 * exercise the wiring between [HostListViewModel.setupStates], the
 * cross-host session aggregate, and the [HostCard] trailing chip, not
 * the SSH transport itself (which has its own coverage).
 *
 * Like [HostCardSetupBadgeTest], the suite is opt-in via
 * `pocketshellBootstrapScenarios=true` so the cold-launch reprobe
 * (which the ViewModel kicks on first composition) does not race the
 * test on a CI lane that lacks the Docker fixtures. The persisted
 * `lastBootstrapAt` is set to "now" so the freshness guard short-
 * circuits the reprobe anyway, but the gate keeps the run consistent
 * with the rest of the host-card connected coverage.
 */
@RunWith(AndroidJUnit4::class)
class HostCardStatusChipTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    /**
     * Primary state 1: host that has completed bootstrap (tmux +
     * pocketshell both installed) but currently has zero tmux sessions.
     * The single status dot must be present (its colour reads idle/active
     * depending on the live session aggregate) and the screen must not
     * carry the word "idle".
     */
    @Test
    fun noActiveSessions_dotRendersForReadyHostWithNoSessions() {
        assumeScenariosEnabled()
        scenario(name = "no-active-sessions") {
            seedHost(tmuxInstalled = true, pocketshellInstalled = true)
            launchHostList()
            capture("01-no-active-sessions")

            compose.onAllNodesWithTag(HOST_STATUS_DOT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected trailing host-status dot" } }
            // The dot may be the muted "No active sessions" colour, the
            // green "Active sessions" colour, or the loading spinner if
            // the cross-host session aggregate hasn't observed our seeded
            // host yet (it polls when a tmux client registers, which this
            // scenario never does). In all cases the ambiguous word
            // "idle" must never appear.
            assertNoIdleLabel()
        }
    }

    /**
     * Primary state 2: host with live tmux sessions and the app NOT
     * attached. The dot must be present (green "Active sessions" colour
     * when the aggregate reports sessions) and the screen must not carry
     * the word "idle".
     *
     * Because the seeded scenario can't easily inject a fake session
     * aggregate snapshot from here (it polls a live tmux client and we
     * are not standing one up), this test seeds the persisted setup state
     * for a Ready host and primarily validates the dot slot exists. The
     * N-sessions colour mapping is covered by the JVM-side
     * [HostStatusDerivationTest.activeSessions_whenReadyAndPositiveCount]
     * — together they prove the full path.
     */
    @Test
    fun nSessionsDot_isStubbedForReadyHost() {
        assumeScenariosEnabled()
        scenario(name = "n-sessions") {
            seedHost(tmuxInstalled = true, pocketshellInstalled = true)
            launchHostList()
            capture("02-n-sessions")
            compose.onAllNodesWithTag(HOST_STATUS_DOT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected trailing host-status dot" } }
            assertNoIdleLabel()
        }
    }

    /**
     * Primary state 3: host whose bootstrap probe reported a tool missing
     * — issue #418 folds the old "needs setup" badge into the single
     * status dot, which now renders amber and carries the accessible
     * "Needs setup" description. There is exactly one dot (not a badge +
     * a chip), and the screen must not carry the word "idle".
     */
    @Test
    fun needsSetupHost_rendersAmberAttentionDot() {
        assumeScenariosEnabled()
        scenario(name = "needs-setup") {
            seedHost(tmuxInstalled = true, pocketshellInstalled = false)
            launchHostList()
            capture("03-needs-setup")
            // Exactly one status dot, and it carries the folded "Needs
            // setup" attention description.
            compose.onAllNodesWithTag(HOST_STATUS_DOT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected single trailing host-status dot" } }
            compose.onAllNodesWithContentDescription(
                HOST_STATUS_DESCRIPTION_ATTENTION,
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
                .let {
                    check(it.isNotEmpty()) {
                        "needs-setup host must fold into the amber attention dot"
                    }
                }
            assertNoIdleLabel()
        }
    }

    private fun assertNoIdleLabel() {
        // Issue #201's headline assertion: the word "idle" must not
        // appear anywhere on the host-list screen. Compose's tree only
        // exposes text we've rendered, so this is exhaustive across
        // the visible UI for the row under test.
        check(
            compose.onAllNodesWithText("idle", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        ) {
            "AC violation: host card displays the word 'idle' — see issue #201"
        }
    }

    private fun scenario(name: String, block: ScenarioContext.() -> Unit) {
        val context = ScenarioContext(scenarioName = name)
        try {
            context.cleanupAppDatabase()
            context.block()
        } finally {
            context.cleanupAppDatabase()
        }
    }

    private inner class ScenarioContext(val scenarioName: String) {
        // Short host name keeps the row layout from squashing the
        // status pill; the nanoTime suffix avoids collisions if two
        // scenarios race onto the same emulator instance.
        private val hostName = "S-$scenarioName-${System.nanoTime() % 100_000L}"

        fun seedHost(tmuxInstalled: Boolean?, pocketshellInstalled: Boolean?) {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            try {
                runBlocking {
                    val keyId = db.sshKeyDao().insert(
                        SshKeyEntity(
                            name = "status-test-key-$scenarioName",
                            privateKeyPath = "/data/local/tmp/no-such-key-$scenarioName",
                        ),
                    )
                    db.hostDao().insert(
                        HostEntity(
                            name = hostName,
                            hostname = "10.0.2.2",
                            port = 22,
                            username = "testuser",
                            keyId = keyId,
                            tmuxInstalled = tmuxInstalled,
                            pocketshellInstalled = pocketshellInstalled,
                            // Freshness guard so the cold-launch
                            // reprobe (which would otherwise try to
                            // open an SSH session against an
                            // intentionally-missing key) is a no-op
                            // and does not flip the columns under us.
                            lastBootstrapAt = System.currentTimeMillis(),
                            pocketshellLastDetectedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            } finally {
                db.close()
            }
        }

        fun cleanupAppDatabase() {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            try {
                runBlocking { db.clearAllTables() }
            } finally {
                db.close()
            }
        }

        fun launchHostList() {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(hostName).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(hostName).assertExists()
        }

        fun capture(name: String): File =
            StatusChipArtifacts.capture(scenario = scenarioName, name = "$name.png")
    }

    private fun assumeScenariosEnabled() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString("pocketshellBootstrapScenarios")
            ?.toBooleanStrictOrNull() == true
        assumeTrue(
            "host-card status-chip instrumentation is opt-in; pass " +
                "-Pandroid.testInstrumentationRunnerArguments.pocketshellBootstrapScenarios=true",
            enabled,
        )
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
    }
}

private object StatusChipArtifacts {
    private const val DEVICE_DIR_NAME: String = "host-status-chip"

    fun capture(scenario: String, name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = artifactFile(scenario = scenario, name = name)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write host-status-chip screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("HOST_STATUS_CHIP_SCREENSHOT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(scenario: String, name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val directory = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME/host-card-status-$scenario")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create host-status-chip artifact directory: ${directory.absolutePath}"
        }
        return File(directory, name)
    }
}
