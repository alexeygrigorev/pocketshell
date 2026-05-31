package com.pocketshell.app.session

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.session.SessionViewModel.Modifier
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.uikit.model.KeyModifierState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {

    private fun newVm(
        projectRootDao: ProjectRootDao? = null,
    ): SessionViewModel = SessionViewModel(
        applicationContext = ApplicationProvider.getApplicationContext(),
        projectRootDao = projectRootDao,
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
    fun onChipTapAcceptsEmptyString() {
        val vm = newVm()
        // We cannot observe terminalState.writeInput without attaching a
        // session — but [SessionViewModel.onChipTap] is a thin wrapper
        // around `terminalState.writeInput(text + "\r")`. Verify the empty
        // guard at least.
        vm.onChipTap("")
        // No exception means the empty-string guard worked.
    }

    @Test
    fun projectNavigationBindsStoredRoots() = runTest {
        val dao = FakeProjectRootDao()
        dao.roots.value = listOf(ProjectRootEntity(hostId = 42, label = "work", path = "~/work"))
        val vm = newVm(projectRootDao = dao)

        vm.bindProjectNavigationHost(42)
        advanceUntilIdle()

        assertEquals(42L, vm.projectNavigation.value.hostId)
        assertEquals(listOf("~/work"), vm.projectNavigation.value.roots.map { it.path })
        assertTrue(vm.projectNavigation.value.items.any { it.path == "~/work" })
    }

    @Test
    fun addProjectRootValidatesAndPersistsForBoundHost() = runTest {
        val dao = FakeProjectRootDao()
        val vm = newVm(projectRootDao = dao)
        vm.bindProjectNavigationHost(42)

        vm.addProjectRoot("/srv/client apps", "clients")
        advanceUntilIdle()

        assertEquals("/srv/client apps", dao.inserted.single().path)
        assertEquals("clients", dao.inserted.single().label)
        assertEquals("Project root saved.", vm.projectNavigation.value.feedback)
    }

    @Test
    fun addProjectRootRejectsRelativePath() = runTest {
        val dao = FakeProjectRootDao()
        val vm = newVm(projectRootDao = dao)
        vm.bindProjectNavigationHost(42)

        vm.addProjectRoot("client apps", "clients")
        advanceUntilIdle()

        assertTrue(dao.inserted.isEmpty())
        assertEquals("Use an absolute path or a path under ~.", vm.projectNavigation.value.feedback)
    }

    @Test
    fun projectCommandsUpdateRecentDirectoriesAndFeedback() {
        val vm = newVm()

        vm.navigateToDirectory("~/work/current")
        vm.createFolderAndCd("~/work", "new app")
        vm.cloneRepositoryAndCd("~/src", "https://github.com/example/repo.git")

        assertEquals(
            listOf("~/src/repo", "~/work/new app", "~/work/current"),
            vm.projectNavigation.value.recentDirectories,
        )
        assertEquals(
            "Running git clone and cd. Result will print in the terminal.",
            vm.projectNavigation.value.feedback,
        )
    }

    @Test
    fun projectCommandsWriteWrappedFeedbackCommandThroughTerminalBridge() = runBlocking {
        val vm = newVm()
        val stdin = ByteArrayOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerJob = vm.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = stdin,
        )

        try {
            vm.createFolderAndCd("~/work", "new app")

            waitForStdinContaining(stdin, "mkdir -p ~/'work/new app' && cd ~/'work/new app'")
            val command = stdin.toString(Charsets.UTF_8.name())
            assertTrue(command.contains("[pocketshell] %s succeeded: %s"))
            assertTrue(command.contains("[pocketshell] %s failed with exit %s: %s"))
            assertTrue(command.endsWith("\r"))
            assertEquals(
                "Running mkdir and cd. Result will print in the terminal.",
                vm.projectNavigation.value.feedback,
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            vm.terminalState.detachExternalProducer()
        }
    }

    @Test
    fun sendSnippetWithEnterWritesBodyAndCarriageReturnThroughTerminalBridge() = runBlocking {
        // Issue #187 / #227: the explicit-intent picker chip `Send + ↵`
        // routes through SessionViewModel.sendSnippet(snippet, withEnter
        // = true), which must append the same carriage return Termux
        // emits for keyboard Enter so the command actually executes on
        // the remote shell.
        val vm = newVm()
        val stdin = ByteArrayOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerJob = vm.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = stdin,
        )

        try {
            vm.sendSnippet(
                SnippetEntity(
                    id = 1,
                    hostId = 1,
                    label = "marker",
                    body = "printf marker",
                    kind = "command",
                ),
                withEnter = true,
            )

            waitForStdin(stdin, "printf marker\r")
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            vm.terminalState.detachExternalProducer()
        }
    }

    @Test
    fun sendSnippetWithoutEnterPastesBodyOnlyThroughTerminalBridge() = runBlocking {
        // Issue #187 / #227: the explicit-intent picker chip `Send`
        // (no Enter) routes through SessionViewModel.sendSnippet(
        // snippet, withEnter = false), which must paste the body alone
        // so the user can continue editing on the input line before
        // pressing Enter manually.
        val vm = newVm()
        val stdin = ByteArrayOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerJob = vm.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = stdin,
        )

        try {
            vm.sendSnippet(
                SnippetEntity(
                    id = 2,
                    hostId = 1,
                    label = "prompt",
                    body = "explain this diff",
                    kind = "prompt",
                ),
                withEnter = false,
            )

            waitForStdin(stdin, "explain this diff")
            assertEquals("explain this diff", stdin.toString(Charsets.UTF_8.name()))
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            vm.terminalState.detachExternalProducer()
        }
    }

    @Test
    fun promptComposerSendWritesDraftWithoutEnterThroughTerminalBridge() = runBlocking {
        val vm = newVm()
        val stdin = ByteArrayOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerJob = vm.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = stdin,
        )

        try {
            vm.sendText("review the failing tests", withEnter = false)

            waitForStdin(stdin, "review the failing tests")
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            vm.terminalState.detachExternalProducer()
        }
    }

    @Test
    fun promptComposerSendEnterWritesDraftWithKeyboardEnterThroughTerminalBridge() = runBlocking {
        val vm = newVm()
        val stdin = ByteArrayOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerJob = vm.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = stdin,
        )

        try {
            vm.sendText("summarize git status", withEnter = true)

            waitForStdin(stdin, "summarize git status\r")
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            vm.terminalState.detachExternalProducer()
        }
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

    // ─── Issue #160 (review round 2) — optimistic dedup contract ───
    //
    // The conversation pane inserts a placeholder `Message(role=User)`
    // when the user taps Send so the UI updates immediately, then
    // waits for the agent's JSONL to emit the real event via the tail.
    // Without dedup the user sees the same prompt twice (once as
    // optimistic, once as the real event). [reconcileAgentEvents]
    // recognises the optimistic prefix on the id and drops the
    // optimistic entry when the real one arrives with matching text.
    // See [OPTIMISTIC_USER_MESSAGE_ID_PREFIX].

    @Test
    fun reconcileAgentEventsCollapsesOptimisticIntoRealUserMessage() {
        val optimistic = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}1234567",
            agent = AgentKind.ClaudeCode,
            atMillis = 1000L,
            role = ConversationRole.User,
            text = "hello agent",
        )
        val real = ConversationEvent.Message(
            id = "u1",
            agent = AgentKind.ClaudeCode,
            atMillis = 1500L,
            role = ConversationRole.User,
            text = "hello agent",
        )

        val reconciled = reconcileAgentEvents(listOf(optimistic, real))

        assertEquals(
            "optimistic + real should reduce to one Message keeping the real id",
            listOf("u1"),
            reconciled.map { it.id },
        )
        assertEquals("hello agent", (reconciled.single() as ConversationEvent.Message).text)
    }

    @Test
    fun reconcileAgentEventsKeepsOptimisticUntilRealArrives() {
        // While the tail has not yet caught up, the optimistic event
        // must remain visible so the user sees their own prompt.
        val optimistic = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}999",
            agent = AgentKind.ClaudeCode,
            atMillis = 1000L,
            role = ConversationRole.User,
            text = "still in flight",
        )

        val reconciled = reconcileAgentEvents(listOf(optimistic))

        assertEquals(listOf(optimistic.id), reconciled.map { it.id })
    }

    @Test
    fun reconcileAgentEventsCollapsesOnlyOneOptimisticPerRealUserMessage() {
        // Two back-to-back identical user prompts → two optimistic
        // entries. When the first real event arrives it should
        // collapse exactly one optimistic, leaving the second
        // placeholder visible until its own real event arrives.
        val optimistic1 = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}1",
            agent = AgentKind.ClaudeCode,
            atMillis = 1000L,
            role = ConversationRole.User,
            text = "ping",
        )
        val optimistic2 = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}2",
            agent = AgentKind.ClaudeCode,
            atMillis = 1100L,
            role = ConversationRole.User,
            text = "ping",
        )
        val real1 = ConversationEvent.Message(
            id = "u1",
            agent = AgentKind.ClaudeCode,
            atMillis = 1500L,
            role = ConversationRole.User,
            text = "ping",
        )

        val reconciled = reconcileAgentEvents(listOf(optimistic1, optimistic2, real1))

        // optimistic1 collapses into real1; optimistic2 is still
        // pending its own real event and must stay visible. Order
        // follows insertion: optimistic2 was inserted before real1
        // (which only arrived once optimistic1 had been added then
        // collapsed), so the visible feed reads optimistic2 → real1.
        assertEquals(listOf(optimistic2.id, "u1"), reconciled.map { it.id })
    }

    @Test
    fun reconcileAgentEventsKeepsOptimisticWhenRealEventHasDifferentText() {
        // The dedup contract matches on text + role; a real user
        // message with different content is a different prompt and
        // must not collapse the unrelated optimistic placeholder.
        val optimistic = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "first prompt",
        )
        val real = ConversationEvent.Message(
            id = "u1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "completely different prompt",
        )

        val reconciled = reconcileAgentEvents(listOf(optimistic, real))

        assertEquals(listOf(optimistic.id, "u1"), reconciled.map { it.id })
    }

    @Test
    fun reconcileAgentEventsDoesNotCollapseAssistantMessages() {
        // Only user-role optimistic placeholders are dropped — the
        // dedup pass is one-directional. An assistant message with
        // the same text as an existing user message is a different
        // conversation row and must be preserved.
        val optimistic = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "hello",
        )
        val assistant = ConversationEvent.Message(
            id = "a1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "hello",
        )

        val reconciled = reconcileAgentEvents(listOf(optimistic, assistant))

        assertEquals(listOf(optimistic.id, "a1"), reconciled.map { it.id })
    }

    @Test
    fun reconcileAgentEventsPreservesIdDedupAndBoundContract() {
        // The legacy contract is unchanged for non-optimistic
        // callers: same-id events collapse to the latest version,
        // and the trailing bound caps the list at `maxEvents`.
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

        val reconciled = reconcileAgentEvents(events)

        assertEquals(500, reconciled.size)
        assertEquals("event-11", reconciled.first().id)
        assertEquals(
            "message 510 replacement",
            (reconciled.last() as ConversationEvent.Message).text,
        )
    }

    @Test
    fun sendToAgentInsertsOptimisticUserMessageVisibleBeforeTail() = runBlocking {
        // Pins the round-2 dedup invariant on the production
        // `sendToAgent` path: the local insert is tagged with the
        // optimistic prefix (so the tail-side reconcile can collapse
        // it) and the trimmed payload reaches the terminal-input
        // bridge with a trailing carriage return.
        val vm = newVm()
        vm.startAgentConversationForTest(
            detection = AgentDetection(
                agent = AgentKind.ClaudeCode,
                sourcePath = "/tmp/claude.jsonl",
                sessionId = "claude",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
            initialEvents = emptyList(),
        )

        val stdin = ByteArrayOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerJob = vm.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = stdin,
        )

        try {
            vm.sendToAgent("  please review the diff  ")

            // Optimistic event is visible immediately; id carries the
            // dedup prefix; trimmed text matches the trimmed payload.
            val events = vm.agentConversation.value.events
            assertEquals(1, events.size)
            val message = events.single() as ConversationEvent.Message
            assertEquals(ConversationRole.User, message.role)
            assertEquals("please review the diff", message.text)
            assertTrue(
                "expected optimistic id prefix, got id=${message.id}",
                message.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX),
            )

            // Send-keys equivalent: the trimmed text + Enter reaches
            // the remote PTY's stdin via the terminal bridge.
            waitForStdin(stdin, "please review the diff\r")
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            vm.terminalState.detachExternalProducer()
        }
    }

    @Test
    fun sendToAgentIsNoOpWhenNoDetection() = runBlocking {
        // Defensive: the conversation pane cannot be reached without
        // a detection, but the public API still guards against being
        // called from a test or future entry point that has not
        // detected an agent yet.
        val vm = newVm()
        val stdin = ByteArrayOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerJob = vm.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = stdin,
        )
        try {
            vm.sendToAgent("won't fire")
            assertTrue(
                "no agent detected — sendToAgent must not append to the feed",
                vm.agentConversation.value.events.isEmpty(),
            )
            // No bytes written to stdin either.
            assertEquals(0, stdin.size())
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            vm.terminalState.detachExternalProducer()
        }
    }

    @Test
    fun nonTmuxAgentDetectionDoesNotUseFreshExecCwd() = runTest {
        val session = FakeSshSession()
        val detection = AgentConversationRepository().detect(session)

        assertNull(detection)
        assertTrue(session.recordedExecCommands.isEmpty())
    }

    @Test
    fun runtimeDetectionCommandProbesOpenCodeSqliteDatabase() {
        // Issue #247: real OpenCode stores sessions in SQLite, not JSONL.
        // Runtime detection must query opencode.db and match the active
        // pane cwd against the session/project cwd columns.
        val command = AgentConversationRepository().detectionCommand("/home/alexey/git/pocketshell")

        assertTrue("OpenCode runtime detection scans .local/share/opencode/", ".local/share/opencode" in command)
        assertTrue("OpenCode runtime detection uses opencode.db", "opencode.db" in command)
        assertTrue("OpenCode runtime detection uses sqlite3", "sqlite3" in command)
        assertTrue("OpenCode runtime detection checks project worktree", "p.worktree" in command)
        assertTrue("OpenCode runtime detection checks session directory", "s.directory" in command)
        assertTrue("OpenCode detection emits an 'opencode|...' candidate row", "opencode|" in command)
        assertTrue("OpenCode detection must not scan JSONL rows", "find \"\$opencode_dir\" -maxdepth 1 -type f -name '*.jsonl'" !in command)
    }

    @Test
    fun runtimeDetectionCommandProbesCodexSessionLogs() {
        // Issue #183: Codex runtime detection is re-enabled. The
        // detection command must walk the `~/.codex/sessions/` rollout
        // tree for recent JSONL files so attaching to an already-running
        // Codex session surfaces the Conversation tab. The free-text
        // cwd grep is still avoided — pane-correlation continues to
        // come from the `cwd` field emitted alongside each candidate.
        val command = AgentConversationRepository().detectionCommand("/home/alexey/git/pocketshell")

        assertTrue("Codex session logs are scanned", ".codex/sessions" in command)
        assertTrue("Codex detection emits a 'codex|...' candidate row", "codex|" in command)
        assertTrue("Codex detection must not use free-text cwd grep", "grep -F" !in command)
    }

    @Test
    fun runtimeDetectionAcceptsCodexCandidatesFromRemoteOutput() = runTest {
        // Issue #183: a Codex candidate whose path lives under the
        // expected `.codex/sessions/` tree must produce a detection.
        // The previous behaviour returned `null` because the detector
        // hard-coded Codex to `false`; with the uniform path-hint
        // filter the row now flows through.
        val recentMtimeSeconds = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            execStdout = "codex|$recentMtimeSeconds|/home/alexey/git/pocketshell|/home/alexey/.codex/sessions/2026/05/22/rollout-123.jsonl\n",
        )
        val detection = AgentConversationRepository().detect(
            session = session,
            cwd = "/home/alexey/git/pocketshell",
            processHints = listOf("123 codex"),
        )

        assertEquals(AgentKind.Codex, detection?.agent)
        assertEquals(
            "/home/alexey/.codex/sessions/2026/05/22/rollout-123.jsonl",
            detection?.sourcePath,
        )
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
    }

    @Test
    fun runtimeDetectionAcceptsOpenCodeSqliteCandidatesFromRemoteOutput() = runTest {
        // Issue #247: OpenCode candidates encode the selected session as
        // `opencode.db#<session-id>` so downstream reads can query that
        // database/session pair instead of tailing a JSONL file.
        val recentMtimeSeconds = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            execStdout = "opencode|$recentMtimeSeconds|/home/alexey/git/pocketshell|/home/alexey/.local/share/opencode/opencode.db#session-1\n",
        )
        val detection = AgentConversationRepository().detect(
            session = session,
            cwd = "/home/alexey/git/pocketshell",
            processHints = listOf("123 opencode"),
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals(
            "/home/alexey/.local/share/opencode/opencode.db#session-1",
            detection?.sourcePath,
        )
        assertEquals("session-1", detection?.sessionId)
        assertEquals(AgentDetection.Confidence.ProcessConfirmed, detection?.confidence)
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

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private suspend fun waitForStdin(stdin: ByteArrayOutputStream, expected: String) {
        withTimeout(5_000) {
            while (stdin.toString(Charsets.UTF_8.name()) != expected) {
                delay(25)
            }
        }
    }

    private suspend fun waitForStdinContaining(stdin: ByteArrayOutputStream, expected: String) {
        withTimeout(5_000) {
            while (!stdin.toString(Charsets.UTF_8.name()).contains(expected)) {
                delay(25)
            }
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        val mainLooper = shadowOf(Looper.getMainLooper())
        withTimeout(5_000) {
            while (!predicate()) {
                mainLooper.idle()
                delay(25)
            }
        }
    }

    @Test
    fun rawSshAgentConversationCanSwitchTabsWithoutHintState() {
        val vm = newVm()
        val detection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/u/.claude/sessions/abc.jsonl",
            sessionId = "abc",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )
        val event = ConversationEvent.Message(
            id = "message-1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "ready",
        )

        vm.startAgentConversationForTest(detection, listOf(event))

        vm.selectSessionTab(SessionTab.Conversation)

        val state = vm.agentConversation.value
        assertEquals(detection, state.detection)
        assertEquals(SessionTab.Conversation, state.selectedTab)
        assertEquals(listOf(event), state.events)
    }

    // Issue #165 — cancelConnect tests. The progress overlay's 15s
    // Cancel affordance routes through [SessionViewModel.cancelConnect];
    // these tests assert it cancels the in-flight connect job AND flips
    // status to Failed so the screen renders a deterministic post-cancel
    // state instead of staying stuck on Connecting.

    @Test
    fun cancelConnectFlipsConnectingStatusToFailedAndCancelsJob() = runTest {
        val vm = newVm()
        val job = Job()
        vm.beginConnectingForTest(host = "alpha.example", port = 22, user = "alex", job = job)
        assertTrue(
            "precondition: status must be Connecting",
            vm.connectionStatus.value is SessionViewModel.ConnectionStatus.Connecting,
        )

        val fired = vm.cancelConnect()

        assertTrue("cancelConnect() must report success when called during Connecting", fired)
        val status = vm.connectionStatus.value
        assertTrue(
            "status must be Failed after cancel, was $status",
            status is SessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Connect cancelled by user.",
            (status as SessionViewModel.ConnectionStatus.Failed).message,
        )
        assertTrue("connectJob must be cancelled by cancelConnect()", job.isCancelled)
    }

    @Test
    fun cancelConnectIsNoOpWhenNotConnecting() {
        val vm = newVm()
        // Status starts Idle — cancel must be a no-op.
        val fired = vm.cancelConnect()
        assertFalse("cancelConnect() must no-op when status is Idle", fired)
        assertTrue(vm.connectionStatus.value is SessionViewModel.ConnectionStatus.Idle)
    }

    @Test
    fun cancelConnectDuringRealConnectKeepsUserCancelledState() = runBlocking {
        val vm = newVm()
        val connectStarted = CompletableDeferred<Unit>()
        val connectorCancelled = CompletableDeferred<Unit>()
        vm.setRawSshConnectorForTest { host, port, user, _, _, knownHosts ->
            assertEquals("alpha.example", host)
            assertEquals(2222, port)
            assertEquals("alex", user)
            assertEquals(KnownHostsPolicy.AcceptAll, knownHosts)
            connectStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                connectorCancelled.complete(Unit)
            }
        }

        vm.connect(host = "alpha.example", port = 2222, user = "alex", keyPath = "/tmp/test-key")
        withTimeout(5_000) { connectStarted.await() }
        assertTrue(
            "precondition: real connect() must enter Connecting",
            vm.connectionStatus.value is SessionViewModel.ConnectionStatus.Connecting,
        )

        val fired = vm.cancelConnect()

        assertTrue("cancelConnect() must report success during real connect()", fired)
        withTimeout(5_000) { connectorCancelled.await() }
        shadowOf(Looper.getMainLooper()).idle()
        delay(50)
        shadowOf(Looper.getMainLooper()).idle()
        val status = vm.connectionStatus.value
        assertTrue(
            "status must remain Failed after runConnect cancellation unwinds, was $status",
            status is SessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Connect cancelled by user.",
            (status as SessionViewModel.ConnectionStatus.Failed).message,
        )
        assertFalse(
            "user-cancelled raw connect must not become a retryable reconnect failure",
            vm.canReconnect.value,
        )
    }

    @Test
    fun rawSshStdoutCompletionDisconnectCanReconnectAndStartsFreshSession() = runBlocking {
        val vm = newVm()
        val sessions = mutableListOf<FakeRawSshSession>()
        vm.setRawSshConnectorForTest { host, port, user, _, _, knownHosts ->
            assertEquals("alpha.example", host)
            assertEquals(2222, port)
            assertEquals("alex", user)
            assertEquals(KnownHostsPolicy.AcceptAll, knownHosts)
            Result.success(FakeRawSshSession("session-${sessions.size + 1}").also { sessions += it })
        }

        try {
            vm.connect(host = "alpha.example", port = 2222, user = "alex", keyPath = "/tmp/test-key")

            waitUntil { vm.connectionStatus.value is SessionViewModel.ConnectionStatus.Connected }
            assertEquals(1, sessions.size)
            assertEquals(1, sessions.single().startedShells.size)
            waitUntil { sessions.single().startedShells.single().stdinText() == "\r" }

            sessions.single().startedShells.single().finishStdout()

            waitUntil { vm.connectionStatus.value is SessionViewModel.ConnectionStatus.Failed }
            val disconnected = vm.connectionStatus.value as SessionViewModel.ConnectionStatus.Failed
            assertEquals(
                "Disconnected from alex@alpha.example:2222. Tap Reconnect to retry.",
                disconnected.message,
            )
            assertTrue("raw SSH reconnect target must remain available after socket death", vm.canReconnect.value)

            assertTrue("reconnect() must accept the retained raw SSH target", vm.reconnect())

            waitUntil {
                sessions.size == 2 &&
                    vm.connectionStatus.value is SessionViewModel.ConnectionStatus.Connected &&
                    sessions[0].startedShells.single().closed &&
                    sessions[0].closed
            }
            assertTrue("dead raw shell must be closed before reconnect", sessions[0].startedShells.single().closed)
            assertTrue("dead raw session must be closed before reconnect", sessions[0].closed)
            assertEquals(1, sessions[1].startedShells.size)
            waitUntil { sessions[1].startedShells.single().stdinText() == "\r" }
        } finally {
            sessions.flatMap { it.startedShells }.forEach { it.close() }
            sessions.forEach { it.close() }
            vm.terminalState.detachExternalProducer()
        }
    }
}

private class FakeRawSshSession(
    private val id: String,
) : SshSession {
    val startedShells = mutableListOf<FakeRawSshShell>()
    var closed: Boolean = false
        private set

    override val isConnected: Boolean get() = !closed

    override suspend fun exec(command: String): ExecResult =
        ExecResult(stdout = "", stderr = "", exitCode = 0)

    override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward {
        throw NotImplementedError()
    }

    override fun startShell(): SshShell =
        FakeRawSshShell(id).also { startedShells += it }

    override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
        error("uploadFile not used in this test")

    override suspend fun uploadStream(
        input: java.io.InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String = error("uploadStream not used in this test")

    override fun close() {
        closed = true
    }
}

private class FakeRawSshShell(
    private val id: String,
) : SshShell {
    private val stdoutReader = FinishableInputStream()
    private val stdinBuffer = ByteArrayOutputStream()

    var closed: Boolean = false
        private set

    override val stdin: OutputStream = stdinBuffer
    override val stdout: InputStream = stdoutReader
    override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))

    fun stdinText(): String = stdinBuffer.toString(Charsets.UTF_8.name())

    fun finishStdout() {
        stdoutReader.finish()
    }

    override fun close() {
        closed = true
        stdoutReader.finish()
    }

    override fun toString(): String = "FakeRawSshShell($id)"
}

private class FinishableInputStream : InputStream() {
    private val lock = Object()
    private var finished: Boolean = false

    fun finish() {
        synchronized(lock) {
            finished = true
            lock.notifyAll()
        }
    }

    override fun read(): Int {
        synchronized(lock) {
            while (!finished) {
                lock.wait()
            }
        }
        return -1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
}

private class FakeProjectRootDao : ProjectRootDao {
    val roots = MutableStateFlow<List<ProjectRootEntity>>(emptyList())
    val inserted = mutableListOf<ProjectRootEntity>()

    override fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>> = roots

    override suspend fun insert(root: ProjectRootEntity): Long {
        inserted += root
        roots.value = roots.value + root.copy(id = inserted.size.toLong())
        return inserted.size.toLong()
    }

    override suspend fun update(root: ProjectRootEntity) {
        roots.value = roots.value.map { if (it.id == root.id) root else it }
    }

    override suspend fun delete(root: ProjectRootEntity) {
        roots.value = roots.value.filterNot { it.id == root.id }
    }
}
