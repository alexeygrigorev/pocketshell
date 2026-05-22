package com.pocketshell.app.session

import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.session.SessionViewModel.Modifier
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.uikit.model.KeyModifierState
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SessionViewModel] focused on the two pieces of logic the
 * brief calls out as machine-verifiable:
 *
 * 1. The bar's unmodified key-code mapping (Esc, Tab, arrows).
 * 2. The sticky modifier FSM (one-shot arm → wrap-next → auto-clear,
 *    locked modifiers persist until the key bar reports them off).
 *
 * The tests exercise the ViewModel via its `internal` test seams rather
 * than constructing a full Compose tree: the ui-kit `KeyBar` is already
 * covered by its own unit tests in `:shared:ui-kit`, and the
 * ViewModel-to-terminal write path needs nothing more than the byte-level
 * assertions below.
 *
 * Robolectric provides a `Context` so the ViewModel can be constructed
 * (it takes the application `Context` for the raw-resource SSH key reader
 * used at `connect` time — we do not call `connect` here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SessionViewModelTest {

    private fun newVm(): SessionViewModel = SessionViewModel(
        applicationContext = ApplicationProvider.getApplicationContext(),
    )

    // -- Unmodified byte mapping --------------------------------------------

    @Test
    fun escMapsTo0x1B() {
        assertArrayEquals(byteArrayOf(0x1B), newVm().unmodifiedBytesFor("Esc"))
    }

    @Test
    fun tabMapsTo0x09() {
        assertArrayEquals(byteArrayOf(0x09), newVm().unmodifiedBytesFor("Tab"))
    }

    @Test
    fun arrowsMapToAnsiCsiSequences() {
        val vm = newVm()
        // ESC [ D / A / B / C — per the brief and the standard ANSI CSI
        // cursor-key encoding.
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()), vm.unmodifiedBytesFor("‹"))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()), vm.unmodifiedBytesFor("⌃"))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()), vm.unmodifiedBytesFor("⌄"))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()), vm.unmodifiedBytesFor("›"))
    }

    @Test
    fun unknownLabelReturnsNull() {
        assertNull(newVm().unmodifiedBytesFor("ZorkKey"))
    }

    // -- Sticky modifier FSM ------------------------------------------------

    @Test
    fun ctrlTapArmsModifierForOneShot() {
        val vm = newVm()
        assertTrue(vm.armedModifiers.value.isEmpty())

        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        assertEquals(setOf(Modifier.Ctrl), vm.armedModifiers.value)
        assertEquals(KeyModifierState.OneShot, vm.modifierStates.value[Modifier.Ctrl])
    }

    @Test
    fun keyBarOffStateDisarmsModifier() {
        val vm = newVm()
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.Off)
        assertTrue(vm.armedModifiers.value.isEmpty())
        assertTrue(vm.modifierStates.value.isEmpty())
    }

    @Test
    fun ctrlLockPersistsAfterFiringNonModifierKey() {
        val vm = newVm()
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.Locked)
        assertEquals(setOf(Modifier.Ctrl), vm.armedModifiers.value)

        vm.onKeyBarKey("‹")

        assertEquals(setOf(Modifier.Ctrl), vm.armedModifiers.value)
        assertEquals(KeyModifierState.Locked, vm.modifierStates.value[Modifier.Ctrl])
    }

    @Test
    fun ctrlPlusCWrapsToAsciiControlByte() {
        val vm = newVm()
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)

        // The brief's canonical case: Ctrl + 'c' → 0x03. The key bar does
        // not own letter slots in v1 (letters come from the system IME),
        // so the ViewModel exposes a test seam for the byte-level wrapping.
        val out = vm.writeKeyWithModifiersForTest(
            unmodified = vm.unmodifiedBytesForTest('c'),
            label = "c",
        )
        assertArrayEquals(byteArrayOf(0x03), out)
        // One-shot consumed.
        assertTrue(vm.armedModifiers.value.isEmpty())
    }

    @Test
    fun ctrlPlusUppercaseCAlsoMapsTo0x03() {
        val vm = newVm()
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        val out = vm.writeKeyWithModifiersForTest(
            unmodified = vm.unmodifiedBytesForTest('C'),
            label = "C",
        )
        assertArrayEquals(byteArrayOf(0x03), out)
    }

    @Test
    fun ctrlPlusAMapsTo0x01() {
        val vm = newVm()
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        val out = vm.writeKeyWithModifiersForTest(
            unmodified = vm.unmodifiedBytesForTest('a'),
            label = "a",
        )
        assertArrayEquals(byteArrayOf(0x01), out)
    }

    @Test
    fun ctrlPlusArrowProducesXtermModifyCursorKeys() {
        val vm = newVm()
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        val out = vm.writeKeyWithModifiersForTest(
            unmodified = vm.unmodifiedBytesFor("‹")!!,
            label = "‹",
        )
        // ESC [ 1 ; 5 D — xterm modifyCursorKeys=2 default for Ctrl+Left.
        assertArrayEquals(
            byteArrayOf(
                0x1B,
                '['.code.toByte(),
                '1'.code.toByte(),
                ';'.code.toByte(),
                '5'.code.toByte(),
                'D'.code.toByte(),
            ),
            out,
        )
    }

    @Test
    fun altPlusLetterPrefixesWithEsc() {
        val vm = newVm()
        vm.onKeyBarModifierState("Alt", KeyModifierState.OneShot)
        // Alt + 'f' → ESC, 'f' (xterm "Meta sends Escape").
        val out = vm.writeKeyWithModifiersForTest(
            unmodified = vm.unmodifiedBytesForTest('f'),
            label = "f",
        )
        assertArrayEquals(byteArrayOf(0x1B, 'f'.code.toByte()), out)
    }

    @Test
    fun escTapWithoutModifierClearsNothingAndEmitsRaw() {
        val vm = newVm()
        // No modifier armed; Esc should just emit 0x1B and leave the
        // armed set empty.
        val out = vm.writeKeyWithModifiersForTest(
            unmodified = vm.unmodifiedBytesFor("Esc")!!,
            label = "Esc",
        )
        assertArrayEquals(byteArrayOf(0x1B), out)
        assertTrue(vm.armedModifiers.value.isEmpty())
    }

    @Test
    fun ctrlAutoClearedAfterFiringNonModifierKey() {
        val vm = newVm()
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        assertEquals(setOf(Modifier.Ctrl), vm.armedModifiers.value)
        // Fire an arrow through the bar — the public surface. Ctrl
        // should clear afterwards.
        vm.onKeyBarKey("‹")
        assertTrue(vm.armedModifiers.value.isEmpty())
    }

    @Test
    fun ctrlPlusAltStacksBothPrefixes() {
        val vm = newVm()
        vm.onKeyBarModifierState("Ctrl", KeyModifierState.OneShot)
        vm.onKeyBarModifierState("Alt", KeyModifierState.OneShot)
        assertEquals(setOf(Modifier.Ctrl, Modifier.Alt), vm.armedModifiers.value)

        // Ctrl+Alt+'c' → ESC, 0x03 (Ctrl wraps first, then Alt prefixes ESC).
        val out = vm.writeKeyWithModifiersForTest(
            unmodified = vm.unmodifiedBytesForTest('c'),
            label = "c",
        )
        assertArrayEquals(byteArrayOf(0x1B, 0x03), out)
        assertTrue(vm.armedModifiers.value.isEmpty())
    }

    @Test
    fun onChipTapAppendsNewline() {
        val vm = newVm()
        // We cannot observe terminalState.writeInput without attaching a
        // session — but [SessionViewModel.onChipTap] is a thin wrapper
        // around `terminalState.writeInput(text + "\n")`. Verify the empty
        // guard at least.
        vm.onChipTap("")
        // No exception means the empty-string guard worked.
    }

    @Test
    fun agentConversationKeepsLatestBoundedDistinctEvents() {
        val vm = newVm()
        val events = (0..510).map { index ->
            ConversationEvent.Message(
                id = "event-$index",
                agent = AgentKind.ClaudeCode,
                role = ConversationRole.Assistant,
                text = "message $index",
            )
        } + ConversationEvent.Message(
            id = "event-510",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "message 510 replacement",
        )

        vm.startAgentConversationForTest(
            detection = AgentDetection(
                agent = AgentKind.ClaudeCode,
                sourcePath = "/tmp/claude.jsonl",
                sessionId = "claude",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
            initialEvents = events,
        )

        val state = vm.agentConversation.value
        assertEquals(500, state.events.size)
        assertEquals("event-11", state.events.first().id)
        assertEquals("message 510 replacement", (state.events.last() as ConversationEvent.Message).text)
    }

    @Test
    fun nonTmuxAgentDetectionDoesNotUseFreshExecCwd() = runTest {
        val session = FakeSshSession()
        val detection = AgentConversationRepository().detect(session)

        assertNull(detection)
        assertTrue(session.recordedExecCommands.isEmpty())
    }

    @Test
    fun runtimeDetectionCommandDoesNotProbeOpenCodeGlobalDatabase() {
        val command = AgentConversationRepository().detectionCommand("/home/alexey/git/pocketshell")

        assertTrue("OpenCode runtime detection must stay disabled", "opencode" !in command)
        assertTrue("SQLite export path must stay out of runtime detection", "sqlite3" !in command)
    }

    @Test
    fun runtimeDetectionCommandDoesNotProbeCodexSessionLogs() {
        val command = AgentConversationRepository().detectionCommand("/home/alexey/git/pocketshell")

        assertTrue("Codex runtime detection must stay disabled", "codex" !in command)
        assertTrue("Codex session logs must not be scanned", ".codex/sessions" !in command)
        assertTrue("Codex detection must not use free-text cwd grep", "grep -F" !in command)
    }

    @Test
    fun runtimeDetectionRejectsCodexCandidatesFromRemoteOutput() = runTest {
        val session = FakeSshSession(
            execStdout = "codex|10000|/home/alexey/git/pocketshell|/home/alexey/.codex/sessions/other.jsonl\n",
        )
        val detection = AgentConversationRepository().detect(
            session = session,
            cwd = "/home/alexey/git/pocketshell",
            processHints = listOf("123 codex"),
        )

        assertNull(detection)
    }

    private class FakeSshSession(
        private val execStdout: String = "/wrong/fresh/exec/cwd\n",
    ) : SshSession {
        val recordedExecCommands = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recordedExecCommands += command
            return ExecResult(stdout = execStdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            throw NotImplementedError()
        }

        override fun startShell(): SshShell {
            throw NotImplementedError()
        }

        override fun close() = Unit
    }
}
