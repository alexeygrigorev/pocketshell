package com.pocketshell.app.share

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
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
            // send-keys -H succeeds.
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
        vm.setItem(ShareableItem.TextItem(text = "para 1\npara 2", displayName = "note"))

        vm.pasteIntoSession(host)
        advanceUntilIdle()

        val state = vm.uploadState.first { it is UploadState.Success }
        assertTrue(state is UploadState.Success)
        assertEquals(2, client.sentCommands.size)
        val sendKeys = client.sentCommands[1]
        assertTrue(
            "expected send-keys -H targeting %7, got '$sendKeys'",
            sendKeys.startsWith("send-keys -H -t %7 "),
        )
        assertTrue(
            "expected bracketed-paste start marker, got '$sendKeys'",
            sendKeys.contains("1b 5b 32 30 30 7e"),
        )
        assertTrue(
            "expected bracketed-paste end marker, got '$sendKeys'",
            sendKeys.endsWith("1b 5b 32 30 31 7e"),
        )
        // Exactly one LF inside the body (two paragraphs).
        val hexBody = sendKeys.substringAfter("send-keys -H -t %7 ")
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

    private fun newVm(registry: ActiveTmuxClients): ShareViewModel {
        return ShareViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            activeTmuxClients = registry,
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
