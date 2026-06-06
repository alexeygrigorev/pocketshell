package com.pocketshell.app.portfwd

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Connected coverage for the global "ports forwarding" app-bar indicator
 * (issue #446, epic #432 slice D).
 *
 * The indicator is a pure read surface over the production
 * [ForwardingController] singleton: it appears only while ≥1 host is
 * actively auto-forwarding, and tapping it deep-links to the port-forward
 * panel entry (the chooser). The test drives the controller directly (no
 * Docker / SSH needed) so it isolates the indicator wiring from the
 * forwarding transport, which has its own coverage.
 */
@RunWith(AndroidJUnit4::class)
class ForwardingIndicatorE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found"). Replaces
    // this test's previous inline POST_NOTIFICATIONS-only grant with the
    // shared rule that also covers RECORD_AUDIO / CAMERA.
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var registeredHostId: Long? = null

    private fun controller(): ForwardingController {
        val ctx: Context = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext
        return EntryPointAccessors
            .fromApplication(ctx, TestAccessEntryPoint::class.java)
            .forwardingController()
    }

    @After
    fun teardown() {
        registeredHostId?.let { runCatching { controller().unregisterActiveHost(it) } }
        registeredHostId = null
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun indicatorHiddenWithNoForwards_thenAppearsAndOpensPanelOnTap() {
        // Runtime permissions are pre-granted by the [grantPermissions]
        // rule before this body runs, so MainActivity's Android-13+ runtime
        // request never throws a system dialog over the activity window.

        // Make sure no stale registration leaks in from a sibling test.
        controller().activeHostIdsSnapshot().forEach { controller().unregisterActiveHost(it) }

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // 1. With no active forwards the indicator pill is absent.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Hosts").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Indicator must be hidden when no forwards are active",
            compose.onAllNodesWithTag(com.pocketshell.app.hosts.FORWARDING_INDICATOR_TAG)
                .fetchSemanticsNodes()
                .isEmpty(),
        )

        // 2. Register an active host with two tunnels — the indicator
        // appears (driven purely by the production controller flow).
        val hostId = 987_654L
        registeredHostId = hostId
        controller().registerActiveHost(hostId = hostId, hostName = "Indicator Host")
        controller().updateTunnelCount(hostId, 2)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(com.pocketshell.app.hosts.FORWARDING_INDICATOR_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("2 ports forwarding active").assertIsDisplayed()
        captureScreenshot("indicator-visible")

        // 3. Tapping the indicator deep-links to the port-forward panel
        // entry (the host chooser).
        compose.onNodeWithContentDescription("2 ports forwarding active").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Port Forwarding").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Choose a saved host").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Tapping the indicator must deep-link to the port-forward chooser",
            compose.onAllNodesWithText("Choose a saved host").fetchSemanticsNodes().isNotEmpty(),
        )
        captureScreenshot("indicator-deeplink-chooser")
    }

    private fun captureScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "forwarding-indicator",
        ).apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write forwarding-indicator screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("FORWARDING_INDICATOR_SCREENSHOT ${file.absolutePath}")
    }
}
