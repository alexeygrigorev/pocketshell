package com.pocketshell.app.crash

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.share.ShareItemUploader
import com.pocketshell.app.share.ShareTarget
import com.pocketshell.app.share.ShareableItem
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * Unit tests for [CrashReportsViewModel] (issue #466): zip-packing every
 * report into one archive, uploading via the injected uploader, deleting
 * all reports, and preserving reports when the upload fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CrashReportsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearReportsDir()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        clearReportsDir()
    }

    @Test
    fun shareAllPacksEveryReportIntoOneZipAndUploads() = runTest {
        seedReports(2)
        val host = seededHost(1, "hetzner")
        val uploader = RecordingUploader(Result.success("~/inbox/pocketshell/bundle.zip"))
        val vm = newVm(uploader)
        advanceUntilIdle()

        vm.shareAllTo(host)
        advanceUntilIdle()

        val item = uploader.captured as ShareableItem.FileItem
        val entries = ZipFile(item.file.takeIf { it.exists() } ?: uploader.zipCopy).use { zip ->
            zip.entries().toList().map { it.name }
        }
        assertEquals(2, entries.size)
        val state = vm.shareAllState.value
        assertTrue(state is ShareAllState.Success)
        assertEquals(2, (state as ShareAllState.Success).reportCount)
        // Reports are preserved after a successful share (delete is explicit).
        assertEquals(2, vm.reports.value.size)
    }

    @Test
    fun shareAllPreservesReportsWhenUploadFails() = runTest {
        seedReports(3)
        val host = seededHost(1, "hetzner")
        val uploader = RecordingUploader(Result.failure(SshException("Connection refused")))
        val vm = newVm(uploader)
        advanceUntilIdle()

        vm.shareAllTo(host)
        advanceUntilIdle()

        val state = vm.shareAllState.value
        assertTrue(state is ShareAllState.Failed)
        // Critically: a failed upload must NOT clear the reports.
        assertEquals(3, vm.reports.value.size)
    }

    @Test
    fun deleteAllClearsTheStore() = runTest {
        seedReports(3)
        val uploader = RecordingUploader(Result.success("x"))
        val vm = newVm(uploader)
        advanceUntilIdle()
        assertEquals(3, vm.reports.value.size)

        vm.deleteAll()

        assertEquals(0, vm.reports.value.size)
        assertNull(uploader.captured)
    }

    @Test
    fun shareAllWithNoReportsDoesNothing() = runTest {
        val host = seededHost(1, "hetzner")
        val uploader = RecordingUploader(Result.success("x"))
        val vm = newVm(uploader)
        advanceUntilIdle()

        vm.shareAllTo(host)
        advanceUntilIdle()

        assertNull(uploader.captured)
        assertTrue(vm.shareAllState.value is ShareAllState.Failed)
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

    private suspend fun seededHost(id: Long, name: String): HostEntity {
        val keyFile = File.createTempFile("key-$id", ".pem", context.cacheDir).apply {
            writeText("dummy")
        }
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "key-$id", privateKeyPath = keyFile.absolutePath),
        )
        val rowId = db.hostDao().insert(
            HostEntity(
                name = name,
                hostname = "$name.example",
                port = 22,
                username = "alex",
                keyId = keyId,
            ),
        )
        return db.hostDao().getById(rowId)!!
    }

    private fun newVm(uploader: ShareItemUploader): CrashReportsViewModel {
        val vm = CrashReportsViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
        )
        vm.uploader = uploader
        return vm
    }

    /**
     * Captures the uploaded [ShareableItem] and snapshots the zip bytes so
     * the assertion can inspect the archive even after the ViewModel deletes
     * the temp file post-upload.
     */
    private inner class RecordingUploader(
        private val result: Result<String>,
    ) : ShareItemUploader {
        var captured: ShareableItem? = null
        lateinit var zipCopy: File

        override suspend fun upload(
            host: HostEntity,
            keyEntity: SshKeyEntity,
            item: ShareableItem,
            target: ShareTarget,
        ): Result<String> {
            captured = item
            if (item is ShareableItem.FileItem) {
                zipCopy = File.createTempFile("zipcopy", ".zip", context.cacheDir)
                item.file.copyTo(zipCopy, overwrite = true)
            }
            return result
        }
    }
}
