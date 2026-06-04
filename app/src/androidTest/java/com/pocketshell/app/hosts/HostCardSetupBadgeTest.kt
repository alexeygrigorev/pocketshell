package com.pocketshell.app.hosts

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
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
 * Connected (instrumentation) coverage for issue #120: per-host setup
 * badge + "Re-check setup" kebab item.
 *
 * Two Docker bootstrap fixtures from `tests/docker/docker-compose.yml`
 * back the test: the `bootstrap-ready` profile, which already has
 * `pocketshell` installed and the daemon enabled, and the
 * `bootstrap-uv-install` profile, which has the installers but the
 * required tools missing — equivalent to a "needs setup" host. A third
 * scenario seeds a host with the bootstrap columns left `null` to
 * exercise the `Unknown` badge path; this scenario also exercises the
 * "Re-check setup" kebab entry to confirm it is wired and reachable.
 *
 * Issue #418 folded the old per-state setup **badge** pill into the
 * single host-card status **dot** (design-system §8). The setup state is
 * therefore asserted via the dot ([HOST_STATUS_DOT_TAG]) and its
 * accessible `contentDescription`: a `NeedsSetup` host renders the amber
 * attention dot ([HOST_STATUS_DESCRIPTION_ATTENTION]); a `Ready` host
 * does NOT. The "Re-check setup" kebab item is still asserted by its
 * label, and we capture the screenshot for the reviewer.
 *
 * The suite is opt-in (`-Pandroid.testInstrumentationRunnerArguments.
 * pocketshellBootstrapScenarios=true`) so the Docker-dependent run
 * only fires when the orchestrator opts in via
 * `scripts/phone-walkthrough.sh setup-detection`.
 */
@RunWith(AndroidJUnit4::class)
class HostCardSetupBadgeTest {

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
     * A pre-bootstrapped (`Ready`) host renders the single status dot and
     * does NOT fold into the amber attention state — a ready host has no
     * setup attention to call out. Seeding
     * `tmuxInstalled=true, pocketshellInstalled=true` exercises the
     * derivation rule without going through a real connect.
     *
     * The fixture connects to `bootstrap-ready` so the reviewer can also
     * verify the host is reachable (artifact `01-ready-badge.png` shows
     * the list with the dot for documentation).
     */
    @Test
    fun readyHost_rendersStatusDotWithoutAttention() {
        assumeScenariosEnabled()
        val ctx = scenario(name = "ready", port = 2230) {
            seedHost(tmuxInstalled = true, pocketshellInstalled = true)
            launchHostList()
            capture("01-ready-badge")
            // The single status dot is present.
            compose.onAllNodesWithTag(HOST_STATUS_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected host status dot" } }
            // And it is NOT the amber "Needs setup" attention dot.
            check(
                compose.onAllNodesWithContentDescription(
                    HOST_STATUS_DESCRIPTION_ATTENTION,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty(),
            ) {
                "a ready host must not render the needs-setup attention dot"
            }
        }
        ctx.assertOk()
    }

    /**
     * A host whose persisted bootstrap row reports a missing tool
     * (`tmuxInstalled=true, pocketshellInstalled=false`) folds into the
     * amber attention dot carrying the "Needs setup" description. The
     * fixture is `bootstrap-uv-install`, which would surface the install
     * sheet on a real connect — here we seed the persisted state directly
     * so the dot is observable before any tap.
     */
    @Test
    fun needsSetup_rendersAmberAttentionDot() {
        assumeScenariosEnabled()
        val ctx = scenario(name = "needs-setup", port = 2231) {
            seedHost(tmuxInstalled = true, pocketshellInstalled = false)
            launchHostList()
            capture("01-needs-setup-badge")
            compose.onAllNodesWithTag(HOST_STATUS_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected host status dot" } }
            compose.onAllNodesWithContentDescription(
                HOST_STATUS_DESCRIPTION_ATTENTION,
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected needs-setup attention dot" } }
        }
        ctx.assertOk()
    }

    /**
     * Cold-launch path: a host that has never been bootstrapped (both
     * columns `null`) renders the single status dot (in its unverified /
     * non-attention state — an unknown host has no setup attention to
     * call out), AND the card's overflow menu includes the "Re-check
     * setup" item that lands on [HostListViewModel.recheckSetup]. The
     * kebab and the dot are the two affordances; this test covers both in
     * a single user journey.
     *
     * The re-check tap goes against the same `bootstrap-uv-install`
     * fixture used for the needs-setup case so the Docker side is
     * reachable; here we only assert the menu item is present + tappable.
     */
    @Test
    fun unknownDot_andRecheckKebab_areVisibleForFreshHost() {
        assumeScenariosEnabled()
        val ctx = scenario(name = "unknown", port = 2231) {
            seedHost(tmuxInstalled = null, pocketshellInstalled = null)
            launchHostList()
            capture("01-unknown-badge")
            compose.onAllNodesWithTag(HOST_STATUS_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes()
                .let { check(it.isNotEmpty()) { "expected host status dot" } }
            // An unknown-setup host is not in setup attention — the dot
            // must not be the amber "Needs setup" one.
            check(
                compose.onAllNodesWithContentDescription(
                    HOST_STATUS_DESCRIPTION_ATTENTION,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty(),
            ) {
                "an unknown-setup host must not render the needs-setup attention dot"
            }

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

        fun seedHost(tmuxInstalled: Boolean?, pocketshellInstalled: Boolean?) {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
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
                            pocketshellInstalled = pocketshellInstalled,
                            // Mark the cache fresh so the cold-launch
                            // reprobe does not race the test by silently
                            // flipping the persisted columns under us.
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
