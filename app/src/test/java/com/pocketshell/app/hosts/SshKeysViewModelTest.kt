package com.pocketshell.app.hosts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [SshKeysViewModel] against an in-memory Room database.
 *
 * Covers:
 * - `generateKey` writes a private key PEM to disk and inserts a row.
 * - `deleteKey` removes the row and the on-disk file.
 *
 * The "import key" path is exercised indirectly — it shares the
 * `persistKey` write path with `generateKey`. The SAF cursor + content
 * resolver interaction is left to instrumentation tests; here we keep
 * the surface small and focused on persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SshKeysViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            // Pin Room's executors so suspending DAO calls resume on the
            // test dispatcher rather than racing a background executor.
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        // Make sure each test starts from a clean filesDir/ssh-keys.
        File(context.filesDir, "ssh-keys").deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // These tests run in real time (not `runTest` / virtual time) because
    // [SshKeysViewModel] uses `withContext(Dispatchers.IO)` for disk +
    // crypto operations, which doesn't compose with `runTest`'s virtual
    // scheduler. The `MainDispatcherRule` still pins viewModelScope's
    // entry-point dispatcher so the launches start on the test thread.

    @Test
    fun generateKey_writesFileAndDbRow() = runBlocking {
        val vm = SshKeysViewModel(db.sshKeyDao())
        vm.generateKey(context)

        // Real-time wait for the row to appear — RSA-3072 generation is
        // a few hundred ms on the host JVM.
        val rows = withTimeout(15_000) {
            vm.keys.first { it.isNotEmpty() }
        }
        assertEquals(1, rows.size)
        val key = rows[0]
        assertTrue("name starts with generated-", key.name.startsWith("generated-"))
        val file = File(key.privateKeyPath)
        assertTrue("private key file exists on disk", file.exists())
        val pem = file.readText()
        assertTrue("PEM body has BEGIN header", pem.contains("-----BEGIN PRIVATE KEY-----"))
        assertTrue("PEM body has END header", pem.contains("-----END PRIVATE KEY-----"))
    }

    @Test
    fun deleteKey_removesRowAndFile() = runBlocking {
        val vm = SshKeysViewModel(db.sshKeyDao())

        // Seed a key directly (skip generate's compute cost).
        val keyDir = File(context.filesDir, "ssh-keys").apply { mkdirs() }
        val keyFile = File(keyDir, "seed-key").apply { writeText("not-a-real-key") }
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "seed-key", privateKeyPath = keyFile.absolutePath),
        )
        val seeded = db.sshKeyDao().getById(keyId)!!

        vm.deleteKey(seeded)

        withTimeout(5_000) {
            vm.keys.first { it.isEmpty() }
        }
        assertEquals(0, db.sshKeyDao().getAll().first().size)
        assertTrue("on-disk file removed", !keyFile.exists())
    }

    /**
     * Issue #38 item 4: a successful operation must drop any stale
     * `error` left over from a previous failure so the banner does not
     * persist across the recovery. We prime the error via the
     * `importKey` SAF path with an unreadable URI (returns `null`
     * stream → friendly error message), then call `generateKey` which
     * must clear `error` back to `null` on success.
     */
    @Test
    fun generateKey_clearsLingeringError() = runBlocking {
        val vm = SshKeysViewModel(db.sshKeyDao())

        // Prime: a SAF URI the resolver cannot open → ViewModel sets
        // the user-facing "Could not open the selected file" error.
        vm.importKey(context, android.net.Uri.parse("content://no.such.provider/x"))
        withTimeout(5_000) { vm.error.first { it != null } }
        assertTrue("error must be primed", vm.error.value != null)

        // Act: a successful generate runs the persist path → on
        // success the new code clears `_error.value = null`.
        vm.generateKey(context)
        withTimeout(15_000) { vm.keys.first { it.isNotEmpty() } }
        assertEquals(null, vm.error.value)
    }

    /**
     * Issue #38 item 5: file delete must happen BEFORE the DB row
     * delete. If the file delete fails, the row must stay so the user
     * can retry. We force a failure by handing `deleteKey` a path
     * inside a directory we mark read-only, then assert the row is
     * still present afterward.
     */
    @Test
    fun deleteKey_keepsRow_whenFileDeleteFails() = runBlocking {
        val vm = SshKeysViewModel(db.sshKeyDao())

        // Seed a key whose private-key path points at a file inside a
        // directory we'll mark non-writable. `File.delete()` returns
        // false in that case — our new code converts that to an
        // IOException, which aborts before the DAO delete.
        val lockedDir = File(context.filesDir, "locked-keys").apply {
            mkdirs()
        }
        val keyFile = File(lockedDir, "victim").apply {
            writeText("not-a-real-key")
        }
        // Strip the parent directory's write bit so `File.delete()`
        // returns false. On the host JVM this is honoured by both Unix
        // and Robolectric's fs shim.
        val originallyWritable = lockedDir.canWrite()
        assertTrue("setup precondition", lockedDir.setWritable(false))
        try {
            val keyId = db.sshKeyDao().insert(
                SshKeyEntity(name = "victim", privateKeyPath = keyFile.absolutePath),
            )
            val seeded = db.sshKeyDao().getById(keyId)!!

            vm.deleteKey(seeded)

            // Wait for the error to surface (the delete launches into
            // viewModelScope; the failure flows through the catch).
            withTimeout(5_000) {
                vm.error.first { it != null }
            }

            // Critical assertion: the DB row is intact because the
            // file-delete failure aborted before `sshKeyDao.delete()`.
            assertEquals(1, db.sshKeyDao().getAll().first().size)
        } finally {
            // Restore the writable bit so tearDown / other tests work.
            lockedDir.setWritable(originallyWritable || true)
        }
    }
}
