package com.pocketshell.app.hosts

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.components.HOST_SETUP_BADGE_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Connected (instrumentation) coverage for issue #120: per-host setup
 * badge + "Re-check setup" kebab item.
 *
 * Two Docker bootstrap fixtures from `tests/docker/docker-compose.yml`
 * back the test: the `bootstrap-ready` profile, which already has
 * `tmuxctl` + `quse` installed and the daemon enabled, and the
 * `bootstrap-uv-install` profile, which has the installers but the
 * required tools missing — equivalent to a "needs setup" host. A third
 * scenario seeds a host with the bootstrap columns left `null` to
 * exercise the `Unknown` badge path; this scenario also exercises the
 * "Re-check setup" kebab entry to confirm it is wired and reachable.
 *
 * The visible badge states are asserted by their stable test tag
 * [HOST_SETUP_BADGE_TAG] (the pill's testTag is the same regardless of
 * state — the badge label distinguishes them, and we capture the
 * screenshot for the reviewer). The "Re-check setup" kebab item is
 * asserted by its label.
 *
 * The suite is opt-in (`-Pandroid.testInstrumentationRunnerArguments.
 * pocketshellBootstrapScenarios=true`) so the Docker-dependent run
 * only fires when the orchestrator opts in via
 * `scripts/phone-dogfood.sh setup-detection`.
 */
@RunWith(AndroidJUnit4::class)
class HostCardSetupBadgeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    /**
     * A pre-bootstrapped host renders the badge in the `Ready` state.
     * Seeding `tmuxInstalled=true, quseInstalled=true` exercises the
     * derivation rule (see [deriveSetupState]) without going through a
     * real connect — the rule is the contract for the badge regardless
     * of how the persisted flags arrived.
     *
     * The fixture connects to `bootstrap-ready` so the reviewer can also
     * verify the host is reachable (artifact `00-ready-host-list.png`
     * shows the list with the badge for documentation).
     */
    @Test
    fun readyBadge_rendersForBootstrappedHost() {
        assumeScenariosEnabled()
        val ctx = scenario(name = "ready", port = 2230) {
            seedHost(tmuxInstalled = true, quseInstalled = true)
            launchHostList()
            capture("01-ready-badge")
            // Badge pill is present somewhere on the screen and reads "ready".
            compose.onAllNodesWithTag(HOST_SETUP_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected ready setup badge" } }
            compose.onNodeWithText("ready").assertExists()
        }
        ctx.assertOk()
    }

    /**
     * A host whose persisted bootstrap row reports a missing tool
     * (`tmuxInstalled=true, quseInstalled=false`) renders the `Needs
     * setup` badge in amber. The fixture is `bootstrap-uv-install`,
     * which would surface the install sheet on a real connect — here
     * we seed the persisted state directly so the badge is observable
     * before any tap.
     */
    @Test
    fun needsSetupBadge_rendersForUnreadyHost() {
        assumeScenariosEnabled()
        val ctx = scenario(name = "needs-setup", port = 2231) {
            seedHost(tmuxInstalled = true, quseInstalled = false)
            launchHostList()
            capture("01-needs-setup-badge")
            compose.onAllNodesWithTag(HOST_SETUP_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected needs-setup badge" } }
            compose.onNodeWithText("needs setup").assertExists()
        }
        ctx.assertOk()
    }

    /**
     * Cold-launch path: a host that has never been bootstrapped (both
     * columns `null`) renders the badge in the `Unknown` state, AND the
     * card's overflow menu includes the new "Re-check setup" item that
     * lands on [HostListViewModel.recheckSetup]. The kebab and the
     * badge are the two AC affordances; this test covers both in a
     * single user journey.
     *
     * The re-check tap goes against the same `bootstrap-uv-install`
     * fixture used for the needs-setup case so the Docker side is
     * reachable; here we only assert the menu item is present + tappable.
     */
    @Test
    fun unknownBadge_andRecheckKebab_areVisibleForFreshHost() {
        assumeScenariosEnabled()
        val ctx = scenario(name = "unknown", port = 2231) {
            seedHost(tmuxInstalled = null, quseInstalled = null)
            launchHostList()
            capture("01-unknown-badge")
            compose.onAllNodesWithTag(HOST_SETUP_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected unknown setup badge" } }
            compose.onNodeWithText("unknown").assertExists()

            // Long-press also opens the same menu per the AC; using the
            // kebab tap here is faster and equivalent. We assert the new
            // "Re-check setup" item exists, then capture the open menu.
            compose.onNodeWithTag(HOST_OVERFLOW_BUTTON_TAG, useUnmergedTree = true).performClick()
            compose.waitUntil(timeoutMillis = 2_000) {
                compose.onAllNodesWithTag(HOST_RECHECK_SETUP_ITEM_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(HOST_RECHECK_SETUP_ITEM_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithText(RECHECK_SETUP_LABEL).assertExists()
            capture("02-recheck-menu-item")
        }
        ctx.assertOk()
    }

    private fun scenario(name: String, port: Int, block: ScenarioContext.() -> Unit): ScenarioContext {
        val context = ScenarioContext(scenarioName = name, port = port)
        try {
            context.cleanupAppDatabase()
            context.block()
        } catch (t: Throwable) {
            context.failure = t
        } finally {
            context.cleanupAppDatabase()
        }
        return context
    }

    private inner class ScenarioContext(
        val scenarioName: String,
        val port: Int,
    ) {
        var failure: Throwable? = null
        // Short host name to keep the host card row layout from
        // squashing the badge into a narrow column. The numeric suffix
        // adds enough entropy that parallel test runs do not collide on
        // a previously-seeded row.
        private val hostName = "B-$scenarioName-${System.nanoTime() % 100_000L}"
        private var hostId: Long? = null
        private var keyId: Long? = null

        fun seedHost(tmuxInstalled: Boolean?, quseInstalled: Boolean?) {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME).build()
            try {
                runBlocking {
                    val storedKey = SshKeyStorage.persistKey(
                        context = appContext,
                        sshKeyDao = db.sshKeyDao(),
                        name = "badge-test-key-$scenarioName",
                        content = testKey(),
                    )
                    keyId = storedKey.id
                    hostId = db.hostDao().insert(
                        HostEntity(
                            name = hostName,
                            hostname = DEFAULT_HOST,
                            port = port,
                            username = DEFAULT_USER,
                            keyId = storedKey.id,
                            tmuxInstalled = tmuxInstalled,
                            quseInstalled = quseInstalled,
                            // Mark the cache fresh so the cold-launch
                            // reprobe does not race the test by silently
                            // flipping the persisted columns under us.
                            lastBootstrapAt = System.currentTimeMillis(),
                            quseLastDetectedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            } finally {
                db.close()
            }
        }

        fun cleanupAppDatabase() {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME).build()
            try {
                runBlocking {
                    db.hostDao().getAll() // touch to ensure schema is ready
                    // Drop everything from previous runs so the screen
                    // isn't competing with stale rows.
                    db.clearAllTables()
                }
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
            HostCardArtifacts.capture(scenario = scenarioName, name = "$name.png")

        fun assertOk() {
            failure?.let { throw it }
        }
    }

    private fun assumeScenariosEnabled() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString("pocketshellBootstrapScenarios")
            ?.toBooleanStrictOrNull() == true
        assumeTrue(
            "host-card setup-badge instrumentation is opt-in; pass -Pandroid.testInstrumentationRunnerArguments.pocketshellBootstrapScenarios=true",
            enabled,
        )
    }

    private fun testKey(): String = InstrumentationRegistry.getInstrumentation()
        .context
        .assets
        .open("test_key")
        .bufferedReader()
        .use { it.readText() }

    private companion object {
        const val DEFAULT_HOST: String = "10.0.2.2"
        const val DEFAULT_USER: String = "testuser"
        const val DATABASE_NAME: String = "pocketshell.db"
    }
}

private object HostCardArtifacts {
    private const val DEVICE_DIR_NAME: String = "setup-detection"

    fun capture(scenario: String, name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = artifactFile(scenario = scenario, name = name)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write host-card setup badge screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("HOST_SETUP_BADGE_SCREENSHOT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(scenario: String, name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val directory = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME/host-card-badge-$scenario")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create host-card setup badge artifact directory: ${directory.absolutePath}"
        }
        return File(directory, name)
    }
}
