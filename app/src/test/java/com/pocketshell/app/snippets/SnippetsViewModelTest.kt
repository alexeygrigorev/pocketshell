package com.pocketshell.app.snippets

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SnippetsViewModel] against an in-memory Room database.
 *
 * Covers the per-host filtering, CRUD, and Room round-trip required by
 * issue #17's acceptance criteria:
 *
 *  - Adding a snippet writes a row tied to the bound host id.
 *  - The per-host flow only emits snippets for the bound host.
 *  - Updating + deleting cascade through the Room change stream.
 *  - The DAO row survives the ViewModel being torn down and rebuilt
 *    against the same database — the "restart" stand-in that
 *    demonstrates persistence across app launches without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SnippetsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private var hostId: Long = 0L
    private var otherHostId: Long = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            // Mirror AddEditHostViewModelTest: synchronous Room executors so
            // suspending DAO calls resume on the test dispatcher rather
            // than racing Room's internal background executor.
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()

        // Snippets have an FK on hosts; insert a key + host first so the
        // CASCADE doesn't trip during inserts.
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "test-key", privateKeyPath = "/tmp/k"),
        )
        hostId = db.hostDao().insert(
            HostEntity(
                name = "prod",
                hostname = "prod.example.com",
                port = 22,
                username = "deploy",
                keyId = keyId,
            ),
        )
        otherHostId = db.hostDao().insert(
            HostEntity(
                name = "stage",
                hostname = "stage.example.com",
                port = 22,
                username = "deploy",
                keyId = keyId,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun snippetsFlowIsEmpty_whenNoHostBound() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())
        // Cold-start: bindHost has not been called, so the flow defaults
        // to an empty list. The screen relies on this to render its
        // empty-state without showing stale data from a prior host.
        assertEquals(emptyList<SnippetEntity>(), vm.snippets.value)
    }

    @Test
    fun addSnippet_writesRowAndShowsUpInFlow() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)

        vm.addSnippet(label = "tail logs", body = "kubectl logs -f deploy/api", kind = SnippetKind.Command)

        // Read directly from the DAO so we don't depend on the
        // StateFlow's WhileSubscribed timing.
        val rows = db.snippetDao().getByHostId(hostId).first()
        assertEquals(1, rows.size)
        assertEquals("tail logs", rows[0].label)
        assertEquals("kubectl logs -f deploy/api", rows[0].body)
        assertEquals("command", rows[0].kind)
        assertEquals(hostId, rows[0].hostId)
        assertNull(vm.error.value)
    }

    @Test
    fun addSnippet_trimsLabel() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = "  spaced  ", body = "body", kind = SnippetKind.Prompt)

        val rows = db.snippetDao().getByHostId(hostId).first()
        assertEquals(1, rows.size)
        // Label is trimmed on write; body is preserved verbatim (it may
        // legitimately end in whitespace, e.g. "echo done && ").
        assertEquals("spaced", rows[0].label)
        assertEquals("body", rows[0].body)
        assertEquals("prompt", rows[0].kind)
    }

    @Test
    fun addSnippet_rejectsBlankFields() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)

        vm.addSnippet(label = "", body = "x", kind = SnippetKind.Command)
        assertNotNull(vm.error.value)
        assertEquals(0, db.snippetDao().getByHostId(hostId).first().size)

        vm.clearError()
        vm.addSnippet(label = "x", body = "  ", kind = SnippetKind.Command)
        assertNotNull(vm.error.value)
        assertEquals(0, db.snippetDao().getByHostId(hostId).first().size)
    }

    @Test
    fun addSnippet_withoutBoundHost_surfacesError() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())
        vm.addSnippet(label = "x", body = "y", kind = SnippetKind.Command)
        assertNotNull(vm.error.value)
        // Nothing was written — bindHost was never called.
        assertEquals(0, db.snippetDao().getByHostId(hostId).first().size)
    }

    @Test
    fun perHostFiltering_onlyReturnsRowsForBoundHost() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())

        vm.bindHost(hostId)
        vm.addSnippet(label = "prod-only", body = "x", kind = SnippetKind.Command)
        vm.bindHost(otherHostId)
        vm.addSnippet(label = "stage-only", body = "y", kind = SnippetKind.Command)

        // Snippets for hostId should NOT include the stage row.
        val prodRows = db.snippetDao().getByHostId(hostId).first()
        assertEquals(1, prodRows.size)
        assertEquals("prod-only", prodRows[0].label)

        val stageRows = db.snippetDao().getByHostId(otherHostId).first()
        assertEquals(1, stageRows.size)
        assertEquals("stage-only", stageRows[0].label)
    }

    @Test
    fun updateSnippet_persistsChanges() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = "orig", body = "echo orig", kind = SnippetKind.Command)
        val written = db.snippetDao().getByHostId(hostId).first().single()

        vm.updateSnippet(written.copy(label = "renamed", body = "echo new"))

        val updated = db.snippetDao().getByHostId(hostId).first().single()
        assertEquals("renamed", updated.label)
        assertEquals("echo new", updated.body)
        assertEquals(written.id, updated.id)
    }

    @Test
    fun updateSnippet_rejectsBlankLabel() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = "ok", body = "ok body", kind = SnippetKind.Command)
        val written = db.snippetDao().getByHostId(hostId).first().single()

        vm.updateSnippet(written.copy(label = ""))

        assertNotNull(vm.error.value)
        // Database row stayed at its pre-edit value.
        val unchanged = db.snippetDao().getByHostId(hostId).first().single()
        assertEquals("ok", unchanged.label)
    }

    @Test
    fun deleteSnippet_removesRow() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = "to-delete", body = "x", kind = SnippetKind.Command)
        val written = db.snippetDao().getByHostId(hostId).first().single()

        vm.deleteSnippet(written)
        assertEquals(0, db.snippetDao().getByHostId(hostId).first().size)
    }

    @Test
    fun snippets_survive_appRestart() = runTest {
        // First "launch": create a ViewModel, write a snippet.
        val firstVm = SnippetsViewModel(db.snippetDao())
        firstVm.bindHost(hostId)
        firstVm.addSnippet(
            label = "long-lived",
            body = "tmux ls",
            kind = SnippetKind.Command,
        )
        assertEquals(1, db.snippetDao().getByHostId(hostId).first().size)

        // Second "launch": new ViewModel against the same DB. Without
        // any explicit re-insert, the row must still be there — that is
        // the Room round-trip the acceptance criteria asks us to prove.
        val secondVm = SnippetsViewModel(db.snippetDao())
        secondVm.bindHost(hostId)
        val rows = db.snippetDao().getByHostId(hostId).first()
        assertEquals(1, rows.size)
        assertEquals("long-lived", rows[0].label)
        assertEquals("tmux ls", rows[0].body)
        assertEquals("command", rows[0].kind)
    }

    @Test
    fun clearError_resetsBanner() = runTest {
        val vm = SnippetsViewModel(db.snippetDao())
        vm.addSnippet(label = "x", body = "y", kind = SnippetKind.Command) // no host -> error
        assertNotNull(vm.error.value)
        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun snippetKind_fromStorage_roundTripsKnownValues() {
        assertEquals(SnippetKind.Command, SnippetKind.fromStorage("command"))
        assertEquals(SnippetKind.Prompt, SnippetKind.fromStorage("prompt"))
        // Case-insensitive — defensive against legacy / hand-edited rows.
        assertEquals(SnippetKind.Prompt, SnippetKind.fromStorage("PROMPT"))
        // Unknown -> default to Command so the user can edit the row.
        assertEquals(SnippetKind.Command, SnippetKind.fromStorage("mystery"))
    }

    @Test
    fun snippets_persistThroughHostDeletion_cascade() = runTest {
        // Sanity check the FK cascade declared on SnippetEntity:
        // deleting the host wipes its snippets. The screen never invokes
        // this path itself, but the host-deletion flow does, so the
        // behaviour must be observable.
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = "doomed", body = "x", kind = SnippetKind.Command)
        assertTrue(db.snippetDao().getByHostId(hostId).first().isNotEmpty())

        db.hostDao().deleteById(hostId)
        // Snippet row went with the host via the CASCADE.
        assertEquals(0, db.snippetDao().getByHostId(hostId).first().size)
    }
}
