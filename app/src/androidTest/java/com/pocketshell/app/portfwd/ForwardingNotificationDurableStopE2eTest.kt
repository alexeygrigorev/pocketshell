package com.pocketshell.app.portfwd

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.FORWARDING_INDICATOR_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Issue #1202 recurrence (2026-08-12), D33/G10 connected proof.
 *
 * The notification's real Stop action used to clear only the process-local
 * [ForwardingController]. The durable `HostEntity.enabled` intent stayed true,
 * so the production [ForwardingResumeScheduler] read it on the next app
 * foreground and brought forwarding plus the notification back. The earlier
 * #1202 journey watched only the same-process controller for a short settle
 * window and therefore stayed green with this recurrence.
 *
 * This journey starts from two real Room-backed enabled hosts, lets the
 * production lifecycle scheduler adopt one through a real Docker-backed SSH
 * connection, backgrounds the app, and fires the notification action's actual
 * PendingIntent. It then closes and relaunches the activity so the real
 * foreground resume path reads Room again. Stop must have disabled both hosts,
 * torn runtime state down, removed the notification, and prevented the
 * relaunch from resurrecting either the controller or the in-app indicator.
 */
@RunWith(AndroidJUnit4::class)
class ForwardingNotificationDurableStopE2eTest {

    private val compose = createEmptyComposeRule()
    private var activity: ActivityScenario<MainActivity>? = null

    private val forwardingIsolation = PortForwardingTestIsolationRule(
        afterStop = {
            activity?.close()
            activity = null
        },
    )

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(forwardingIsolation)
        .around(PreGrantPermissionsRule())
        .around(compose)

    private fun context(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun entryPoint(): TestAccessEntryPoint =
        EntryPointAccessors.fromApplication(context(), TestAccessEntryPoint::class.java)

    private fun controller(): ForwardingController = entryPoint().forwardingController()

    private fun notificationManager(): NotificationManager =
        context().getSystemService(NotificationManager::class.java)

    @Test
    fun notificationStopDisablesEveryPersistedHost_andRelaunchCannotResumeForwarding() {
        val fixtureKey = readFixtureKey()
        runBlocking { waitForSshFixtureReady(SshKey.Pem(fixtureKey)) }

        val db = entryPoint().appDatabase()
        lateinit var firstSeededBeforeBootstrap: HostEntity
        lateinit var secondSeededBeforeBootstrap: HostEntity
        runBlocking {
            db.clearAllTables()
            controller().activeHostIdsSnapshot().forEach(controller()::stopForwarding)
            val storedKey = SshKeyStorage.persistKey(
                context = context(),
                sshKeyDao = db.sshKeyDao(),
                name = "issue1202-durable-stop-${System.currentTimeMillis()}",
                content = fixtureKey,
            )
            val firstId = db.hostDao().insert(
                HostEntity(
                    name = "Issue 1202 durable stop A",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    enabled = true,
                    maxAutoPort = 4_321,
                    skipPortsBelow = 1_234,
                ),
            )
            firstSeededBeforeBootstrap = requireNotNull(db.hostDao().getById(firstId))

            // Seed the second durable intent after the first host is adopted
            // below. Both must still be disabled by ONE global notification
            // action, while every unrelated field remains byte-for-byte equal.
            secondSeededBeforeBootstrap = HostEntity(
                id = SECOND_HOST_ID,
                name = "Issue 1202 durable stop B",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = storedKey.id,
                enabled = true,
                maxAutoPort = 9_876,
                skipPortsBelow = 2_345,
            )
        }

        // Launch and force a real STOP -> START edge so App's production
        // ForwardingResumeScheduler reads the first persisted enabled host and
        // adopts a real SSH session from the agents fixture.
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitForHostList()
        forceProcessStopStart()
        assertTrue(
            "production foreground resume must adopt the persisted enabled host",
            pollUntil(FORWARD_START_TIMEOUT_MS) {
                controller().isHostActive(firstSeededBeforeBootstrap.id)
            },
        )
        assertTrue(
            "the real resumed forward must post its persistent notification",
            pollUntil(NOTIFICATION_TIMEOUT_MS) { forwardingNotification() != null },
        )
        assertTrue(
            "the preservation baseline must wait for the real bootstrap metadata, not use the seed row",
            pollUntil(FORWARD_START_TIMEOUT_MS) {
                runBlocking {
                    db.hostDao().getById(firstSeededBeforeBootstrap.id)?.let { host ->
                        host.lastBootstrapAt != null && host.pocketshellLastDetectedAt != null
                    } == true
                }
            },
        )

        // Add a second enabled host to durable and runtime state. The action is
        // global, so this catches an implementation that disables only the
        // notification's primary/current host.
        runBlocking { db.hostDao().insert(secondSeededBeforeBootstrap) }
        controller().registerActiveHost(
            secondSeededBeforeBootstrap.id,
            secondSeededBeforeBootstrap.name,
        )
        assertEquals(
            listOf(firstSeededBeforeBootstrap.id, secondSeededBeforeBootstrap.id).sorted(),
            controller().activeHostIdsSnapshot().sorted(),
        )

        // The host-list cold probe snapshots its rows on first composition.
        // Host B was inserted after that snapshot, so recreate the real host
        // list before taking the preservation baseline. This is a production
        // bootstrap/resume path, not a test-only metadata write.
        relaunchHostListForBootstrap()

        val expectedIdsInOrder = listOf(
            firstSeededBeforeBootstrap.id,
            secondSeededBeforeBootstrap.id,
        )
        assertTrue(
            "relaunch must retain both active forwarding owners before notification Stop",
            pollUntil(FORWARD_START_TIMEOUT_MS) {
                controller().activeHostIdsSnapshot().containsAll(expectedIdsInOrder)
            },
        )

        // Exact maintainer journey: minimize/background, then tap Stop in the
        // persistent notification. The test enters through the real PendingIntent;
        // it never calls a production disable helper directly.
        activity!!.moveToState(Lifecycle.State.CREATED)
        assertTrue(
            "the process must really reach background before notification Stop",
            pollUntil(PROCESS_LIFECYCLE_TIMEOUT_MS) {
                ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.CREATED
            },
        )
        val stopAction = requireNotNull(
            forwardingNotification()?.notification?.actions?.firstOrNull {
                it.title?.toString() == "Stop"
            },
        ) { "the real forwarding notification must expose Stop" }

        // The preservation baseline is intentionally captured IMMEDIATELY
        // before the actual notification action. It requires two identical,
        // fully bootstrapped DAO observations separated by a production-idle
        // observation. A one-shot read can race the second host's legitimate
        // capability writes and falsely attribute those writes to Stop.
        val rowsImmediatelyBeforeStop = awaitStablePreStopRows(db, expectedIdsInOrder)
        assertExactDurableRows(
            rows = rowsImmediatelyBeforeStop,
            expectedIdsInOrder = expectedIdsInOrder,
        )
        assertEquals(
            "both exact rows must still be durably enabled immediately before notification Stop",
            listOf(true, true),
            rowsImmediatelyBeforeStop.map { it.enabled },
        )
        assertTrue(
            "both pre-Stop rows must include real bootstrap writes; seed rows are not a valid preservation baseline",
            rowsImmediatelyBeforeStop[0] != firstSeededBeforeBootstrap &&
                rowsImmediatelyBeforeStop[1] != secondSeededBeforeBootstrap,
        )
        stopAction.actionIntent.send()

        // Runtime/service teardown still happens: both controller owners and
        // the persistent notification must go away before relaunch.
        assertTrue(
            "notification Stop must tear down every active forwarding owner",
            pollUntil(STOP_TIMEOUT_MS) { controller().activeHostIdsSnapshot().isEmpty() },
        )
        assertTrue(
            "notification Stop must remove the forwarding notification",
            pollUntil(STOP_TIMEOUT_MS) { forwardingNotification() == null },
        )

        // Re-open the app as the maintainer did. This drives the production
        // foreground resume scheduler against Room again. Nothing may be
        // re-adopted and neither OS nor in-app forwarding status may return.
        activity!!.close()
        activity = null
        assertTrue(
            "closing the background activity must keep the process stopped",
            pollUntil(PROCESS_LIFECYCLE_TIMEOUT_MS) {
                ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.CREATED
            },
        )
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitForHostList()

        val stableDeadline = SystemClock.elapsedRealtime() + RELAUNCH_STABLE_MS
        while (SystemClock.elapsedRealtime() < stableDeadline) {
            assertTrue(
                "relaunch must not resurrect a host stopped from the notification; active=" +
                    controller().activeHostIdsSnapshot(),
                controller().activeHostIdsSnapshot().isEmpty(),
            )
            assertFalse(
                "relaunch must not repost the forwarding notification",
                forwardingNotification() != null,
            )
            assertTrue(
                "relaunch must not restore the in-app forwarding indicator",
                compose.onAllNodesWithTag(FORWARDING_INDICATOR_TAG)
                    .fetchSemanticsNodes().isEmpty(),
            )
            SystemClock.sleep(POLL_INTERVAL_MS)
        }

        // Durable stop is the recurrence's second load-bearing assertion. A
        // single atomic UPDATE must clear every enabled host and preserve all
        // other presentation/connection fields. This is intentionally checked
        // after the relaunch hold: on the buggy base the real resume gets the
        // chance to recreate the exact reported notification/forward first.
        val persistedAfterStop = runBlocking {
            db.hostDao().getEnabled().first() to db.hostDao().getAll().first()
        }
        assertTrue(
            "Stop must clear durable forwarding intent for every host; still enabled=" +
                persistedAfterStop.first.map { it.id },
            persistedAfterStop.first.isEmpty(),
        )
        val expectedRowsAfterStop = rowsImmediatelyBeforeStop.map { it.copy(enabled = false) }
        assertEquals(
            "Stop may change only enabled=false on each exact pre-Stop row",
            expectedRowsAfterStop,
            persistedAfterStop.second,
        )
        assertExactDurableRows(
            rows = persistedAfterStop.second,
            expectedIdsInOrder = expectedIdsInOrder,
        )
        assertEquals(
            "both exact rows must be durably disabled after notification Stop and relaunch",
            listOf(false, false),
            persistedAfterStop.second.map { it.enabled },
        )

        // UTP removes the app package before an outer `run-as` can copy Room.
        // Export and verify the durable rows while instrumentation still owns
        // the DB. AGP pulls this directory into connected_android_test_additional_output.
        val artifactContents = durableStateArtifactContents(
            enabledIdsAfterStop = persistedAfterStop.first.map { it.id },
            rowsImmediatelyBeforeStop = rowsImmediatelyBeforeStop,
            rowsAfterStop = persistedAfterStop.second,
        )
        val artifact = exportDurableStateArtifact(artifactContents)
        assertTrue(
            "instrumentation must export the final durable-state artifact before teardown: ${artifact.absolutePath}",
            artifact.isFile,
        )
        assertEquals(
            "exported durable-state artifact byte count",
            artifactContents.toByteArray(Charsets.UTF_8).size.toLong(),
            artifact.length(),
        )
        assertEquals(
            "exported durable-state artifact must contain the exact asserted snapshots",
            artifactContents,
            artifact.readText(),
        )
    }

    private fun assertExactDurableRows(
        rows: List<HostEntity>,
        expectedIdsInOrder: List<Long>,
    ) {
        assertEquals("exact durable row count", 2, rows.size)
        assertEquals("durable host IDs and Room name-order", expectedIdsInOrder, rows.map { it.id })
        assertEquals(
            "durable host names",
            listOf("Issue 1202 durable stop A", "Issue 1202 durable stop B"),
            rows.map { it.name },
        )
        assertEquals("durable maxAutoPort values", listOf(4_321, 9_876), rows.map { it.maxAutoPort })
        assertEquals("durable skipPortsBelow values", listOf(1_234, 2_345), rows.map { it.skipPortsBelow })
    }

    /**
     * Return a preservation baseline only after both exact Room rows have
     * completed the real host-list bootstrap/detection writes and the full
     * rows are unchanged across two bounded reads. We deliberately compare the
     * complete entities, rather than timestamps or selected fields, because the
     * post-Stop assertion promises that only `enabled` may change.
     */
    private fun awaitStablePreStopRows(
        db: AppDatabase,
        expectedIdsInOrder: List<Long>,
    ): List<HostEntity> {
        val deadline = SystemClock.elapsedRealtime() + PRE_STOP_BOOTSTRAP_TIMEOUT_MS
        var previousEligible: List<HostEntity>? = null
        var lastRows = emptyList<HostEntity>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val rows = runBlocking { db.hostDao().getAll().first() }
            lastRows = rows
            if (rowsHaveCompleteBootstrapState(rows, expectedIdsInOrder)) {
                if (previousEligible == rows) return rows
                previousEligible = rows
                // Observe the UI/production work queue idle between the two DAO
                // reads, then leave a real-time interval for late IO persistence.
                compose.waitForIdle()
                SystemClock.sleep(PRE_STOP_STABILITY_INTERVAL_MS)
                compose.waitForIdle()
            } else {
                previousEligible = null
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }
        error(
            "both exact pre-Stop rows never reached a stable completed bootstrap state; " +
                "expectedIds=$expectedIdsInOrder rows=$lastRows",
        )
    }

    private fun rowsHaveCompleteBootstrapState(
        rows: List<HostEntity>,
        expectedIdsInOrder: List<Long>,
    ): Boolean =
        rows.map { it.id } == expectedIdsInOrder &&
            rows.all { row ->
                row.enabled &&
                    row.tmuxInstalled != null &&
                    row.lastBootstrapAt != null &&
                    row.pocketshellInstalled != null &&
                    row.pocketshellLastDetectedAt != null &&
                    !row.pocketshellCliVersion.isNullOrBlank() &&
                    !row.pocketshellExpectedCliVersion.isNullOrBlank() &&
                    row.pocketshellVersionCompatible != null &&
                    row.pocketshellDaemonRunning != null &&
                    row.pocketshellDaemonEnabled != null
            }

    private fun durableStateArtifactContents(
        enabledIdsAfterStop: List<Long>,
        rowsImmediatelyBeforeStop: List<HostEntity>,
        rowsAfterStop: List<HostEntity>,
    ): String = buildString {
        appendLine("schema=issue-1202-durable-stop-v1")
        appendLine("enabled_ids_after_stop=${enabledIdsAfterStop.joinToString(",")}")
        appendLine("row_count=${rowsAfterStop.size}")
        rowsImmediatelyBeforeStop.forEachIndexed { index, row ->
            appendHostEntity("before_stop[$index]", row)
        }
        rowsAfterStop.forEachIndexed { index, row ->
            appendHostEntity("after_stop[$index]", row)
        }
    }

    private fun StringBuilder.appendHostEntity(prefix: String, row: HostEntity) {
        appendLine("$prefix.id=${row.id}")
        appendLine("$prefix.name=${row.name}")
        appendLine("$prefix.hostname=${row.hostname}")
        appendLine("$prefix.port=${row.port}")
        appendLine("$prefix.username=${row.username}")
        appendLine("$prefix.key_id=${row.keyId}")
        appendLine("$prefix.max_auto_port=${row.maxAutoPort}")
        appendLine("$prefix.skip_ports_below=${row.skipPortsBelow}")
        appendLine("$prefix.scan_interval_sec=${row.scanIntervalSec}")
        appendLine("$prefix.enabled=${row.enabled}")
        appendLine("$prefix.created_at=${row.createdAt}")
        appendLine("$prefix.last_connected_at=${row.lastConnectedAt}")
        appendLine("$prefix.tmux_installed=${row.tmuxInstalled}")
        appendLine("$prefix.last_bootstrap_at=${row.lastBootstrapAt}")
        appendLine("$prefix.pocketshell_installed=${row.pocketshellInstalled}")
        appendLine("$prefix.pocketshell_last_detected_at=${row.pocketshellLastDetectedAt}")
        appendLine("$prefix.pocketshell_cli_version=${row.pocketshellCliVersion}")
        appendLine("$prefix.pocketshell_expected_cli_version=${row.pocketshellExpectedCliVersion}")
        appendLine("$prefix.pocketshell_version_compatible=${row.pocketshellVersionCompatible}")
        appendLine("$prefix.pocketshell_daemon_running=${row.pocketshellDaemonRunning}")
        appendLine("$prefix.pocketshell_daemon_enabled=${row.pocketshellDaemonEnabled}")
        appendLine("$prefix.usage_command_override=${row.usageCommandOverride}")
    }

    private fun exportDurableStateArtifact(contents: String): File {
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(context())
        val dir = File(mediaRoot, "additional_test_output/issue-1202-durable-stop")
        check(dir.exists() || dir.mkdirs()) {
            "could not create durable-state artifact directory ${dir.absolutePath}"
        }
        val file = File(dir, "final-durable-state.txt")
        check(!file.exists() || file.delete()) {
            "could not remove stale durable-state artifact ${file.absolutePath}"
        }
        file.writeText(contents)
        println("ISSUE1202_DURABLE_STATE_ARTIFACT ${file.absolutePath}")
        return file
    }

    private fun forceProcessStopStart() {
        activity!!.moveToState(Lifecycle.State.CREATED)
        assertTrue(
            "test setup must reach process STOP before foreground resume",
            pollUntil(PROCESS_LIFECYCLE_TIMEOUT_MS) {
                ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.CREATED
            },
        )
        activity!!.moveToState(Lifecycle.State.RESUMED)
        assertTrue(
            "test setup must reach process START",
            pollUntil(PROCESS_LIFECYCLE_TIMEOUT_MS) {
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            },
        )
    }

    private fun relaunchHostListForBootstrap() {
        activity!!.close()
        activity = null
        assertTrue(
            "closing the first host list must reach process STOP before the two-row bootstrap relaunch",
            pollUntil(PROCESS_LIFECYCLE_TIMEOUT_MS) {
                ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.CREATED
            },
        )
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitForHostList()
        assertTrue(
            "the two-row bootstrap relaunch must reach process START",
            pollUntil(PROCESS_LIFECYCLE_TIMEOUT_MS) {
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            },
        )
    }

    private fun waitForHostList() {
        compose.waitUntil(timeoutMillis = PROCESS_LIFECYCLE_TIMEOUT_MS) {
            compose.onAllNodesWithText("Hosts").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun forwardingNotification() =
        notificationManager().activeNotifications.firstOrNull { notification ->
            notification.packageName == context().packageName &&
                notification.notification.channelId.startsWith("pocketshell_forwarding_status")
        }

    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("test_key")
            .bufferedReader().use { it.readText() }

    private companion object {
        const val SECOND_HOST_ID = 1_202_002L
        const val POLL_INTERVAL_MS = 100L
        const val PRE_STOP_STABILITY_INTERVAL_MS = 500L
        const val PROCESS_LIFECYCLE_TIMEOUT_MS = 10_000L
        const val FORWARD_START_TIMEOUT_MS = 30_000L
        const val PRE_STOP_BOOTSTRAP_TIMEOUT_MS = 60_000L
        const val NOTIFICATION_TIMEOUT_MS = 10_000L
        const val STOP_TIMEOUT_MS = 15_000L
        // Long enough for the unfixed production scheduler to complete a real
        // agents-fixture SSH connect and visibly resurrect the forward. A short
        // same-process settle window is the coverage hole in the old #1202 test.
        const val RELAUNCH_STABLE_MS = 30_000L
    }
}
