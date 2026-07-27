package com.pocketshell.app.usage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageResetCredit
import com.pocketshell.core.usage.UsageResetCredits
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class UsageResetCreditsLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inventoryIsContainedAndScrollableOnNarrowViewport() {
        assertInventoryContained(fontScale = 1.0f)
    }

    @Test
    fun inventoryIsContainedAndScrollableWithLargeFont() {
        assertInventoryContained(fontScale = 2.0f)
    }

    @Test
    fun authoritativeZeroRendersHonestHeaderWithoutInventingRows() {
        setUsageScreen(
            UsageResetCredits(
                availableCount = 0,
                credits = emptyList(),
                unavailable = false,
            ),
        )

        compose.onNodeWithText("Reset credits · 0 available", useUnmergedTree = true)
            .performScrollTo()
            .assertExists()
        compose.onAllNodesWithTag(
            usageResetCreditTitleTag(0),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun supplementaryErrorRendersQuietUnavailableState() {
        setUsageScreen(
            UsageResetCredits(
                availableCount = null,
                credits = emptyList(),
                unavailable = true,
            ),
        )

        compose.onNodeWithText("Reset credits unavailable", useUnmergedTree = true)
            .performScrollTo()
            .assertExists()
        compose.onNodeWithText("Reset credits ·", substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun missingInventoryOmitsTheSection() {
        setUsageScreen(resetCredits = null)

        compose.onNodeWithTag(USAGE_RESET_CREDITS_SECTION_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    private fun assertInventoryContained(fontScale: Float) {
        setUsageScreen(resetCredits = populatedCredits(), fontScale = fontScale)
        compose.waitForIdle()

        val lastExpiryTag = usageResetCreditExpiryTag(2)
        compose.onNodeWithTag(lastExpiryTag, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()

        val narrowRoot = compose.onNodeWithTag(NARROW_USAGE_ROOT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        listOf(
            USAGE_RESET_CREDITS_HEADER_TAG,
            usageResetCreditTitleTag(2),
            lastExpiryTag,
        ).forEach { tag ->
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$tag overflowed the 280dp usage viewport at fontScale=$fontScale: " +
                    "node=$bounds root=$narrowRoot",
                bounds.left >= narrowRoot.left && bounds.right <= narrowRoot.right,
            )
            compose.assertNodeFullyWithinRoot(tag = tag, useUnmergedTree = true)
        }
    }

    private fun setUsageScreen(
        resetCredits: UsageResetCredits?,
        fontScale: Float = 1.0f,
    ) {
        compose.setContent {
            PocketShellTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PocketShellColors.Background),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .requiredWidth(280.dp)
                                .fillMaxHeight()
                                .testTag(NARROW_USAGE_ROOT_TAG),
                        ) {
                            UsageScreen(
                                state = state(resetCredits),
                                onBack = {},
                                onRefresh = {},
                                modifier = Modifier.fillMaxSize(),
                                now = NOW,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun state(resetCredits: UsageResetCredits?): UsageScreenState = UsageScreenState(
        hosts = listOf(
            UsageHostSnapshot(
                hostId = 1L,
                hostName = "host",
                records = listOf(
                    UsageProviderRecord(
                        provider = "codex",
                        status = UsageStatus.Ok,
                        rawStatus = "ok",
                        windows = emptyList(),
                        resetCredits = resetCredits,
                    ),
                ),
                lastSyncedAt = NOW,
            ),
        ),
    )

    private fun populatedCredits(): UsageResetCredits = UsageResetCredits(
        availableCount = 3,
        credits = listOf(
            UsageResetCredit(
                title = "A deliberately very long source title that must ellipsize safely",
                expiresAt = Instant.parse("2026-07-28T12:00:00Z"),
            ),
            UsageResetCredit(
                title = "Second credit",
                expiresAt = null,
            ),
            UsageResetCredit(
                title = "Third credit",
                expiresAt = Instant.parse("2026-07-29T12:00:00Z"),
            ),
        ),
        unavailable = false,
    )

    private companion object {
        const val NARROW_USAGE_ROOT_TAG: String = "usage:reset-credits:narrow-root"
        val NOW: Instant = Instant.parse("2026-07-27T12:00:00Z")
    }
}
