package com.pocketshell.app.proof

import org.junit.Assert.assertTrue

internal const val FAKE_AGENT_SUBMITTED_STRIPPED: String = "FAKE-AGENTSUBMITTED:"

/**
 * The text the fake agent SUBMITTED, whitespace-stripped — everything between
 * its `FAKE-AGENT SUBMITTED: ` marker and the `> ` input box that follows it.
 * The input box wraps across rows as it grows, so the capture is compared
 * whitespace-insensitively; the payload carries no whitespace of its own, so
 * this is exact for the property under test.
 */
internal fun submittedTextStripped(capture: String): String {
    val stripped = capture.filterNot { it.isWhitespace() }
    val start = stripped.indexOf(FAKE_AGENT_SUBMITTED_STRIPPED)
    assertTrue(
        "the fixture must have submitted a prompt; capture:\n$capture",
        start >= 0,
    )
    val body = stripped.substring(start + FAKE_AGENT_SUBMITTED_STRIPPED.length)
    val inputBox = body.indexOf('>')
    return if (inputBox >= 0) body.substring(0, inputBox) else body
}

internal fun countOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = haystack.indexOf(needle)
    while (index >= 0) {
        count += 1
        index = haystack.indexOf(needle, index + needle.length)
    }
    return count
}

internal fun countCollapsedPasteChips(capture: String): Int =
    Regex("""\[Pasted text #\d+ \+\d+ lines?]""").findAll(capture).count()
