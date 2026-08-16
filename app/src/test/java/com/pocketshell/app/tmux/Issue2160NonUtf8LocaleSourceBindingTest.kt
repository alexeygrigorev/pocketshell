package com.pocketshell.app.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxRead
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #2160 — REPRODUCE-FIRST (G10/D33) for: *"Conversation source binding
 * silently dead on hosts whose SSH exec lacks a UTF-8 locale."*
 *
 * ### The mechanism, measured
 *
 * `@ps_agent_source` is stored `"<generation>\t<path>"`. tmux decides at client
 * start-up whether it is in UTF-8 mode, from the invoking process's
 * `LC_ALL`/`LC_CTYPE`/`LANG`; when it is not, everything it prints goes through
 * `utf8_sanitize()`, which replaces each non-printable character and each
 * non-ASCII character with `_`. The TAB becomes `0x5f`,
 * `recordedAgentSourceFromRaw` cannot split, and — because a generation IS live —
 * it returns `null`. Exact-source binding then falls through to the same-kind
 * mtime selector: the #819/#825 wrong-source class, silently restored.
 *
 * Storage is never touched; this is purely read-side, which is why the listing
 * form (`show-options` without `-v`) always looks healthy.
 *
 * Measured on the repository's own `agents` Docker fixture (tmux 3.6b, sshd
 * exports no locale) over a real SSH exec channel:
 *
 * ```
 * $ tmux    show-options -v -t '=s:' @ps_agent_source | od -c
 *   G E N 1 2 3   _   / h o m e / t e s t u s e r / x . j s o n l
 * $ tmux -u show-options -v -t '=s:' @ps_agent_source | od -c
 *   G E N 1 2 3  \t  / h o m e / t e s t u s e r / x . j s o n l
 * ```
 *
 * and on the maintainer's box (tmux 3.4, `LANG=en_US.UTF-8` from sshd) where the
 * bare read is already correct and `-u` changes nothing. So the failure is gated
 * on the HOST's sshd environment, not on the tmux version.
 *
 * ### What makes this a reproduction rather than a proxy
 *
 * [NonUtf8TmuxHost] is the fixture that can ENTER the failing state: it applies
 * tmux's real sanitiser to every option value it prints, **per invocation**,
 * honouring `-u` exactly as tmux does. A happy fixture (one that always returns
 * the intact TAB) cannot enter the state and proves nothing — the v0.4.10/#847
 * lesson. This is the same shape as `AgentConversationRepositoryTest`'s
 * `applyLineClamp`, which emulates the server-side byte clamp so #1225's proof is
 * a genuine red->green.
 *
 * Every case drives the REAL production chain — `applyParsedPanes` ->
 * `startAgentDetectionForPane` -> `AgentConversationRepository` ->
 * `SshSession.exec` — not a helper in isolation.
 *
 * ### Class coverage (G2)
 *
 * 1. the reported symptom: the TAB delimiter destroyed on a first open;
 * 2. #2155's mechanism, which this bug MASKS entirely (`generationScoped =
 *    source != null && currentGeneration.isNotEmpty()` can never be true when the
 *    source never resolves) — the in-session relaunch must re-anchor;
 * 3. a NON-ASCII transcript path, the other byte class the sanitiser destroys
 *    (a Cyrillic project directory), which no TAB-only fix would cover;
 * 4. a runtime ratchet over the commands THIS path issues (see
 *    [everyTmuxCommandIssuedByTheDetectionPathIsLocaleProof] — note the name:
 *    it observes only what this fixture drives). The whole-class ratchet, over
 *    every read site in the production source including files this path never
 *    touches, is [Issue2160LocaleProofReadSiteSourceGuardTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2160NonUtf8LocaleSourceBindingTest : TmuxSessionViewModelTestBase() {

    private companion object {
        const val CWD = "/workspace/proj"
        const val TTY = "/dev/pts/7"
        const val SESSION_ID = "\$0"
        const val FIRST = "/home/u/.claude/projects/-workspace-proj/first.jsonl"
        const val SECOND = "/home/u/.claude/projects/-workspace-proj/second.jsonl"

        /**
         * A busier same-cwd sibling of the SAME kind that flushed MORE recently
         * than the recorded transcript. It is what makes every assertion here
         * discriminating (G6): the mtime selector picks THIS one, so "the
         * recorded source resolved" and "the recorded source was ignored" have
         * different, observable answers. Without it, a fix that stopped
         * resolving the option at all would still look green.
         */
        const val BUSIER_SIBLING = "/home/u/.claude/projects/-workspace-proj/busier.jsonl"

        /** Cyrillic cwd + transcript — the non-ASCII half of the byte class. */
        const val CYRILLIC_CWD = "/home/u/проекты/почта"
        const val CYRILLIC_SOURCE = "/home/u/.claude/projects/-home-u-проекты-почта/первый.jsonl"
        const val CYRILLIC_SIBLING = "/home/u/.claude/projects/-home-u-проекты-почта/второй.jsonl"

        const val KIND_SENTINEL = "@@PS_RECORDED_KIND@@"
        const val GENERATION_SENTINEL = "@@PS_RECORDED_SOURCE_GENERATION@@"
        const val SOURCE_SENTINEL = "@@PS_RECORDED_SOURCE@@"
        const val CLAUDE_WINDOW_SENTINEL = "@@PS_CLAUDE_WINDOW@@"

        /**
         * tmux `utf8_sanitize()`: every character that is not printable ASCII
         * (`0x20`..`0x7e`) is replaced by a single `_`. Verified against real
         * tmux 3.4 and 3.6b — `A<TAB>B` -> `A_B`, `ПРИВЕТ-✓` -> `______-_`
         * (per CHARACTER, not per byte).
         */
        fun utf8Sanitize(value: String): String =
            value.map { ch -> if (ch.code in 0x20..0x7e) ch else '_' }.joinToString("")
    }

    /**
     * A host whose sshd hands the SSH exec channel NO locale — the Docker
     * `agents` fixture, an Alpine/BusyBox container, a hardened sshd with no
     * `AcceptEnv`/PAM locale.
     *
     * It serves genuine `tmux` option state and a genuine cwd-scoped candidate
     * enumeration, and it models the ONE behaviour under test: an option value
     * printed by a tmux client that is not in UTF-8 mode comes back sanitised.
     * The decision is made **per invocation**, by inspecting the `tmux ...`
     * words that precede the option name in the command the app actually sent —
     * so a command that passes `-u` reads clean bytes and a command that does
     * not reads `_`, exactly as measured on the fixture.
     */
    private class NonUtf8TmuxHost(
        var recordedKind: String = "claude",
        var generation: String = "",
        /** The raw `@ps_agent_source` option value, i.e. `"<generation>\t<path>"`. */
        var sourceOption: String = "",
        /** `agent|mtimeSeconds|cwd|path` rows. */
        var candidates: String = "",
        var wcOutput: String = "1\n",
        var windowJsonl: String = "",
        var daemonKind: String = "unknown",
    ) : SshSession {
        val execCommands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        override val isConnected: Boolean = true

        /** Relaunch an agent in the SAME tmux session, exactly as the host CLI does. */
        fun relaunchAgent(newGeneration: String, newSource: String?, candidateRows: String) {
            generation = newGeneration
            sourceOption = newSource?.let { "$newGeneration\t$it" }.orEmpty()
            candidates = candidateRows
        }

        /**
         * The `tmux ...` words that precede [option] in [command], i.e. the
         * invocation that reads it. `@ps_agent_source` is matched so it does NOT
         * also match `@ps_agent_source_generation`.
         */
        private fun invocationReading(command: String, option: String): String? {
            val match = Regex(Regex.escape(option) + "(?![A-Za-z0-9_])").find(command) ?: return null
            val start = command.lastIndexOf("tmux", match.range.first)
            if (start < 0) return null
            return command.substring(start, match.range.first)
        }

        /**
         * The bytes a tmux client prints for [option] when invoked the way
         * [command] invokes it. This IS the bug: without `-u`, the client is not
         * in UTF-8 mode and sanitises.
         */
        fun optionValueAsPrinted(command: String, option: String, stored: String): String {
            val invocation = invocationReading(command, option) ?: return stored
            return if (TmuxRead.isLocaleProofInvocation(invocation)) stored else utf8Sanitize(stored)
        }

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            val kind = optionValueAsPrinted(command, "@ps_agent_kind", recordedKind)
            val gen = optionValueAsPrinted(command, "@ps_agent_source_generation", generation)
            val source = optionValueAsPrinted(command, "@ps_agent_source", sourceOption)
            val stdout = when {
                command.contains("agents kind") ->
                    """{"results":[{"pane_id":"%0","agent_kind":"$daemonKind"}]}"""
                // The #828 single-round-trip recorded-session open.
                command.contains(KIND_SENTINEL) -> buildString {
                    append(kind.trim())
                    append("\n$KIND_SENTINEL\n")
                    append(gen.trim())
                    append("\n$GENERATION_SENTINEL\n")
                    append(source.trim())
                    append("\n$SOURCE_SENTINEL\n")
                    append(candidates)
                    append("\n$CLAUDE_WINDOW_SENTINEL\n")
                }
                // The per-detection enumeration with the source read folded in.
                command.contains(SOURCE_SENTINEL) -> buildString {
                    append(gen.trim())
                    append("\n$GENERATION_SENTINEL\n")
                    append(source.trim())
                    append("\n$SOURCE_SENTINEL\n")
                    append(candidates)
                }
                command.contains(GENERATION_SENTINEL) -> buildString {
                    append(gen.trim())
                    append("\n$GENERATION_SENTINEL\n")
                    append(source.trim())
                }
                command.contains("show-options -v") && command.contains("@ps_agent_kind") -> kind
                command.contains("@@PS_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_WINDOW@@\n$windowJsonl"
                command.contains("wc -l < ") -> wcOutput
                command.startsWith("tail -n ") -> windowJsonl
                command.contains(".claude") ||
                    command.contains(".codex") ||
                    command.contains("opencode") -> candidates
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        val tailJobs: MutableList<CompletableJob> = java.util.concurrent.CopyOnWriteArrayList()

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            Job().also { tailJobs += it }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            Job().also { tailJobs += it }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError()

        override fun startShell(): SshShell = throw NotImplementedError()

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun candidateRow(path: String, ageSeconds: Long, cwd: String = CWD): String =
        "claude|${nowSeconds() - ageSeconds}|$cwd|$path"

    private fun pane(
        paneId: String,
        windowId: String = "@0",
        currentCommand: String = "claude",
        tty: String = TTY,
        cwd: String = CWD,
    ): TmuxSessionViewModel.ParsedPane = TmuxSessionViewModel.ParsedPane(
        paneId = paneId,
        windowId = windowId,
        sessionId = SESSION_ID,
        title = currentCommand,
        paneIndex = 0,
        cwd = cwd,
        currentCommand = currentCommand,
        paneTty = tty,
        panePid = 4242L,
        sessionName = "work",
    )

    private fun TmuxSessionViewModel.connectWith(host: NonUtf8TmuxHost) {
        replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = host,
        )
        setSessionRefForTest(host)
    }

    private fun boundSource(vm: TmuxSessionViewModel, paneId: String): String? =
        vm.agentConversations.value[paneId]?.detection?.sourcePath

    /**
     * THE REPORTED SYMPTOM. On a host with no UTF-8 locale in its SSH exec
     * environment, the TAB in `@ps_agent_source` is read back as `_`, the value
     * cannot be split against the live generation, and exact-source binding is
     * silently replaced by the mtime selector — which binds the busier sibling.
     *
     * RED on base (`44c93855`): binds [BUSIER_SIBLING].
     * GREEN with `tmux -u`: binds [FIRST].
     */
    @Test
    fun recordedSourceBindsOnAHostWhoseSshExecHasNoUtf8Locale() =
        runTest(scheduler) {
            val host = NonUtf8TmuxHost(
                generation = "gen-1",
                sourceOption = "gen-1\t$FIRST",
                candidates = listOf(
                    candidateRow(FIRST, ageSeconds = 120),
                    candidateRow(BUSIER_SIBLING, ageSeconds = 1),
                ).joinToString("\n"),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane("%0")))
            advanceUntilIdle()

            // LOAD-BEARING (G6): the symptom-defining signal is WHICH transcript
            // the Conversation is bound to. Binding BUSIER_SIBLING means the
            // recorded source never resolved — the #819/#825 wrong-source class.
            assertEquals(
                "#2160: the exact recorded transcript must bind on a host whose " +
                    "SSH exec carries no UTF-8 locale. Binding $BUSIER_SIBLING means " +
                    "the TAB in @ps_agent_source was sanitised to '_' on read and " +
                    "exact-source binding silently died. execs=${host.execCommands.size}",
                FIRST,
                boundSource(vm, "%0"),
            )
        }

    /**
     * Class coverage (G2) — this bug MASKS #2155. `generationScoped = source !=
     * null && currentGeneration.isNotEmpty()`; when the source never resolves,
     * #2155's re-anchor can never be reached on an affected host, so its fix is
     * correct but latent. With `-u` the in-session relaunch must re-anchor to the
     * NEW transcript.
     *
     * RED on base: binds [BUSIER_SIBLING] both before AND after the relaunch (the
     * option is never usable at all — strictly worse than the #2155 symptom).
     */
    @Test
    fun inSessionRelaunchReAnchorsOnceTheOptionCanBeReadAtAll() =
        runTest(scheduler) {
            val host = NonUtf8TmuxHost(
                generation = "gen-1",
                sourceOption = "gen-1\t$FIRST",
                candidates = candidateRow(FIRST, ageSeconds = 120),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane("%0")))
            advanceUntilIdle()
            assertEquals(
                "precondition: the first agent's recorded transcript binds",
                FIRST,
                boundSource(vm, "%0"),
            )

            host.relaunchAgent(
                newGeneration = "gen-2",
                newSource = SECOND,
                candidateRows = listOf(
                    candidateRow(FIRST, ageSeconds = 120),
                    candidateRow(SECOND, ageSeconds = 90),
                    candidateRow(BUSIER_SIBLING, ageSeconds = 1),
                ).joinToString("\n"),
            )
            vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "bash")))
            advanceUntilIdle()
            vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "claude")))
            advanceUntilIdle()

            assertEquals(
                "#2160 unmasks #2155: after a new agent starts in the same tmux " +
                    "session the Conversation must re-anchor to $SECOND. Binding " +
                    "$BUSIER_SIBLING means the option read is still unusable, so " +
                    "#2155's generation comparison is unreachable in production.",
                SECOND,
                boundSource(vm, "%0"),
            )
        }

    /**
     * Class coverage (G2) — the OTHER byte class the sanitiser destroys. The TAB
     * is not special: `utf8_sanitize()` replaces every non-printable-ASCII
     * character, so a transcript path under a Cyrillic project directory is
     * mangled beyond recognition even if the delimiter survived. A fix that only
     * moved the delimiter to a printable byte would NOT cover this; forcing the
     * client into UTF-8 mode does.
     */
    @Test
    fun recordedSourceWithANonAsciiPathBindsExactly() =
        runTest(scheduler) {
            val host = NonUtf8TmuxHost(
                generation = "gen-1",
                sourceOption = "gen-1\t$CYRILLIC_SOURCE",
                candidates = listOf(
                    candidateRow(CYRILLIC_SOURCE, ageSeconds = 120, cwd = CYRILLIC_CWD),
                    candidateRow(CYRILLIC_SIBLING, ageSeconds = 1, cwd = CYRILLIC_CWD),
                ).joinToString("\n"),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane("%0", cwd = CYRILLIC_CWD)))
            advanceUntilIdle()

            assertEquals(
                "#2160 (G2, non-ASCII): a Cyrillic transcript path must survive " +
                    "the option read intact. Binding $CYRILLIC_SIBLING means every " +
                    "multi-byte character came back as '_' and the recorded path " +
                    "matched nothing.",
                CYRILLIC_SOURCE,
                boundSource(vm, "%0"),
            )
        }

    /**
     * A ratchet over the commands the `AgentConversationRepository` detection
     * path ACTUALLY ISSUES when this fixture drives it — including the options
     * that carry no control bytes today (`@ps_agent_kind`) and would therefore
     * never redden a value assertion.
     *
     * **Its reach is exactly that path**, which is why it is named for it. It
     * cannot see a read site in a file this fixture never drives — round 1
     * proved that the hard way: it was green while
     * `SshHostTmuxSessionsGateway`'s cold picker enumeration and
     * `TmuxSessionViewModel.refreshCurrentSessionRecordedKind` were both still
     * bare. The whole-class sweep over the production SOURCE, which does reach
     * those, is [Issue2160LocaleProofReadSiteSourceGuardTest].
     */
    @Test
    fun everyTmuxCommandIssuedByTheDetectionPathIsLocaleProof() =
        runTest(scheduler) {
            val host = NonUtf8TmuxHost(
                generation = "gen-1",
                sourceOption = "gen-1\t$FIRST",
                candidates = listOf(
                    candidateRow(FIRST, ageSeconds = 120),
                    candidateRow(BUSIER_SIBLING, ageSeconds = 1),
                ).joinToString("\n"),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane("%0")))
            advanceUntilIdle()
            // Drive the relaunch path too so BOTH the #828 folded open and the
            // per-detection enumeration contribute their commands.
            host.relaunchAgent(
                newGeneration = "gen-2",
                newSource = SECOND,
                candidateRows = candidateRow(SECOND, ageSeconds = 5),
            )
            vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "bash")))
            advanceUntilIdle()
            vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "claude")))
            advanceUntilIdle()

            val tmuxInvocations = host.execCommands
                .flatMap { command -> Regex("""\btmux\b[^\n;|&]*""").findAll(command).map { it.value } }
                .distinct()
            assertTrue(
                "the conversation/detection path must issue tmux commands at all; " +
                    "execs=${host.execCommands.size}",
                tmuxInvocations.isNotEmpty(),
            )
            val bare = tmuxInvocations.filterNot { TmuxRead.isLocaleProofInvocation(it) }
            assertTrue(
                "#2160: every tmux command this detection path ISSUES must use " +
                    "`${TmuxRead.CLIENT}` so a host without a UTF-8 locale cannot " +
                    "sanitise the bytes we parse. (Read sites this path never drives " +
                    "are covered by Issue2160LocaleProofReadSiteSourceGuardTest.) " +
                    "Bare invocations: $bare",
                bare.isEmpty(),
            )
        }
}
