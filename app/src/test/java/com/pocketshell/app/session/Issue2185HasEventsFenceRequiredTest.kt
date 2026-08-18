package com.pocketshell.app.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #2185 — the load-outcome fence's `hasEvents` parameter must be
 * required. A default of `true` silently disables the fence at any future
 * call site that forgets the argument, and the call site still looks
 * correct.
 *
 * Mutation that must redden this (G6): restore `hasEvents: Boolean = true`
 * on [ConversationSyncStatusRow] (or add the same default to
 * [resolvedConversationSyncStatus] / [conversationSyncStatusForLoad] /
 * [conversationLoadStateForOutcome]).
 */
class Issue2185HasEventsFenceRequiredTest {

    @Test
    fun conversationSyncStatusRowDoesNotDefaultHasEventsOn() {
        val source = locate("app/src/main/java/com/pocketshell/app/session/ConversationSyncStatusUi.kt")
        val signature = extractFunction(source, "ConversationSyncStatusRow")
        assertTrue(
            "#2185: ConversationSyncStatusRow must take hasEvents so the " +
                "render-boundary fence cannot be skipped. Signature was:\n$signature",
            signature.contains("hasEvents"),
        )
        assertFalse(
            "#2185: hasEvents must be required. A default of `true` turns the " +
                "Live-over-empty fence off at any call site that omits the " +
                "argument. Signature was:\n$signature",
            HAS_EVENTS_DEFAULT.containsMatchIn(signature),
        )
    }

    @Test
    fun loadOutcomeHelpersDoNotDefaultHasEventsOn() {
        val source = locate("app/src/main/java/com/pocketshell/app/session/ConversationLoadOutcome.kt")
        for (name in listOf("conversationSyncStatusForLoad", "conversationLoadStateForOutcome")) {
            val signature = extractFunction(source, name)
            assertTrue(
                "#2185: $name must take hasEvents. Signature was:\n$signature",
                signature.contains("hasEvents"),
            )
            assertFalse(
                "#2185: $name must not default hasEvents to true — that is the " +
                    "same silent opt-out as the status-row default. Signature " +
                    "was:\n$signature",
                HAS_EVENTS_DEFAULT.containsMatchIn(signature),
            )
        }
    }

    private fun extractFunction(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        require(start >= 0) { "Could not find fun $name( in source" }
        val open = source.indexOf('(', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unbalanced parentheses for $name")
    }

    private fun locate(relative: String): String {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val file = cursor.resolve(relative)
            if (file.isFile) return file.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Could not locate $relative from ${File(".").absolutePath}")
    }

    private companion object {
        val HAS_EVENTS_DEFAULT: Regex = Regex("""hasEvents\s*:\s*Boolean\s*=\s*true""")
    }
}
