package com.pocketshell.next.usage

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageResetCredit
import com.pocketshell.core.usage.UsageResetCredits
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.uikit.theme.PocketShellTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The usage panel's host-scoped Quiet rows (issue #2611).
 *
 * Journey J12 proves the glance-pill → panel path against a real host; this
 * suite pins the composition rules that journey would only catch by screenshot:
 * first paint keeps provider rows compact (plus last-sync / counts / reset
 * banner), tapping a row reveals its real windows and reset credits inline, and
 * the compact percent stays the most-constrained window even when a less-used
 * window resets sooner.
 */
@RunWith(AndroidJUnit4::class)
class UsageScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `back button fires onBack`() {
        var backs = 0
        composeRule.setContent {
            PocketShellTheme {
                UsageScreen(
                    state = UsageScreenState(),
                    onBack = { backs += 1 },
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(USAGE_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_BACK_TAG).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun opening_usage_shows_quiet_provider_rows_without_legacy_chrome() {
        setContent()

        composeRule.onNodeWithTag(USAGE_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_PROVIDER_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(usageProviderRowTag("Codex")).assertIsDisplayed()
        composeRule.onNodeWithTag(usageProviderRowTag("claude")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_SYNC_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_COUNTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_RESET_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Codex limits reset at 5:00 PM").assertIsDisplayed()
        composeRule.onNodeWithText("Heavy work can resume.").assertIsDisplayed()

        composeRule.onNodeWithTag(usageProviderDetailsTag("codex")).assertDoesNotExist()
        composeRule.onNodeWithTag(usageProviderDetailsTag("claude")).assertDoesNotExist()
        composeRule.onNodeWithTag(usageProviderDetailsTag("copilot")).assertDoesNotExist()
    }

    @Test
    fun tapping_codex_row_shows_inline_details_and_tapping_again_collapses() {
        setContent()

        composeRule.onNodeWithTag(usageProviderDetailsTag("codex")).assertDoesNotExist()

        composeRule.onNodeWithTag(usageProviderToggleTag("codex")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(usageProviderDetailsTag("codex")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(usageWindowRowTag("codex", "7d")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_RESET_CREDITS_SECTION_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(usageProviderDetailsTag("claude")).assertDoesNotExist()

        composeRule.onNodeWithTag(usageProviderToggleTag("codex")).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(usageProviderDetailsTag("codex")).assertDoesNotExist()
        composeRule.onNodeWithTag(USAGE_RESET_CREDITS_SECTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(usageProviderDetailsTag("claude")).assertDoesNotExist()
    }

    @Test
    fun tapping_claude_expands_only_claude() {
        setContent()

        composeRule.onNodeWithTag(usageProviderToggleTag("claude"), useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(usageProviderDetailsTag("claude")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(usageWindowRowTag("claude", "5h")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(usageProviderDetailsTag("codex")).assertDoesNotExist()
    }

    @Test
    fun quiet_codex_row_shows_most_constrained_percent() {
        setContent()

        composeRule.onNode(
            hasTestTag(usageProviderRowTag("Codex")) and
                hasAnyDescendant(hasText("60% used", substring = true)) and
                hasAnyDescendant(hasText("7d window", substring = true)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `large text can scroll to provider details`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.8f)) {
                PocketShellTheme {
                    UsageScreen(
                        state = sampleState(),
                        onBack = {},
                        onRefresh = {},
                        now = NOW,
                        initiallyExpandedProviders = setOf("Codex"),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(usageProviderDetailsTag("codex"))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun dashboardRows_picks_most_constrained_window_not_the_soonest_reset_window() {
        val rows = sampleState().dashboardRows()
        val codex = rows.single { it.provider == "Codex" }

        assertEquals(60.0, codex.percent, 0.0)
        assertEquals("60% used", codex.percentLabel)
        assertEquals(CODEX_5H_RESET, codex.soonestReset)
    }

    private fun setContent(state: UsageScreenState = sampleState()) {
        composeRule.setContent {
            PocketShellTheme {
                UsageScreen(
                    state = state,
                    onBack = {},
                    onRefresh = {},
                    now = NOW,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun sampleState(): UsageScreenState = UsageScreenState(
        selectedHostId = 1,
        selectedHostName = "hetzner",
        hosts = listOf(
            UsageHostSnapshot(
                hostId = 1,
                hostName = "hetzner",
                records = listOf(claude(), codex(), copilot()),
                lastSyncedAt = NOW,
            ),
        ),
        loaded = true,
        connectedHostCount = 1,
        resetBanner = UsageResetBannerState(
            title = "Codex limits reset at 5:00 PM",
            detail = "Heavy work can resume.",
            resetKey = "codex-7d",
        ),
    )

    private fun claude(): UsageProviderRecord = record(
        provider = "claude",
        windows = listOf(
            window("5h", percent = 12.0, resetAt = CLAUDE_5H_RESET),
            window("7d", percent = 11.0, resetAt = CLAUDE_7D_RESET),
        ),
    )

    private fun codex(): UsageProviderRecord = record(
        provider = "codex",
        windows = listOf(
            window("5h", percent = 10.0, resetAt = CODEX_5H_RESET),
            window("7d", percent = 60.0, resetAt = CODEX_7D_RESET),
        ),
        resetCredits = UsageResetCredits(
            availableCount = 3,
            credits = listOf(
                UsageResetCredit(title = "Full reset", expiresAt = CODEX_CREDIT_EXPIRY),
            ),
            unavailable = false,
        ),
    )

    private fun copilot(): UsageProviderRecord = record(
        provider = "copilot",
        windows = listOf(
            window("5h", percent = 0.0, resetAt = null),
            window("monthly", percent = 6.0, resetAt = COPILOT_MONTHLY_RESET),
        ),
    )

    private fun record(
        provider: String,
        windows: List<UsageWindow>,
        resetCredits: UsageResetCredits? = null,
    ): UsageProviderRecord = UsageProviderRecord(
        provider = provider,
        status = UsageStatus.Ok,
        windows = windows,
        rawStatus = "ok",
        resetCredits = resetCredits,
    )

    private fun window(name: String, percent: Double, resetAt: Instant?): UsageWindow =
        UsageWindow(
            name = name,
            used = percent,
            limit = 100.0,
            unit = "percent",
            resetAt = resetAt,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-05T12:00:00Z")
        val CODEX_5H_RESET: Instant = Instant.parse("2026-09-05T16:00:00Z")
        val CODEX_7D_RESET: Instant = Instant.parse("2026-09-10T12:00:00Z")
        val CODEX_CREDIT_EXPIRY: Instant = Instant.parse("2026-09-21T12:00:00Z")
        val CLAUDE_5H_RESET: Instant = Instant.parse("2026-09-05T14:35:00Z")
        val CLAUDE_7D_RESET: Instant = Instant.parse("2026-09-10T16:59:00Z")
        val COPILOT_MONTHLY_RESET: Instant = Instant.parse("2026-10-01T00:00:00Z")
    }
}
