package com.pocketshell.next.usage

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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The usage panel's compact-first layout (issue #2534).
 *
 * Journey J12 proves the glance-pill → panel path against a real host; this
 * suite pins the composition rules that journey would only catch by screenshot:
 * first paint is the compact strip (plus last-sync / counts / reset banner), a
 * provider card is mounted only after its compact row is tapped, and the compact
 * percent stays the most-constrained window even when a less-used window resets
 * sooner.
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
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("‹").assertDoesNotExist()
        composeRule.onNodeWithTag(USAGE_BACK_TAG).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun opening_usage_shows_compact_strip_without_provider_cards() {
        setContent()

        composeRule.onNodeWithTag(USAGE_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_SUMMARY_STRIP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(usageSummaryRowTag("Codex")).assertIsDisplayed()
        composeRule.onNodeWithTag(usageSummaryRowTag("Claude Code")).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_SYNC_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_COUNTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_RESET_BANNER_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(usageProviderCardTag("codex")).assertDoesNotExist()
        composeRule.onNodeWithTag(usageProviderCardTag("claude")).assertDoesNotExist()
        composeRule.onNodeWithTag(usageProviderCardTag("copilot")).assertDoesNotExist()
    }

    @Test
    fun tapping_codex_row_shows_its_card_and_tapping_again_collapses() {
        setContent()

        composeRule.onNodeWithTag(usageProviderCardTag("codex")).assertDoesNotExist()

        composeRule.onNodeWithTag(usageSummaryRowTag("Codex")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(usageProviderCardTag("codex")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(usageWindowRowTag("codex", "7d")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(USAGE_RESET_CREDITS_SECTION_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(usageProviderCardTag("claude")).assertDoesNotExist()

        composeRule.onNodeWithTag(usageSummaryRowTag("Codex")).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(usageProviderCardTag("codex")).assertDoesNotExist()
        composeRule.onNodeWithTag(USAGE_RESET_CREDITS_SECTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(usageProviderCardTag("claude")).assertDoesNotExist()
    }

    @Test
    fun tapping_claude_expands_only_claude() {
        setContent()

        composeRule.onNodeWithTag(usageSummaryRowTag("Claude Code")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(usageProviderCardTag("claude")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(usageWindowRowTag("claude", "5h")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(usageProviderCardTag("codex")).assertDoesNotExist()
    }

    @Test
    fun compact_codex_row_shows_most_constrained_percent_and_soonest_reset() {
        setContent()

        composeRule.onNode(
            hasTestTag(usageSummaryRowTag("Codex")) and
                hasAnyDescendant(hasText("60% used")) and
                hasAnyDescendant(hasText("in 4h")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
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
