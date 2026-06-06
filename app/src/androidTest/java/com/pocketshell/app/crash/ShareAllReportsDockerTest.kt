package com.pocketshell.app.crash

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Connected emulator proof for issue #575.
 *
 * Seeds local crash reports, drives the real [CrashReportsViewModel.shareAll]
 * path that prepares one local zip for Android's share sheet, then verifies
 * the zip contains every report. Finally exercises Delete-all and confirms
 * the local store is cleared.
 *
 * Evidence (archive path, zip entry names, before/after report counts) is
 * written to the instrumentation's additional_test_output dir.
 */
@RunWith(AndroidJUnit4::class)
class ShareAllReportsDockerTest {

    @After
    fun tearDown() {
        clearReportsDir()
        clearArchivesDir()
    }

    @Test
    fun shareAllPreparesZipForShareSheetThenDeleteAllClearsReports() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        clearReportsDir()
        clearArchivesDir()
        val evidence = StringBuilder()

        // Seed three reports into the production store.
        val store = CrashReporter.store(context)
        repeat(3) { index ->
            store.save(
                throwable = RuntimeException("issue575 boom $index"),
                threadName = "main",
                metadata = CrashReportMetadata(
                    appVersion = "test",
                    androidRelease = "15",
                    sdkInt = 35,
                    device = "emulator",
                ),
            )
        }
        val seededNames = store.list().map { it.file.name }.sorted()
        evidence.appendLine("seeded_report_files=${seededNames.size}")
        seededNames.forEach { evidence.appendLine("  report=$it") }
        assertEquals(3, seededNames.size)

        val vm = CrashReportsViewModel(applicationContext = context)
        vm.shareAll()
        val finalState = withTimeout(45_000) {
            var state = vm.shareAllState.value
            while (state is ShareAllState.Idle || state is ShareAllState.Preparing) {
                delay(200)
                state = vm.shareAllState.value
            }
            state
        }
        assertTrue(
            "expected Share-all to prepare an archive, got $finalState",
            finalState is ShareAllState.Prepared,
        )
        val prepared = finalState as ShareAllState.Prepared
        evidence.appendLine("share_all_state=Prepared")
        evidence.appendLine("archive=${prepared.archive.absolutePath}")
        evidence.appendLine("report_count=${prepared.reportCount}")
        assertEquals(3, prepared.reportCount)
        assertTrue("expected archive file to exist", prepared.archive.isFile)

        val zipEntries = ZipFile(prepared.archive).use { zip ->
            zip.entries().toList().map { it.name }.sorted()
        }
        evidence.appendLine("zip_entries=${zipEntries.joinToString()}")
        seededNames.forEach { name ->
            assertTrue(
                "expected zip entry $name in local archive entries:\n$zipEntries",
                zipEntries.contains(name),
            )
        }

        // Reports preserved after a successful share (delete is explicit).
        vm.reload()
        assertEquals(
            "Share-all must NOT delete reports",
            3,
            vm.reports.value.size,
        )
        evidence.appendLine("reports_after_share=${vm.reports.value.size}")

        // Delete-all clears the local store.
        vm.deleteAll()
        assertEquals(
            "Delete-all must clear every local report",
            0,
            vm.reports.value.size,
        )
        evidence.appendLine("reports_after_delete_all=${vm.reports.value.size}")
        assertEquals(0, store.list().size)

        writeEvidence(evidence.toString())
    }

    private fun clearReportsDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        File(context.filesDir, "crash-reports").deleteRecursively()
    }

    private fun clearArchivesDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        File(context.cacheDir, REPORT_ARCHIVES_CACHE_DIR).deleteRecursively()
    }

    private fun writeEvidence(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue575-share-all-reports")
        check(dir.exists() || dir.mkdirs()) { "Could not create evidence dir: ${dir.absolutePath}" }
        val file = File(dir, "share-all-summary.txt")
        file.writeText(text)
        println("ISSUE575_EVIDENCE ${file.absolutePath}")
        println(text)
    }
}
