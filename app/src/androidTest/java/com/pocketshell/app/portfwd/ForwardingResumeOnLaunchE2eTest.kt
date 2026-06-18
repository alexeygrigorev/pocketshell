package com.pocketshell.app.portfwd

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
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
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * End-to-end emulator + Docker proof for issue #752 (REOPENED): a host the
 * user **previously enabled** (persisted `host.enabled = true` in the real
 * Room DB) must, on app foreground, have its forwarding **re-established by
 * the production [ForwardingResumeScheduler]** so the always-on forwarding
 * indicator visibly appears — without the user re-toggling the panel switch.
 *
 * This is the glued journey the reviewer required (per #641/#657): the
 * persisted-enabled state and the render surface were each proven in
 * isolation (a Robolectric unit test for the trigger logic, a separate
 * connected test for the pill render when the controller is *already*
 * populated), but nothing exercised
 *
 *   persisted-enabled host in Room → real `App` `ON_START` resume →
 *   real SSH connect to the Docker `agents` fixture → indicator visibly paints
 *
 * on a device. That split is exactly the proxy-vs-real-state gap the rule
 * exists to close, and the maintainer reopened this issue saying the
 * indicator "wasn't working", so the acceptance bar is a full-device capture
 * of the indicator present after launch for a persisted-enabled host.
 *
 * The test seeds the enabled host into the SAME singleton [AppDatabase] the
 * running app flows observe (via [TestAccessEntryPoint]) BEFORE launching
 * [MainActivity], points it at the deterministic `agents` SSH fixture on
 * `10.0.2.2:2222`, launches the activity, forces a real STOP→START process
 * lifecycle cycle so the production foreground-resume hook fires against the
 * seeded DB, and then asserts the host-list forwarding pill appears, the
 * production [ForwardingController] reports the host active, and the ⇄
 * ongoing notification is posted. A full-device screenshot is captured.
 *
 * No Docker fixture beyond the default `agents:2222` is used, so no
 * `.github/workflows/tests.yml` change is needed (the emulator job already
 * brings up `agents` on 2222).
 */
@RunWith(AndroidJUnit4::class)
class ForwardingResumeOnLaunchE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var seededHostId: Long? = null

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun entryPoint(): TestAccessEntryPoint =
        EntryPointAccessors.fromApplication(appContext(), TestAccessEntryPoint::class.java)

    private fun controller(): ForwardingController = entryPoint().forwardingController()

    @After
    fun teardown() {
        // Stop the resumed forward so the foreground service + notification
        // clear and a sibling test starts clean.
        runCatching { controller().stopAllForwarding() }
        seededHostId = null
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun persistedEnabledHost_resumesForwardingAndShowsIndicatorOnLaunch() {
        val key = readFixtureKey()
        runBlocking { waitForSshFixtureReady(SshKey.Pem(key)) }

        // 1. Seed an ENABLED host pointing at the agents fixture into the real
        //    singleton DB (the same instance the app's running flows observe),
        //    BEFORE launching MainActivity. This is the persisted "I enabled
        //    auto-forward last session" state the reopened bug is about.
        val db = entryPoint().appDatabase()
        runBlocking {
            db.clearAllTables()
            // Make sure no stale controller registration leaks in from a
            // sibling test in the same instrumentation process.
            controller().activeHostIdsSnapshot().forEach { controller().stopForwarding(it) }

            val storedKey = SshKeyStorage.persistKey(
                context = appContext(),
                sshKeyDao = db.sshKeyDao(),
                name = "issue752-resume-key-${System.currentTimeMillis()}",
                content = key,
            )
            seededHostId = db.hostDao().insert(
                HostEntity(
                    name = "Resume Host 752",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    // The persisted intent that origin/main never read back.
                    enabled = true,
                ),
            )
        }
        val hostId = requireNotNull(seededHostId)

        // 2. Launch the real app. The production ForwardingResumeScheduler was
        //    wired to ProcessLifecycleOwner in App.onCreate; force a genuine
        //    STOP→START cycle so its ON_START resume runs against the now-seeded
        //    DB (a fresh launch may reuse an already-STARTED process from a
        //    sibling test, in which case ON_START would not re-fire on its own).
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Hosts").fetchSemanticsNodes().isNotEmpty()
        }
        launchedActivity!!.moveToState(Lifecycle.State.CREATED) // ON_STOP
        SystemClock.sleep(300)
        launchedActivity!!.moveToState(Lifecycle.State.RESUMED) // ON_START → resume

        // 3. The production resume connects to the fixture and adopts the host,
        //    so activeHostCount goes > 0 and the always-on forwarding pill
        //    appears — with NO manual panel toggle in this process.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(FORWARDING_INDICATOR_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot("resume-indicator-visible")

        assertTrue(
            "ForwardingController must report the persisted-enabled host active after launch resume",
            controller().isHostActive(hostId),
        )
        assertTrue(
            "Always-on forwarding indicator pill must be present after launch for a persisted-enabled host",
            compose.onAllNodesWithTag(FORWARDING_INDICATOR_TAG).fetchSemanticsNodes().isNotEmpty(),
        )

        // 4. The ⇄ ongoing foreground-service notification is also posted
        //    (POST_NOTIFICATIONS is pre-granted by the rule).
        val notificationManager =
            appContext().getSystemService(NotificationManager::class.java)
        val forwardingNotificationPresent = pollUntil(timeoutMs = 10_000) {
            notificationManager.activeNotifications.any { sbn ->
                sbn.packageName == appContext().packageName &&
                    sbn.notification.channelId.startsWith("pocketshell_forwarding_status")
            }
        }
        assertTrue(
            "The ⇄ port-forwarding ongoing notification must be posted after the launch resume",
            forwardingNotificationPresent,
        )
        Log.i(LOG_TAG, "resume-on-launch indicator + notification verified for host $hostId")
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(250)
        }
        return condition()
    }

    private fun captureScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        // Write under the instrumentation artifacts root so the
        // connected-test wrapper auto-pulls it into
        // app/build/.../connected_android_test_additional_output for review.
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/forwarding-resume-on-launch").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write forwarding-resume screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("FORWARDING_RESUME_SCREENSHOT ${file.absolutePath}")
    }

    private companion object {
        const val LOG_TAG = "Issue752ResumeE2e"
    }
}
