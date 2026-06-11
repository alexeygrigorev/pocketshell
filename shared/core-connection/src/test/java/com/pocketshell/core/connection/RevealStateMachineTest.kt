package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive unit tests for the pure-JVM [RevealStateMachine] (EPIC #686 Phase-0).
 *
 * The session screen must be a pure function of one target session id. These tests
 * pin the load-bearing reveal/identity invariants the old accreted screen logic
 * kept regressing:
 *  - nav -> Navigating shows the TARGET name immediately, never the previous one;
 *  - a Seed (or lifecycle event) for a non-target id is DROPPED, never revealed;
 *  - A->B->C->A only ever projects the active id's name + content;
 *  - a deleted target -> Gone, with no resurrect;
 *  - a control-drop -> Error(retrying) then Live on heal.
 *
 * The machine owns no transport/jobs/leases, so every input is faked directly: a
 * [ConnectionState] (as the controller would emit), a nav target, and id-tagged
 * [Seed]s.
 */
class RevealStateMachineTest {

    private val host = HostKey("alice@host:22")
    private val a = SessionId("A")
    private val b = SessionId("B")
    private val c = SessionId("C")

    private fun seed(id: SessionId, pane: String = "%0", frame: String = "content-$id") =
        Seed(id, pane, frame)

    /** Drive a target all the way to Live with a single active-pane seed. */
    private fun RevealStateMachine.bringLive(id: SessionId, name: String) {
        navigate(id, name)
        onConnectionState(ConnectionState.Attaching(host, id))
        onSeed(seed(id))
    }

    // --- nav shows the target name immediately ----------------------------

    @Test
    fun `nav goes to Navigating showing the target name immediately`() {
        val m = RevealStateMachine()
        assertEquals(RevealState.Idle, m.state.value)

        m.navigate(b, "session-B")

        // First emission after nav is the TARGET's name, before any seed.
        assertEquals(RevealState.Navigating(b, "session-B"), m.state.value)
    }

    @Test
    fun `nav from a live target never flashes the previous name or content`() {
        val m = RevealStateMachine()
        m.bringLive(a, "session-A")
        assertTrue(m.state.value is RevealState.Live)

        // Navigate to B: synchronously replaced — never (A name, B id) or vice versa.
        m.navigate(b, "session-B")

        val state = m.state.value
        assertEquals(RevealState.Navigating(b, "session-B"), state)
        // No A content bled through.
        assertEquals(b, state.targetIdOrNull())
        assertEquals("session-B", state.targetNameOrNull())
    }

    // --- a foreign seed is dropped, never revealed ------------------------

    @Test
    fun `a seed for a non-target id is dropped and never painted`() {
        val m = RevealStateMachine()
        m.navigate(b, "session-B")
        m.onConnectionState(ConnectionState.Attaching(host, b))
        assertEquals(RevealState.Seeding(b, "session-B"), m.state.value)

        // A late seed for the superseded target A must be dropped — stay Seeding(B).
        m.onSeed(seed(a, frame = "stale-A-frame"))
        assertEquals(RevealState.Seeding(b, "session-B"), m.state.value)

        // The target's own seed reveals content.
        m.onSeed(seed(b, frame = "content-B"))
        assertEquals(
            RevealState.Live(b, "session-B", listOf(seed(b, frame = "content-B")), null),
            m.state.value,
        )
    }

    @Test
    fun `a connection-state for a non-target id is dropped`() {
        val m = RevealStateMachine()
        m.navigate(b, "session-B")
        m.onConnectionState(ConnectionState.Attaching(host, b))

        // A late lifecycle event for superseded A is dropped.
        m.onConnectionState(ConnectionState.Gone(host, a))
        assertEquals(RevealState.Seeding(b, "session-B"), m.state.value)

        m.onConnectionState(ConnectionState.Live(host, a))
        assertEquals(RevealState.Seeding(b, "session-B"), m.state.value)
    }

    // --- A -> B -> C -> A only ever shows the active id's name + content ----

    @Test
    fun `A to B to C to A only ever projects the active id name and content`() {
        val m = RevealStateMachine()

        // For each step assert the state's id, name, AND surface match the active
        // target — never a (leaving name, arriving id) or (arriving name, leaving
        // content) cross.
        val steps = listOf(
            Triple(a, "session-A", "content-A"),
            Triple(b, "session-B", "content-B"),
            Triple(c, "session-C", "content-C"),
            Triple(a, "session-A", "content-A2"),
        )

        for ((id, name, frame) in steps) {
            m.navigate(id, name)
            // The instant after nav: header is the TARGET, surface is loading.
            assertEquals(RevealState.Navigating(id, name), m.state.value)

            m.onConnectionState(ConnectionState.Attaching(host, id))
            assertEquals(RevealState.Seeding(id, name), m.state.value)

            // A stray late seed for any OTHER id at this point is dropped.
            val other = if (id == a) b else a
            m.onSeed(seed(other, frame = "stale-$other"))
            assertEquals(RevealState.Seeding(id, name), m.state.value)

            m.onSeed(seed(id, frame = frame))
            val live = m.state.value
            assertTrue("expected Live for $id", live is RevealState.Live)
            live as RevealState.Live
            assertEquals(id, live.targetId)
            assertEquals(name, live.targetName)
            assertEquals(listOf(seed(id, frame = frame)), live.panes)
        }
    }

    @Test
    fun `switching back to a target re-seeds fresh content, not the stale frame`() {
        val m = RevealStateMachine()
        m.bringLive(a, "session-A")
        m.bringLive(b, "session-B")

        // Back to A: Navigating shows A immediately with NO A content yet.
        m.navigate(a, "session-A")
        assertEquals(RevealState.Navigating(a, "session-A"), m.state.value)

        m.onConnectionState(ConnectionState.Attaching(host, a))
        assertEquals(RevealState.Seeding(a, "session-A"), m.state.value)

        // Fresh A seed reveals; the panes list is the new capture only.
        m.onSeed(seed(a, frame = "fresh-A"))
        val live = m.state.value as RevealState.Live
        assertEquals(listOf(seed(a, frame = "fresh-A")), live.panes)
    }

    // --- deleted target -> Gone, no resurrect -----------------------------

    @Test
    fun `deleted target shows Gone with no resurrect and no stale frame`() {
        val m = RevealStateMachine()
        m.bringLive(b, "session-B")
        assertTrue(m.state.value is RevealState.Live)

        m.onConnectionState(ConnectionState.Gone(host, b))

        val state = m.state.value
        assertEquals(RevealState.Gone(b, "session-B"), state)
        // Header still B; no panes/content surface from the Gone state.
        assertEquals("session-B", state.targetNameOrNull())
        assertNull(state.targetIdOrNull()?.let { if (state is RevealState.Live) state else null })

        // A stray seed for B does NOT resurrect content from Gone.
        m.onSeed(seed(b, frame = "resurrect-attempt"))
        assertEquals(RevealState.Gone(b, "session-B"), m.state.value)
    }

    // --- control-drop -> Error(retrying) then Live on heal ----------------

    @Test
    fun `control drop shows Error retrying while healing then Live on heal`() {
        val m = RevealStateMachine()
        m.bringLive(a, "session-A")

        // The controller heals silently through Reattaching/Reconnecting -> the
        // view shows a calm loading (Seeding), not a scary error band, while healing.
        m.onConnectionState(ConnectionState.Reattaching(host, a))
        assertEquals(RevealState.Seeding(a, "session-A"), m.state.value)

        // Heal succeeds: lifecycle back to Live, content reveals again.
        m.onConnectionState(ConnectionState.Live(host, a))
        val live = m.state.value as RevealState.Live
        assertEquals(a, live.targetId)
        assertEquals("session-A", live.targetName)
    }

    @Test
    fun `exhausted reconnect surfaces honest non-retrying Error`() {
        val m = RevealStateMachine()
        m.bringLive(a, "session-A")

        m.onConnectionState(ConnectionState.Unreachable(host, a))

        assertEquals(RevealState.Error(a, "session-A", retrying = false), m.state.value)

        // From an honest error, a stray seed does not silently re-reveal.
        m.onSeed(seed(a, frame = "stray"))
        assertEquals(RevealState.Error(a, "session-A", retrying = false), m.state.value)
    }

    // --- never-reveal-black -----------------------------------------------

    @Test
    fun `empty active-pane seed keeps Seeding then a non-empty seed reveals`() {
        val m = RevealStateMachine()
        m.navigate(a, "session-A")
        m.onConnectionState(ConnectionState.Attaching(host, a))

        // An empty frame must NOT promote to Live with a black pane.
        m.onSeed(seed(a, frame = ""))
        assertEquals(RevealState.Seeding(a, "session-A"), m.state.value)

        // A later non-empty seed reveals.
        m.onSeed(seed(a, frame = "real"))
        assertTrue(m.state.value is RevealState.Live)
    }

    @Test
    fun `Live lifecycle with no seed yet stays Seeding never a black pane`() {
        val m = RevealStateMachine()
        m.navigate(a, "session-A")

        // Controller reports Live but no content has landed for this id yet.
        m.onConnectionState(ConnectionState.Live(host, a))
        assertEquals(RevealState.Seeding(a, "session-A"), m.state.value)

        m.onSeed(seed(a, frame = "content"))
        assertTrue(m.state.value is RevealState.Live)
    }

    // --- agentName override (only in Live, only for this id) ---------------

    @Test
    fun `agent name overrides the leaf label only once Live and only for this id`() {
        val m = RevealStateMachine()
        m.navigate(a, "session-A")
        m.onConnectionState(ConnectionState.Attaching(host, a))

        // Before Live, an agent name is stored but not yet projected onto a label.
        m.onAgentName(a, "claude")
        assertEquals(RevealState.Seeding(a, "session-A"), m.state.value)

        // Once content reveals, the stored agent name is on the Live state.
        m.onSeed(seed(a))
        assertEquals("claude", (m.state.value as RevealState.Live).agentName)

        // A foreign-id agent name is dropped.
        m.onAgentName(b, "codex")
        assertEquals("claude", (m.state.value as RevealState.Live).agentName)
    }

    // --- header name is the target in EVERY state -------------------------

    @Test
    fun `header name is the target name in every non-idle state`() {
        val m = RevealStateMachine()

        m.navigate(a, "session-A")
        assertEquals("session-A", m.state.value.targetNameOrNull())

        m.onConnectionState(ConnectionState.Connecting(host, a))
        assertEquals("session-A", m.state.value.targetNameOrNull())

        m.onConnectionState(ConnectionState.Attaching(host, a))
        assertEquals("session-A", m.state.value.targetNameOrNull())

        m.onSeed(seed(a))
        assertEquals("session-A", m.state.value.targetNameOrNull())

        m.onConnectionState(ConnectionState.Gone(host, a))
        assertEquals("session-A", m.state.value.targetNameOrNull())
    }

    // --- idempotent re-navigation -----------------------------------------

    @Test
    fun `re-navigating to the same live id keeps the live state`() {
        val m = RevealStateMachine()
        m.bringLive(a, "session-A")
        val before = m.state.value

        // A recompose re-nav to the same id+name must not drop a Live reveal.
        m.navigate(a, "session-A")
        assertEquals(before, m.state.value)
    }

    @Test
    fun `re-navigating to the same id with a new name refreshes the header`() {
        val m = RevealStateMachine()
        m.bringLive(a, "old-name")

        m.navigate(a, "renamed-A")

        val live = m.state.value as RevealState.Live
        assertEquals("renamed-A", live.targetName)
        // The reveal itself (content) is preserved.
        assertTrue(live.panes.isNotEmpty())
    }
}
