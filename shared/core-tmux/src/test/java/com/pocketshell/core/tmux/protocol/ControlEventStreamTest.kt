package com.pocketshell.core.tmux.protocol

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ControlEventStream]'s response-block framing and event
 * filtering. The parser's per-line behaviour lives in
 * [ControlModeParserTest]; here we focus on the state machine across lines.
 */
class ControlEventStreamTest {

    @Test
    fun `emits structured events from a happy-path transcript`() = runTest {
        // Representative session transcript per issue #43 brief.
        val lines = listOf(
            "%session-changed \$0 main",
            "%window-add @0",
            "%layout-change @0 b25d,80x24,0,0,0",
            "%output %0 hello",
            "%exit",
        )

        val events = ControlEventStream().events(lines.asFlow()).toList()

        assertEquals(5, events.size)
        assertTrue(events[0] is ControlEvent.SessionChanged)
        assertTrue(events[1] is ControlEvent.WindowAdd)
        assertTrue(events[2] is ControlEvent.LayoutChange)
        assertTrue(events[3] is ControlEvent.Output)
        assertTrue(events[4] is ControlEvent.Exit)
    }

    @Test
    fun `filters parser nulls (unknown opcodes and blank lines)`() = runTest {
        val lines = listOf(
            "",
            "%future-event with args",
            "%session-changed \$0 main",
            "garbage line without percent prefix",
            "%exit",
        )

        val events = ControlEventStream().events(lines.asFlow()).toList()

        // Only the two recognised events survive.
        assertEquals(2, events.size)
        assertTrue(events[0] is ControlEvent.SessionChanged)
        assertTrue(events[1] is ControlEvent.Exit)
    }

    @Test
    fun `does not leak response-block payload as events`() = runTest {
        // Between %begin and %end, the lines are command output — they
        // must reach onResponsePayload but never become events.
        val payload = mutableListOf<Pair<Long, String>>()
        val stream = ControlEventStream(
            onResponsePayload = { number, line -> payload += number to line },
        )

        val lines = listOf(
            "%output %0 before",
            "%begin 1234567890 1 0",
            "session 0: 1 windows (created Fri May 21 12:00:00 2026)",
            "session 1: 2 windows",
            "%end 1234567890 1 0",
            "%output %0 after",
        )

        val events = stream.events(lines.asFlow()).toList()

        // 4 events expected: the two %outputs plus %begin and %end. The two
        // payload lines must NOT be there.
        assertEquals(4, events.size)
        assertTrue(events[0] is ControlEvent.Output)
        assertTrue(events[1] is ControlEvent.Begin)
        assertTrue(events[2] is ControlEvent.End)
        assertTrue(events[3] is ControlEvent.Output)

        // Both payload lines forwarded with the correct command-number.
        assertEquals(2, payload.size)
        assertEquals(1L, payload[0].first)
        assertEquals("session 0: 1 windows (created Fri May 21 12:00:00 2026)", payload[0].second)
        assertEquals(1L, payload[1].first)
        assertEquals("session 1: 2 windows", payload[1].second)
    }

    @Test
    fun `error closes a response block just like end`() = runTest {
        val payload = mutableListOf<String>()
        val stream = ControlEventStream(
            onResponsePayload = { _, line -> payload += line },
        )

        val lines = listOf(
            "%begin 1234567890 2 0",
            "no such window: bogus",
            "%error 1234567890 2 0",
            "%output %0 next",
        )

        val events = stream.events(lines.asFlow()).toList()

        // Begin and Error are events; the error message body is payload.
        assertEquals(3, events.size)
        assertTrue(events[0] is ControlEvent.Begin)
        assertTrue(events[1] is ControlEvent.Error)
        assertTrue(events[2] is ControlEvent.Output)
        assertEquals(listOf("no such window: bogus"), payload)
    }

    @Test
    fun `payload lines that look like control events are still treated as payload`() = runTest {
        // tmux command output can legitimately contain `%`-prefixed lines
        // (e.g. a session name happens to start with `%`). Once we're
        // inside a block, the only lines that escape are the matching
        // %end / %error.
        val payload = mutableListOf<String>()
        val stream = ControlEventStream(
            onResponsePayload = { _, line -> payload += line },
        )

        val lines = listOf(
            "%begin 1 5 0",
            "%output %0 fake",     // looks like an event, must be payload
            "%window-add @99",      // ditto
            "%end 1 5 0",
        )

        val events = stream.events(lines.asFlow()).toList()

        // Only Begin and End escape as events; both `%`-prefixed payload
        // lines remain in the payload bucket.
        assertEquals(2, events.size)
        assertTrue(events[0] is ControlEvent.Begin)
        assertTrue(events[1] is ControlEvent.End)
        assertEquals(listOf("%output %0 fake", "%window-add @99"), payload)
    }

    @Test
    fun `end with different command number stays inside the block`() = runTest {
        // tmux only allows one outstanding command at a time, but if a
        // stray mismatched `%end` ever appears it must NOT close our
        // currently open block. Otherwise we'd start emitting subsequent
        // payload lines as events.
        val payload = mutableListOf<String>()
        val stream = ControlEventStream(
            onResponsePayload = { _, line -> payload += line },
        )

        val lines = listOf(
            "%begin 1 1 0",
            "real payload line",
            "%end 1 99 0",          // mismatched number — treat as payload
            "still inside block",
            "%end 1 1 0",           // matching close
        )

        val events = stream.events(lines.asFlow()).toList()

        // Begin + matching End only.
        assertEquals(2, events.size)
        assertTrue(events[0] is ControlEvent.Begin)
        assertTrue(events[1] is ControlEvent.End)
        // The mismatched %end and the surrounding text all went to payload.
        assertEquals(
            listOf("real payload line", "%end 1 99 0", "still inside block"),
            payload,
        )
    }

    @Test
    fun `multiple sequential blocks correlate independently`() = runTest {
        val payload = mutableListOf<Pair<Long, String>>()
        val stream = ControlEventStream(
            onResponsePayload = { n, line -> payload += n to line },
        )

        val lines = listOf(
            "%begin 1 10 0",
            "first response",
            "%end 1 10 0",
            "%begin 2 11 0",
            "second response",
            "%end 2 11 0",
        )

        val events = stream.events(lines.asFlow()).toList()

        assertEquals(4, events.size)
        assertEquals(
            listOf(10L to "first response", 11L to "second response"),
            payload,
        )
    }

    @Test
    fun `output event survives octal escapes through the stream`() = runTest {
        // End-to-end sanity: parser hex/octal decoding must propagate
        // through the stream unchanged (no double-decoding, no truncation).
        val lines = listOf("%output %1 \\033[31mred\\033[0m")
        val events = ControlEventStream().events(lines.asFlow()).toList()
        assertEquals(1, events.size)
        val output = events.single() as ControlEvent.Output
        // 0x1b [ 3 1 m r e d 0x1b [ 0 m
        assertEquals(12, output.data.size)
        assertEquals(0x1b.toByte(), output.data[0])
        assertEquals(0x1b.toByte(), output.data[8])
    }
}
