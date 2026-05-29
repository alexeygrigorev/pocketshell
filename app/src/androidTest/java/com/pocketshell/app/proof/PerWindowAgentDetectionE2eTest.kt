package com.pocketshell.app.proof

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #186 regression test — agent detection must be **per window**,
 * not per session.
 *
 * The maintainer's v0.2.8 feedback: a tmux session with 3 windows
 * where only Window 1 had Claude running, but the app surfaced the
 * old agent-detected chip on Windows 2 and 3 too. The root cause
 * was that [com.pocketshell.app.session.AgentConversationRepository.detect]
 * runs a host-wide process scan, so a JSONL file modified in any window
 * (or shared across panes via cwd) light up the Conversation tab on
 * every pane in the session.
 *
 * The fix introduces
 * [com.pocketshell.app.session.AgentConversationRepository.detectForPane]
 * which scopes the process scan to the pane's TTY (via `ps -t <tty>`)
 * and requires the agent's command to appear on that TTY. This test
 * pins that contract against the deterministic Docker `agents` fixture:
 *
 *  1. Seed a tmux session with three windows. Window 0's pane runs a
 *     `claude`-named wrapper process; Windows 1 and 2 each run a plain
 *     `sleep` (no agent in sight).
 *  2. Touch the seeded Claude JSONL so its mtime sits inside the
 *     detector's 5-minute recency window. Both panes share the same
 *     cwd (`/workspace/pocketshell`) so the JSONL is reachable from
 *     either pane.
 *  3. Resolve each window's pane TTY via `tmux list-panes -F '#{pane_tty}'`.
 *  4. Call [AgentConversationRepository.detectForPane] for each.
 *  5. Assert: Window 0's pane returns a non-null Claude detection;
 *     Windows 1 and 2 each return null.
 *
 * This test is intentionally repository-scoped (not a full UI E2E)
 * because the bug is a data-layer attribution failure, not a
 * presentation layer issue — once the detection per-window contract
 * holds, the screen's per-pane Conversation tab + hint banner trivially
 * fall out from the existing [TmuxSessionScreen] composition.
 */
@RunWith(AndroidJUnit4::class)
class PerWindowAgentDetectionE2eTest {

    private val timings = mutableListOf<String>()
    private val cleanupCommands = mutableListOf<String>()

    @After
    fun cleanup() {
        if (cleanupCommands.isEmpty()) return
        runBlocking {
            runCatching {
                withSshSession { session ->
                    session.exec(cleanupCommands.joinToString("\n"))
                }
            }
        }
    }

    @Test
    fun onlyClaudeWindowReturnsDetectionForOtherWindowsItStaysNull() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        val started = System.currentTimeMillis()
        val sessionName = "issue186-perwin-${System.currentTimeMillis().toString().takeLast(8)}"
        val processDir = "/tmp/$CLAUDE_PROCESS_TAG-${System.nanoTime()}"
        val wrapperPath = "$processDir/claude"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        cleanupCommands += "pkill -f ${shellQuote(wrapperPath)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(processDir)} 2>/dev/null || true"

        withSshSession { session ->
            // Refresh the seeded JSONL mtime so it sits within the
            // detector's 5-minute recency window. The fixture image
            // copies the file at build time, which is far in the past.
            val touch = session.exec("touch ${shellQuote(CLAUDE_PATH)}")
            assertEquals(
                "expected to refresh seeded Claude JSONL mtime, stderr='${touch.stderr}'",
                0,
                touch.exitCode,
            )

            // Seed a tmux session with three windows whose pane
            // processes are deliberately shaped so per-pane detection
            // has a discriminating signal:
            //
            // Window 0: a tiny shell wrapper with the exact agent CLI
            //   basename (`claude`) runs `sleep 600` as a child.
            //   `ps -t <tty> -o comm,args` on this pane's TTY reports
            //   a row whose `comm`/`args` contain "claude" — which is
            //   the substring the detector's `namesAgent` grep keys
            //   off (case-insensitive `contains("claude")`).
            // Windows 1 and 2: each runs a plain `sleep 600`. Their
            //   panes are alive (sleep keeps the PTY open) but
            //   `ps -t <tty>` shows only `sleep` — no
            //   "claude"/"codex"/"opencode" anywhere. Per-window
            //   detection must therefore return null for both panes.
            //   Two non-agent windows (instead of one) makes the
            //   "doesn't bleed through" assertion robust against an
            //   accidental short-circuit that ignores window index.
            //
            // Why a script + child sleep rather than `exec -a`:
            // the fixture container's userland uses busybox `sleep`,
            // which dispatches by argv[0]. `exec -a claude sleep 600`
            // makes argv[0]="claude", and busybox then refuses to
            // run because "claude" is not a recognised applet. Naming
            // the wrapper *script* "claude-issue186-perwin" and
            // launching it as a child process avoids that path
            // entirely: the kernel resolves the binary by argv[0]
            // (which IS the script's name), and ps's `comm` column
            // ends up populated from the script's filename.
            //
            // Both panes are launched with `-c /workspace/pocketshell`
            // so their `pane_current_path` matches the cwd that the
            // seeded Claude JSONL was indexed under. Without that
            // alignment, the JSONL find would not even pick up the
            // candidate and we'd be testing the wrong gate.
            val setup = buildString {
                append("set -eu; ")
                append("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true; ")
                append("rm -rf ${shellQuote(processDir)} 2>/dev/null || true; ")
                append("mkdir -p ${shellQuote(processDir)}; ")
                // Build the claude-named wrapper script. The script
                // body intentionally does NOT `exec sleep` — that
                // would replace the script's process image with
                // sleep's, and ps would then report `comm=sleep`
                // instead of the wrapper's name (which is what we
                // want the detector to grep). A child-process sleep
                // keeps the script alive as the pane's foreground
                // process, with comm/args carrying the "claude"
                // substring.
                append("cat > ${shellQuote(wrapperPath)} <<'WRAPPER_EOF'\n")
                append("#!/bin/sh\n")
                append("sleep 600\n")
                append("WRAPPER_EOF\n")
                append("chmod +x ${shellQuote(wrapperPath)}; ")
                // Window 0 — claude-named wrapper on its TTY.
                append(
                    "tmux new-session -d -s ${shellQuote(sessionName)} " +
                        "-c ${shellQuote(REMOTE_CWD)} ${shellQuote(wrapperPath)}; ",
                )
                // Windows 1 + 2 — plain sleep, no agent name on
                // either TTY. `new-window -t '<session>:'` (note the
                // trailing colon) is tmux's syntax for "add a window
                // to this session". Without the colon, `-t NAME` is
                // treated as a window name and tmux errors with
                // "can't find window: NAME".
                append(
                    "tmux new-window -t ${shellQuote("$sessionName:")} " +
                        "-c ${shellQuote(REMOTE_CWD)} ${shellQuote("sleep 600")}; ",
                )
                append(
                    "tmux new-window -t ${shellQuote("$sessionName:")} " +
                        "-c ${shellQuote(REMOTE_CWD)} ${shellQuote("sleep 600")}; ",
                )
                append("sleep 0.5; ")
                // Dump the pane TTYs + their foreground processes for
                // diagnostic visibility in the test logs.
                append(
                    "tmux list-panes -s -t ${shellQuote(sessionName)} " +
                        "-F '#{window_index}\t#{pane_index}\t#{pane_id}\t#{pane_tty}\t#{pane_current_command}'",
                )
            }
            val setupResult = session.exec(setup)
            assertEquals(
                "tmux setup failed: stderr='${setupResult.stderr}' stdout='${setupResult.stdout}'",
                0,
                setupResult.exitCode,
            )
            recordTiming("setup_ms", System.currentTimeMillis() - started)
            println("ISSUE186_PERWIN_TMUX_PANES\n${setupResult.stdout.trim()}")
            // Sanity: tmux must have returned three pane rows. If
            // fewer show, one of the new-* commands failed silently
            // and the rest of the test would chase a misattributed
            // signal.
            val paneRows = setupResult.stdout.lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(
                "expected tmux to seed exactly 3 panes (one per window); got rows=$paneRows " +
                    "from stdout='${setupResult.stdout}'",
                3,
                paneRows.size,
            )
        }

        // Resolve the pane TTYs via a fresh display-message call so we
        // have authoritative per-window TTY strings to feed into
        // [detectForPane]. The session+window+pane format matches what
        // [TmuxSessionViewModel] sees in its own `list-panes -F` reply.
        // Use `|` as the field separator here (instead of `\t`) so the
        // value survives any shell / SSH layer that might collapse or
        // re-quote raw tab bytes; tmux's `-F` argument carries the
        // literal pipes inside the single-quoted format string.
        val (claudeWindow, plainWindowA, plainWindowB) = withSshSession { session ->
            val raw = session.exec(
                "tmux list-panes -s -t ${shellQuote(sessionName)} " +
                    "-F '#{window_index}|#{pane_id}|#{pane_tty}'",
            )
            assertEquals(
                "list-panes for $sessionName should succeed, stderr='${raw.stderr}'",
                0,
                raw.exitCode,
            )
            println("ISSUE186_PERWIN_LISTPANES_RAW '${raw.stdout}'")
            val rows = raw.stdout
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    val parts = line.split('|')
                    require(parts.size >= 3) {
                        "malformed list-panes row: '$line' " +
                            "(expected 3 |-separated fields, got ${parts.size})"
                    }
                    PaneInfo(
                        windowIndex = parts[0].trim().toInt(),
                        paneId = parts[1],
                        paneTty = parts[2],
                    )
                }
                .toList()
            val w0 = rows.firstOrNull { it.windowIndex == 0 }
                ?: error("expected window 0 in $sessionName, got rows=$rows")
            val w1 = rows.firstOrNull { it.windowIndex == 1 }
                ?: error("expected window 1 in $sessionName, got rows=$rows")
            val w2 = rows.firstOrNull { it.windowIndex == 2 }
                ?: error("expected window 2 in $sessionName, got rows=$rows")
            // Sanity: all three windows must have DISTINCT TTYs —
            // that's the whole reason per-pane scoping works.
            val distinctTtys = setOf(w0.paneTty, w1.paneTty, w2.paneTty)
            require(distinctTtys.size == 3) {
                "expected three windows to have distinct TTYs; got w0=${w0.paneTty} " +
                    "w1=${w1.paneTty} w2=${w2.paneTty}"
            }
            Triple(w0, w1, w2)
        }

        // Run the production detect-for-pane path against each window.
        // Window 0 has the claude-named process on its TTY → expect a
        // detection. Windows 1 and 2 have only bare `sleep` → expect
        // null on both.
        val repo = AgentConversationRepository()
        withSshSession { session ->
            val detectStarted = System.currentTimeMillis()
            val detectionWindow0: AgentDetection? = withTimeout(20_000) {
                repo.detectForPane(
                    session = session,
                    cwd = REMOTE_CWD,
                    paneTty = claudeWindow.paneTty,
                    paneCommand = "bash",
                )
            }
            recordTiming("window0_detect_ms", System.currentTimeMillis() - detectStarted)
            assertNotNull(
                "expected detectForPane to return a Claude detection on window 0 " +
                    "(pane tty=${claudeWindow.paneTty}); this window has the claude-named " +
                    "process on its TTY",
                detectionWindow0,
            )
            assertEquals(
                "detection on window 0 must be Claude (the only agent we seeded)",
                AgentKind.ClaudeCode,
                detectionWindow0!!.agent,
            )
            assertEquals(
                "detection on window 0 must be ProcessConfirmed (the claude process " +
                    "is alive on the pane's TTY)",
                AgentDetection.Confidence.ProcessConfirmed,
                detectionWindow0.confidence,
            )
            assertEquals(
                "detection sourcePath should point at the seeded JSONL",
                CLAUDE_PATH,
                detectionWindow0.sourcePath,
            )

            for ((index, plainWindow) in listOf(
                "1" to plainWindowA,
                "2" to plainWindowB,
            )) {
                val plainStarted = System.currentTimeMillis()
                val plainDetection: AgentDetection? = withTimeout(20_000) {
                    repo.detectForPane(
                        session = session,
                        cwd = REMOTE_CWD,
                        paneTty = plainWindow.paneTty,
                        paneCommand = "bash",
                    )
                }
                recordTiming(
                    "window${index}_detect_ms",
                    System.currentTimeMillis() - plainStarted,
                )
                assertNull(
                    "expected detectForPane to return null on window $index " +
                        "(pane tty=${plainWindow.paneTty}); this window has only a plain " +
                        "sleep and must NOT inherit a sibling window's agent detection. " +
                        "Got: $plainDetection",
                    plainDetection,
                )
            }
        }

        // Sanity: blank TTY input must always return null — defensive
        // contract that legacy session-scoped callers cannot
        // accidentally fall back into per-pane detection without a
        // real TTY signal.
        withSshSession { session ->
            val emptyTtyResult = repo.detectForPane(
                session = session,
                cwd = REMOTE_CWD,
                paneTty = "",
                paneCommand = "bash",
            )
            assertNull(
                "detectForPane with empty paneTty must return null (no signal = no detection)",
                emptyTtyResult,
            )
        }

        recordTiming("total_ms", System.currentTimeMillis() - started)
        writeTimings()
    }

    // ----------------------------------------------------------- Helpers

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val key = readFixtureKey()
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun recordTiming(label: String, value: Long) {
        val line = "ISSUE186_PERWIN_TIMING $label=$value"
        timings += line
        println(line)
    }

    private fun writeTimings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/per-window-agent-detection")
        if (!dir.exists() && !dir.mkdirs()) return
        val file = File(dir, "timings.txt")
        FileOutputStream(file).use { out ->
            out.write(
                timings.joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.UTF_8),
            )
        }
        println("ISSUE186_PERWIN_TIMINGS ${file.absolutePath}")
    }

    private data class PaneInfo(
        val windowIndex: Int,
        val paneId: String,
        val paneTty: String,
    )

    private companion object {
        // Same fixture-seeded paths as AgentDetectionAcrossEnginesE2eTest
        // — single source of truth, kept in sync via the shared
        // `tests/docker/agent-entrypoint.sh`.
        const val CLAUDE_PATH: String =
            "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl"
        const val REMOTE_CWD: String = "/workspace/pocketshell"
        // Unique directory prefix used by the seeded "claude on this
        // TTY" wrapper. The executable basename remains exactly
        // `claude`, matching the same command-token shape as the real
        // CLI while keeping cleanup scoped to this test's temp path.
        const val CLAUDE_PROCESS_TAG: String = "claude-issue186-perwin"
    }
}
