package com.pocketshell.app.settings

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.SeedBeforeLaunchRule
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.proof.clearOutboundDeliveryAuthorityPin
import com.pocketshell.app.proof.pinOutboundDeliveryAuthority
import com.pocketshell.app.proof.settingsRepositorySingletonForTest
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Issue #2124 AC8 (step 1b of epic #2121) — **the active outbound-delivery
 * authority is visible in-app, so a field report is unambiguous about which
 * path produced it.**
 *
 * ## Why this class exists (G9 — reviewer round 2)
 *
 * The criterion shipped with real behaviour and **no triggering test**: the
 * reviewer deleted the ENTIRE "Outbound delivery confirmation" block from
 * `SettingsScreen.kt` — both radio rows *and* the `Active:` line — and 107
 * `com.pocketshell.app.settings.*` tests stayed green. The pure-function pins
 * on [outboundDeliveryAuthorityLabel] / [outboundDeliveryAuthorityOptionTag]
 * never assert those strings reach a composed screen, so nothing in the suite
 * fails when the criterion's whole implementation is removed.
 *
 * ## What this class constrains that a "the row exists" test would not
 *
 * The field report is only unambiguous if the screen names the authority that
 * is **actually in effect** — not a hard-coded default, and not merely whatever
 * the UI last tapped. So the two tests below pin the binding in both
 * directions:
 *
 * - [activeLineNamesTheAuthorityInEffectAndFollowsItWhenItChangesUnderneath]
 *   seeds the **non-default** legacy authority on the singleton the app really
 *   reads (via [pinOutboundDeliveryAuthority], the #2124 round-1 lesson: a
 *   direct prefs write is a silent no-op because [SettingsRepository] is a
 *   `@Singleton` built during `App.onCreate` that never re-reads the file).
 *   The `Active:` line must name the LEGACY path. An implementation that
 *   hard-codes the shipped default reddens here. It then flips the authority
 *   underneath the composed screen through the production setter and requires
 *   the line to follow — so the line must be bound to repository state, not to
 *   a local `remember`.
 * - [tappingARowFlipsTheVisibleActivePathAndPersistsIt] drives the real radio
 *   rows and requires BOTH halves of the on-device escape hatch: the visible
 *   `Active:` line moves, AND the choice reaches the persisted `app_settings`
 *   preference (the maintainer must be able to flip paths on-device without a
 *   reinstall, and the next launch must honour it).
 *
 * Each assertion also names the OTHER authority as forbidden, so a line that
 * renders both labels — or the wrong one — is a failure rather than a pass.
 *
 * ## Harness / gate
 *
 * `createAndroidComposeRule<MainActivity>()` + [SeedBeforeLaunchRule] (the #788
 * launch-owned shape) drives the REAL Settings screen through the production
 * navigation — the settings button on the landing surface — not an isolated
 * composable. No Docker fixture and no SSH: ~15s, in the shape of
 * `InheritedFocusOwnerVerdictE2eTest`. Wired into `scripts/ci-journey-suite.sh`
 * so it runs in the per-push emulator journey gate; an unregistered androidTest
 * would reproduce exactly the G9 hole this class closes.
 */
@RunWith(AndroidJUnit4::class)
class Issue2124OutboundAuthorityVisibleInSettingsE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    /**
     * Seed the NON-default authority before MainActivity launches, so "the
     * screen shows the shipped default" can never be mistaken for "the screen
     * shows what is in effect".
     */
    private fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        pinOutboundDeliveryAuthority(OutboundDeliveryAuthority.TerminalInference)
    }

    @After
    fun restoreShippedDefault() {
        clearOutboundDeliveryAuthorityPin()
        clearLastSessionPrefs()
    }

    @Test
    fun activeLineNamesTheAuthorityInEffectAndFollowsItWhenItChangesUnderneath() {
        openOutboundDeliveryConfirmationBlock()

        // Both choices are offered on-device (no reinstall needed to flip).
        compose.onNodeWithTag(hostCliAckRowTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(terminalInferenceRowTag, useUnmergedTree = true).assertExists()

        // The seeded authority is the LEGACY one; the line must say so.
        awaitActiveLineNaming(OutboundDeliveryAuthority.TerminalInference)
        captureFullDevice(File(screenshotDir(), "ac8-01-active-legacy-as-seeded.png"))

        // Move the authority underneath the composed screen through the
        // production setter. The line is only trustworthy for a field report if
        // it tracks repository state rather than the last tap.
        settingsRepositorySingletonForTest()
            .setOutboundDeliveryAuthority(OutboundDeliveryAuthority.HostCliAck)
        awaitActiveLineNaming(OutboundDeliveryAuthority.HostCliAck)
        captureFullDevice(File(screenshotDir(), "ac8-02-active-follows-repository.png"))
    }

    @Test
    fun tappingARowFlipsTheVisibleActivePathAndPersistsIt() {
        openOutboundDeliveryConfirmationBlock()
        awaitActiveLineNaming(OutboundDeliveryAuthority.TerminalInference)

        // Legacy -> acknowledged, by tapping the row the maintainer would tap.
        tapAuthorityRow(hostCliAckRowTag)
        awaitActiveLineNaming(OutboundDeliveryAuthority.HostCliAck)
        assertAuthorityIsInEffectAndPersisted(OutboundDeliveryAuthority.HostCliAck)
        captureFullDevice(File(screenshotDir(), "ac8-03-tapped-host-cli-ack.png"))

        // ...and back, so the escape hatch is reachable in both directions.
        tapAuthorityRow(terminalInferenceRowTag)
        awaitActiveLineNaming(OutboundDeliveryAuthority.TerminalInference)
        assertAuthorityIsInEffectAndPersisted(OutboundDeliveryAuthority.TerminalInference)
        captureFullDevice(File(screenshotDir(), "ac8-04-tapped-legacy-back.png"))
    }

    // ---------------------------------------------------------------- helpers

    private val hostCliAckRowTag: String =
        outboundDeliveryAuthorityOptionTag(OutboundDeliveryAuthority.HostCliAck)

    private val terminalInferenceRowTag: String =
        outboundDeliveryAuthorityOptionTag(OutboundDeliveryAuthority.TerminalInference)

    /** Navigate the production route: landing surface -> Settings -> the block. */
    private fun openOutboundDeliveryConfirmationBlock() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG).assertExists()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(OUTBOUND_DELIVERY_AUTHORITY_ACTIVE_TAG))
        compose.onNodeWithTag(OUTBOUND_DELIVERY_AUTHORITY_ACTIVE_TAG, useUnmergedTree = true)
            .assertExists()
    }

    /**
     * Tap one authority row the way the maintainer would.
     *
     * The whole Terminal card is a SINGLE `LazyColumn` item, so scrolling to the
     * `Active:` line leaves the rows below it outside the viewport and a tap
     * there lands on nothing (observed: the first run's flip never happened and
     * the assertion timed out rather than failing on a wrong label). Scroll to
     * the row itself first — the same shape the sibling #818 radio-group journey
     * uses — so the tap is a real, reachable one.
     */
    private fun tapAuthorityRow(rowTag: String) {
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(rowTag))
        compose.onNodeWithTag(rowTag, useUnmergedTree = true).performClick()
    }

    /**
     * The visible `Active:` line must name [expected] and must NOT name the
     * other authority — an ambiguous line is exactly what AC8 forbids.
     */
    private fun awaitActiveLineNaming(expected: OutboundDeliveryAuthority) {
        val wanted = outboundDeliveryAuthorityLabel(expected)
        val forbidden = outboundDeliveryAuthorityLabel(otherThan(expected))
        compose.waitUntil(timeoutMillis = 10_000) { activeLineText().contains(wanted) }
        val line = activeLineText()
        assertTrue(
            "the Settings \"Active:\" line must name the authority in effect " +
                "($wanted) so a field report is unambiguous (issue #2124 AC8); saw: $line",
            line.contains(wanted),
        )
        assertTrue(
            "the Settings \"Active:\" line must NOT also name $forbidden — an " +
                "ambiguous line cannot identify which path produced a field " +
                "report (issue #2124 AC8); saw: $line",
            !line.contains(forbidden),
        )
    }

    /** Both the running app AND the next launch must see the chosen path. */
    private fun assertAuthorityIsInEffectAndPersisted(expected: OutboundDeliveryAuthority) {
        assertEquals(
            "tapping the row must move the authority the app actually reads " +
                "(SettingsRepository singleton, issue #2124 AC8)",
            expected,
            settingsRepositorySingletonForTest().settings.value.outboundDeliveryAuthority,
        )
        val persisted = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SETTINGS_KEY_OUTBOUND_DELIVERY_AUTHORITY, null)
        assertEquals(
            "the chosen authority must PERSIST so the maintainer can flip paths " +
                "on-device without a reinstall and the next launch honours it " +
                "(issue #2124 AC8)",
            expected.name,
            persisted,
        )
    }

    private fun otherThan(authority: OutboundDeliveryAuthority): OutboundDeliveryAuthority =
        when (authority) {
            OutboundDeliveryAuthority.HostCliAck -> OutboundDeliveryAuthority.TerminalInference
            OutboundDeliveryAuthority.TerminalInference -> OutboundDeliveryAuthority.HostCliAck
        }

    private fun activeLineText(): String =
        compose.onNodeWithTag(OUTBOUND_DELIVERY_AUTHORITY_ACTIVE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString(separator = " ") { it.text }

    private fun screenshotDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-2124-outbound-authority")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-2124 AC8 screenshot dir: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-2124 AC8 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_2124_AC8_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
