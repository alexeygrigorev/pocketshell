package com.pocketshell.app.costs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.core.storage.dao.AiApiCallLogDao
import com.pocketshell.core.storage.entity.AiApiCallEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Connected emulator coverage for the AI Costs screen (issue #181).
 *
 * The screen is data-driven, so the test seeds an in-memory fake DAO
 * (not the real Room DB) — that keeps the run hermetic, fast, and free
 * of Docker dependencies. The Room schema migration + DAO round-trip
 * are covered separately by [com.pocketshell.core.storage.AppDatabaseTest]
 * and the migration unit test. The Compose render path is the part
 * worth driving from an emulator: pixel-correct layout, scrolling, and
 * the confirmation-dialog interaction.
 */
class CostsScreenE2eTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersSeededCostsWithBreakdownAndRecentRows() {
        val dao = FakeAiApiCallLogDao(
            initial = listOf(
                AiApiCallEntry(
                    id = 1L,
                    timestampMillis = System.currentTimeMillis() - 60_000L,
                    provider = "openai",
                    feature = "whisper",
                    inputUnits = 12,
                    outputUnits = 84,
                    unitCostUsdMillicents = 10,
                    computedCostUsdMillicents = 120,
                    metadataJson = null,
                ),
                AiApiCallEntry(
                    id = 2L,
                    timestampMillis = System.currentTimeMillis() - 120_000L,
                    provider = "openai",
                    feature = "whisper",
                    inputUnits = 3,
                    outputUnits = 20,
                    unitCostUsdMillicents = 10,
                    computedCostUsdMillicents = 30,
                    metadataJson = null,
                ),
            ),
        )
        val viewModel = CostsViewModel(dao)

        compose.setContent {
            CostsScreen(
                onBack = { },
                viewModel = viewModel,
            )
        }

        compose.onNodeWithTag(COSTS_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COSTS_TOTAL_LIFETIME_TAG).assertIsDisplayed()
        // The "OpenAI · Whisper" breakdown row must render.
        compose.onNodeWithText("OpenAI · Whisper").assertIsDisplayed()
        // The "Recent calls" section materialises both seeded rows.
        compose.onNodeWithTag(COSTS_RECENT_ROW_TAG_PREFIX + "1").assertIsDisplayed()
        compose.onNodeWithTag(COSTS_RECENT_ROW_TAG_PREFIX + "2").assertIsDisplayed()
        // The Export + Clear action rows are present so the user has a
        // path to both affordances called out by the issue.
        compose.onNodeWithTag(COSTS_EXPORT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COSTS_CLEAR_TAG).assertIsDisplayed()
    }

    @Test
    fun clearLogConfirmationDialogOpens() {
        val dao = FakeAiApiCallLogDao(
            initial = listOf(
                AiApiCallEntry(
                    id = 1L,
                    timestampMillis = System.currentTimeMillis(),
                    provider = "openai",
                    feature = "whisper",
                    inputUnits = 5,
                    outputUnits = 10,
                    unitCostUsdMillicents = 10,
                    computedCostUsdMillicents = 50,
                    metadataJson = null,
                ),
            ),
        )
        val viewModel = CostsViewModel(dao)

        compose.setContent {
            CostsScreen(
                onBack = { },
                viewModel = viewModel,
            )
        }

        // Tap "Clear log" → confirm dialog opens. The dialog is the issue
        // body's "Clear log affordance with a confirmation dialog (deletes
        // everything; historical data lost)" gate.
        compose.onNodeWithTag(COSTS_CLEAR_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Clear cost log?").assertIsDisplayed()
    }

    @Test
    fun rendersEmptyStateForFreshInstall() {
        val dao = FakeAiApiCallLogDao(initial = emptyList())
        val viewModel = CostsViewModel(dao)

        compose.setContent {
            CostsScreen(
                onBack = { },
                viewModel = viewModel,
            )
        }

        compose.onNodeWithTag(COSTS_BREAKDOWN_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COSTS_RECENT_EMPTY_TAG).assertIsDisplayed()
    }

    /**
     * In-memory implementation of [AiApiCallLogDao] backed by a single
     * [MutableStateFlow]. Enough for the screen rendering test — Room
     * round-trip is exercised in `core-storage` unit tests.
     */
    private class FakeAiApiCallLogDao(
        initial: List<AiApiCallEntry>,
    ) : AiApiCallLogDao {
        private val flow = MutableStateFlow(initial)

        override fun getAll(): Flow<List<AiApiCallEntry>> = flow

        override suspend fun insert(entry: AiApiCallEntry): Long {
            flow.value = (flow.value + entry).sortedByDescending { it.timestampMillis }
            return entry.id
        }

        override suspend fun deleteAll() {
            flow.value = emptyList()
        }
    }
}
