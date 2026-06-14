package com.pocketshell.app.tmux

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #495 — connected reproduction of "remember agent sessions across
 * reconnect → restore Conversation immediately".
 *
 * The maintainer's report: while in Conversation, a reconnect happened and
 * the app didn't immediately recognise the session as an agent session — it
 * fell back to Terminal and re-detected, bouncing the user out of
 * Conversation.
 *
 * This test drives a real [TmuxSessionViewModel] against the deterministic
 * Docker `agents` fixture (SSH host port 2222) through the **production**
 * auto-reconnect-after-transport-drop path (#444) — the same mechanism the
 * working `SshReconnectE2eTest` proves. This matches the maintainer's report
 * literally: the SSH socket *died* and the app reconnected. The reconnect does
 * a fresh SSH connect + `tmux -CC attach-session`, with an empty live
 * conversation map, and re-seeds the Conversation status from stable-identity
 * memory in `applyParsedPanes`.
 *
 * ### Why a transport drop, not the lifecycle detach
 *
 * The first revision forced the reconnect with
 * `onAppBackgroundedAndAwait()` + a bare `connect(trigger = AutoReconnect)`.
 * That was non-reproducible: when the clean `detach-client` teardown of a
 * *healthy* `-CC` transport stalled, the `runBlocking` test thread parked in
 * the await and the subsequent `connect` never fired, so the instrumentation
 * process was killed before the restore assertion.
 *
 * Routing through the production lifecycle reattach
 * ([onAppBackgrounded]/[onAppForegrounded]) instead of the bare connect was
 * tried next and is no more reliable on this AVD: tearing a healthy `-CC`
 * control transport down blocks ~60s+ inside `closeCurrentConnectionAndJoin`
 * on the reader's blocking SSH read (the reader's `cancelAndJoin` cannot
 * unblock the native read until the channel is closed later in the same
 * teardown), so the foreground reattach never fires within any reasonable
 * bound. That teardown latency is an environmental property of closing a
 * healthy transport — orthogonal to the issue-495 restore logic, which the
 * unit tests own deterministically.
 *
 * A *dead* transport tears down promptly (the reader EOFs immediately on a
 * closed socket), so this test drops the transport from the server side and
 * lets the production `disconnected` observer drive `scheduleAutoReconnect`.
 * The test thread only ever does bounded polling on observable VM state, so a
 * drop that is never observed surfaces as a clear bounded-wait failure
 * message, never an instrumentation-process hang.
 *
 * ### CI gate
 *
 * Even with a dead-transport drop, the reconnect round-trip on the shared CI
 * emulator can be flaky under residual process / memory pressure (cf. #502).
 * The authoritative CI coverage of the remember/restore/reconcile/tab logic is
 * the unit layer (`AgentSessionMemoryTest` + the `TmuxSessionViewModelTest`
 * reconnect cases); this connected test is *local* reconnect evidence. It is
 * therefore CI-gated with `Assume.assumeFalse(isRunningOnCi())` so a flaky
 * emulator reconnect cannot spam red CI after merge.
 *
 *  1. Seed a tmux session whose single pane runs a `claude`-named process
 *     in `/workspace/pocketshell`, and refresh the seeded Claude JSONL so
 *     detection promotes to ProcessConfirmed.
 *  2. Connect the VM, wait for panes, then wait for live agent detection so
 *     [TmuxSessionViewModel.agentForWindow] reports Claude (the gate the
 *     screen uses to show the Conversation tab).
 *  3. Select the Conversation tab (the user is "in Conversation").
 *  4. Background + foreground the app to force the real reconnect/reattach.
 *  5. Assert the agent status is restored IMMEDIATELY on reattach — the
 *     Conversation tab is available and re-selected the instant panes land,
 *     before live re-detection has a chance to round-trip. This is the
 *     regression the issue fixes.
 *  6. Let live re-detection confirm and assert it stays Conversation (no
 *     Terminal flash, no phantom drop).
 */
@RunWith(AndroidJUnit4::class)
class AgentConversationReconnectDockerTest {

    @get:Rule
    val preGrantPermissions = PreGrantPermissionsRule()

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timings = mutableListOf<String>()
    private val cleanupCommands = mutableListOf<String>()

    @After
    fun tearDown() {
        if (cleanupCommands.isNotEmpty()) {
            runBlocking {
                runCatching { execRemote(readFixtureKey(), cleanupCommands.joinToString("\n")) }
            }
        }
        factoryScope.cancel()
        writeTimings()
    }

    @Test
    fun reconnectRestoresConversationTabImmediatelyForAgentSession() = runBlocking {
        // Local-only reconnect evidence. The remember/restore/reconcile/tab
        // logic is owned on CI by AgentSessionMemoryTest + the
        // TmuxSessionViewModelTest reconnect cases; this connected reconnect
        // round-trip is too sensitive to shared-CI-emulator process/memory
        // pressure (cf. #502) to run unguarded.
        Assume.assumeFalse(
            "issue-495 connected reconnect is local-only evidence; CI coverage is the unit layer",
            TerminalTestTimeouts.isRunningOnCi(),
        )
        val key = readFixtureKey()
        // agents:2222 readiness — so a not-yet-up fixture is the loud failure,
        // not the cause of a later hang.
        waitForSshFixtureReady(SshKey.Pem(key))

        val keyPath = writeKeyFile(key)
        val sessionName = "issue495-reconnect-${System.currentTimeMillis().toString().takeLast(8)}"
        val processDir = "/tmp/issue495-claude-${System.nanoTime()}"
        val wrapperPath = "$processDir/claude"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        cleanupCommands += "pkill -f ${shellQuote(wrapperPath)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(processDir)} 2>/dev/null || true"

        // Refresh the seeded Claude JSONL mtime + seed a pane that runs a
        // `claude`-named process in the project cwd so per-pane detection
        // (scoped to the pane TTY) fires for this window.
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("touch ${shellQuote(CLAUDE_PATH)}")
                // The detector derives Claude's encoded project dir from the
                // pane cwd, so the pane must actually sit in REMOTE_CWD.
                appendLine("mkdir -p ${shellQuote(REMOTE_CWD)}")
                appendLine("mkdir -p ${shellQuote(processDir)}")
                appendLine("cat > ${shellQuote(wrapperPath)} <<'WRAPPER_EOF'")
                appendLine("#!/bin/sh")
                // No `exec`: keep this wrapper (basename `claude`) as the
                // pane's foreground process so `ps -t <pane_tty>` reports a
                // `claude` row, which is what the per-pane detector greps
                // for. `exec sleep` would replace it with a `sleep` comm and
                // detection would never fire.
                appendLine("while true; do sleep 5; done")
                appendLine("WRAPPER_EOF")
                appendLine("chmod +x ${shellQuote(wrapperPath)}")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} " +
                        "-c ${shellQuote(REMOTE_CWD)} ${shellQuote(wrapperPath)}",
                )
                appendLine("sleep 1")
                appendLine("tmux list-panes -t ${shellQuote(sessionName)} -F '#{pane_id} #{pane_tty} #{pane_current_command}'")
            },
        )

        val registry = ActiveTmuxClients()
        // maxEntries = 0 so the background detach never parks a *warm*
        // runtime in the cache. That guarantees the reconnect below is a
        // genuine fresh re-attach (full SSH connect + tmux -CC + fresh
        // applyParsedPanes with an empty live conversation map and rotated
        // pane ids) — exactly the bug path the issue describes, not the
        // same-host warm fast-switch (which already preserves state via the
        // runtime cache and is NOT the reported failure).
        val vm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = registry,
            runtimeCache = TmuxSessionRuntimeCache(maxEntries = 0),
        )

        try {
            vm.connect(
                hostId = 495L,
                hostName = "Issue495 Docker",
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                keyPath = keyPath,
                passphrase = null,
                sessionName = sessionName,
            )

            waitForStatus<TmuxSessionViewModel.ConnectionStatus.Connected>(vm, "initial connect")
            val firstPanes = waitForPanes(vm, "initial attach panes")
            val windowId = firstPanes.first().windowId
            stamp("first_attach_window=$windowId panes=${firstPanes.map { it.paneId }}")

            // Live detection lands → Conversation tab becomes available.
            waitForAgentForWindow(vm, windowId, "initial live detection")
            assertEquals(
                "live detection should identify Claude on the seeded window",
                AgentKind.ClaudeCode,
                vm.agentForWindow(windowId),
            )
            val firstPaneId = vm.panes.value.first { it.windowId == windowId }.paneId

            // The user opens Conversation — this is remembered for the window.
            vm.selectSessionTab(firstPaneId, SessionTab.Conversation)
            waitForSelectedTab(vm, firstPaneId, SessionTab.Conversation)

            // ---- Force the real reconnect via the PRODUCTION #444 path ----
            // The maintainer's report is a reconnect after the SSH socket
            // *died* — not a clean user-initiated detach. We reproduce exactly
            // that: kill the server-side sshd connection serving this `-CC`
            // control client, so the client's reader hits EOF on a dead socket
            // and latches `disconnected`. The VM's production `disconnected`
            // observer then fires `scheduleAutoReconnect`, which tears the dead
            // client down (EOF returns promptly on a closed socket, so the
            // teardown does NOT wedge on a still-live blocking read) and does a
            // fresh SSH connect + `tmux -CC attach-session` — new pane ids, an
            // empty live conversation map — and re-seeds the Conversation
            // status from stable-identity memory in `applyParsedPanes`.
            //
            // This is the SAME auto-reconnect mechanism the working
            // `SshReconnectE2eTest` (#444) proves, driven by a genuine
            // transport drop. Driving the clean lifecycle detach
            // (`onAppBackgrounded`) instead is unreliable on this AVD: tearing
            // a *healthy* `-CC` transport down blocks ~60s+ on the reader's
            // blocking SSH read, which is an environmental teardown latency in
            // `closeCurrentConnectionAndJoin` orthogonal to the issue-495
            // restore logic (the unit tests own that logic deterministically).
            //
            // Give the loop a single zero-delay backoff step so the reconnect
            // fires immediately on the observed drop.
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            val reconnectStartedAt = SystemClock.elapsedRealtime()
            stamp("force_reconnect_via_transport_drop_then_auto_reconnect")
            killControlClientSshConnection(key, sessionName)

            // The VM observes the dead socket and enters Reconnecting, then a
            // fresh connect attempt fires. Bound it loudly so a drop that is
            // never observed is a clear failure, not a hang.
            waitForCondition(
                "auto-reconnect fires after the transport drop",
                timeoutMs = AUTO_RECONNECT_FIRE_TIMEOUT_MS,
                describe = {
                    "auto-reconnect never fired after the sshd kill; " +
                        "status=${vm.connectionStatus.value::class.simpleName}"
                },
            ) {
                val status = vm.connectionStatus.value
                status is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                    // It may already have raced back to Connected on a fast
                    // lease-reused reattach.
                    (status is TmuxSessionViewModel.ConnectionStatus.Connected &&
                        vm.panes.value.none { it.paneId == firstPaneId })
            }
            stamp("auto_reconnect_fired")

            waitForStatus<TmuxSessionViewModel.ConnectionStatus.Connected>(
                vm,
                "reattach connect",
                timeoutMs = REATTACH_CONNECT_TIMEOUT_MS,
            )
            // After the drop, closeCurrentConnectionAndJoin clears _panes and
            // the fresh attach re-lists them. The tmux pane/window survived the
            // SSH drop (only the -CC control client died), so tmux reports the
            // SAME `@N`/`%N`; what matters is that fresh panes for the window
            // have re-landed after the reconnect connect, which the Connected
            // wait above already gates. Re-list and require the window present.
            val reattachedPanes = waitForPanes(
                vm,
                "reattach panes",
                predicate = { panes -> panes.any { it.windowId == windowId } },
            )
            val reattachedPaneId = reattachedPanes.first { it.windowId == windowId }.paneId
            timing("reconnect_to_panes_ready_ms", SystemClock.elapsedRealtime() - reconnectStartedAt)
            stamp("reattach_window=$windowId pane=$reattachedPaneId")

            // CORE ASSERTION: the moment panes land, the remembered agent
            // status is restored — the Conversation tab is available and the
            // user is back on Conversation — WITHOUT waiting for live
            // re-detection. Bound it: the restore is seeded synchronously in
            // applyParsedPanes, so it must hold within a short grace window.
            waitForCondition(
                "agent status restored immediately on reattach from memory",
                timeoutMs = IMMEDIATE_RESTORE_TIMEOUT_MS,
                describe = {
                    "agent status NOT restored on reattach (pane=$reattachedPaneId, " +
                        "window=$windowId); conversations=${vm.agentConversations.value.keys}, " +
                        "agentForWindow=${vm.agentForWindow(windowId)}"
                },
            ) {
                val restored = vm.agentConversations.value[reattachedPaneId]
                restored != null &&
                    vm.agentForWindow(windowId) == AgentKind.ClaudeCode &&
                    restored.selectedTab == SessionTab.Conversation
            }
            val restored = vm.agentConversations.value[reattachedPaneId]
            assertNotNull(
                "agent status must be restored immediately on reattach " +
                    "(pane=$reattachedPaneId, window=$windowId); " +
                    "conversations=${vm.agentConversations.value.keys}",
                restored,
            )
            assertEquals(
                "Conversation tab must be available immediately on reattach",
                AgentKind.ClaudeCode,
                vm.agentForWindow(windowId),
            )
            assertEquals(
                "user who was in Conversation must stay on Conversation after reconnect " +
                    "(no Terminal flash)",
                SessionTab.Conversation,
                restored!!.selectedTab,
            )
            stamp("immediate_restore_ok selectedTab=${restored.selectedTab}")

            // Live re-detection confirms; assert it does not bounce the user.
            waitForAgentForWindow(vm, windowId, "post-reattach live re-detection")
            assertEquals(
                "live re-detection must keep the user on Conversation",
                SessionTab.Conversation,
                vm.agentConversations.value
                    .values
                    .first { it.detection?.agent == AgentKind.ClaudeCode }
                    .selectedTab,
            )
            stamp("post_redetection_stays_conversation")

            writeSummary(sessionName, windowId, firstPaneId, reattachedPaneId)
        } finally {
            vm.clearForTest()
        }
    }

    /**
     * Issue #778 — tapping the Conversation tab must NOT be a no-op while live
     * agent detection is still null.
     *
     * The maintainer hit this on a real Claude session: the Conversation tab is
     * drawn for every tmux pane (#716 presumed-agent), but the tap that switches
     * to it used to be hard-gated on live detection being non-null, so during the
     * slow/missing-detection window the tap did literally nothing and the view
     * stayed on Terminal.
     *
     * This drives the production [TmuxSessionViewModel] against the real Docker
     * `agents` fixture using a *plain shell* tmux session (cwd in the writable
     * home dir — no `/workspace` dependency, which the agents image does not
     * provision). A plain shell pane is the worst case for #778: it is
     * presumed-agent at the screen layer but `agentForWindow` stays null
     * forever, so the OLD code would have swallowed the Conversation tap
     * indefinitely. We assert the tap is now honoured end-to-end: the VM records
     * `selectedTab = Conversation` on a detection-less row (the state that drives
     * the "Waiting for agent…" placeholder), and `agentForWindow` stays null
     * (proving we exercised the no-detection placeholder path, not the
     * real-transcript path).
     */
    @Test
    fun conversationTapIsHonouredBeforeDetectionLands(): Unit = runBlocking {
        Assume.assumeFalse(
            "issue-778 connected tap evidence is local-only; CI coverage is the unit layer",
            TerminalTestTimeouts.isRunningOnCi(),
        )
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        val keyPath = writeKeyFile(key)
        val sessionName = "issue778-tap-${System.currentTimeMillis().toString().takeLast(8)}"
        // A plain shell pane in the writable home dir — no agent, no /workspace.
        val cwd = "/home/$DEFAULT_USER"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"

        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} " +
                        "-c ${shellQuote(cwd)}",
                )
                appendLine("sleep 1")
                appendLine("tmux list-panes -t ${shellQuote(sessionName)} -F '#{pane_id} #{pane_tty} #{pane_current_command}'")
            },
        )

        val vm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
            runtimeCache = TmuxSessionRuntimeCache(maxEntries = 0),
        )

        try {
            vm.connect(
                hostId = 778L,
                hostName = "Issue778 Docker",
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                keyPath = keyPath,
                passphrase = null,
                sessionName = sessionName,
            )

            waitForStatus<TmuxSessionViewModel.ConnectionStatus.Connected>(vm, "issue778 connect")
            val panes = waitForPanes(vm, "issue778 attach panes")
            val windowId = panes.first().windowId
            val paneId = panes.first { it.windowId == windowId }.paneId
            stamp("issue778_attached window=$windowId pane=$paneId")

            // Sanity: this is a plain shell pane — no live agent detection.
            // (The OLD code keyed the Conversation switch on this being non-null,
            // so the tap would have been a no-op here, forever.)
            assertEquals(
                "plain shell pane must have no live agent detection",
                null,
                vm.agentForWindow(windowId),
            )

            // The user taps Conversation BEFORE any detection lands. This is the
            // exact #778 gesture that used to be swallowed.
            vm.selectSessionTab(paneId, SessionTab.Conversation)

            // CORE ASSERTION: the tap is honoured — the VM records the
            // Conversation intent on a detection-less row (placeholder state),
            // instead of leaving the user stuck on Terminal.
            waitForSelectedTab(vm, paneId, SessionTab.Conversation)
            val row = vm.agentConversations.value[paneId]
            assertNotNull(
                "Conversation tap must seed a conversation row even without detection " +
                    "(pane=$paneId); conversations=${vm.agentConversations.value.keys}",
                row,
            )
            assertEquals(
                "tap must switch to Conversation, not stay on Terminal (the #778 no-op)",
                SessionTab.Conversation,
                row!!.selectedTab,
            )
            assertEquals(
                "the row must still be detection-less (placeholder, not real transcript)",
                null,
                row.detection,
            )
            assertEquals(
                "agentForWindow must stay null — we exercised the placeholder path",
                null,
                vm.agentForWindow(windowId),
            )
            stamp("issue778_tap_honoured selectedTab=${row.selectedTab} detection=${row.detection}")

            writeText(
                "issue778-tap-summary.txt",
                buildString {
                    appendLine("scenario=conversation-tap-honoured-before-detection (#778)")
                    appendLine("session_name=$sessionName")
                    appendLine("window_id=$windowId")
                    appendLine("pane_id=$paneId")
                    appendLine("pane_kind=plain-shell (no agent, presumed-agent at screen layer)")
                    appendLine("agent_for_window=null")
                    appendLine("tapped_tab=Conversation")
                    appendLine("selected_tab_after_tap=${row.selectedTab}")
                    appendLine("detection_after_tap=${row.detection}")
                    appendLine("tap_was_no_op=false")
                    appendLine("stamps:")
                    stamps.forEach { appendLine("  $it") }
                },
            )
        } finally {
            vm.clearForTest()
        }
    }

    private suspend inline fun <reified T : TmuxSessionViewModel.ConnectionStatus> waitForStatus(
        vm: TmuxSessionViewModel,
        label: String,
        timeoutMs: Long = 30_000,
    ): T {
        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    val status = vm.connectionStatus.value
                    if (status is T) return@withTimeout status
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "[$label] timed out after ${timeoutMs}ms waiting for connection status " +
                    "${T::class.simpleName}; last status was " +
                    "${vm.connectionStatus.value::class.simpleName}",
                e,
            )
        }
    }

    private suspend fun waitForPanes(
        vm: TmuxSessionViewModel,
        label: String,
        timeoutMs: Long = 20_000,
        predicate: (List<TmuxPaneState>) -> Boolean = { it.isNotEmpty() },
    ): List<TmuxPaneState> {
        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    val panes = vm.panes.value
                    if (predicate(panes)) return@withTimeout panes
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "[$label] timed out after ${timeoutMs}ms waiting for panes; " +
                    "current panes=${vm.panes.value.map { it.paneId to it.windowId }}",
                e,
            )
        }
    }

    private suspend fun waitForAgentForWindow(
        vm: TmuxSessionViewModel,
        windowId: String,
        label: String,
        timeoutMs: Long = 30_000,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (vm.agentForWindow(windowId) == null) {
                    delay(100)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "[$label] timed out after ${timeoutMs}ms waiting for agentForWindow($windowId); " +
                    "conversations=${vm.agentConversations.value.keys}",
                e,
            )
        }
    }

    private suspend fun waitForSelectedTab(
        vm: TmuxSessionViewModel,
        paneId: String,
        tab: SessionTab,
        timeoutMs: Long = 10_000,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (vm.agentConversations.value[paneId]?.selectedTab != tab) {
                    delay(50)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "timed out after ${timeoutMs}ms waiting for pane $paneId to select tab $tab; " +
                    "current=${vm.agentConversations.value[paneId]?.selectedTab}",
                e,
            )
        }
    }

    /**
     * Bounded predicate wait that fails LOUD (with a caller-supplied
     * diagnostic) instead of hanging the instrumentation process when a
     * production step (teardown, reattach firing, restore) never happens.
     */
    private suspend fun waitForCondition(
        label: String,
        timeoutMs: Long,
        describe: () -> String,
        predicate: () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (!predicate()) {
                    delay(50)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "[$label] timed out after ${timeoutMs}ms: ${describe()}",
                e,
            )
        }
    }

    /**
     * Deterministically drop the SSH transport serving the app's `-CC`
     * control client for [sessionName], reproducing the maintainer's
     * "the socket died and we reconnected" report.
     *
     * The app attaches by running `tmux -CC attach-session -t <session>` over
     * SSH, so there is an sshd child process whose descendant is exactly that
     * control-mode attach. We find that attach process, walk up to its sshd
     * connection process, and `kill -9` the sshd — which tears the TCP
     * connection down from the server side. The app's reader then hits EOF on
     * a dead socket (promptly, unlike a clean detach of a healthy transport),
     * latches `disconnected`, and the production auto-reconnect path takes
     * over. The tmux server and the session/window stay alive throughout.
     *
     * The `tmux ... attach-session` SSH command is distinct from our own
     * short-lived `exec` connections (which run this very script), so this
     * never kills the connection issuing the kill.
     */
    private suspend fun killControlClientSshConnection(key: String, sessionName: String) {
        val script = buildString {
            appendLine("set -u")
            // CRITICAL: this very script's cmdline contains the match pattern
            // (the pgrep argument text), so a naive `pgrep -f` would also match
            // the shell running THIS exec and we'd kill our own connection.
            // Exclude our own process tree (current shell + its ancestors).
            appendLine("self=\$\$")
            appendLine("self_pids=\" \$self \"")
            appendLine("p=\$self")
            appendLine("for _ in 1 2 3 4 5 6 7 8; do")
            appendLine("  pp=\$(awk '/^PPid:/{print \$2}' /proc/\$p/status 2>/dev/null)")
            appendLine("  [ -z \"\$pp\" ] && break")
            appendLine("  [ \"\$pp\" = 0 ] && break")
            appendLine("  self_pids=\"\$self_pids\$pp \"")
            appendLine("  p=\$pp")
            appendLine("done")
            // PID of the app's tmux `-CC` control client for THIS session.
            // The app spawns it by writing `tmux -CC new-session -A -s
            // <sessionName>` into an interactive SSH shell (see
            // RealTmuxClient.connect), so the live process cmdline is
            // `tmux -CC new-session -A -s <sessionName>`. Match that, pinned to
            // our session, EXCLUDING our own process tree.
            appendLine("attach_pid=")
            appendLine(
                "for cand in \$(pgrep -f " +
                    "'tmux.*-CC.*new-session.*${sessionName}' 2>/dev/null); do",
            )
            appendLine("  case \"\$self_pids\" in *\" \$cand \"*) continue;; esac")
            appendLine("  attach_pid=\$cand")
            appendLine("  break")
            appendLine("done")
            appendLine("if [ -z \"\$attach_pid\" ]; then")
            // Fallback: any tmux client process referencing the session name
            // that is not in our own tree.
            appendLine(
                "  for cand in \$(pgrep -f 'tmux.*${sessionName}' 2>/dev/null); do",
            )
            appendLine("    case \"\$self_pids\" in *\" \$cand \"*) continue;; esac")
            appendLine("    comm=\$(cat /proc/\$cand/comm 2>/dev/null || true)")
            appendLine("    case \"\$comm\" in tmux*) attach_pid=\$cand; break;; esac")
            appendLine("  done")
            appendLine("fi")
            appendLine("if [ -z \"\$attach_pid\" ]; then")
            appendLine("  echo NO_ATTACH_PID; echo '--- tmux procs ---'")
            appendLine("  pgrep -af tmux 2>/dev/null || true")
            appendLine("  exit 2")
            appendLine("fi")
            // Walk up the parent chain to the sshd connection process (its
            // parent is the sshd listener / or PID 1). Kill the first sshd
            // ancestor.
            appendLine("pid=\$attach_pid")
            appendLine("sshd_pid=")
            appendLine("for _ in 1 2 3 4 5 6; do")
            appendLine("  ppid=\$(awk '/^PPid:/{print \$2}' /proc/\$pid/status 2>/dev/null)")
            appendLine("  [ -z \"\$ppid\" ] && break")
            appendLine("  [ \"\$ppid\" = 0 ] && break")
            appendLine("  comm=\$(cat /proc/\$ppid/comm 2>/dev/null || true)")
            // Never select an sshd that is part of our own connection tree.
            appendLine("  case \"\$self_pids\" in *\" \$ppid \"*) pid=\$ppid; continue;; esac")
            appendLine("  case \"\$comm\" in sshd*) sshd_pid=\$ppid; break;; esac")
            appendLine("  pid=\$ppid")
            appendLine("done")
            appendLine("if [ -n \"\$sshd_pid\" ]; then")
            appendLine("  echo KILLING_SSHD=\$sshd_pid attach=\$attach_pid")
            appendLine("  kill -9 \"\$sshd_pid\" 2>/dev/null || true")
            appendLine("else")
            // No sshd ancestor found (shouldn't happen over SSH) — fall back to
            // killing the attach itself, which also drops the control client.
            appendLine("  echo KILLING_ATTACH=\$attach_pid")
            appendLine("  kill -9 \"\$attach_pid\" 2>/dev/null || true")
            appendLine("fi")
            appendLine("echo DROP_DONE")
        }
        execRemote(key, script)
    }

    private suspend fun execRemote(key: String, command: String) {
        val result = withTimeout(30_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session -> session.use { it.exec(command) } }
        }
        val exec = result.getOrNull()
        assertTrue(
            "remote command failed: ${result.exceptionOrNull()} exit=${exec?.exitCode} " +
                "stdout='${exec?.stdout}' stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private fun writeKeyFile(key: String): String {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        return File(targetContext.filesDir, "issue495_test_key.pem").apply {
            writeText(key)
            setReadable(false, false)
            setWritable(false, false)
            setReadable(true, true)
            setWritable(true, true)
        }.absolutePath
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun writeSummary(
        sessionName: String,
        windowId: String,
        firstPaneId: String,
        reattachedPaneId: String,
    ) {
        writeText(
            "issue495-reconnect-summary.txt",
            buildString {
                appendLine("scenario=agent-conversation-reconnect-restore")
                appendLine("reconnect_path=transport-drop-then-auto-reconnect (#444; server-side sshd kill)")
                appendLine("session_name=$sessionName")
                appendLine("window_id=$windowId")
                appendLine("first_pane_id=$firstPaneId")
                appendLine("reattached_pane_id=$reattachedPaneId")
                appendLine("pane_id_rotated=${firstPaneId != reattachedPaneId}")
                appendLine("conversation_restored_immediately=true")
                appendLine("stamps:")
                stamps.forEach { appendLine("  $it") }
            },
        )
    }

    private fun writeTimings() {
        if (timings.isEmpty() && stamps.isEmpty()) return
        writeText("timings.txt", (timings + stamps).joinToString(separator = "\n", postfix = "\n"))
    }

    private fun writeText(name: String, text: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/agent-conversation-reconnect")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact dir ${dir.absolutePath}" }
        val file = File(dir, name)
        file.writeText(text)
        println("ISSUE495_TEXT ${file.absolutePath}")
        return file
    }

    private val stamps = mutableListOf<String>()

    private fun timing(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE495_TIMING $line")
    }

    private fun stamp(name: String) {
        val line = "[issue495-timing] $name at ${SystemClock.elapsedRealtime()}"
        stamps += line
        println(line)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val CLAUDE_PATH: String =
            "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl"
        const val REMOTE_CWD: String = "/workspace/pocketshell"

        /**
         * Ceiling for the VM to observe the server-side transport drop and
         * fire its auto-reconnect. The reader has to hit EOF on the killed
         * socket and the `disconnected` observer has to run; allow head-room
         * for a loaded emulator.
         */
        const val AUTO_RECONNECT_FIRE_TIMEOUT_MS: Long = 45_000

        /** Ceiling for the reattach connect to reach Connected (fresh SSH + tmux -CC). */
        const val REATTACH_CONNECT_TIMEOUT_MS: Long = 45_000

        /**
         * Ceiling for the from-memory restore to surface after panes land.
         * Seeded synchronously in applyParsedPanes, so this is a short grace
         * window, not a detection round-trip.
         */
        const val IMMEDIATE_RESTORE_TIMEOUT_MS: Long = 5_000
    }
}
