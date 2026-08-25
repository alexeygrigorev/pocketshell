package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.TmuxRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #2160 — the SOURCE-LEVEL sweep for locale-proof tmux reads.
 *
 * ## Why this exists (round 2)
 *
 * Round 1 shipped a runtime ratchet
 * ([Issue2160NonUtf8LocaleSourceBindingTest.everyTmuxCommandIssuedByTheDetectionPathIsLocaleProof])
 * that collects the commands the `AgentConversationRepository` detection path
 * actually issues. It is a good ratchet for that path and a bad one for the
 * CLASS: it was green while
 * `SshHostTmuxSessionsGateway`'s cold session-picker enumeration and the two
 * `TmuxSessionViewModel` user-option reads were still bare, because those
 * sites are simply never driven by that fixture. A guard whose name promises
 * "every read" while it can only observe one path is how those sites stayed
 * invisible.
 *
 * This test closes that by reading the PRODUCTION SOURCE instead of running
 * one path: it scans every Kotlin file under `app/src/main/java` and every
 * `shared/<module>/src/main/java` for a tmux client invocation of a READ verb,
 * and fails on any that is not the locale-proof form. A new bare read added
 * anywhere in those trees — in a file nobody thought to look at — reddens
 * this test.
 *
 * ## The bug it ratchets
 *
 * tmux decides at client start-up whether it is in UTF-8 mode from the
 * invoking process's `LC_ALL`/`LC_CTYPE`/`LANG`. When it is not, everything it
 * prints goes through `utf8_sanitize()`, which replaces each character that is
 * not printable ASCII with a single `_`. An SSH **exec** channel gets whatever
 * environment the host's sshd hands it, and a minimal host hands it none — so
 * a TAB delimiter, a Cyrillic session name, a Cyrillic project path, or a
 * non-ASCII profile label all come back destroyed. `${'$'}{TmuxRead.CLIENT}`
 * (`tmux -u`) forces UTF-8 mode regardless of the environment. See [TmuxRead].
 *
 * ## What this guard does NOT reach — stated, not hidden
 *
 * Two classes of read cannot be expressed as a `tmux …` shell word at their
 * call site and are therefore invisible to this scan by construction. Both are
 * tracked, and neither is silently omitted:
 *
 *  - **`-CC` control-mode sends.** `client.sendCommand("list-sessions -F …")`
 *    and friends travel over the attached control client, whose UTF-8 mode is
 *    fixed by the `tmux -CC` attach command line. Making that leg locale-proof
 *    means changing the attach itself (connection-core surface, and at least
 *    one connected identity oracle keys on that line) — issue **#2175**.
 *  - **Lanes whose sub-command is interpolated at the call site.**
 *    `TmuxClient.listPanesViaExec` / `sendLifecycleViaExec` build
 *    `"${'$'}{TmuxRead.CLIENT} ${'$'}listPanesCommand"`, so no read verb is
 *    spelled there and this scan cannot see them. They are covered instead by
 *    [theTmuxClientExecLanesWithInterpolatedSubCommandsAreLocaleProof] below,
 *    which pins those two exact call sites, and behaviourally by
 *    `TmuxClientExecLaneTest`'s assertions derived from [TmuxRead.CLIENT].
 *
 * And the exec-lane sites listed in [EXEMPTIONS] below, each with its reason and
 * its tracking issue. [everyExemptionStillMatchesALiveSite] fails if any of them
 * stops matching, so an exemption cannot outlive the site it excuses and quietly
 * cover a different one.
 */
class Issue2160LocaleProofReadSiteSourceGuardTest {

    private companion object {

        /**
         * tmux sub-commands whose stdout PocketShell parses. A command that
         * only reports through its exit status (`has-session`) or that writes
         * (`set-option`, `send-keys`, `new-session`, `kill-session`,
         * `rename-session`) prints nothing we read, so it needs no `-u`.
         */
        val READ_VERBS: List<String> = listOf(
            "show-options",
            "show-option",
            "show-environment",
            "list-sessions",
            "list-panes",
            "list-windows",
            "list-clients",
            "display-message",
            "capture-pane",
        )

        /**
         * Sites deliberately NOT locale-proof yet. Each entry names the file,
         * a marker that identifies the exact invocation, why it is exempt, and
         * the issue that will remove it. Adding an entry here is a visible,
         * reviewable act; leaving a site out of the guard's reach is not.
         */
        val EXEMPTIONS: List<Exemption> = listOf(
            Exemption(
                pathSuffix = "app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt",
                marker = "@ps_agent_kind",
                reason = "refreshCurrentSessionRecordedKind's recorded-kind read. On the " +
                    "detection path and in the same class as the fixed sites, but the file " +
                    "is owned by the concurrent #2124 lane, so the two-line change is " +
                    "sequenced after it rather than conflicting with it. Not a live hole: " +
                    "@ps_agent_kind is one of claude/codex/opencode/shell.",
                issue = "#2177",
            ),
            Exemption(
                pathSuffix = "app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt",
                marker = "@ps_agent_profile",
                reason = "refreshCurrentSessionRecordedKind's profile-label read. Same file " +
                    "ownership as above. @ps_agent_profile IS free-form human text, so this " +
                    "one is a live (cosmetic) instance of the class.",
                issue = "#2177",
            ),
        )

        /**
         * Production diagnostics may name the read operation they report. They
         * are not invocations, so the scanner must not mistake the marker text
         * for a bare tmux read. Keep this allowlist narrow and anchored to the
         * diagnostic marker's owning source file.
         */
        val NON_READ_DIAGNOSTICS: List<NonReadDiagnostic> = listOf(
            NonReadDiagnostic(
                pathSuffix = "app/src/main/java/com/pocketshell/app/sessions/HostTmuxSessionsGateway.kt",
                marker = "JOURNEY_ENUMERATION_STALL",
                reason = "typed picker enumeration timeout marker; reports a completed diagnostic",
                issue = "#2317",
            ),
        )

        /**
         * Files that MUST contain at least one locale-proof read after the fix.
         * This is the anti-vacuity control: if the scanner's regexes ever stop
         * matching (a refactor, a formatting change), it would find nothing at
         * all and the ratchet would pass while protecting nothing — the
         * repository's most expensive recurring failure shape.
         */
        val MUST_HAVE_A_PROOF_SITE: List<String> = listOf(
            "app/src/main/java/com/pocketshell/app/session/AgentConversationRepository.kt",
            "app/src/main/java/com/pocketshell/app/projects/FolderListGateway.kt",
            "app/src/main/java/com/pocketshell/app/sessions/HostTmuxSessionsGateway.kt",
            "shared/core-tmux/src/main/java/com/pocketshell/core/tmux/TmuxClient.kt",
        )

        const val TMUX_CLIENT_PATH: String =
            "shared/core-tmux/src/main/java/com/pocketshell/core/tmux/TmuxClient.kt"

        /**
         * The two `TmuxClient` exec lanes whose sub-command is interpolated —
         * no read verb is spelled at the call site, so the scan above cannot
         * see them. Both carry `-F` enumeration formats that expand `@ps_*`
         * user options and `#{pane_current_path}` / `#{session_path}`.
         */
        val INTERPOLATED_READ_LANES: List<String> = listOf(
            "\$listPanesCommand",
            "\$sessionCommand",
        )

        /**
         * The scanned trees hold ~570 Kotlin files today. A floor well below
         * that catches a broken root resolution (which would otherwise scan
         * zero files and pass).
         */
        const val MIN_SCANNED_FILES: Int = 300

        /**
         * A `${'$'}{TmuxRead.CLIENT}` interpolation or a literal `tmux`, plus any
         * client flags, immediately before the read verb.
         */
        val CLIENT_PREFIX = Regex("([$]\\{TmuxRead[.]CLIENT}|\\btmux\\b)((?:\\s+-[A-Za-z]+)*)\\s+$")

        val BLOCK_COMMENT = Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("//[^\\n]*")
    }

    data class Exemption(
        val pathSuffix: String,
        val marker: String,
        val reason: String,
        val issue: String,
    )

    data class NonReadDiagnostic(
        val pathSuffix: String,
        val marker: String,
        val reason: String,
        val issue: String,
    )

    private data class ReadSite(
        val path: String,
        val line: Int,
        val snippet: String,
        val localeProof: Boolean,
    ) {
        override fun toString(): String = "$path:$line  `$snippet`"
    }

    /**
     * THE RATCHET. Every tmux read invocation in the production trees must be
     * the locale-proof form, unless it is an explicitly documented exemption.
     *
     * Mutation that must redden this: drop the `-u` from any fixed site, e.g.
     * restore `SshHostTmuxSessionsGateway.LIST_SESSIONS_COMMAND` to
     * `"tmux list-sessions -F …"`.
     */
    @Test
    fun everyTmuxReadInProductionSourceIsLocaleProofOrExplicitlyExempt() {
        val (sites, scannedFiles) = scan()

        assertTrue(
            "scanned only $scannedFiles Kotlin files — the source roots did not resolve, " +
                "so this guard would pass while protecting nothing",
            scannedFiles >= MIN_SCANNED_FILES,
        )

        val bare = sites.filterNot { it.localeProof }
        val unexplained = bare.filter { site -> exemptionsFor(site).isEmpty() }
        assertTrue(
            "#2160: these tmux READ invocations are not locale-proof. On a host whose SSH " +
                "exec carries no UTF-8 locale, tmux sanitises every non-printable-ASCII " +
                "character it prints, so whatever this command's stdout is parsed for comes " +
                "back as `_`. Use `\${TmuxRead.CLIENT}` (`${TmuxRead.CLIENT}`) instead of a " +
                "bare `tmux`, or add a documented EXEMPTIONS entry naming the reason and the " +
                "tracking issue.\n" +
                unexplained.joinToString("\n") { "  $it" },
            unexplained.isEmpty(),
        )
    }

    /**
     * An exemption that no longer matches any site is a stale excuse: it stops
     * describing reality and can silently cover a DIFFERENT invocation added
     * later to the same file. Fail so it is deleted when its site is fixed.
     */
    @Test
    fun everyExemptionStillMatchesALiveSite() {
        val (sites, _) = scan()
        val stale = EXEMPTIONS.filter { exemption ->
            sites.none { site -> !site.localeProof && matches(exemption, site) }
        }
        assertEquals(
            "#2160: these EXEMPTIONS no longer match a bare read site — the site was fixed " +
                "or moved. Delete the entry (or re-anchor it) so the guard's stated reach " +
                "matches its actual reach:\n" +
                stale.joinToString("\n") { "  ${it.pathSuffix} [${it.marker}] ${it.issue}" },
            emptyList<Exemption>(),
            stale,
        )
    }

    /**
     * Regression proof for the explicit diagnostic exception above. The source
     * marker must remain live, while its human-readable tmux operation must not
     * become an unproof read site in the scanner's result.
     */
    @Test
    fun everyNonReadDiagnosticStillMatchesALiveSourceAndIsIgnoredByScanner() {
        val root = repoRoot()
        val stale = NON_READ_DIAGNOSTICS.filter { diagnostic ->
            val text = File(root, diagnostic.pathSuffix).readText()
            text.lineSequence().none { line -> line.contains(diagnostic.marker) }
        }
        assertEquals(
            "#2160: these non-read diagnostic allowlist entries no longer match a live " +
                "production marker; delete or re-anchor them:\n" +
                stale.joinToString("\n") { "  ${it.pathSuffix} [${it.marker}] ${it.issue}" },
            emptyList<NonReadDiagnostic>(),
            stale,
        )

        val (sites, _) = scan()
        val diagnosticReads = sites.filter { site ->
            site.path == "app/src/main/java/com/pocketshell/app/sessions/HostTmuxSessionsGateway.kt" &&
                site.snippet.contains("tmux list-sessions")
        }
        assertTrue(
            "#2317: the JOURNEY_ENUMERATION_STALL log names the operation but is not a " +
                "tmux invocation; it must not be classified as a read site: $diagnosticReads",
            diagnosticReads.isEmpty(),
        )
    }

    /**
     * Anti-vacuity (the G3/FROM-CACHE family applied to a source guard): prove
     * the scanner actually finds the locale-proof sites it is meant to police.
     * Without this, a scanner that matched nothing would report "no violations"
     * — a green over nothing.
     */
    @Test
    fun theScannerFindsTheKnownLocaleProofReadSites() {
        val (sites, _) = scan()
        val proofFiles = sites.filter { it.localeProof }.map { it.path }.toSet()
        MUST_HAVE_A_PROOF_SITE.forEach { path ->
            assertTrue(
                "#2160: expected at least one locale-proof tmux read in $path, found none. " +
                    "Either the fix regressed or the scanner stopped matching (in which case " +
                    "this whole guard is vacuous). Locale-proof sites found: " +
                    sites.filter { it.localeProof }.joinToString("\n  ", prefix = "\n  "),
                proofFiles.contains(path),
            )
        }
    }

    /**
     * The blind spot the scan cannot cover, covered explicitly rather than
     * left unmentioned: `TmuxClient`'s two enumeration exec lanes interpolate
     * their sub-command, so no read verb appears at the call site.
     *
     * `sendKeysViaExec` is deliberately NOT here — `send-keys` prints nothing
     * PocketShell parses, so it is a write lane, not a read one.
     */
    @Test
    fun theTmuxClientExecLanesWithInterpolatedSubCommandsAreLocaleProof() {
        val text = File(repoRoot(), TMUX_CLIENT_PATH).readText()
        INTERPOLATED_READ_LANES.forEach { lane ->
            assertTrue(
                "#2160: $TMUX_CLIENT_PATH must build `\${TmuxRead.CLIENT} $lane`; a bare " +
                    "`tmux $lane` reads the `-F` enumeration (which expands the @ps_* user " +
                    "options and the path fields) through a client that sanitises its output " +
                    "on a host with no UTF-8 locale",
                text.contains("\"\${TmuxRead.CLIENT} $lane\""),
            )
            assertTrue(
                "#2160: $TMUX_CLIENT_PATH must not reintroduce a bare `tmux $lane`",
                !text.contains("\"tmux $lane\""),
            )
        }
    }

    private fun exemptionsFor(site: ReadSite): List<Exemption> =
        EXEMPTIONS.filter { matches(it, site) }

    private fun matches(exemption: Exemption, site: ReadSite): Boolean =
        site.path == exemption.pathSuffix && site.snippet.contains(exemption.marker)

    /** @return every tmux read invocation found, and the number of files scanned. */
    private fun scan(): Pair<List<ReadSite>, Int> {
        val root = repoRoot()
        val roots = buildList {
            add(File(root, "app/src/main/java"))
            File(root, "shared").listFiles()
                ?.sortedBy { it.name }
                ?.map { File(it, "src/main/java") }
                ?.filter { it.isDirectory }
                ?.forEach { add(it) }
        }.filter { it.isDirectory }

        val sites = mutableListOf<ReadSite>()
        var scanned = 0
        roots.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                scanned++
                val relative = file.relativeTo(root).path
                sites += readSitesIn(relative, stripComments(file.readText()))
            }
        }
        return sites to scanned
    }

    /**
     * Comments (including KDoc) are replaced by whitespace, not deleted, so
     * line numbers stay truthful. Documentation of this very bug quotes bare
     * `tmux show-options -v …` invocations and must not be flagged.
     */
    private fun stripComments(text: String): String {
        val withoutBlocks = BLOCK_COMMENT.replace(text) { match ->
            match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
        }
        return LINE_COMMENT.replace(withoutBlocks) { match -> " ".repeat(match.value.length) }
    }

    private fun readSitesIn(path: String, text: String): List<ReadSite> {
        val found = mutableListOf<ReadSite>()
        READ_VERBS.forEach { verb ->
            Regex(Regex.escape(verb)).findAll(text).forEach { match ->
                if (isNonReadDiagnostic(path, text, match)) return@forEach
                // A tmux SUB-COMMAND is followed by a flag, or ends the string
                // literal. Prose that merely names one ("tmux list-panes failed
                // during prewarm") is followed by a word, so it is not an
                // invocation and is not scanned.
                val after = text.substring(match.range.last + 1)
                    .takeWhile { it == ' ' }
                    .let { spaces -> text.getOrNull(match.range.last + 1 + spaces.length) }
                if (after != '-' && after != '"' && after != '\'') return@forEach

                val prefixWindow = text.substring(maxOf(0, match.range.first - 48), match.range.first)
                val prefix = CLIENT_PREFIX.find(prefixWindow) ?: return@forEach

                val client = prefix.groupValues[1]
                val flags = prefix.groupValues[2]
                val localeProof = client.contains("TmuxRead.CLIENT") ||
                    Regex("""\s-u\b""").containsMatchIn(flags)

                val snippetStart = match.range.first - (prefix.value.length)
                val snippet = text
                    .substring(maxOf(0, snippetStart), minOf(text.length, match.range.last + 40))
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                found += ReadSite(
                    path = path,
                    line = text.substring(0, match.range.first).count { it == '\n' } + 1,
                    snippet = snippet,
                    localeProof = localeProof,
                )
            }
        }
        return found.sortedWith(compareBy({ it.path }, { it.line }))
    }

    private fun isNonReadDiagnostic(path: String, text: String, match: MatchResult): Boolean {
        val lineStart = text.lastIndexOf('\n', startIndex = match.range.first - 1) + 1
        val lineEnd = text.indexOf('\n', startIndex = match.range.last + 1)
            .let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        return NON_READ_DIAGNOSTICS.any { diagnostic ->
            diagnostic.pathSuffix == path && line.contains(diagnostic.marker)
        }
    }

    private fun repoRoot(): File {
        var cursor: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val current = cursor ?: return@repeat
            if (File(current, "settings.gradle.kts").isFile) return current
            cursor = current.parentFile
        }
        error("Cannot locate the repository root from ${System.getProperty("user.dir")}")
    }
}
