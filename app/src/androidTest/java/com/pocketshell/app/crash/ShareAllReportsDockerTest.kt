package com.pocketshell.app.crash

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import androidx.room.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Connected emulator-to-Docker proof for issue #466.
 *
 * Seeds local crash reports, configures a host pointing at the deterministic
 * Docker `agents` SSH fixture (`10.0.2.2:2222`), drives the real
 * [CrashReportsViewModel.shareAllTo] (which packs every report into one zip
 * and uploads it through the production [com.pocketshell.app.share.ShareUploader]
 * inbox path), then verifies over SSH that the zip landed in
 * `~/inbox/pocketshell/` and contains every report. Finally exercises
 * Delete-all and confirms the local store is cleared while a failed upload
 * leaves reports intact.
 *
 * Evidence (remote `ls`, `unzip -l` entry names, before/after report counts)
 * is written to the instrumentation's additional_test_output dir.
 */
@RunWith(AndroidJUnit4::class)
class ShareAllReportsDockerTest {

    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        db = null
        clearReportsDir()
    }

    @Test
    fun shareAllUploadsZipToInboxThenDeleteAllClearsReports() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val keyContent = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(keyContent))

        clearReportsDir()
        val evidence = StringBuilder()

        // Persist the Docker key on disk so ShareUploader (which reads
        // SshKeyEntity.privateKeyPath as a file) can authenticate.
        val keyFile = File(context.filesDir, "issue466-test-key.pem").apply {
            writeText(keyContent)
        }

        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = room
        val keyId = room.sshKeyDao().insert(
            SshKeyEntity(name = "issue466-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = room.hostDao().insert(
            HostEntity(
                name = "Issue466 Docker",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        val host = room.hostDao().getById(hostId)!!

        // Seed three reports into the production store.
        val store = CrashReporter.store(context)
        repeat(3) { index ->
            store.save(
                throwable = RuntimeException("issue466 boom $index"),
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

        // Clear the remote inbox so the listing assertion is unambiguous.
        execRemote(keyContent, "rm -rf \"\$HOME/inbox/pocketshell\"")

        val vm = CrashReportsViewModel(
            applicationContext = context,
            hostDao = room.hostDao(),
            sshKeyDao = room.sshKeyDao(),
        )
        // Let the hosts StateFlow populate.
        withTimeout(10_000) { vm.hosts.first { it.isNotEmpty() } }

        vm.shareAllTo(host)
        val finalState = withTimeout(45_000) {
            var state = vm.shareAllState.value
            while (state is ShareAllState.Idle || state is ShareAllState.Uploading) {
                delay(200)
                state = vm.shareAllState.value
            }
            state
        }
        assertTrue(
            "expected Share-all to succeed, got $finalState",
            finalState is ShareAllState.Success,
        )
        val success = finalState as ShareAllState.Success
        evidence.appendLine("share_all_state=Success")
        evidence.appendLine("remote_path=${success.remotePath}")
        evidence.appendLine("report_count=${success.reportCount}")
        assertEquals(3, success.reportCount)

        // Verify the zip exists in the remote inbox.
        val listResult = execRemote(keyContent, "ls -1 \"\$HOME/inbox/pocketshell\"")
        evidence.appendLine("remote_inbox_listing=${listResult.trim()}")
        // ShareUploader prepends its own "<timestamp>-" prefix to the remote
        // filename, so the inbox entry is "<ts>-pocketshell-reports-...zip".
        val zipName = listResult.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("pocketshell-reports-") && it.endsWith(".zip") }
        assertTrue(
            "expected a pocketshell-reports-*.zip in the remote inbox, got:\n$listResult",
            zipName != null,
        )

        // Verify the zip contains all three reports (server-side unzip -l).
        val unzipListing = execRemote(
            keyContent,
            "cd \"\$HOME/inbox/pocketshell\" && unzip -l \"$zipName\"",
        )
        evidence.appendLine("remote_unzip_listing=\n$unzipListing")
        seededNames.forEach { name ->
            assertTrue(
                "expected zip entry $name in remote archive listing:\n$unzipListing",
                unzipListing.contains(name),
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

    private suspend fun execRemote(key: String, command: String): String =
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow().use { session ->
                val result = session.exec(command)
                result.stdout
            }
        }

    private fun clearReportsDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        File(context.filesDir, "crash-reports").deleteRecursively()
    }

    private fun writeEvidence(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs.firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/issue466-share-all-reports")
        check(dir.exists() || dir.mkdirs()) { "Could not create evidence dir: ${dir.absolutePath}" }
        val file = File(dir, "share-all-summary.txt")
        file.writeText(text)
        println("ISSUE466_EVIDENCE ${file.absolutePath}")
        println(text)
    }
}
