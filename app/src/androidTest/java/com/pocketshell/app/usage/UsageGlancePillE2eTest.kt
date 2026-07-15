package com.pocketshell.app.usage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.hosts.HostsAppBar
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.portfwd.ForwardingIndicatorState
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1241: on-device Compose proof for the landing app-bar usage glance
 * pill. Composes the PRODUCTION [HostsAppBar] (the real landing app bar) inside
 * the real [PocketShellTheme] — not a proxy — and pins the acceptance criteria
 * that need a rendered composition:
 *
 *  - AC1: the most-constraining percent renders; HIDDEN when there is no data.
 *  - AC2: tapping the pill routes to Usage (invokes `onOpenUsage`).
 *  - AC3: the pill does NOT crowd the app bar — it and the Settings gear are
 *    both fully contained within the window root (the #418 declutter guard).
 *  - AC4: a stale reading renders honestly ("cached from HH:mm").
 *
 * Pure Compose-rule UI test (like `EnvScreenE2eTest`): no Docker fixture, no
 * SSH/tmux, no toxiproxy, deterministic on the CI swiftshader AVD, and it does
 * NOT self-skip on CI. Wired into `scripts/ci-journey-suite.sh` so it gates at
 * per-push/batched time (G9). The pure state-derivation coverage
 * (most-constraining pick, hidden-when-no-data, kind, stale flag) lives in the
 * per-push Unit gate: `UsageGlancePillStateTest`.
 */
@RunWith(AndroidJUnit4::class)
class UsageGlancePillE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun appBar(
        pill: UsageGlancePillState?,
        onOpenUsage: (() -> Unit)? = {},
        forwarding: ForwardingIndicatorState = ForwardingIndicatorState(
            activeHostCount = 1,
            totalTunnelCount = 2,
        ),
    ) {
        compose.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    HostsAppBar(
                        hostCount = 5,
                        activeSessionCount = 4,
                        forwardingIndicator = forwarding,
                        usageGlancePill = pill,
                        onOpenUsage = onOpenUsage,
                    )
                }
            }
        }
    }

    // AC1 + #1566: shows the most-constraining percent from cache, ATTRIBUTED to
    // its provider (+ window) — not a bare number.
    @Test
    fun showsMostConstrainingPercentAttributed() {
        appBar(
            pill = UsageGlancePillState(
                percent = 72,
                provider = "Codex",
                window = "7d",
                kind = PillKind.Warn,
                stale = false,
                capturedClock = "14:05",
            ),
        )
        compose.onNodeWithTag(USAGE_GLANCE_PILL_TAG).assertIsDisplayed()
        compose.onNodeWithText("72%").assertIsDisplayed()
        // The provider (+window) attribution is visibly rendered on the pill …
        compose.onNodeWithText("Codex 7d").assertIsDisplayed()
        // … and the accessibility label spells out the attributed usage.
        compose.onNodeWithContentDescription("Usage Codex 7d 72%").assertIsDisplayed()
    }

    // AC1: hidden when there is no data.
    @Test
    fun hiddenWhenNoData() {
        appBar(pill = null)
        compose.onNodeWithTag(USAGE_GLANCE_PILL_TAG).assertDoesNotExist()
        // The Settings gear must still be present — the app bar is intact.
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG).assertIsDisplayed()
    }

    // AC1: hidden when the Usage route is not wired (no destination to tap into).
    @Test
    fun hiddenWhenNoUsageRoute() {
        appBar(
            pill = UsageGlancePillState(
                percent = 50,
                provider = "Claude",
                window = null,
                kind = PillKind.Ok,
                stale = false,
                capturedClock = "14:05",
            ),
            onOpenUsage = null,
        )
        compose.onNodeWithTag(USAGE_GLANCE_PILL_TAG).assertDoesNotExist()
    }

    // AC2: tap navigates to UsageScreen (invokes onOpenUsage).
    @Test
    fun tapInvokesUsageRoute() {
        var tapped = false
        appBar(
            pill = UsageGlancePillState(
                percent = 88,
                provider = "Claude",
                window = "5h",
                kind = PillKind.Warn,
                stale = false,
                capturedClock = "14:05",
            ),
            onOpenUsage = { tapped = true },
        )
        compose.onNodeWithTag(USAGE_GLANCE_PILL_TAG).assertHasClickAction()
        compose.onNodeWithTag(USAGE_GLANCE_PILL_TAG).performClick()
        compose.waitForIdle()
        assertTrue("tapping the usage pill must route to Usage", tapped)
    }

    // AC3: does not crowd the app bar — pill AND settings gear both fully within
    // the window root, even alongside the forwarding indicator (the #418 guard).
    @Test
    fun pillAndSettingsAreBothFullyContained() {
        // A LONG attribution (OpenCode + window) alongside the stale clock stresses
        // the app-bar width — the #418 declutter guard must still fully contain it.
        appBar(
            pill = UsageGlancePillState(
                percent = 100,
                provider = "OpenCode",
                window = "5h",
                kind = PillKind.Blocked,
                stale = true,
                capturedClock = "13:40",
            ),
        )
        compose.assertNodeFullyWithinRoot(USAGE_GLANCE_PILL_TAG)
        compose.assertNodeFullyWithinRoot(SETTINGS_BUTTON_TAG)
    }

    // AC4: stale data is visually honest ("cached from HH:mm").
    @Test
    fun staleReadingRendersHonestly() {
        appBar(
            pill = UsageGlancePillState(
                percent = 63,
                provider = "Claude",
                window = null,
                kind = PillKind.Ok,
                stale = true,
                capturedClock = "13:40",
            ),
        )
        compose.onNodeWithTag(USAGE_GLANCE_PILL_TAG).assertIsDisplayed()
        // The provider attribution is still shown …
        compose.onNodeWithText("Claude").assertIsDisplayed()
        // … the muted capture clock is rendered inline …
        compose.onNodeWithText("13:40").assertIsDisplayed()
        // … and the honest provenance is in the accessibility description.
        compose.onNodeWithContentDescription("Usage Claude 63%, cached from 13:40").assertIsDisplayed()
    }
}
