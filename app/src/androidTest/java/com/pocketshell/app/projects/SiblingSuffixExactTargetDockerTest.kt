package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.tmux.TmuxSessionGeneration
import com.pocketshell.app.tmux.buildTmuxPaneListingCommand
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxTarget
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1820, rounds 2–3 — **the `<base>` + `<base>-2` sibling must not steal
 * another session's tmux operations.**
 *
 * ## What this reproduces
 *
 * A bare `tmux ... -t <name>` is NOT an exact match. tmux resolves a session
 * target as exact name → **name prefix** → `fnmatch(3)`, so once `<base>-2`
 * exists, every bare-`-t` call site aimed at `<base>` silently lands on the
 * sibling as soon as `<base>` itself is gone (and `has-session -t <base>`
 * answers "taken" even when `<base>` is free).
 *
 * #1820's host-side unique-name resolution makes `<base>` + `<base>-2` the
 * ROUTINE, intended outcome of a second "+ New session" in an occupied folder.
 * That promotes this from a rare edge to a reliable defect on the older
 * sibling:
 *
 *  - **Stop** kills `<base>` fine, then its `has-session` verify prefix-matches
 *    the surviving `<base>-2`, throws `"tmux session '<base>' is still
 *    running."`, the screen shows `Couldn't stop <base>: …` and **the row never
 *    leaves the tree** — closed issue **#168** coming back.
 *  - **Rename** renames fine, then the old-name verify prefix-matches the
 *    sibling and reports `"was not renamed"`.
 *  - **Close window** on a *gone* `<base>` destroys `<base>-2`'s window
 *    instead (verified destructive on tmux 3.4 — it took the sibling session
 *    down with it).
 *  - **Recorded agent kind** is written onto / read back from the sibling
 *    (`ManualKindWriter` is the SOLE kind authority — a second writer target is
 *    the #1 risk), which is the #819/#825 wrong-source class with a new trigger.
 *
 * Round 3 added the two sites a hand-swept inventory missed:
 *
 *  - **`setWindowSizeLatest`** (`TmuxClient`, called on every attach as
 *    `client.setWindowSizeLatest(target.sessionName)`) is an unmitigated
 *    wrong-session **write**: `set-window-option -t '<base>'` returns 0 after
 *    setting the NEIGHBOUR's `window-size` policy.
 *  - **the reconcile `list-panes -s`** returns the SIBLING's rows, which is
 *    worse than reading the wrong session: they parse NON-empty, so
 *    `reconcilePanes`' preserve-last-known-state guard does not fire, and
 *    `applyParsedPanes`' exact-session-name filter then drops every row —
 *    `_panes` is **silently cleared** (blank terminal, no error, nothing to
 *    retry) instead of surfacing `PaneReconcileResult.Failed`.
 *
 * Round 3 also pins [com.pocketshell.core.tmux.TmuxTarget]'s own contract, one
 * case per verb class, against this real tmux server. Centralising the target
 * form is leverage and risk in equal measure — a wrong rule in one place is
 * wrong everywhere at once — and round 2's rule WAS wrong for `list-panes -s`
 * while its KDoc table read plausibly. A described rule is not a pinned rule.
 *
 * ## Why this test is connected, not a string-shape unit test
 *
 * The bug is entirely in what REAL tmux does with a target string. An assertion
 * that the command contains `=` proves only that we typed a character; it
 * cannot fail for the reason the user's Stop button failed. Every case below
 * seeds the real fixture into the `<base>` + `<base>-2` state, drives the
 * PRODUCTION entry point, and asserts on the host's own
 * `tmux list-sessions` / `show-options` afterwards.
 *
 * Each `@Test` here FAILS on the pre-fix tree (bare `-t`) and passes with
 * [com.pocketshell.core.tmux.TmuxTarget]. Red→green captured on the issue.
 *
 * Docker service: `agents` (default host port `2222`; `--pool` lanes override
 * it through the standard instrumentation args). No new fixture required.
 */
@RunWith(AndroidJUnit4::class)
class SiblingSuffixExactTargetDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val createdSessions = mutableListOf<String>()
    private lateinit var trustedHostKeySha256: String

    private val gateway = SshFolderListGateway()

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Issue #2446: was an eagerly-initialized `val`, constructed before
    // `trustedHostKeySha256` is known (property initializers run before
    // `@Before`) — a computed property so every access (all from `@Test`
    // methods, after `setUp()` captured the real presented fingerprint)
    // rebuilds it with the current trust binding.
    private val host: HostEntity
        get() = HostEntity(
            id = 1L,
            name = "issue1820-sibling-suffix",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = 1L,
            trustedHostKeySha256 = trustedHostKeySha256,
        )

    @Before
    fun setUp() { runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        keyFile = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "issue1820-sibling-suffix-key",
        ).apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        trustedHostKeySha256 = waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown() { runBlocking {
        if (createdSessions.isNotEmpty()) {
            runCatching {
                withTimeout(20_000) {
                    withHostSession { session ->
                        for (name in createdSessions) {
                            // Kill by EXACT target so cleanup cannot itself take
                            // out a sibling (the very bug under test).
                            session.exec("tmux kill-session -t '=$name' 2>/dev/null || true")
                        }
                    }
                }
            }
        }
        runCatching { keyFile.delete() }
        clientScope.cancel()
        Unit
    } }

    // --- the four production entry points --------------------------------

    @Test
    fun stopOnTheOlderSiblingReportsSuccessAndDropsOnlyThatSession() { runBlocking {
        val base = seedSiblingPair("stop")
        val sibling = "$base-2"

        val result = gateway.killSession(
            host = host,
            keyPath = keyFile.absolutePath,
            passphrase = null,
            sessionName = base,
        )

        // THE load-bearing assertion: the user pressed Stop, the session really
        // died, so the gateway must report success. Pre-fix this is
        // `Failure(tmux session '<base>' is still running.)` because the verify
        // prefix-matched `<base>-2` — and FolderListViewModel turns that into
        // `Couldn't stop <base>: …` and keeps the dead row (issue #168).
        assertTrue(
            "Stop on '$base' while '$sibling' is alive must SUCCEED; got ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        val live = liveSessionNames()
        assertTrue("'$base' must be gone from the host; live=$live", base !in live)
        assertTrue("'$sibling' must survive an unrelated Stop; live=$live", sibling in live)
    } }

    @Test
    fun renameOnTheOlderSiblingReportsSuccessAndLeavesTheSiblingAlone() { runBlocking {
        val base = seedSiblingPair("rename")
        val sibling = "$base-2"
        // The new name must NOT itself start with `<base>`. That detail is
        // load-bearing and cost this test a round: with `<base>-renamed` as the
        // target, the post-rename host has TWO sessions sharing the `<base>`
        // prefix (`-2` and `-renamed`), tmux's prefix lookup goes ambiguous and
        // answers "can't find session" — so the buggy bare-`-t` verify passed by
        // accident and the case was green on the unfixed tree. With an unrelated
        // new name exactly ONE prefix candidate (`<base>-2`) survives, the bare
        // verify matches it, and the gateway throws "was not renamed". Verified
        // both ways on real tmux 3.4 before pinning it.
        val renamed = base.replace("issue1820-rename-", "issue1820-newname-")
        createdSessions += renamed
        val baseGeneration = sessionGeneration(base)
        assertTrue(
            "the new name must not share the base's prefix, or the bug cannot bite",
            !renamed.startsWith(base),
        )

        val result = gateway.renameSession(
            host = host,
            keyPath = keyFile.absolutePath,
            passphrase = null,
            oldName = base,
            newName = renamed,
            expectedGeneration = baseGeneration,
        )

        assertTrue(
            "Rename '$base'->'$renamed' while '$sibling' is alive must SUCCEED; " +
                "got ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        val live = liveSessionNames()
        assertTrue("'$renamed' must exist after the rename; live=$live", renamed in live)
        assertTrue("'$base' must be gone after the rename; live=$live", base !in live)
        // The sibling must be untouched — a bare `-t` would have renamed IT.
        assertTrue("'$sibling' must be untouched by the rename; live=$live", sibling in live)
    } }

    @Test
    fun closeWindowOnAGoneSessionNeverDestroysTheSiblingsWindow() { runBlocking {
        val base = seedSiblingPair("window")
        val sibling = "$base-2"
        // Enter the reported state: `<base>` is gone (killed out of band /
        // already exited) while the tree still shows its row, and only the
        // `-2` sibling is live. A bare `kill-window -t '<base>:0'` then
        // prefix-matches and kills the SIBLING's only window, which tmux turns
        // into "destroy the sibling session".
        withHostSession { it.exec("tmux kill-session -t '=$base'") }
        assertTrue("precondition: '$base' must be gone", base !in liveSessionNames())

        val result = gateway.killWindow(
            host = host,
            keyPath = keyFile.absolutePath,
            passphrase = null,
            sessionName = base,
            windowIndex = 0,
        )

        val live = liveSessionNames()
        assertTrue(
            "'$sibling' must SURVIVE a close-window aimed at the gone '$base'; live=$live",
            sibling in live,
        )
        assertTrue(
            "closing a window of a gone session must resolve as session-gone, not a " +
                "success against the sibling; result=$result",
            result.getOrNull()?.sessionSurvived != true,
        )
    } }

    @Test
    fun recordedKindWriteAndReadBackNeverTouchTheSibling() { runBlocking {
        val base = seedSiblingPair("kind")
        val sibling = "$base-2"
        withHostSession { session ->
            // Give the SIBLING a recorded kind, and leave `<base>` without one.
            ManualKindWriter.write(session, sibling, SessionAgentKind.Claude)
            // Now remove `<base>` so a bare `-t <base>` prefix-matches `<base>-2`.
            session.exec("tmux kill-session -t '=$base'")
        }
        assertTrue("precondition: '$base' must be gone", base !in liveSessionNames())

        withHostSession { session ->
            // READ side: asking for the gone `<base>`'s recorded kind must
            // answer "unknown", never the sibling's Claude.
            val readBack = AgentConversationRepository()
                .readRecordedAgentKind(session, base)
            assertNull(
                "reading the recorded kind of the gone '$base' must NOT return " +
                    "'$sibling''s recorded kind; got $readBack",
                readBack,
            )
            // WRITE side: writing a kind for the gone `<base>` must not land on
            // the sibling. ManualKindWriter is the SOLE kind authority, so a
            // stray write here silently rewrites a live session's identity.
            runCatching { ManualKindWriter.write(session, base, SessionAgentKind.Codex) }
            val siblingKind = session.exec(
                "tmux show-options -v -t '=$sibling:' @ps_agent_kind 2>/dev/null || true",
            ).stdout.trim()
            assertEquals(
                "'$sibling''s recorded kind must be untouched by a write aimed at '$base'",
                "claude",
                siblingKind,
            )
        }
    } }

    @Test
    fun setWindowSizeLatestNeverWritesTheWindowPolicyOntoTheSibling() { runBlocking {
        val base = seedSiblingPair("winsize")
        val sibling = "$base-2"
        // Park the SIBLING's window policy at a value that is NOT what
        // `setWindowSizeLatest` writes, so a leak is detectable at all — tmux's
        // default for `window-size` is already `latest`, which would make a
        // wrong-session write invisible.
        withHostSession { session ->
            session.exec("tmux set-window-option -t '${literalExactPane(sibling)}' window-size manual")
            // And remove `<base>`, so a bare `-t '<base>'` prefix-matches the sibling.
            session.exec("tmux kill-session -t '${literalExactSession(base)}'")
        }
        assertTrue("precondition: '$base' must be gone", base !in liveSessionNames())
        assertEquals(
            "precondition: the sibling's window policy must start at manual",
            "manual",
            windowSizeOptionOf(sibling),
        )

        // Drive the PRODUCTION client call. `TmuxSessionViewModel` invokes this
        // as `client.setWindowSizeLatest(target.sessionName)` on every attach
        // that has not yet applied the policy — the parameter is a session NAME
        // despite being called `sessionId`.
        withTmuxClientAttachedTo(sibling) { client ->
            runCatching { client.setWindowSizeLatest(base) }
        }

        // THE load-bearing assertion: an unmitigated wrong-session WRITE. Pre-fix
        // the bare `-t '<base>'` prefix-matched `<base>-2` and tmux returned 0
        // after re-writing the NEIGHBOUR's window-size policy to `latest`.
        assertEquals(
            "'$sibling''s window-size policy must be untouched by a " +
                "setWindowSizeLatest aimed at the gone '$base'",
            "manual",
            windowSizeOptionOf(sibling),
        )
    } }

    @Test
    fun paneReconcileOnAGoneSessionFailsLoudlyInsteadOfReturningTheSiblingsPanes() { runBlocking {
        val base = seedSiblingPair("panes")
        val sibling = "$base-2"
        // Give the sibling a second window so its rows are unmistakable and so
        // the `-s` (whole-session) semantics are actually exercised.
        withHostSession { session ->
            session.exec("tmux new-window -t '${literalExactPane(sibling)}'")
            session.exec("tmux kill-session -t '${literalExactSession(base)}'")
        }
        assertTrue("precondition: '$base' must be gone", base !in liveSessionNames())

        // Drive the EXACT production call `TmuxSessionViewModel.reconcilePanes`
        // makes: the command from [buildTmuxPaneListingCommand] over the real
        // `listPanesViaExec` lane, against a live client. The client is attached
        // to the SIBLING so the transport is healthy — the only thing under test
        // is how tmux resolves the reconcile's `-t`.
        val response = withTmuxClientAttachedTo(sibling) { client ->
            client.listPanesViaExec(buildTmuxPaneListingCommand(base))
        }

        // THE load-bearing assertion. Pre-fix this response was NON-error and
        // carried `<base>-2`'s rows. That is worse than "reads the wrong
        // session": `reconcilePanes` sees a NON-empty parse, so its
        // preserve-last-known-state guard does not fire, and `applyParsedPanes`
        // then drops every row on the exact-session-name filter — `_panes` is
        // SILENTLY CLEARED (blank terminal, no error, nothing to retry).
        // `parseListPanesExecResult` maps a non-zero exit to `isError`, which
        // `reconcilePanes` turns into a named, retryable
        // `PaneReconcileResult.Failed`.
        assertTrue(
            "a reconcile aimed at the gone '$base' must FAIL (session gone), not " +
                "succeed against '$sibling'; output=${response.output}",
            response.isError,
        )
        assertTrue(
            "the reconcile must not return '$sibling''s pane rows; output=${response.output}",
            response.output.none { sibling in it },
        )
    } }

    // --- TmuxTarget's own contract, one case per verb class ---------------
    //
    // [TmuxTarget] centralises the target form, which is leverage and risk in
    // equal measure: a wrong rule in one place is wrong everywhere at once. The
    // KDoc's table is therefore pinned here against a REAL tmux server rather
    // than merely described, one test per row.

    @Test
    fun tmuxTargetSessionFormIsExactForTargetSessionVerbs() { runBlocking {
        val (base, _) = goneBaseWithLiveSibling("vc-session")
        withHostSession { session ->
            // Bare: every one of these prefix-matches the surviving sibling.
            assertEquals(
                "bare has-session must (wrongly) succeed — otherwise this class " +
                    "is not reproducing the hazard at all",
                0,
                session.exec("tmux has-session -t '$base'").exitCode,
            )
            assertEquals(
                "bare list-windows must (wrongly) succeed",
                0,
                session.exec("tmux list-windows -t '$base' -F '#{session_name}'").exitCode,
            )
            // TmuxTarget.session: exact for every target-session verb.
            for (verb in listOf("has-session", "kill-session", "rename-session")) {
                val extra = if (verb == "rename-session") " zzz-unused" else ""
                assertFalse(
                    "$verb -t '${TmuxTarget.session(base)}' must NOT resolve a gone session",
                    session.exec(
                        "tmux $verb -t '${TmuxTarget.session(base)}'$extra",
                    ).exitCode == 0,
                )
            }
            assertFalse(
                "list-windows -t '${TmuxTarget.session(base)}' must NOT resolve a gone session",
                session.exec(
                    "tmux list-windows -t '${TmuxTarget.session(base)}' -F '#{session_name}'",
                ).exitCode == 0,
            )
        }
    } }

    @Test
    fun tmuxTargetPaneFormIsExactForTargetPaneVerbs() { runBlocking {
        val (base, sibling) = goneBaseWithLiveSibling("vc-pane")
        withHostSession { session ->
            // Bare `set-option` is the hazard in its worst shape: it silently
            // WRITES onto the neighbour and reports success.
            assertEquals(
                0,
                session.exec("tmux set-option -t '$base' @ps_vc_leak LEAK").exitCode,
            )
            assertEquals(
                "the bare form really does write onto the neighbour",
                "LEAK",
                session.exec(
                    "tmux show-options -v -t '${literalExactPane(sibling)}' @ps_vc_leak",
                ).stdout.trim(),
            )
            // TmuxTarget.pane: exact for every target-pane verb.
            val paneVerbs = listOf(
                "send-keys -t '${TmuxTarget.pane(base)}' x",
                "set-option -t '${TmuxTarget.pane(base)}' @ps_vc_leak2 LEAK",
                "set-window-option -t '${TmuxTarget.pane(base)}' window-size manual",
                "show-options -v -t '${TmuxTarget.pane(base)}' @ps_vc_leak",
                "capture-pane -p -t '${TmuxTarget.pane(base)}'",
            )
            for (cmd in paneVerbs) {
                assertFalse(
                    "`tmux $cmd` must NOT resolve the gone '$base'",
                    session.exec("tmux $cmd").exitCode == 0,
                )
            }
            assertNull(
                "no target-pane verb may have written onto '$sibling'",
                session.exec(
                    "tmux show-options -v -t '${literalExactPane(sibling)}' @ps_vc_leak2",
                ).stdout.trim().takeIf { it.isNotEmpty() },
            )
        }
    } }

    @Test
    fun tmuxTargetWindowFormIsExactForKillWindow() { runBlocking {
        val (base, sibling) = goneBaseWithLiveSibling("vc-window")
        withHostSession { session ->
            assertFalse(
                "kill-window -t '${TmuxTarget.window(base, 0)}' must NOT resolve the gone '$base'",
                session.exec("tmux kill-window -t '${TmuxTarget.window(base, 0)}'").exitCode == 0,
            )
        }
        assertTrue(
            "'$sibling' must survive; a BARE '<base>:0' takes its only window " +
                "(and with it the whole session)",
            sibling in liveSessionNames(),
        )
    } }

    @Test
    fun tmuxTargetPaneFormIsRequiredForListPanesEvenThoughDashSReadsLikeASessionTarget() { runBlocking {
        val (base, sibling) = goneBaseWithLiveSibling("vc-listpanes")
        withHostSession { session ->
            session.exec("tmux new-window -t '${literalExactPane(sibling)}'")
            val fmt = "-F '#{session_name}:#{window_index}.#{pane_index}'"

            // The trap: `-s` reads like a target-SESSION, so a reader files
            // `list-panes -s` under [TmuxTarget.session] by symmetry with
            // `list-windows`. It does not behave that way — the `=` is IGNORED
            // and the prefix match still happens.
            val viaSessionForm =
                session.exec("tmux list-panes -s -t '${TmuxTarget.session(base)}' $fmt")
            assertEquals(
                "TmuxTarget.session('$base') must be shown to be INSUFFICIENT for " +
                    "list-panes -s — if tmux ever starts honouring it here, the KDoc " +
                    "rule needs revisiting, so fail loudly rather than pass quietly",
                0,
                viaSessionForm.exitCode,
            )
            assertTrue(
                "…and it resolves to the sibling; stdout=${viaSessionForm.stdout}",
                sibling in viaSessionForm.stdout,
            )

            // The shipped form is exact.
            val viaPaneForm =
                session.exec("tmux list-panes -s -t '${TmuxTarget.pane(base)}' $fmt")
            assertFalse(
                "list-panes -s -t '${TmuxTarget.pane(base)}' must NOT resolve the gone " +
                    "'$base'; stdout=${viaPaneForm.stdout}",
                viaPaneForm.exitCode == 0,
            )

            // …and the pane form does not cost `-s` its whole-session semantics:
            // it must still list every pane across every window, exactly like the
            // bare form did (#158).
            suspend fun rowsFor(target: String): List<String> =
                session.exec("tmux list-panes -s -t '$target' $fmt")
                    .stdout
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .sorted()
                    .toList()
            val bareRows = rowsFor(sibling)
            val exactRows = rowsFor(literalExactPane(sibling))
            assertTrue("the sibling must span >1 window for this to mean anything: $bareRows", bareRows.size > 1)
            assertEquals(
                "the exact pane form must list the SAME whole-session pane set as the bare form",
                bareRows,
                exactRows,
            )
        }
    } }

    // --- helpers ----------------------------------------------------------

    /**
     * Exact tmux target forms written out LITERALLY, on purpose.
     *
     * Seeding, teardown and the after-the-fact host reads must not be built out
     * of [TmuxTarget] — the code under test. If they were, mutating [TmuxTarget]
     * (which is how the red→green for this class is captured) would break the
     * HARNESS rather than the assertion, and the failure would no longer say
     * anything about the bug. These duplicate the forms deliberately so a
     * regression in [TmuxTarget] shows up as a failed assertion, never as a
     * broken fixture.
     */
    private fun literalExactSession(sessionName: String): String = "=$sessionName"

    private fun literalExactPane(sessionName: String): String = "=$sessionName:"

    private suspend fun sessionGeneration(sessionName: String): TmuxSessionGeneration =
        withHostSession { session ->
            val output = session.exec(
                "tmux display-message -p -t '${literalExactPane(sessionName)}' " +
                    "'#{session_id} #{session_created}'",
            ).stdout.trim()
            val fields = output.split(Regex("\\s+"))
            check(fields.size == 2) {
                "could not read exact generation for '$sessionName': '$output'"
            }
            TmuxSessionGeneration(
                sessionId = fields[0],
                createdEpochSeconds = fields[1].toLongOrNull()
                    ?: error("invalid session_created for '$sessionName': '$output'"),
            )
        }

    /**
     * Seed `<base>` + `<base>-2`, then remove `<base>` so a bare `-t <base>`
     * prefix-matches the surviving sibling. Returns `(base, sibling)`.
     */
    private suspend fun goneBaseWithLiveSibling(tag: String): Pair<String, String> {
        val base = seedSiblingPair(tag)
        val sibling = "$base-2"
        withHostSession { it.exec("tmux kill-session -t '${literalExactSession(base)}'") }
        val live = liveSessionNames()
        assertTrue("precondition: '$base' must be gone; live=$live", base !in live)
        assertTrue("precondition: '$sibling' must be alive; live=$live", sibling in live)
        return base to sibling
    }

    private suspend fun windowSizeOptionOf(sessionName: String): String =
        withHostSession { session ->
            session.exec(
                "tmux show-options -w -v -t '${literalExactPane(sessionName)}' window-size",
            ).stdout.trim()
        }

    /**
     * Run [body] against a REAL connected [TmuxClient] attached to
     * [sessionName] — the same `-CC` client the app runs, so the calls under
     * test go through production `RealTmuxClient` code rather than a fake.
     */
    private suspend fun <T> withTmuxClientAttachedTo(
        sessionName: String,
        body: suspend (TmuxClient) -> T,
    ): T = withTimeout(60_000) {
        withHostSession { session ->
            val client = TmuxClientFactory(clientScope).create(
                session,
                sessionName = sessionName,
            )
            client.use {
                it.connect()
                // Let tmux finish the `-CC` handshake before the first command;
                // the client is a precondition here, not the thing under test.
                delay(500)
                body(it)
            }
        }
    }

    /**
     * Seed the exact reported state: a `<base>` session and its `<base>-2`
     * sibling, both live, in a fresh folder. Returns `<base>`.
     */
    private suspend fun seedSiblingPair(tag: String): String {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val base = "issue1820-$tag-$suffix"
        val sibling = "$base-2"
        val folder = "/tmp/$base"
        withTimeout(30_000) {
            withHostSession { session ->
                session.exec("mkdir -p $folder")
                session.exec("tmux new-session -d -s '$base' -c $folder")
                session.exec("tmux new-session -d -s '$sibling' -c $folder")
            }
        }
        createdSessions += listOf(base, sibling)
        val live = liveSessionNames()
        assertTrue("seed failed: '$base' not live; live=$live", base in live)
        assertTrue("seed failed: '$sibling' not live; live=$live", sibling in live)
        return base
    }

    private suspend fun liveSessionNames(): List<String> = withHostSession { session ->
        session.exec("tmux list-sessions -F '#{session_name}' 2>/dev/null || true")
            .stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private suspend fun <T> withHostSession(body: suspend (SshSession) -> T): T =
        withTimeout(30_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 15_000,
            ).getOrThrow().use { session -> body(session) }
        }
}
