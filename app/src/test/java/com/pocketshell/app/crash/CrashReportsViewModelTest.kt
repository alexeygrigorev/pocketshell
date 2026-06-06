package com.pocketshell.app.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Unit tests for [CrashReportsViewModel]: zip-packing every report into one
 * local archive for Android's share sheet, deleting all reports, and
 * preserving reports when archive preparation fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CrashReportsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearReportsDir()
        clearArchivesDir()
    }

    @After
    fun tearDown() {
        clearReportsDir()
        clearArchivesDir()
    }

    @Test
    fun shareAllPacksEveryReportIntoOneLocalZip() = runTest {
        seedReports(2)
        val vm = newVm()
        advanceUntilIdle()

        vm.shareAll()
        advanceUntilIdle()

        val state = vm.shareAllState.value
        assertTrue(state is ShareAllState.Prepared)
        state as ShareAllState.Prepared
        assertEquals(2, state.reportCount)
        assertTrue(state.archive.exists())
        assertEquals(REPORT_ARCHIVES_CACHE_DIR, state.archive.parentFile?.name)

        val entries = ZipFile(state.archive).use { zip ->
            zip.entries().toList().map { it.name }
        }
        assertEquals(2, entries.size)
        // Reports are preserved after a successful share (delete is explicit).
        assertEquals(2, vm.reports.value.size)
    }

    @Test
    fun shareAllPreservesReportsWhenArchivePreparationFails() = runTest {
        seedReports(3)
        val vm = newVm()
        advanceUntilIdle()

        vm.reports.value.forEach { it.file.delete() }
        vm.shareAll()
        advanceUntilIdle()

        val state = vm.shareAllState.value
        assertTrue(state is ShareAllState.Failed)
        // Critically: a failed preparation must NOT clear the report list.
        assertEquals(3, vm.reports.value.size)
    }

    @Test
    fun deleteAllClearsTheStore() = runTest {
        seedReports(3)
        val vm = newVm()
        advanceUntilIdle()
        assertEquals(3, vm.reports.value.size)

        vm.deleteAll()

        assertEquals(0, vm.reports.value.size)
    }

    @Test
    fun shareAllWithNoReportsDoesNothing() = runTest {
        val vm = newVm()
        advanceUntilIdle()

        vm.shareAll()
        advanceUntilIdle()

        assertTrue(vm.shareAllState.value is ShareAllState.Idle)
    }

    @Test
    fun markShareAllLaunchedClearsPreparedState() = runTest {
        seedReports(1)
        val vm = newVm()

        vm.shareAll()
        advanceUntilIdle()
        assertTrue(vm.shareAllState.value is ShareAllState.Prepared)

        vm.markShareAllLaunched()

        assertTrue(vm.shareAllState.value is ShareAllState.Idle)
        assertEquals(1, vm.reports.value.size)
    }

    @Test
    fun shareAllPrunesStalePreparedArchivesBeforeWritingNewOne() = runTest {
        seedReports(1)
        val staleArchive = File(archivesDir(), "pocketshell-reports-old.zip").apply {
            parentFile?.mkdirs()
            writeText("old")
            setLastModified(0L)
        }
        val recentArchive = File(archivesDir(), "pocketshell-reports-recent.zip").apply {
            writeText("recent")
        }
        val unrelated = File(archivesDir(), "keep-me.txt").apply {
            writeText("keep")
        }
        val vm = newVm()

        vm.shareAll()
        advanceUntilIdle()

        val state = vm.shareAllState.value
        assertTrue(state is ShareAllState.Prepared)
        assertFalse(staleArchive.exists())
        assertTrue(recentArchive.exists())
        assertTrue(unrelated.exists())
    }

    private fun seedReports(count: Int) {
        val store = CrashReporter.store(context)
        repeat(count) { index ->
            store.save(
                throwable = RuntimeException("boom $index"),
                threadName = "main",
                metadata = CrashReportMetadata(
                    appVersion = "0.1.0",
                    androidRelease = "15",
                    sdkInt = 35,
                    device = "test",
                ),
            )
        }
    }

    private fun clearReportsDir() {
        File(context.filesDir, "crash-reports").deleteRecursively()
    }

    private fun clearArchivesDir() {
        archivesDir().deleteRecursively()
    }

    private fun archivesDir(): File =
        File(context.cacheDir, REPORT_ARCHIVES_CACHE_DIR)

    private fun newVm(): CrashReportsViewModel =
        CrashReportsViewModel(applicationContext = context)
}
