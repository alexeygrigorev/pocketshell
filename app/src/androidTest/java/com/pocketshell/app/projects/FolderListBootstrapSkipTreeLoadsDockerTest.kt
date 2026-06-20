package com.pocketshell.app.projects

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SHEET_TAG
import com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SKIP_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.SeedBeforeLaunchRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Issue #847 (P0) — connected (emulator + Docker) UI-JOURNEY proof of the REAL
 * root cause v0.4.11 did NOT fix: the **timeout inversion** on the cold-start
 * reconcile dial.
 *
 * ## The maintainer's reported journey
 *
 * Tap a host whose `pocketshell` CLI is OLDER than the app → the "Host setup
 * needed" bootstrap sheet appears → tap **Skip** to proceed → the tree screen
 * shows "Loading workspace tree" and ~28s later fails with "Session list didn't
 * load within 12000ms. Tap to retry."
 *
 * The chain: the bootstrap probe opens a warm `warm-host-connect` SSH lease;
 * tapping Skip ([HOST_BOOTSTRAP_SKIP_TAG] → `dismissBootstrapAndOpen`) RELEASES
 * that warm lease; the tree's cold-start reconcile then needs a FRESH cold dial,
 * and (pre-fix) that dial was wrapped by the 12s reconcile bound — shorter than
 * the 35s connect timeout it absorbed — so a slow real-network dial surfaced the
 * spurious 12s `FolderReconcileTimeoutException` → ERROR panel even though the
 * host was perfectly connectable.
 *
 * ## What this test exercises (the REAL UI path, not a proxy)
 *
 * It drives the PRODUCTION navigation through `createAndroidComposeRule<MainActivity>()`
 * + the #788 seed-before-launch harness against the dedicated `agents-old-cli`
 * fixture (port 2238) — whose `pocketshell` rejects `tree`, so the bootstrap
 * sheet genuinely appears. It taps the host, dismisses the sheet via Skip (which
 * releases the warm lease — the #847 trigger), and asserts the tree screen
 * reaches a usable state (NOT the [FOLDER_LIST_ERROR_TAG] "didn't load" panel).
 *
 * On a localhost Docker fixture the cold dial is fast, so this test cannot by
 * itself reproduce the 12s timeout the maintainer hit on a slow real network —
 * the deterministic red→green for the inversion is the JVM
 * `FolderListViewModelConnectTimeoutInversionTest` (slow virtual-clock dial).
 * This connected test is the e2e regression guard that the Skip → warm-lease-
 * release → reconcile journey reaches Ready over a real SSH transport, and the
 * orchestrator re-tests against the real hetzner host before release.
 *
 * ## CI gating (issue #849 — this test now RUNS on CI)
 *
 * Previously `assumeFalse(isRunningOnCi())`-gated because the workflow started
 * only `agents` on 2222. As of #849 the `emulator-journey` workflow ALSO brings
 * up `agents-old-cli` on 2238 and adds this class to
 * `scripts/ci-journey-suite.sh::JOURNEY_CLASSES`, so the self-skip is GONE — the
 * bootstrap-Skip → warm-lease-release → tree-loads journey is now gated at PR
 * time and in the pre-release gate. The always-runnable backstop is the JVM
 * inversion test (per-push Unit job).
 */
@RunWith(AndroidJUnit4::class)
class FolderListBootstrapSkipTreeLoadsDockerTest {

    private val compose = createAndroidComposeRule<MainActivity>()
    private var hostRowTag: String = ""

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { hostRowTag = seedOldCliHost() })
        .around(compose)

    @Test
    fun bootstrapSkipThenTreeReachesReadyNotTimeoutPanel() {
        // Issue #849: NO assumeFalse(isRunningOnCi()) self-skip. The
        // emulator-journey workflow (and the pre-release gate) now start the
        // agents-old-cli fixture on 2238, so this bootstrap-Skip connect journey
        // RUNS on CI. The seedOldCliHost() SeedBeforeLaunchRule HARD-fails fast
        // (waitForSshFixtureReady + a `tree get` non-zero require) if 2238 is
        // unreachable, so a missing fixture surfaces loudly instead of a vacuous
        // skip — process.md G3/G10.

        // Tap the seeded old-CLI host.
        compose.waitUntil(timeoutMillis = 20_000) { countTag(hostRowTag) > 0 }
        compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true).onFirst().performClick()

        // The bootstrap "Host setup needed" sheet appears (old CLI < app).
        compose.waitUntil(timeoutMillis = 30_000) { countTag(HOST_BOOTSTRAP_SHEET_TAG) > 0 }

        // Tap Skip → `dismissBootstrapAndOpen` releases the warm-host-connect
        // lease and routes to the tree (the #847 trigger).
        compose.onAllNodesWithTag(HOST_BOOTSTRAP_SKIP_TAG, useUnmergedTree = true).onFirst().performClick()

        // The load-bearing assertion: after Skip the tree must reach a usable
        // state and MUST NOT land on the "Session list didn't load within
        // 12000ms" error panel. Poll until the screen leaves Loading.
        compose.waitUntil(timeoutMillis = 40_000) {
            countTag(FOLDER_LIST_SCREEN_TAG) > 0 &&
                countTag(FOLDER_LIST_LOADING_TAG) == 0
        }
        val errorPanels = countTag(FOLDER_LIST_ERROR_TAG)
        assertTrue(
            "after dismissing the bootstrap sheet via Skip (which releases the warm " +
                "lease), the tree must load — it must NOT surface the #847 " +
                "'didn't load within 12000ms' error panel (errorPanels=$errorPanels)",
            errorPanels == 0,
        )
    }

    private fun countTag(tag: String): Int = runCatching {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private suspend fun seedOldCliHost(): String {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        // Confirm the fixture is reachable + really old (rejects `tree`).
        waitForSshFixtureReady(SshKey.Pem(keyText), port = OLD_CLI_PORT)
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = OLD_CLI_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(keyText),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                val treeProbe = session.exec("printf '%s' '{\"host\":\"h\"}' | pocketshell tree get")
                require(treeProbe.exitCode != 0) {
                    "agents-old-cli fixture must reject `tree` (exit=${treeProbe.exitCode}); " +
                        "otherwise the bootstrap sheet would not appear"
                }
            }
        }

        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "ps847-oldcli-${System.currentTimeMillis()}",
                content = keyText,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Old-CLI host (#847)",
                    hostname = DEFAULT_HOST,
                    port = OLD_CLI_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    // No fresh bootstrap cache → the foreground probe runs and the
                    // old CLI drives the "Host setup needed" sheet.
                    tmuxInstalled = null,
                    lastBootstrapAt = null,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private companion object {
        const val OLD_CLI_PORT: Int = 2238
        const val DATABASE_NAME: String = "pocketshell.db"
    }
}
