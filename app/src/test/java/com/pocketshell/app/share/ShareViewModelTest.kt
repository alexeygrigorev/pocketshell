package com.pocketshell.app.share

import android.app.NotificationManager
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
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
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
        context.getSystemService(NotificationManager::class.java).cancelAll()
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

    // ---- Issue #1475: Share→attached-pane must ride the send-keys exec lane
    // (not the shared `-CC` channel), so a share paste into a live agent
    // mid-`%output`-burst does NOT head-of-line-block behind the burst and wedge
    // the app — the same send-lane wedge class #1460 fixed for the composer send
    // sites, here for `ShareViewModel.sendTextToAttachedPane`. ----

    @Test
    fun `single-line share paste completes on the exec lane while a -CC burst wedges the send`() =
        runTest {
            // RED on the pre-#1475 code (send-keys via `sendCommand`): the send's
            // `%begin`/`%end` reply is head-of-line-blocked behind the burst on the
            // one sshj reader, so the paste never completes (stays Running) — the
            // maintainer's "app froze". GREEN with the fix: the send rides the
            // dedicated exec lane and completes even while the burst holds `-CC`.
            assertSharePasteCompletesDuringCcSendWedge(
                hostId = 1475L,
                text = "echo hello",
                expectedSendKeysPrefix = "send-keys -l -t %0 --",
            )
        }

    @Test
    fun `multi-line bracketed share paste completes on the exec lane while a -CC burst wedges the send`() =
        runTest {
            // Class coverage (G2): the multi-line bracketed-paste route funnels
            // through `send-keys -H`, the other interactive-send shape Share uses.
            // It too must ride the exec lane, not the wedged `-CC` channel.
            assertSharePasteCompletesDuringCcSendWedge(
                hostId = 14751L,
                text = "para 1\npara 2",
                expectedSendKeysPrefix = "send-keys -H -t %0 ",
            )
        }

    /**
     * Issue #1475 shared driver: register a host whose live client models a
     * sustained agent `%output` burst saturating the shared `-CC` control
     * channel (so any `-CC` `send-keys` round-trip is head-of-line-blocked and
     * never returns), stage [text], drive `pasteIntoSession`, and assert the
     * paste COMPLETES — proving the interactive send rode the dedicated
     * `send-keys` exec lane, independent of the wedged `-CC` channel. On the
     * pre-fix code the send used `sendCommand` and the paste stays Running
     * forever (RED); with the exec-lane routing it reaches Success (GREEN).
     */
    private fun TestScope.assertSharePasteCompletesDuringCcSendWedge(
        hostId: Long,
        text: String,
        expectedSendKeysPrefix: String,
    ) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = host(id = hostId, name = "burst-host-$hostId")
        val client = ShareCcBurstWedgeClient(paneIdReply = "%0")
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = "/tmp/key",
            client = client,
        )
        vm.setItem(ShareableItem.TextItem(text = text, displayName = "note"))

        try {
            vm.pasteIntoSession(host)
            advanceUntilIdle()

            val state = vm.uploadState.value
            assertTrue(
                "share paste must complete during a live -CC %output burst, but stayed " +
                    "$state (exec-lane sends so far: ${client.execLaneSendKeys}); the " +
                    "interactive send is head-of-line-blocked behind the burst on the shared " +
                    "-CC channel",
                state is UploadState.Success,
            )
            assertTrue(
                "the interactive send must ride the send-keys exec lane, got " +
                    "${client.execLaneSendKeys}",
                client.execLaneSendKeys.isNotEmpty() &&
                    client.execLaneSendKeys.all { it.startsWith("send-keys") },
            )
            assertTrue(
                "expected an exec-lane send carrying '$expectedSendKeysPrefix', got " +
                    "${client.execLaneSendKeys}",
                client.execLaneSendKeys.any { it.startsWith(expectedSendKeysPrefix) },
            )
            assertTrue(
                "the interactive send must NOT be issued on the wedged -CC sendCommand path, " +
                    "got ${client.ccSendKeys}",
                client.ccSendKeys.isEmpty(),
            )
        } finally {
            // Release the parked -CC coroutine so it does not outlive the test.
            client.releaseCcWedge()
        }
    }

    // ---- Issue #1488: the FULL residual `-CC` wedge the 2026-07-12 transport
    // audit found (H2). #1475 moved only the interactive SEND onto the exec lane;
    // the share picker path still ran FOUR serial `-CC` read round-trips — the
    // `list-panes` enumeration, the `list-sessions` identity resolution, the
    // confirm-time staleness check, and the paste pane-id resolution. A share
    // into a host mid-`%output`-burst head-of-line-blocked the WHOLE picker
    // behind the saturated channel for well over a minute. This drives all four
    // behind a saturated `-CC` and proves each now rides the exec lane. ----

    @Test
    fun `full share picker path completes off the exec lane while a -CC burst wedges the channel`() =
        runTest {
            // RED on base (#1488 unfixed): each of the four picker reads rides
            // `sendCommand`, so its reply is head-of-line-blocked behind the burst
            // on the one sshj reader. The picker build never resolves (stays
            // loading), the confirm staleness check never returns (stage stays
            // Running), and the paste parks at RESOLUTION (stays Running). GREEN
            // with the fix: all four ride `listPanesViaExec` (the exec lane),
            // return fast even mid-burst, so the whole path completes.
            val registry = ActiveTmuxClients()
            val vm = newVm(registry)
            val host = host(id = 1488L, name = "burst-host-1488")
            val client = ShareCcPickerBurstWedgeClient(
                paneIdReply = "%0",
                listPanesRows = listOf(
                    "work::0::main::0::1::/home/alexey/git/pocketshell::/dev/pts/1::bash",
                    "scratch::0::main::1::1::/home/alexey/git/live::/dev/pts/2::bash",
                ),
                listSessionsRows = listOf(
                    "work::\$1::101::101::0::::::::::/home/alexey/git/pocketshell",
                    "scratch::\$2::202::202::1::::::::::/home/alexey/git/live",
                ),
                // Mismatches the resolved scratch identity ($2::202) so the
                // confirm staleness check returns a terminal Failed(stale) — which
                // proves the check COMPLETED off `-CC` (on base it wedges Running).
                staleIdentityReply = "\$9::999",
            )
            registry.register(
                hostId = host.id,
                hostName = host.name,
                hostname = host.hostname,
                port = host.port,
                username = host.username,
                keyPath = "/tmp/key",
                client = client,
            )

            try {
                // Step A: build the picker — resolveActiveSessions (`list-panes`)
                // + resolveSessionIdentities (`list-sessions`), both serial `-CC`.
                vm.setItem(uriItem("shot.png"))
                vm.selectTargetHost(host)
                advanceUntilIdle()

                val selection = vm.targetSelection.value
                // Load-bearing GREEN assertion (G6): the picker RESOLVES during a
                // live `-CC` burst — on base it head-of-line-blocks at the
                // `list-panes` enumeration and stays loading forever.
                assertTrue(
                    "share picker must resolve during a live -CC %output burst, but was " +
                        "$selection; the list-panes/list-sessions enumeration is " +
                        "head-of-line-blocked at the wedged -CC sendCommand (exec-lane " +
                        "resolves so far: ${client.execLaneResolveCommands}, wedged -CC " +
                        "resolves: ${client.ccResolveCommands})",
                    selection != null && !selection.loading && selection.activeSessions.isNotEmpty(),
                )
                val session = selection!!.activeSessions.first()
                assertEquals("scratch", session.sessionName)
                assertEquals("\$2", session.tmuxSessionId)

                // Step B: confirm a session target — isSessionTargetCurrent
                // (`display-message -p -t … #{session_id}::#{session_created}`).
                vm.stageIntoSession(host, session)
                advanceUntilIdle()

                val stageState = vm.uploadState.value
                // GREEN: the confirm staleness check COMPLETES (reaches a terminal
                // Failed/Success) — on base it wedges on `-CC` and stays Running.
                assertTrue(
                    "confirm-time staleness check must complete during a live -CC burst, but " +
                        "stayed $stageState; it is head-of-line-blocked at the wedged -CC " +
                        "sendCommand (wedged -CC resolves: ${client.ccResolveCommands})",
                    stageState is UploadState.Failed || stageState is UploadState.Success,
                )
                vm.clearUploadState()

                // Step C: paste — resolveActivePaneIdOrNull
                // (`display-message -p '#{pane_id}'`).
                vm.setItem(ShareableItem.TextItem(text = "echo hello", displayName = "note"))
                vm.pasteIntoSession(host)
                advanceUntilIdle()

                val pasteState = vm.uploadState.value
                assertTrue(
                    "share paste must complete during a live -CC %output burst, but stayed " +
                        "$pasteState; pane-id resolution is head-of-line-blocked at the wedged " +
                        "-CC sendCommand (wedged -CC resolves: ${client.ccResolveCommands})",
                    pasteState is UploadState.Success,
                )

                // Class coverage (D31/D32): ALL FOUR picker reads rode the exec
                // lane, carrying their SAME command strings.
                val execReads = client.execLaneResolveCommands
                assertTrue(
                    "list-panes enumeration must ride the exec lane, got $execReads",
                    execReads.any { it.startsWith("list-panes") },
                )
                assertTrue(
                    "list-sessions identity resolution must ride the exec lane, got $execReads",
                    execReads.any { it.startsWith("list-sessions") },
                )
                assertTrue(
                    "confirm-time staleness display-message must ride the exec lane, got $execReads",
                    execReads.any { it.startsWith("display-message -p -t") },
                )
                assertTrue(
                    "paste pane-id display-message must ride the exec lane, got $execReads",
                    execReads.any { it.startsWith("display-message -p") && it.contains("#{pane_id}") },
                )
                // NONE of the four reads hit the wedged `-CC` sendCommand.
                assertTrue(
                    "no picker read may be issued on the wedged -CC sendCommand path, got " +
                        "${client.ccResolveCommands}",
                    client.ccResolveCommands.isEmpty(),
                )
                // The resolved pane id flows into the targeted exec-lane send.
                assertTrue(
                    "expected the resolved pane %0 to target the exec-lane send, got " +
                        "${client.execLaneSendKeys}",
                    client.execLaneSendKeys.any { it.startsWith("send-keys -l -t %0 --") },
                )
            } finally {
                // Release any parked -CC coroutine so it does not outlive the test.
                client.releaseCcWedge()
            }
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
    fun startUploadSurfacesLiveByteProgressDetailWhileTransferring() = runTest {
        // Issue #1037 acceptance ("show progress"): the share UI must show bytes
        // MOVING during a transfer (not just a static up-front total), surfaced
        // through UploadState.Running.detail. The uploader emits progress ticks
        // and we snapshot the live detail synchronously after each one.
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 1037L, name = "hetzner")
        val observedRunningDetails = mutableListOf<String>()
        val progressUploader = object : ShareItemUploader {
            override suspend fun upload(
                host: HostEntity,
                keyEntity: SshKeyEntity,
                item: ShareableItem,
                target: ShareTarget,
                onProgress: ((com.pocketshell.core.ssh.SshUploadProgress) -> Unit)?,
            ): Result<String> {
                fun tick(transferred: Long) {
                    onProgress?.invoke(
                        com.pocketshell.core.ssh.SshUploadProgress(
                            bytesTransferred = transferred,
                            totalBytes = 4_800_000L,
                        ),
                    )
                    (vm.uploadState.value as? UploadState.Running)?.detail?.let {
                        observedRunningDetails += it
                    }
                }
                tick(0L)
                tick(2_400_000L)
                tick(4_800_000L)
                return Result.success("~/inbox/pocketshell/${item.displayName}.txt")
            }
        }
        vm.uploader = progressUploader
        vm.setItem(ShareableItem.TextItem(text = "report body", displayName = "report"))

        vm.startUpload(host)
        advanceUntilIdle()

        assertTrue(
            "expected the upload to succeed, state=${vm.uploadState.value}",
            vm.uploadState.value is UploadState.Success,
        )
        assertTrue(
            "expected a live mid-transfer detail showing 0 B moving toward 4.6 MB, got " +
                "$observedRunningDetails",
            observedRunningDetails.any { it.contains("0 B") && it.contains("4.6 MB") },
        )
        assertTrue(
            "expected a live detail showing ~half (2.3 MB) of 4.6 MB transferred, got " +
                "$observedRunningDetails",
            observedRunningDetails.any { it.contains("2.3 MB") && it.contains("4.6 MB") },
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
    fun startUploadTreatsBlankReturnedLinkAsFailure() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 131L, name = "hetzner")
        val fake = FakeUploader(blankNames = setOf("empty.txt")).also { vm.uploader = it }
        vm.setItem(uriItem("empty.txt"))

        vm.startUpload(host)
        advanceUntilIdle()

        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertEquals("hetzner", failed.hostName)
        assertTrue(
            "a successful transport with no returned path is not usable: ${failed.message}",
            failed.message.contains("returned no remote path", ignoreCase = true),
        )
        assertEquals(listOf("empty.txt"), failed.failedNames)
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
        assertEquals(
            "partial failure must preserve returned links for files that did land",
            listOf("~/inbox/pocketshell/ok1.png", "~/inbox/pocketshell/ok2.png"),
            failed.successfulPaths,
        )
        assertEquals(
            "~/inbox/pocketshell/ok1.png\n~/inbox/pocketshell/ok2.png",
            failed.copyText,
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
    @Config(sdk = [28])
    fun startUploadSuccessDoesNotPostAndroidNotification() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 35L, name = "hetzner")
        val fake = FakeUploader().also { vm.uploader = it }
        vm.setItems(listOf(uriItem("a.png"), uriItem("b.png"), uriItem("c.png")))
        val manager = context.getSystemService(NotificationManager::class.java)

        vm.startUpload(host)
        advanceUntilIdle()

        val success = vm.uploadState.first { it is UploadState.Success }
        assertTrue(success is UploadState.Success)
        assertTrue(
            "successful share uploads must not leave a status-bar notification; active=" +
                manager.activeNotifications.map {
                    it.notification.extras.getCharSequence("android.title")?.toString()
                },
            manager.activeNotifications.isEmpty(),
        )
        assertEquals(listOf("a.png", "b.png", "c.png"), fake.uploadedNames)
    }

    @Test
    @Config(sdk = [28])
    fun startUploadFailureInForegroundDoesNotPostDuplicateAndroidNotification() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 36L, name = "hetzner")
        val fake = FakeUploader(failNames = setOf("bad.png")).also { vm.uploader = it }
        vm.setItem(uriItem("bad.png"))
        val manager = context.getSystemService(NotificationManager::class.java)

        vm.startUpload(host)
        advanceUntilIdle()

        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertEquals("Permission denied", failed.message)
        assertTrue(
            "foreground share flow already shows the failure surface; no duplicate notification expected",
            manager.activeNotifications.isEmpty(),
        )
    }

    @Test
    fun retryLastShareActionPreservesPayloadAndTarget() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 137L, name = "hetzner")
        val fake = FakeUploader(failNames = setOf("retry.txt")).also { vm.uploader = it }
        val item = uriItem("retry.txt")
        vm.setItem(item)

        vm.startUpload(host, ShareTarget.Project("/home/alexey/git/pocketshell"))
        advanceUntilIdle()
        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertEquals("Permission denied", failed.message)

        fake.failNames = emptySet()
        vm.retryLastShareAction()
        advanceUntilIdle()

        val success = vm.uploadState.first { it is UploadState.Success } as UploadState.Success
        assertEquals("/home/alexey/git/pocketshell/.inbox/retry.txt", success.copyText)
        assertEquals(listOf(item), vm.items.value)
        assertEquals(
            listOf(
                ShareTarget.Project("/home/alexey/git/pocketshell"),
                ShareTarget.Project("/home/alexey/git/pocketshell"),
            ),
            fake.uploadedTargets,
        )
    }

    @Test
    fun startUploadReusesShareUploadPurposeLeaseInsteadOfStartingFreshAuth() = runTest {
        val host = seededHost(id = 138L, name = "hetzner")
        val keyPath = "/tmp/key-138"
        val fakeSession = FakeStagingSshSession()
        var connectCalls = 0
        val leaseManager = SshLeaseManager(
            connector = { _: SshLeaseTarget ->
                connectCalls += 1
                if (connectCalls == 1) {
                    Result.success(fakeSession)
                } else {
                    Result.failure(SshException("Authentication failed"))
                }
            },
        )
        val activeLease = leaseManager.acquire(
            host.shareTestLeaseTarget(keyPath, purpose = "share-upload"),
        ).getOrThrow()
        try {
            val vm = newVm(ActiveTmuxClients(), sshLeaseManager = leaseManager)
            vm.setItem(ShareableItem.TextItem(text = "crash report body", displayName = "report"))

            vm.startUpload(host)
            advanceUntilIdle()

            val success = vm.uploadState.first { it is UploadState.Success } as UploadState.Success
            assertTrue(
                "share upload must return the staged reference, got ${success.copyText}",
                success.copyText.contains("~/inbox/pocketshell/") &&
                    success.copyText.endsWith("report.txt"),
            )
            assertEquals(
                "a live share-upload lease for the same host/key should be reused",
                1,
                connectCalls,
            )
            assertTrue(
                "the upload should have used the already-live session",
                fakeSession.uploadedRemotePaths.isNotEmpty(),
            )
            assertFalse(
                "releasing the share lease must not close the active app session",
                fakeSession.closed,
            )
        } finally {
            activeLease.release()
            leaseManager.close()
        }
    }

    @Test
    fun startUploadDoesNotReuseGenericInteractiveLease() = runTest {
        val host = seededHost(id = 139L, name = "hetzner")
        val keyPath = "/tmp/key-139"
        val genericSession = FakeStagingSshSession()
        val uploadSession = FakeStagingSshSession()
        val seenCredentialIds = mutableListOf<String>()
        var connectCalls = 0
        val leaseManager = SshLeaseManager(
            connector = { target: SshLeaseTarget ->
                seenCredentialIds += target.leaseKey.credentialId
                connectCalls += 1
                if (connectCalls == 1) {
                    Result.success(genericSession)
                } else {
                    Result.success(uploadSession)
                }
            },
        )
        val activeLease = leaseManager.acquire(host.shareTestLeaseTarget(keyPath)).getOrThrow()
        try {
            val vm = newVm(ActiveTmuxClients(), sshLeaseManager = leaseManager)
            vm.setItem(ShareableItem.TextItem(text = "body", displayName = "report"))

            vm.startUpload(host)
            advanceUntilIdle()

            val success = vm.uploadState.first { it is UploadState.Success }
            assertTrue(success is UploadState.Success)
            assertEquals(
                listOf("${host.id}:$keyPath", "${host.id}:$keyPath|purpose=share-upload"),
                seenCredentialIds,
            )
            assertTrue(uploadSession.uploadedRemotePaths.isNotEmpty())
            assertTrue(genericSession.uploadedRemotePaths.isEmpty())
        } finally {
            activeLease.release()
            leaseManager.close()
        }
    }

    @Test
    @Config(sdk = [28])
    fun stageIntoSessionUsesShareStagingPurposeLease() {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Default)
            try {
                val registry = ActiveTmuxClients()
                val host = seededHost(id = 140L, name = "hetzner")
                val fakeSession = FakeStagingSshSession()
                val seenCredentialIds = mutableListOf<String>()
                val leaseManager = SshLeaseManager(
                    connector = { target: SshLeaseTarget ->
                        seenCredentialIds += target.leaseKey.credentialId
                        Result.success(fakeSession)
                    },
                )
                val vm = newVm(registry, sshLeaseManager = leaseManager)
                registry.register(
                    hostId = host.id,
                    hostName = host.name,
                    hostname = host.hostname,
                    port = host.port,
                    username = host.username,
                    keyPath = "/tmp/key-140",
                    client = FakeTmuxClient(),
                )
                vm.setItem(ShareableItem.TextItem(text = "attach me", displayName = "note"))

                vm.stageIntoSession(
                    host,
                    ActiveSessionTarget(sessionName = "scratch", cwd = "/x", label = "scratch"),
                )
                kotlinx.coroutines.withTimeout(5_000L) {
                    fakeSession.uploadStarted.await()
                }

                assertEquals(
                    listOf("${host.id}:/tmp/key-140|purpose=share-staging"),
                    seenCredentialIds,
                )
                leaseManager.close()
            } finally {
                kotlinx.coroutines.Dispatchers.setMain(
                    kotlinx.coroutines.test.UnconfinedTestDispatcher(),
                )
            }
        }
    }

    @Test
    fun startUploadDoesNotReAuthWhenWarmLeaseExistsForPassphraseKey() = runTest {
        // Issue #654: a passphrase-protected key must STILL reuse the warm
        // app lease without any passphrase prompt — the passphrase is not
        // part of the lease key, so an already-unlocked lease is shared.
        val host = seededHost(id = 654L, name = "hetzner", hasPassphrase = true)
        val keyPath = "/tmp/key-654"
        val fakeSession = FakeStagingSshSession()
        var connectCalls = 0
        // The first connect (the live app session's) succeeds; any SECOND
        // connect would mean the share re-authenticated instead of reusing
        // the warm lease, which the assertion below forbids.
        val leaseManager = SshLeaseManager(
            connector = { _: SshLeaseTarget ->
                connectCalls += 1
                if (connectCalls == 1) {
                    Result.success(fakeSession)
                } else {
                    Result.failure(SshException("Authentication failed"))
                }
            },
        )
        val activeLease = leaseManager.acquire(
            host.shareTestLeaseTarget(keyPath, purpose = "share-upload"),
        ).getOrThrow()
        try {
            val vm = newVm(ActiveTmuxClients(), sshLeaseManager = leaseManager)
            vm.setItem(ShareableItem.TextItem(text = "report body", displayName = "report"))

            vm.startUpload(host)
            advanceUntilIdle()

            val success = vm.uploadState.first { it is UploadState.Success }
            assertTrue("warm lease reuse must succeed", success is UploadState.Success)
            assertEquals(
                "the passphrase-protected key's warm lease was reused; no fresh auth",
                1,
                connectCalls,
            )
        } finally {
            activeLease.release()
            leaseManager.close()
        }
    }

    @Test
    fun startUploadPromptsForPassphraseWhenFreshAuthFailsOnLockedKey() = runTest {
        // Issue #654: no warm lease -> fresh connect -> auth fails because
        // the key is passphrase-protected. Instead of a bare
        // "Authentication failed", the share must surface the same unlock
        // the main app uses (NeedsPassphrase), then succeed once the
        // passphrase is supplied.
        val host = seededHost(id = 6541L, name = "hetzner", hasPassphrase = true)
        val keyPath = "/tmp/key-6541"
        val fakeSession = FakeStagingSshSession()
        val seenPassphrases = mutableListOf<String?>()
        var connectCalls = 0
        val leaseManager = SshLeaseManager(
            connector = { target: SshLeaseTarget ->
                connectCalls += 1
                seenPassphrases += target.passphrase?.concatToString()
                if (target.passphrase != null) {
                    Result.success(fakeSession)
                } else {
                    Result.failure(SshException("Authentication failed"))
                }
            },
        )
        try {
            val vm = newVm(ActiveTmuxClients(), sshLeaseManager = leaseManager)
            vm.setItem(ShareableItem.TextItem(text = "report body", displayName = "report"))

            vm.startUpload(host)
            advanceUntilIdle()

            val prompt = vm.uploadState.first { it is UploadState.NeedsPassphrase }
                as UploadState.NeedsPassphrase
            assertEquals("hetzner", prompt.hostName)
            assertEquals(1, connectCalls)

            vm.submitPassphrase("hunter2".toCharArray())
            advanceUntilIdle()

            val success = vm.uploadState.first { it is UploadState.Success }
            assertTrue("upload must succeed once unlocked", success is UploadState.Success)
            assertEquals(
                "fresh connect retried with the entered passphrase",
                listOf(null, "hunter2"),
                seenPassphrases,
            )
            assertTrue(
                "the unlocked session uploaded the file",
                fakeSession.uploadedRemotePaths.isNotEmpty(),
            )
        } finally {
            leaseManager.close()
        }
    }

    @Test
    fun startUploadShowsAuthFailureNotPromptForPassphraselessKey() = runTest {
        // Issue #654 guard: a key WITHOUT a passphrase that still fails auth
        // is a genuine failure, not a missing passphrase. Show the normal
        // failure surface, never the passphrase prompt.
        val host = seededHost(id = 6542L, name = "hetzner", hasPassphrase = false)
        val leaseManager = SshLeaseManager(
            connector = { _: SshLeaseTarget -> Result.failure(SshException("Authentication failed")) },
        )
        try {
            val vm = newVm(ActiveTmuxClients(), sshLeaseManager = leaseManager)
            vm.setItem(ShareableItem.TextItem(text = "report body", displayName = "report"))

            vm.startUpload(host)
            advanceUntilIdle()

            val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
            assertEquals("Authentication failed", failed.message)
            assertFalse(
                "passphrase-less key must not raise the unlock prompt",
                vm.uploadState.value is UploadState.NeedsPassphrase,
            )
        } finally {
            leaseManager.close()
        }
    }

    @Test
    @Config(sdk = [28])
    fun startUploadFailureInBackgroundPostsAndroidNotificationWithoutFakeActions() = runTest {
        val vm = newVm(ActiveTmuxClients())
        val host = seededHost(id = 36L, name = "hetzner")
        val fake = FakeUploader(failNames = setOf("bad.png")).also { vm.uploader = it }
        vm.setItem(uriItem("bad.png"))
        vm.setShareFlowForeground(false)
        val manager = context.getSystemService(NotificationManager::class.java)

        vm.startUpload(host)
        advanceUntilIdle()

        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertEquals("Permission denied", failed.message)
        val posted = manager.activeNotifications.singleOrNull()
        assertEquals(
            "failed share uploads must keep Android error feedback",
            "Could not upload to hetzner",
            posted?.notification?.extras?.getCharSequence("android.title")?.toString(),
        )
        assertEquals(
            "Permission denied",
            posted?.notification?.extras?.getCharSequence("android.text")?.toString(),
        )
        assertTrue(
            "background notification must not advertise actions that cannot restore the original share payload",
            posted?.notification?.actions.isNullOrEmpty(),
        )
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
        assertTrue(
            "no live client -> no session projects",
            selection.sessionProjects.isEmpty(),
        )
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
    fun selectTargetHostSurfacesOpenSessionProjectsFocusedFirst() = runTest {
        // Issue #507: the picker must list the current/open sessions'
        // PROJECTS (their active-pane cwd), not only top-level watched
        // roots. Two open sessions in different projects -> two session
        // targets; the focused session leads.
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = seededHost(id = 41L, name = "gpu-box")
        val client = FakeTmuxClient().apply {
            // list-panes -a active-pane rows. Fields:
            // session::window_index::window_name::window_active::
            // pane_active::pane_current_path::pane_tty::pane_current_command
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(
                        "work::0::main::0::1::/home/alexey/git/pocketshell::/dev/pts/1::bash",
                        "scratch::0::main::1::1::/home/alexey/git/live-project::/dev/pts/2::bash",
                    ),
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
            "both open sessions' projects must be surfaced",
            listOf("/home/alexey/git/live-project", "/home/alexey/git/pocketshell"),
            selection.sessionProjects.map { it.path },
        )
        assertEquals(
            "the focused session's project must lead",
            "/home/alexey/git/live-project",
            selection.sessionProjects.first().path,
        )
        assertEquals("live-project", selection.sessionProjects.first().label)
        assertTrue(
            "the command must enumerate all sessions' panes, got ${client.sentCommands}",
            client.sentCommands.first().startsWith("list-panes -a"),
        )
    }

    @Test
    fun selectTargetHostDedupesSessionProjectAgainstWatchedRoot() = runTest {
        // Issue #507: a watched root that is ALSO an open-session project
        // must appear once (in the prominent session slot), not twice.
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = seededHost(id = 45L, name = "gpu-box")
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
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(
                        // Trailing slash on the cwd must normalise to the
                        // watched-root path so the dedupe still fires.
                        "ps::0::main::1::1::/home/alexey/git/pocketshell/::/dev/pts/1::bash",
                    ),
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
            "the shared project must appear once, in the session slot",
            listOf("/home/alexey/git/pocketshell"),
            selection.sessionProjects.map { it.path },
        )
        assertEquals(
            "the watched root that is an open project must be dropped from the root list",
            listOf("/home/alexey/git/other"),
            selection.knownProjects.map { it.path },
        )
    }

    @Test
    fun selectTargetHostDedupesTwoSessionsSharingACwd() = runTest {
        // Issue #507: two open sessions whose active panes share a cwd
        // must surface a single destination.
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = seededHost(id = 46L, name = "gpu-box")
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(
                        "a::0::main::0::1::/home/alexey/git/pocketshell::/dev/pts/1::bash",
                        "b::0::main::1::1::/home/alexey/git/pocketshell::/dev/pts/2::bash",
                    ),
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
            listOf("/home/alexey/git/pocketshell"),
            selection.sessionProjects.map { it.path },
        )
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

    // ---- Issue #560: active sessions as a destination + share-into-session ----

    @Test
    fun selectTargetHostSurfacesActiveSessionsFocusedFirst() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = seededHost(id = 50L, name = "gpu-box")
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(
                        "work::0::main::0::1::/home/alexey/git/pocketshell::/dev/pts/1::bash",
                        "scratch::0::main::1::1::/home/alexey/git/live::/dev/pts/2::bash",
                    ),
                    isError = false,
                ),
            )
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(
                        "work::\$1::101::101::0::::::::::/home/alexey/git/pocketshell",
                        "scratch::\$2::202::202::1::::::::::/home/alexey/git/live",
                    ),
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
            "both active sessions must be offered as destinations",
            listOf("scratch", "work"),
            selection.activeSessions.map { it.sessionName },
        )
        assertEquals("\$2", selection.activeSessions.first().tmuxSessionId)
        assertEquals(202L, selection.activeSessions.first().sessionCreated)
        assertTrue(
            "the focused session must lead and be flagged focused",
            selection.activeSessions.first().sessionName == "scratch" &&
                selection.activeSessions.first().focused,
        )
        assertFalse(
            "non-focused session must not be flagged focused",
            selection.activeSessions[1].focused,
        )
    }

    // Note: NOT a `runTest`. `stageIntoSession` reuses the #544
    // `PromptAttachmentStager`, whose upload runs on the real
    // `Dispatchers.IO` (`withContext(Dispatchers.IO)`); the virtual
    // `runTest` scheduler does not advance that real dispatcher, so this
    // test drives the round-trip on a real scope and waits on the
    // one-shot launch event with a timeout.
    @Test
    @Config(sdk = [28])
    fun stageIntoSessionUploadsToSessionScopeAndEmitsLaunch() {
        kotlinx.coroutines.runBlocking {
            // The ViewModel's `viewModelScope` is bound to the
            // [MainDispatcherRule] dispatcher; left at the default
            // `UnconfinedTestDispatcher` it would not advance under plain
            // runBlocking, so swap Main to the real default dispatcher for
            // this one test.
            kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Default)
            try {
                val registry = ActiveTmuxClients()
                val vm = newVm(registry)
                val host = seededHost(id = 51L, name = "gpu-box")
                val manager = context.getSystemService(NotificationManager::class.java)
                registry.register(
                    hostId = host.id,
                    hostName = host.name,
                    hostname = host.hostname,
                    port = host.port,
                    username = host.username,
                    keyPath = "/tmp/key",
                    client = FakeTmuxClient(),
                )
                val fakeSession = FakeStagingSshSession()
                vm.connectForStaging = { _, _ -> Result.success(fakeSession) }
                val tempFile =
                    java.io.File.createTempFile("share-into-session", ".png", context.cacheDir)
                tempFile.writeBytes(byteArrayOf(1, 2, 3, 4))
                vm.setItem(
                    ShareableItem.UriItem(
                        uri = android.net.Uri.fromFile(tempFile),
                        displayName = "shot.png",
                        size = tempFile.length(),
                        mimeType = "image/png",
                        fallbackExtension = "png",
                    ),
                )

                val session = ActiveSessionTarget(
                    sessionName = "scratch",
                    cwd = "/home/alexey/git/live",
                    label = "scratch",
                    focused = true,
                )

                vm.stageIntoSession(host, session)
                val launch = kotlinx.coroutines.withTimeout(5_000L) {
                    vm.sessionLaunch.first()
                }

                assertEquals(host.id, launch.hostId)
                assertEquals("scratch", launch.sessionName)
                assertEquals("/tmp/key-51", launch.keyPath)
                assertEquals(1, launch.attachmentPaths.size)
                val staged = launch.attachmentPaths.single()
                assertTrue(
                    "staged path must land in the per-session #544 scope, got '$staged'",
                    staged.contains(".pocketshell/attachments/host-51-scratch/"),
                )
                assertTrue(
                    "the stager must create the per-session dir, got ${fakeSession.execCommands}",
                    fakeSession.execCommands.any { it.contains("host-51-scratch") },
                )
                assertTrue(
                    "the file bytes must have been uploaded",
                    fakeSession.uploadedRemotePaths.isNotEmpty(),
                )
                assertTrue(
                    "successful attachment shares must stay silent; active=" +
                        manager.activeNotifications.map {
                            it.notification.extras.getCharSequence("android.title")?.toString()
                        },
                    manager.activeNotifications.isEmpty(),
                )
                assertTrue("the staging session must be closed", fakeSession.closed)
                tempFile.delete()
            } finally {
                // Restore a test Main so the [MainDispatcherRule]'s
                // `finished()` resetMain still has an installed dispatcher to
                // reset (calling resetMain twice would throw).
                kotlinx.coroutines.Dispatchers.setMain(
                    kotlinx.coroutines.test.UnconfinedTestDispatcher(),
                )
            }
        }
    }

    @Test
    @Config(sdk = [28])
    fun stageIntoSessionMaterializesTextShareAsTxtAttachmentAndEmitsLaunch() {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Default)
            try {
                val registry = ActiveTmuxClients()
                val vm = newVm(registry)
                val host = seededHost(id = 53L, name = "gpu-box")
                registry.register(
                    hostId = host.id,
                    hostName = host.name,
                    hostname = host.hostname,
                    port = host.port,
                    username = host.username,
                    keyPath = "/tmp/key",
                    client = FakeTmuxClient(),
                )
                val fakeSession = FakeStagingSshSession()
                vm.connectForStaging = { _, _ -> Result.success(fakeSession) }
                vm.setItem(
                    ShareableItem.TextItem(
                        text = "Exception summary: SshException\nTop frame: RealSshSession.ensureConnected",
                        displayName = "crash-report",
                    ),
                )

                val session = ActiveSessionTarget(
                    sessionName = "scratch",
                    cwd = "/home/alexey/git/live",
                    label = "scratch",
                    focused = true,
                )

                vm.stageIntoSession(host, session)
                val launch = kotlinx.coroutines.withTimeout(5_000L) {
                    vm.sessionLaunch.first()
                }

                assertEquals(host.id, launch.hostId)
                val staged = launch.attachmentPaths.single()
                assertTrue(
                    "text crash reports must stage as a .txt attachment, got '$staged'",
                    staged.endsWith(".txt"),
                )
                assertTrue(
                    "the staged text bytes must be uploaded",
                    fakeSession.uploadedFileBytes.single().decodeToString()
                        .contains("SshException"),
                )
                assertTrue("the staging session must be closed", fakeSession.closed)
            } finally {
                kotlinx.coroutines.Dispatchers.setMain(
                    kotlinx.coroutines.test.UnconfinedTestDispatcher(),
                )
            }
        }
    }

    @Test
    fun stageIntoSessionWithNoLiveClientSurfacesFailure() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = seededHost(id = 52L, name = "gpu-box")
        vm.setItem(uriItem("shot.png"))

        vm.stageIntoSession(
            host,
            ActiveSessionTarget(sessionName = "s", cwd = "/x", label = "s"),
        )
        advanceUntilIdle()

        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertEquals("gpu-box", failed.hostName)
        assertTrue(failed.message.contains("save to inbox", ignoreCase = true))
    }

    @Test
    fun stageIntoSessionRefusesSameNameSessionWhoseTmuxIdentityChanged() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = seededHost(id = 56L, name = "gpu-box")
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf("\$99::999"),
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
        var openedStagingSession = false
        vm.connectForStaging = { _, _ ->
            openedStagingSession = true
            Result.success(FakeStagingSshSession())
        }
        vm.setItem(uriItem("shot.png"))

        vm.stageIntoSession(
            host,
            ActiveSessionTarget(
                sessionName = "scratch",
                cwd = "/x",
                label = "scratch",
                tmuxSessionId = "\$7",
                sessionCreated = 777L,
            ),
        )
        advanceUntilIdle()

        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertEquals("gpu-box", failed.hostName)
        assertTrue(failed.message.contains("no longer active", ignoreCase = true))
        assertFalse("stale target must fail before opening staging SSH", openedStagingSession)
        assertTrue(
            "identity revalidation must target the selected session, got ${client.sentCommands}",
            client.sentCommands.single().contains("display-message -p -t 'scratch'"),
        )
    }

    @Test
    fun stageIntoSessionRefusesVanishedNameOnlySessionBeforeStaging() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = seededHost(id = 57L, name = "gpu-box")
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf("can't find session"),
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
        var openedStagingSession = false
        vm.connectForStaging = { _, _ ->
            openedStagingSession = true
            Result.success(FakeStagingSshSession())
        }
        vm.setItem(uriItem("shot.png"))

        vm.stageIntoSession(
            host,
            ActiveSessionTarget(sessionName = "scratch", cwd = "/x", label = "scratch"),
        )
        advanceUntilIdle()

        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertTrue(failed.message.contains("save to inbox", ignoreCase = true))
        assertFalse("vanished target must fail before opening staging SSH", openedStagingSession)
        assertEquals("has-session -t 'scratch'", client.sentCommands.single())
    }

    @Test
    @Config(sdk = [28])
    fun stageIntoSessionTimeoutSurfacesFailureAndClearsRunning() {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Default)
            try {
                val registry = ActiveTmuxClients()
                val vm = newVm(registry, stageIntoSessionTimeoutMs = 50L)
                val host = seededHost(id = 55L, name = "gpu-box")
                registry.register(
                    hostId = host.id,
                    hostName = host.name,
                    hostname = host.hostname,
                    port = host.port,
                    username = host.username,
                    keyPath = "/tmp/key",
                    client = FakeTmuxClient(),
                )
                val fakeSession = FakeStagingSshSession(blockUploads = true)
                vm.connectForStaging = { _, _ -> Result.success(fakeSession) }
                vm.setItem(
                    ShareableItem.TextItem(
                        text = "staging should time out instead of spinning forever",
                        displayName = "timeout-note",
                    ),
                )

                vm.stageIntoSession(
                    host,
                    ActiveSessionTarget(sessionName = "scratch", cwd = "/x", label = "scratch"),
                )
                kotlinx.coroutines.withTimeout(5_000L) {
                    fakeSession.uploadStarted.await()
                }

                val failed = kotlinx.coroutines.withTimeout(5_000L) {
                    vm.uploadState.first { it is UploadState.Failed }
                } as UploadState.Failed

                assertEquals("gpu-box", failed.hostName)
                assertTrue(
                    "timeout failure should explain the bounded staging failure, got: ${failed.message}",
                    failed.message.contains("Timed out", ignoreCase = true) &&
                        failed.message.contains("scratch"),
                )
                assertTrue(
                    "the UI must leave Running after timeout",
                    vm.uploadState.value is UploadState.Failed,
                )
                assertTrue("timed-out staging session must be closed", fakeSession.closed)
            } finally {
                kotlinx.coroutines.Dispatchers.setMain(
                    kotlinx.coroutines.test.UnconfinedTestDispatcher(),
                )
            }
        }
    }

    @Test
    @Config(sdk = [28])
    fun stageIntoSessionFailureInBackgroundPostsAndroidNotificationWithoutFakeActions() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val host = seededHost(id = 54L, name = "gpu-box")
        vm.setItem(uriItem("shot.png"))
        vm.setShareFlowForeground(false)
        val manager = context.getSystemService(NotificationManager::class.java)

        vm.stageIntoSession(
            host,
            ActiveSessionTarget(sessionName = "scratch", cwd = "/home/alexey/git/live", label = "scratch"),
        )
        advanceUntilIdle()

        val failed = vm.uploadState.first { it is UploadState.Failed } as UploadState.Failed
        assertTrue(failed.message.contains("save to inbox", ignoreCase = true))
        val posted = manager.activeNotifications.singleOrNull()
        assertEquals(
            "failed share-to-session uploads must keep Android error feedback",
            "Could not upload to gpu-box",
            posted?.notification?.extras?.getCharSequence("android.title")?.toString(),
        )
        assertEquals(
            failed.message,
            posted?.notification?.extras?.getCharSequence("android.text")?.toString(),
        )
        assertTrue(
            "background failure notification must not add actions that cannot restore the share payload",
            posted?.notification?.actions.isNullOrEmpty(),
        )
    }

    /**
     * Minimal [com.pocketshell.core.ssh.SshSession] fake for the
     * share-into-session staging round-trip: records exec commands +
     * uploaded paths, succeeds every `mkdir -p`, and reports connected.
     */
    private class FakeStagingSshSession(
        private val blockUploads: Boolean = false,
    ) : com.pocketshell.core.ssh.SshSession {
        val execCommands = mutableListOf<String>()
        val uploadedRemotePaths = mutableListOf<String>()
        val uploadedFileBytes = mutableListOf<ByteArray>()
        val uploadStarted = CompletableDeferred<Unit>()
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult {
            execCommands += command
            return com.pocketshell.core.ssh.ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job =
            kotlinx.coroutines.Job().apply { complete() }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): com.pocketshell.core.ssh.SshPortForward =
            throw NotImplementedError("not needed for staging tests")

        override fun startShell(): com.pocketshell.core.ssh.SshShell =
            throw NotImplementedError("not needed for staging tests")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String {
            uploadStarted.complete(Unit)
            if (blockUploads) CompletableDeferred<Unit>().await()
            uploadedRemotePaths += remotePath
            uploadedFileBytes += file.readBytes()
            return remotePath
        }

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String {
            uploadStarted.complete(Unit)
            if (blockUploads) CompletableDeferred<Unit>().await()
            input.readBytes()
            uploadedRemotePaths += remotePath
            return remotePath
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * Records the order of uploaded item names and lets a test pin which
     * items fail, so the [ShareViewModel] multi-file loop + partial-
     * failure aggregation can be asserted without a live SSH session.
     */
    private class FakeUploader(
        var failNames: Set<String> = emptySet(),
        private val blankNames: Set<String> = emptySet(),
    ) : ShareItemUploader {
        val uploadedNames = mutableListOf<String>()

        val uploadedTargets = mutableListOf<ShareTarget>()

        override suspend fun upload(
            host: HostEntity,
            keyEntity: SshKeyEntity,
            item: ShareableItem,
            target: ShareTarget,
            onProgress: ((com.pocketshell.core.ssh.SshUploadProgress) -> Unit)?,
        ): Result<String> {
            val name = item.displayName.orEmpty()
            uploadedNames += name
            uploadedTargets += target
            // Emit a couple of byte-progress ticks (issue #1037) so tests can
            // observe the live UploadState.Running detail update mid-transfer.
            onProgress?.invoke(
                com.pocketshell.core.ssh.SshUploadProgress(bytesTransferred = 0L, totalBytes = 100L),
            )
            onProgress?.invoke(
                com.pocketshell.core.ssh.SshUploadProgress(bytesTransferred = 50L, totalBytes = 100L),
            )
            onProgress?.invoke(
                com.pocketshell.core.ssh.SshUploadProgress(bytesTransferred = 100L, totalBytes = 100L),
            )
            return if (name in failNames) {
                Result.failure(IllegalStateException("Permission denied"))
            } else if (name in blankNames) {
                Result.success("")
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
    private suspend fun seededHost(
        id: Long,
        name: String,
        hasPassphrase: Boolean = false,
    ): HostEntity {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(
                name = "key-$id",
                privateKeyPath = "/tmp/key-$id",
                hasPassphrase = hasPassphrase,
            ),
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

    private fun newVm(
        registry: ActiveTmuxClients,
        stageIntoSessionTimeoutMs: Long = 90_000L,
        sshLeaseManager: SshLeaseManager = SshLeaseManager(
            connector = { Result.failure(SshException("unexpected SSH connect in share unit test")) },
        ),
    ): ShareViewModel {
        return ShareViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            activeTmuxClients = registry,
            projectRootDao = db.projectRootDao(),
            sshLeaseManager = sshLeaseManager,
            stageIntoSessionTimeoutMs = stageIntoSessionTimeoutMs,
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

    /**
     * Issue #1475 reproduction double. Models a live agent `%output` burst that
     * has saturated the shared per-host `-CC` control channel: any `-CC`
     * [sendCommand] round-trip for a `send-keys` has its `%begin`/`%end` reply
     * HEAD-OF-LINE-BLOCKED behind the burst on the ONE sshj transport reader and
     * never returns (the #1459/#1460 send-lane wedge, here for
     * Share→attached-pane). The DEDICATED `send-keys` exec lane
     * ([sendKeysViaExec]) reads its own channel outside the transport dispatcher,
     * so it returns fast even mid-burst. Post-#1488 the pane-id `display-message`
     * RESOLUTION also rides the exec lane ([listPanesViaExec]) — so it answers
     * there, NOT on the wedged `-CC` [sendCommand]. Every other [TmuxClient]
     * member is delegated to a real [FakeTmuxClient].
     */
    private class ShareCcBurstWedgeClient(
        private val paneIdReply: String,
        private val delegate: FakeTmuxClient = FakeTmuxClient(),
    ) : TmuxClient by delegate {

        /** `send-keys` commands that rode the dedicated exec lane (the fix). */
        val execLaneSendKeys: MutableList<String> = mutableListOf()

        /** `send-keys` commands that hit the wedged shared `-CC` path (the bug). */
        val ccSendKeys: MutableList<String> = mutableListOf()

        private val ccWedge: CompletableDeferred<CommandResponse> = CompletableDeferred()

        override suspend fun sendCommand(cmd: String): CommandResponse = when {
            // The interactive send on the shared -CC channel: wedged behind the
            // burst. Parks until the test releases it (models the never-arriving
            // %begin/%end reply stuck behind the burst on the one sshj reader).
            cmd.startsWith("send-keys") -> {
                ccSendKeys += cmd
                ccWedge.await()
            }
            // Post-#1488 the pane-id resolution no longer rides -CC; if it did,
            // it would wedge behind the burst like everything else.
            cmd.startsWith("display-message") -> {
                ccSendKeys += cmd
                ccWedge.await()
            }
            else -> CommandResponse(number = 0L, output = emptyList(), isError = false)
        }

        override suspend fun listPanesViaExec(
            listPanesCommand: String,
            timeoutMs: Long?,
        ): CommandResponse =
            // Issue #1488: the pane-id `display-message` resolution rides the
            // exec lane, so it answers here even while the burst holds `-CC`.
            CommandResponse(number = 0L, output = listOf(paneIdReply), isError = false)

        override suspend fun sendKeysViaExec(
            sendKeysCommand: String,
            timeoutMs: Long?,
        ): CommandResponse {
            execLaneSendKeys += sendKeysCommand
            // `send-keys` prints nothing on success.
            return CommandResponse(number = 0L, output = emptyList(), isError = false)
        }

        fun releaseCcWedge() {
            ccWedge.complete(CommandResponse(number = 0L, output = emptyList(), isError = false))
        }
    }

    /**
     * Issue #1488 reproduction double. Models a live agent `%output` burst that
     * has saturated the shared per-host `-CC` control channel: EVERY `-CC`
     * [sendCommand] read round-trip the share picker path issues — the
     * `list-panes` session enumeration, the `list-sessions` identity resolution,
     * the confirm-time `display-message`/`has-session` staleness check, AND the
     * paste's `display-message -p '#{pane_id}'` resolution — has its
     * `%begin`/`%end` reply HEAD-OF-LINE-BLOCKED behind the burst on the ONE sshj
     * transport reader and never returns. This is the FULL residual H2 wedge the
     * 2026-07-12 transport audit found: the picker runs four serial `-CC` reads,
     * so a share into a Codex-burst host head-of-line-blocked the WHOLE picker
     * for well over a minute (the 10 s idle gate never fires while %output
     * flows; each call bounded only by the 30 s absolute ceiling). The dedicated
     * exec lane ([listPanesViaExec] for the reads, [sendKeysViaExec] for the
     * send) reads its own channel outside the transport dispatcher, so it returns
     * fast even mid-burst. Every other [TmuxClient] member is delegated to a real
     * [FakeTmuxClient].
     */
    private class ShareCcPickerBurstWedgeClient(
        private val paneIdReply: String,
        private val listPanesRows: List<String>,
        private val listSessionsRows: List<String>,
        /** identity the staleness `display-message -t` reports (mismatch => stale). */
        private val staleIdentityReply: String,
        private val delegate: FakeTmuxClient = FakeTmuxClient(),
    ) : TmuxClient by delegate {

        /** picker/paste read queries that rode the dedicated exec lane (the fix). */
        val execLaneResolveCommands: MutableList<String> = mutableListOf()

        /** picker/paste read queries that hit the wedged shared `-CC` path (the bug). */
        val ccResolveCommands: MutableList<String> = mutableListOf()

        /** `send-keys` commands that rode the dedicated exec lane. */
        val execLaneSendKeys: MutableList<String> = mutableListOf()

        private val ccWedge: CompletableDeferred<CommandResponse> = CompletableDeferred()

        private fun isPickerRead(cmd: String): Boolean =
            cmd.startsWith("list-panes") ||
                cmd.startsWith("list-sessions") ||
                cmd.startsWith("display-message") ||
                cmd.startsWith("has-session")

        override suspend fun sendCommand(cmd: String): CommandResponse = when {
            // Any picker/paste read round-trip on the shared -CC channel is
            // wedged behind the burst. Parks until the test releases it (models
            // the never-arriving %begin/%end reply stuck behind the burst on the
            // one sshj reader) — the WHOLE picker head-of-line-blocks here.
            isPickerRead(cmd) -> {
                ccResolveCommands += cmd
                ccWedge.await()
            }
            // A -CC send would wedge the same way; modelled for completeness.
            cmd.startsWith("send-keys") -> ccWedge.await()
            else -> CommandResponse(number = 0L, output = emptyList(), isError = false)
        }

        override suspend fun listPanesViaExec(
            listPanesCommand: String,
            timeoutMs: Long?,
        ): CommandResponse {
            execLaneResolveCommands += listPanesCommand
            val out = when {
                listPanesCommand.startsWith("list-panes") -> listPanesRows
                listPanesCommand.startsWith("list-sessions") -> listSessionsRows
                // The paste pane-id resolution.
                listPanesCommand.contains("#{pane_id}") -> listOf(paneIdReply)
                // The confirm-time staleness check (session_id::session_created).
                listPanesCommand.contains("#{session_id}") -> listOf(staleIdentityReply)
                listPanesCommand.startsWith("has-session") -> emptyList()
                else -> emptyList()
            }
            return CommandResponse(number = 0L, output = out, isError = false)
        }

        override suspend fun sendKeysViaExec(
            sendKeysCommand: String,
            timeoutMs: Long?,
        ): CommandResponse {
            execLaneSendKeys += sendKeysCommand
            // `send-keys` prints nothing on success.
            return CommandResponse(number = 0L, output = emptyList(), isError = false)
        }

        fun releaseCcWedge() {
            ccWedge.complete(CommandResponse(number = 0L, output = emptyList(), isError = false))
        }
    }

    private fun HostEntity.shareTestLeaseTarget(keyPath: String, purpose: String? = null): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = hostname,
                port = port,
                user = username,
                credentialId = if (purpose == null) "$id:$keyPath" else "$id:$keyPath|purpose=$purpose",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(java.io.File(keyPath)),
        )
}
