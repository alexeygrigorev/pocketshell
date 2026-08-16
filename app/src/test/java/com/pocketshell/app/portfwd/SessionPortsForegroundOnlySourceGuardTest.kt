package com.pocketshell.app.portfwd

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2176, acceptance criteria 9 and 10 — the two properties that are
 * architectural rather than behavioural, and that a future edit is most likely
 * to break without noticing.
 *
 * These are the constraints the whole feature had to be designed around:
 *
 *  - **No polling, no timer, no background work (D21).** Every entry point is
 *    driven by an event that already happened: a confirmed detection on the pane
 *    output flow the terminal is already collecting, or a session attach. The
 *    cheapest way for that to regress is somebody adding a "refresh the ports
 *    every 30s" loop, which reads like an improvement.
 *  - **No per-frame work, and nothing on Main.** The terminal ANR history
 *    (#796/#803) is unambiguous: per-frame overlay scanners over pane text were
 *    a measured contributor to "PocketShell isn't responding". A `PortScanner`
 *    call or a regex sweep reached from a composable would be exactly that
 *    mistake in a new place.
 *
 * A static guard is the right instrument because the failure is the PRESENCE of
 * a construct, not a wrong output — a behavioural test cannot observe a timer
 * that has not fired yet.
 */
class SessionPortsForegroundOnlySourceGuardTest {

    private val captureSources = listOf(
        "app/src/main/java/com/pocketshell/app/portfwd/SessionPortMentionTracker.kt",
        "app/src/main/java/com/pocketshell/app/portfwd/SessionPortsStore.kt",
        "app/src/main/java/com/pocketshell/app/portfwd/SessionPortMention.kt",
        "app/src/main/java/com/pocketshell/app/portfwd/SessionPortsHostOptions.kt",
    )

    private val uiSources = listOf(
        "app/src/main/java/com/pocketshell/app/portfwd/SessionPortsPanel.kt",
        "app/src/main/java/com/pocketshell/app/portfwd/SessionPortsPanelViewModel.kt",
        "app/src/main/java/com/pocketshell/app/portfwd/PortTable.kt",
    )

    /**
     * These files document WHY each construct is banned, so the prose
     * legitimately names `InterestingPortFilter`, `PortScanner`, `WorkManager`
     * and friends. Scanning raw text would therefore flag the explanation as the
     * violation — so the guard reads CODE only, with comments stripped.
     */
    private fun code(path: String): String =
        source(path)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { line -> line.substringBefore("//") }

    /** The comment stripper must actually remove prose, or every check is vacuous. */
    @Test
    fun `the comment stripper removes KDoc and line comments`() {
        val stripped = code(uiSources.first())
        assertTrue("code must remain", stripped.contains("fun SessionPortsOverlay("))
        assertFalse(
            "KDoc prose must be gone",
            stripped.contains("ONE TRANSPORT"),
        )
        assertTrue(
            "...and that prose really is present in the raw source",
            source(uiSources.first()).contains("ONE TRANSPORT"),
        )
    }

    /** D21: nothing here may run on a schedule. */
    @Test
    fun `no timer, poll loop or background-work scheduler in the capture path`() {
        val banned = listOf(
            "WorkManager",
            "AlarmManager",
            "ForegroundService",
            "java.util.Timer",
            "fixedRateTimer",
            "scheduleAtFixedRate",
            "scheduleWithFixedDelay",
            "while (true)",
            "while(true)",
            "delay(",
            "tickerFlow",
            "kotlinx.coroutines.channels.ticker",
        )
        for (path in captureSources + uiSources) {
            val source = code(path)
            for (token in banned) {
                assertFalse(
                    "$path must contain no `$token` — the ports list is event-driven only (D21)",
                    source.contains(token),
                )
            }
        }
    }

    /**
     * The guard above is only worth having if it would actually fire. Apply the
     * mutation it exists to catch to a copy of the real source and confirm the
     * same predicate rejects it.
     */
    @Test
    fun `the poll-loop guard rejects a source that adds a refresh loop`() {
        val mutated = code(captureSources.first())
            .replace("fun ensureHostLoad() {", "fun ensureHostLoad() {\n        while (true) { delay(30_000) }")

        assertTrue("the mutation must land", mutated.contains("while (true)"))
        assertTrue("...and be visible to the guard's predicate", mutated.contains("delay("))
    }

    /**
     * #796/#803: the capture path stays off the main thread. The tracker is
     * driven from the view model's dedicated port-detection dispatcher (#877);
     * it must not hop to Main itself, and it must not block.
     */
    @Test
    fun `the capture path never touches Main and never blocks`() {
        for (path in captureSources) {
            val source = code(path)
            assertFalse("$path must not hop to Main", source.contains("Dispatchers.Main"))
            assertFalse("$path must not block a thread", source.contains("runBlocking"))
            assertFalse("$path must not sleep", source.contains("Thread.sleep"))
        }
    }

    /**
     * The panel renders a list it is HANDED. If a composable could reach a port
     * scan or a regex sweep over terminal text, that work would run on every
     * recomposition — the #796 per-frame-scanner shape, in a new place.
     */
    @Test
    fun `the panel performs no scanning of its own`() {
        for (path in uiSources) {
            val source = code(path)
            assertFalse("$path must not run a port scan", source.contains("PortScanner"))
            assertFalse("$path must not parse terminal text", source.contains("detectLocalhostPortReferences"))
            assertFalse("$path must not build a regex", source.contains("Regex("))
            assertFalse("$path must not exec over SSH", source.contains("SshSession"))
        }
    }

    /**
     * The explicit non-goal: a session's own ports are shown regardless of the
     * host-wide `3000..10000` denoise range. Attribution already solved that
     * problem; reusing the filter here would hide a port the user's own session
     * printed.
     */
    @Test
    fun `the per-session panel does not reuse the host-wide range filter`() {
        for (path in uiSources) {
            assertFalse(
                "$path must not apply InterestingPortFilter to session-attributed ports",
                code(path).contains("InterestingPortFilter"),
            )
        }
    }

    /**
     * Issue #2160: any tmux command whose stdout we parse must force UTF-8 mode.
     * Pinned across the whole feature, not just the one builder, so a second
     * read site added later cannot quietly ship the corrupted form.
     */
    @Test
    fun `every tmux read in this feature is locale-proof`() {
        val localeProof = Regex("""\btmux\s+-u\b""")
        for (path in captureSources + uiSources) {
            for (line in code(path).lines()) {
                if (!line.contains("show-options") && !line.contains("display-message")) continue
                assertTrue(
                    "$path reads tmux without `-u`: $line",
                    localeProof.containsMatchIn(line) || line.contains("READ_CLIENT"),
                )
            }
        }
    }

    private fun source(path: String): String {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(cursor, path)
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
