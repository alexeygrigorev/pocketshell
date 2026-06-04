package com.pocketshell.app.share

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.app.tmux.TMUX_PASTE_BODY_CHUNK_BYTES
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

/**
 * Unit tests for [ShareViewModel] focused on the issue #193 paste-into-
 * session wiring.
 *
 * - `hasAttachedSession` must reflect [ActiveTmuxClients.clients] rather
 *   than the previous hardcoded `false`.
 * - `hostsWithAttachedSession` must surface the per-host attached set so
 *   the picker can filter to hosts that actually have a live tmux
 *   client.
 * - `pasteIntoSession` must drive the [FakeTmuxClient] for the picked
 *   host (sending `display-message` followed by `send-keys -l`), and
 *   must surface a clear "no active session on host X" failure when
 *   the user picks a host that has no registered client.
 *
 * Robolectric runs in plain JVM with an Android Context but no
 * emulator — Room runs in-memory so the suite stays fast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShareViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun hasAttachedSessionIsFalseWhenRegistryEmpty() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)

        // Initial `WhileSubscribed` StateFlow starts at the seed value;
        // subscribing via `first()` triggers the upstream so the
        // mapped value can settle.
        assertFalse(vm.hasAttachedSession.first { it == false })
        assertTrue(vm.hostsWithAttachedSession.first().isEmpty())
    }

    @Test
    fun hasAttachedSessionIsTrueWhenRegistryHasEntry() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        registerClient(registry, hostId = 1L, hostName = "hetzner")
        advanceUntilIdle()

        assertTrue(vm.hasAttachedSession.first { it })
        assertTrue(1L in vm.hostsWithAttachedSession.first { it.contains(1L) })
    }

    @Test
    fun pasteIntoSessionWithNoEntrySurfacesFailedState() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = host(id = 7L, name = "hetzner")
        vm.setItem(ShareableItem.TextItem(text = "hello", displayName = "note"))

        vm.pasteIntoSession(host)
        advanceUntilIdle()

        val state = vm.uploadState.first { it is UploadState.Failed }
        val failed = state as UploadState.Failed
        assertEquals("hetzner", failed.hostName)
        assertTrue(
            "expected message to mention host name and inbox fallback, got: ${failed.message}",
            failed.message.contains("hetzner") &&
                failed.message.contains("save to inbox", ignoreCase = true),
        )
    }

    @Test
    fun pasteIntoSessionSendsKeysLiteralToActivePane() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = host(id = 11L, name = "gpu-box")
        val client = FakeTmuxClient().apply {
            // Response queue order matches sendCommand call order:
            //   1) display-message -p '#{pane_id}'  -> "%5"
            //   2) send-keys -l ...                 -> success
            responses.addLast(
                CommandResponse(number = 0L, output = listOf("%5"), isError = false),
            )
            responses.addLast(
                CommandResponse(number = 0L, output = emptyList(), isError = false),
            )
        }
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = "/tmp/key",
            client = client,
        )
        vm.setItem(ShareableItem.TextItem(text = "echo it's working", displayName = "snippet"))

        vm.pasteIntoSession(host)
        advanceUntilIdle()

        val state = vm.uploadState.first { it is UploadState.Success }
        val success = state as UploadState.Success
        assertEquals("gpu-box", success.hostName)
        assertEquals(
            "expected exactly two tmux commands (display-message + send-keys), got ${client.sentCommands}",
            2,
            client.sentCommands.size,
        )
        assertTrue(
            "expected first command to query active pane id, got '${client.sentCommands[0]}'",
            client.sentCommands[0].startsWith("display-message -p"),
        )
        // The literal text contains a single quote — we expect the tmux
        // single-quote escape pattern `'\''`.
        val sendKeys = client.sentCommands[1]
        assertTrue(
            "expected send-keys -l targeting active pane %5, got '$sendKeys'",
            sendKeys.startsWith("send-keys -l -t %5 --"),
        )
        assertTrue(
            "expected send-keys to carry the escaped payload, got '$sendKeys'",
            sendKeys.contains("echo it'\\''s working"),
        )
    }

    @Test
    fun pasteIntoSessionFallsBackToUntargetedSendKeysWhenPaneIdUnresolvable() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = host(id = 12L, name = "gpu-box-2")
        val client = FakeTmuxClient().apply {
            // display-message returns an error -> caller falls back to
            // an un-targeted send-keys.
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf("no current target"),
                    isError = true,
                ),
            )
            responses.addLast(
                CommandResponse(number = 0L, output = emptyList(), isError = false),
            )
        }
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = "/tmp/key",
            client = client,
        )
        vm.setItem(ShareableItem.TextItem(text = "fallback", displayName = "note"))

        vm.pasteIntoSession(host)
        advanceUntilIdle()

        val state = vm.uploadState.first { it is UploadState.Success }
        assertTrue(state is UploadState.Success)
        val sendKeys = client.sentCommands[1]
        assertTrue(
            "expected an un-targeted send-keys fallback, got '$sendKeys'",
            sendKeys.startsWith("send-keys -l --") && !sendKeys.contains(" -t "),
        )
    }

    @Test
    fun pasteIntoSessionSurfacesFailureWhenTmuxRejectsSendKeys() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = host(id = 13L, name = "gpu-box-3")
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(number = 0L, output = listOf("%0"), isError = false),
            )
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf("can't find pane"),
                    isError = true,
                ),
            )
        }
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = "/tmp/key",
            client = client,
        )
        vm.setItem(ShareableItem.TextItem(text = "boom", displayName = "note"))

        vm.pasteIntoSession(host)
        advanceUntilIdle()

        val state = vm.uploadState.first { it is UploadState.Failed }
        val failed = state as UploadState.Failed
        assertEquals("gpu-box-3", failed.hostName)
        assertTrue(
            "expected the failure detail to surface tmux's error, got '${failed.message}'",
            failed.message.contains("can't find pane"),
        )
    }

    @Test
    fun pasteIntoSessionUsesBracketedPasteForMultiLineText() = runTest {
        // Issue #209: a share payload with embedded newlines must route
        // through `send-keys -H` with the bracketed-paste markers
        // (\e[200~ ... \e[201~) so Claude Code / readline-based shells
        // treat the whole block as one paste rather than N submissions.
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = host(id = 21L, name = "multi-line-host")
        val client = FakeTmuxClient().apply {
            // display-message resolves the active pane id.
            responses.addLast(
                CommandResponse(number = 0L, output = listOf("%7"), isError = false),
            )
            // send-keys -H start/body/end chunks succeed.
            repeat(3) {
                responses.addLast(
                    CommandResponse(number = 0L, output = emptyList(), isError = false),
                )
            }
        }
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = "/tmp/key",
            client = client,
        )
        vm.setItem(ShareableItem.TextItem(text = "para 1\npara 2", displayName = "note"))

        vm.pasteIntoSession(host)
        advanceUntilIdle()

        val state = vm.uploadState.first { it is UploadState.Success }
        assertTrue(state is UploadState.Success)
        assertEquals(4, client.sentCommands.size)
        val sendKeys = client.sentCommands.drop(1)
        assertTrue(
            "expected all paste chunks to target %7, got '$sendKeys'",
            sendKeys.all { it.startsWith("send-keys -H -t %7 ") },
        )
        assertTrue(
            "expected bracketed-paste start marker, got '$sendKeys'",
            sendKeys.first().endsWith("1b 5b 32 30 30 7e"),
        )
        assertTrue(
            "expected bracketed-paste end marker, got '$sendKeys'",
            sendKeys.last().endsWith("1b 5b 32 30 31 7e"),
        )
        // Exactly one LF inside the body (two paragraphs).
        val hexBody = sendKeys[1].substringAfter("send-keys -H -t %7 ")
        val tokens = hexBody.split(' ')
        assertEquals(
            "expected exactly one LF inside the paste body, got '$hexBody'",
            1,
            tokens.count { it == "0a" },
        )
    }

    @Test
    fun pasteIntoSessionKeepsSingleLineTextOnTheLiteralPath() = runTest {
        // Issue #209 (negative case): a one-liner share must keep the
        // existing `send-keys -l` shape. The single-quote escape (a la
        // `it'\''s`) must still appear inside the literal argument.
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = host(id = 22L, name = "single-line-host")
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(number = 0L, output = listOf("%3"), isError = false),
            )
            responses.addLast(
                CommandResponse(number = 0L, output = emptyList(), isError = false),
            )
        }
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = "/tmp/key",
            client = client,
        )
        vm.setItem(ShareableItem.TextItem(text = "echo it's working", displayName = "note"))

        vm.pasteIntoSession(host)
        advanceUntilIdle()

        val sendKeys = client.sentCommands[1]
        assertTrue(
            "single-line text must not use the -H path, got '$sendKeys'",
            sendKeys.startsWith("send-keys -l -t %3 -- '"),
        )
    }

    @Test
    fun pasteIntoSessionLongSingleLineTextUsesBoundedBracketedChunks() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = host(id = 23L, name = "long-single-line-host")
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(number = 0L, output = listOf("%3"), isError = false),
            )
            repeat(6) {
                responses.addLast(
                    CommandResponse(number = 0L, output = emptyList(), isError = false),
                )
            }
        }
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = "/tmp/key",
            client = client,
        )
        vm.setItem(
            ShareableItem.TextItem(
                text = "s".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 3 + 17),
                displayName = "long-note",
            ),
        )

        vm.pasteIntoSession(host)
        advanceUntilIdle()

        val sendKeys = client.sentCommands.drop(1).filter { it.startsWith("send-keys") }
        assertTrue(
            "large single-line share must not create one unbounded literal command: $sendKeys",
            sendKeys.none { it.startsWith("send-keys -l") },
        )
        assertTrue(
            "expected bracketed-paste chunks for large single-line share, got $sendKeys",
            sendKeys.count { it.startsWith("send-keys -H -t %3 ") } > 3,
        )
        val maxExpectedCommandLength =
            "send-keys -H -t %3 ".length + (TMUX_PASTE_BODY_CHUNK_BYTES * 3 - 1)
        val longest = sendKeys.maxOf { it.length }
        assertTrue(
            "tmux commands must stay bounded; longest=$longest max=$maxExpectedCommandLength commands=$sendKeys",
            longest <= maxExpectedCommandLength,
        )
    }

    @Test
    fun pasteIntoSessionRejectsNonTextStagedItems() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = host(id = 4L, name = "gpu-box-4")
        // Stage a UriItem rather than TextItem; pasteIntoSession is
        // text-only and should refuse to operate on a URI payload.
        vm.setItem(
            ShareableItem.UriItem(
                uri = android.net.Uri.parse("content://x/y"),
                displayName = "thing.bin",
                size = null,
                mimeType = "application/octet-stream",
                fallbackExtension = "bin",
            ),
        )

        vm.pasteIntoSession(host)
        advanceUntilIdle()

        val state = vm.uploadState.first { it is UploadState.Failed }
        assertTrue(state is UploadState.Failed)
    }

    // ---- Issue #258: multi-file share upload loop + partial failure ----

    @Test
    fun startUploadSendsEveryStagedItemToTheHost() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 30L, name = "hetzner")
        val fake = FakeUploader().also { vm.uploader = it }
        vm.setItems(
            listOf(
                uriItem("a.png"),
                uriItem("b.png"),
                uriItem("c.png"),
            ),
        )

        vm.startUpload(host)
        advanceUntilIdle()

        assertEquals(
            "expected the loop to upload all three staged items, got ${fake.uploadedNames}",
            listOf("a.png", "b.png", "c.png"),
            fake.uploadedNames,
        )
        val success = vm.uploadState.first { it is UploadState.Success } as UploadState.Success
        assertEquals(3, success.totalCount)
        assertEquals(3, success.successCount)
        assertEquals(
            "clipboard payload must contain only the target remote paths",
            listOf(
                "~/inbox/pocketshell/a.png",
                "~/inbox/pocketshell/b.png",
                "~/inbox/pocketshell/c.png",
            ).joinToString("\n"),
            success.copyText,
        )
        assertTrue(
            "expected the multi-file success detail to list every remote path, got '${success.remotePath}'",
            success.remotePath.contains("a.png") &&
                success.remotePath.contains("b.png") &&
                success.remotePath.contains("c.png"),
        )
    }

    @Test
    fun startUploadWithSingleItemKeepsSinglePathSuccess() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 31L, name = "hetzner")
        val fake = FakeUploader().also { vm.uploader = it }
        vm.setItem(uriItem("only.png"))

        vm.startUpload(host)
        advanceUntilIdle()

        assertEquals(listOf("only.png"), fake.uploadedNames)
        val success = vm.uploadState.first { it is UploadState.Success } as UploadState.Success
        assertEquals(1, success.totalCount)
        assertEquals(1, success.successCount)
        assertEquals(
            "single-file success detail must be just the one remote path",
            "~/inbox/pocketshell/only.png",
            success.remotePath,
        )
        assertEquals(
            "single-file copy payload must be the exact target remote path",
            "~/inbox/pocketshell/only.png",
            success.copyText,
        )
    }

    @Test
    fun startUploadReportsPartialFailureWhenSomeItemsFail() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 32L, name = "hetzner")
        // The middle file fails; the other two succeed.
        val fake = FakeUploader(failNames = setOf("bad.png")).also { vm.uploader = it }
        vm.setItems(
            listOf(
                uriItem("ok1.png"),
                uriItem("bad.png"),
                uriItem("ok2.png"),
            ),
        )

        vm.startUpload(host)
        advanceUntilIdle()

        // All three were attempted — the failure does not abort the loop.
        assertEquals(listOf("ok1.png", "bad.png", "ok2.png"), fake.uploadedNames)
        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertEquals(3, failed.totalCount)
        assertEquals(2, failed.successCount)
        assertEquals(
            "the failed-name list must name only the file that failed",
            listOf("bad.png"),
            failed.failedNames,
        )
        assertTrue(
            "partial-failure message should report the 2 of 3 success and name the failure, got '${failed.message}'",
            failed.message.contains("2 of 3") && failed.message.contains("bad.png"),
        )
    }

    @Test
    fun startUploadReportsTotalFailureWhenAllItemsFail() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 33L, name = "hetzner")
        val fake = FakeUploader(failNames = setOf("x.png", "y.png"))
            .also { vm.uploader = it }
        vm.setItems(listOf(uriItem("x.png"), uriItem("y.png")))

        vm.startUpload(host)
        advanceUntilIdle()

        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertEquals(2, failed.totalCount)
        assertEquals(0, failed.successCount)
        assertEquals(setOf("x.png", "y.png"), failed.failedNames.toSet())
    }

    @Test
    fun startUploadWithNoStagedItemsIsNoOp() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 34L, name = "hetzner")
        val fake = FakeUploader().also { vm.uploader = it }

        vm.startUpload(host)
        advanceUntilIdle()

        assertTrue("nothing staged -> uploader must not be called", fake.uploadedNames.isEmpty())
        assertTrue(vm.uploadState.first() is UploadState.Idle)
    }

    // ---- Issue #473: project target selection + routing ----

    @Test
    fun selectTargetHostListsKnownProjectsForInactiveHost() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 40L, name = "hetzner")
        db.projectRootDao().insert(
            com.pocketshell.core.storage.entity.ProjectRootEntity(
                hostId = host.id,
                label = "[10] PocketShell",
                path = "/home/alexey/git/pocketshell",
            ),
        )
        db.projectRootDao().insert(
            com.pocketshell.core.storage.entity.ProjectRootEntity(
                hostId = host.id,
                label = "Other",
                path = "/home/alexey/git/other",
            ),
        )
        vm.setItem(uriItem("shot.png"))

        vm.selectTargetHost(host)
        advanceUntilIdle()

        val selection = vm.targetSelection.first { it != null && !it.loading }!!
        assertEquals(host.id, selection.host.id)
        assertEquals(null, selection.activeSessionProject)
        assertEquals(
            "known projects must carry their paths",
            setOf("/home/alexey/git/pocketshell", "/home/alexey/git/other"),
            selection.knownProjects.map { it.path }.toSet(),
        )
        assertTrue(
            "order prefix must be stripped from the project label, got ${selection.knownProjects.map { it.label }}",
            selection.knownProjects.any { it.label == "PocketShell" },
        )
    }

    @Test
    fun selectTargetHostSurfacesActiveSessionProjectAsQuickTarget() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = seededHost(id = 41L, name = "gpu-box")
        val client = FakeTmuxClient().apply {
            // display-message -p '#{pane_current_path}' -> the live cwd.
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf("/home/alexey/git/live-project"),
                    isError = false,
                ),
            )
        }
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = "/tmp/key",
            client = client,
        )
        vm.setItem(uriItem("shot.png"))

        vm.selectTargetHost(host)
        advanceUntilIdle()

        val selection = vm.targetSelection.first { it != null && !it.loading }!!
        assertEquals(
            "/home/alexey/git/live-project",
            selection.activeSessionProject?.path,
        )
        assertEquals("live-project", selection.activeSessionProject?.label)
    }

    @Test
    fun startUploadToProjectTargetRoutesEveryItemToTheProject() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 42L, name = "hetzner")
        val fake = FakeUploader().also { vm.uploader = it }
        vm.setItem(uriItem("shot.png"))

        vm.startUpload(host, ShareTarget.Project("/home/alexey/git/foo"))
        advanceUntilIdle()

        assertEquals(
            listOf<ShareTarget>(ShareTarget.Project("/home/alexey/git/foo")),
            fake.uploadedTargets,
        )
        val success = vm.uploadState.first { it is UploadState.Success } as UploadState.Success
        assertEquals(
            "success detail must show the project .inbox path",
            "/home/alexey/git/foo/.inbox/shot.png",
            success.remotePath,
        )
    }

    @Test
    fun startUploadDefaultsToHostInboxTarget() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 43L, name = "hetzner")
        val fake = FakeUploader().also { vm.uploader = it }
        vm.setItem(uriItem("shot.png"))

        vm.startUpload(host)
        advanceUntilIdle()

        assertEquals(listOf<ShareTarget>(ShareTarget.HostInbox), fake.uploadedTargets)
    }

    /**
     * Records the order of uploaded item names and lets a test pin which
     * items fail, so the [ShareViewModel] multi-file loop + partial-
     * failure aggregation can be asserted without a live SSH session.
     */
    private class FakeUploader(
        private val failNames: Set<String> = emptySet(),
    ) : ShareItemUploader {
        val uploadedNames = mutableListOf<String>()

        val uploadedTargets = mutableListOf<ShareTarget>()

        override suspend fun upload(
            host: HostEntity,
            keyEntity: SshKeyEntity,
            item: ShareableItem,
            target: ShareTarget,
        ): Result<String> {
            val name = item.displayName.orEmpty()
            uploadedNames += name
            uploadedTargets += target
            return if (name in failNames) {
                Result.failure(IllegalStateException("Permission denied"))
            } else {
                when (target) {
                    ShareTarget.HostInbox -> Result.success("~/inbox/pocketshell/$name")
                    is ShareTarget.Project ->
                        Result.success("${target.remoteProjectPath}/.inbox/$name")
                }
            }
        }
    }

    private fun uriItem(name: String): ShareableItem.UriItem =
        ShareableItem.UriItem(
            uri = android.net.Uri.parse("content://x/$name"),
            displayName = name,
            size = null,
            mimeType = "image/png",
            fallbackExtension = "png",
        )

    /**
     * Insert a host plus an SSH-key row so the ViewModel's
     * `sshKeyDao.getById(host.keyId)` lookup succeeds and the upload loop
     * runs against the [FakeUploader].
     */
    private suspend fun seededHost(id: Long, name: String): HostEntity {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "key-$id", privateKeyPath = "/tmp/key-$id"),
        )
        val host = HostEntity(
            id = id,
            name = name,
            hostname = "$name.example",
            port = 22,
            username = "alex",
            keyId = keyId,
        )
        // Persist the host so child rows (e.g. project_roots, issue
        // #473) satisfy the foreign-key constraint.
        db.hostDao().insert(host)
        return host
    }

    private fun newVm(registry: ActiveTmuxClients): ShareViewModel {
        return ShareViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            activeTmuxClients = registry,
            projectRootDao = db.projectRootDao(),
        )
    }

    private suspend fun registerClient(
        registry: ActiveTmuxClients,
        hostId: Long,
        hostName: String,
    ): FakeTmuxClient {
        val client = FakeTmuxClient()
        registry.register(
            hostId = hostId,
            hostName = hostName,
            hostname = "$hostName.example",
            port = 22,
            username = "alex",
            keyPath = "/tmp/key",
            client = client,
        )
        return client
    }

    private fun host(id: Long, name: String): HostEntity =
        HostEntity(
            id = id,
            name = name,
            hostname = "$name.example",
            port = 22,
            username = "alex",
            keyId = 99L,
        )
}
