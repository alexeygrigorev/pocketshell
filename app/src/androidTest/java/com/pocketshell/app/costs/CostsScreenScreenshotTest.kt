package com.pocketshell.app.costs

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.dao.AiApiCallLogDao
import com.pocketshell.core.storage.entity.AiApiCallEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Captures a screenshot of the rendered AI Costs screen for the issue
 * #181 status comment. Lives separately from the functional test so the
 * implementer's status comment can attach a single deterministic
 * artifact without having to chase down which test happened to leave a
 * good frame on disk.
 */
class CostsScreenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureRenderedCostsScreen() {
        val now = System.currentTimeMillis()
        val dao = FakeAiApiCallLogDao(
            initial = listOf(
                AiApiCallEntry(
                    id = 1L,
                    timestampMillis = now - 60_000L,
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
                    timestampMillis = now - 120_000L,
                    provider = "openai",
                    feature = "whisper",
                    inputUnits = 3,
                    outputUnits = 20,
                    unitCostUsdMillicents = 10,
                    computedCostUsdMillicents = 30,
                    metadataJson = null,
                ),
                AiApiCallEntry(
                    id = 3L,
                    timestampMillis = now - 3_600_000L,
                    provider = "openai",
                    feature = "whisper",
                    inputUnits = 47,
                    outputUnits = 220,
                    unitCostUsdMillicents = 10,
                    computedCostUsdMillicents = 470,
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

        compose.onNodeWithTag(COSTS_TITLE_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)

        val dir = ensureArtifactDir()
        captureFullDevice(File(dir, "ai-costs-screen.png"))
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/ai-costs-screenshot")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create ai-costs screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write ai-costs screenshot: ${file.absolutePath}"
                }
            }
            println("AI_COSTS_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private class FakeAiApiCallLogDao(
        initial: List<AiApiCallEntry>,
    ) : AiApiCallLogDao {
        private val flow = MutableStateFlow(initial)

        override fun getAll(): Flow<List<AiApiCallEntry>> = flow

        override fun getSince(sinceMillis: Long): Flow<List<AiApiCallEntry>> = flow

        override suspend fun insert(entry: AiApiCallEntry): Long {
            flow.value = (flow.value + entry).sortedByDescending { it.timestampMillis }
            return entry.id
        }

        override suspend fun deleteAll() {
            flow.value = emptyList()
        }
    }
}
