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
    fun addSnippet_nullLabel_storesNullForDerivation() = runTest {
        // Issue #190: the default add flow passes label = null so the
        // UI derives the label from the body's first line at read time.
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = null, body = "echo hello", kind = SnippetKind.Command)

        val rows = db.snippetDao().getByHostId(hostId).first()
        assertEquals(1, rows.size)
        assertNull(rows[0].label)
        assertEquals("echo hello", rows[0].body)
        // The derived-label helper picks up the first line.
        assertEquals("echo hello", rows[0].displayLabel())
        assertNull(vm.error.value)
    }

    @Test
    fun addSnippet_blankLabel_storesNull() = runTest {
        // Issue #190: a blank or whitespace-only label is treated as no
        // override (so the UI falls back to derivation) rather than an
        // error. This is the path the rename dialog uses to clear an
        // existing override.
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = "   ", body = "echo hello", kind = SnippetKind.Command)

        val rows = db.snippetDao().getByHostId(hostId).first()
        assertEquals(1, rows.size)
        assertNull(rows[0].label)
        assertNull(vm.error.value)
    }

    @Test
    fun addSnippet_rejectsBlankBody() = runTest {
        // Issue #190: blank labels are allowed (auto-derive), blank
        // bodies are not (there is nothing to send).
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)

        vm.addSnippet(label = "x", body = "", kind = SnippetKind.Command)
        assertNotNull(vm.error.value)
        assertEquals(0, db.snippetDao().getByHostId(hostId).first().size)

        vm.clearError()
        vm.addSnippet(label = "x", body = "  \n  ", kind = SnippetKind.Command)
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
    fun updateSnippet_blankLabel_clearsOverride() = runTest {
        // Issue #190: the Edit dialog clears the explicit label by
        // submitting an empty string. The ViewModel must normalise that
        // to a null override (deriving the label at read time) rather
        // than reject the update.
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = "ok", body = "ok body", kind = SnippetKind.Command)
        val written = db.snippetDao().getByHostId(hostId).first().single()

        vm.updateSnippet(written.copy(label = ""))

        assertNull(vm.error.value)
        val updated = db.snippetDao().getByHostId(hostId).first().single()
        assertNull(updated.label)
        // The derived label is the body's first line.
        assertEquals("ok body", updated.displayLabel())
    }

    @Test
    fun updateSnippet_rejectsBlankBody() = runTest {
        // Issue #190: body remains a required field even though the
        // label may be cleared.
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = "ok", body = "ok body", kind = SnippetKind.Command)
        val written = db.snippetDao().getByHostId(hostId).first().single()

        vm.updateSnippet(written.copy(body = ""))

        assertNotNull(vm.error.value)
        val unchanged = db.snippetDao().getByHostId(hostId).first().single()
        assertEquals("ok body", unchanged.body)
    }

    @Test
    fun renameSnippet_setsExplicitOverride() = runTest {
        // Issue #190: the rename affordance must persist a user-chosen
        // label and override the derived one.
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = null, body = "echo first\necho second", kind = SnippetKind.Command)
        val written = db.snippetDao().getByHostId(hostId).first().single()
        assertNull(written.label)
        assertEquals("echo first", written.displayLabel())

        vm.renameSnippet(written, "boot script")

        val renamed = db.snippetDao().getByHostId(hostId).first().single()
        assertEquals("boot script", renamed.label)
        assertEquals("boot script", renamed.displayLabel())
        assertEquals("echo first\necho second", renamed.body)
    }

    @Test
    fun renameSnippet_blankInput_clearsOverride() = runTest {
        // Issue #190: clearing the rename field reverts to the derived
        // label, mirroring the Edit dialog's empty-label behaviour.
        val vm = SnippetsViewModel(db.snippetDao())
        vm.bindHost(hostId)
        vm.addSnippet(label = "manual", body = "echo automatic", kind = SnippetKind.Command)
        val written = db.snippetDao().getByHostId(hostId).first().single()

        vm.renameSnippet(written, "   ")

        val reverted = db.snippetDao().getByHostId(hostId).first().single()
        assertNull(reverted.label)
        assertEquals("echo automatic", reverted.displayLabel())
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
    fun deriveSnippetLabel_explicitOverrideWins() {
        // Issue #190: a non-blank label wins over derivation, even when
        // the body has its own first line.
        assertEquals("My label", deriveSnippetLabel("My label", "echo body"))
    }

    @Test
    fun deriveSnippetLabel_trimsExplicitOverride() {
        // Whitespace-only overrides collapse to derivation. Non-blank
        // overrides round-trip with surrounding whitespace stripped so
        // the picker never renders awkward indent.
        assertEquals("echo body", deriveSnippetLabel("   ", "echo body"))
        assertEquals("My label", deriveSnippetLabel("  My label  ", "echo body"))
    }

    @Test
    fun deriveSnippetLabel_shortFirstLineRoundTrips() {
        // Issue #190 rule: short lines (<= 20 chars) keep the full line
        // as the label, no truncation, no ellipsis.
        assertEquals("ls -la", deriveSnippetLabel(null, "ls -la"))
        assertEquals(
            "kubectl get nodes",
            deriveSnippetLabel(null, "kubectl get nodes"),
        )
    }

    @Test
    fun deriveSnippetLabel_truncatesLongFirstLine() {
        // First line over the 40-char cap is truncated with an ellipsis.
        // Pick a deterministic 50-char body so the test does not lean on
        // exact constants.
        val longLine = "kubectl logs --since=24h deploy/api-gateway -n production"
        val derived = deriveSnippetLabel(null, longLine)
        assertEquals(41, derived.length) // 40 chars + ellipsis (1 codepoint)
        assertTrue(derived.endsWith("…"))
        assertTrue(longLine.startsWith(derived.dropLast(1).trimEnd()))
    }

    @Test
    fun deriveSnippetLabel_skipsLeadingBlankLines() {
        // The first NON-EMPTY line wins so a body that starts with a
        // blank line (a copy-paste artefact) still gets a useful label.
        assertEquals(
            "echo hello",
            deriveSnippetLabel(null, "\n  \n  echo hello\necho world"),
        )
    }

    @Test
    fun deriveSnippetLabel_fallsBackForEmptyBody() {
        // Defensive: ViewModel-blocks blank bodies on insert, but legacy
        // rows might still surface here. We never want to render the
        // empty string as a row label.
        assertEquals("(empty snippet)", deriveSnippetLabel(null, ""))
        assertEquals("(empty snippet)", deriveSnippetLabel(null, "   \n  \n"))
    }

    @Test
    fun hasExplicitLabel_distinguishesOverrides() {
        // Picker's secondary-text rule keys off this — null/blank means
        // derived label, non-blank means user picked the wording.
        val derived = SnippetEntity(id = 1, hostId = hostId, label = null, body = "echo", kind = "command")
        val blank = SnippetEntity(id = 2, hostId = hostId, label = "   ", body = "echo", kind = "command")
        val explicit = SnippetEntity(id = 3, hostId = hostId, label = "named", body = "echo", kind = "command")
        assertEquals(false, derived.hasExplicitLabel())
        assertEquals(false, blank.hasExplicitLabel())
        assertEquals(true, explicit.hasExplicitLabel())
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
