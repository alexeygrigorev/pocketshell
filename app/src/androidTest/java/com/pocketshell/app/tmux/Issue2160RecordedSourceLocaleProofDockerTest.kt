package com.pocketshell.app.tmux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.sessions.HostTmuxSessionListResult
import com.pocketshell.app.sessions.SshHostTmuxSessionsGateway
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxRead
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Issue #2160 — the REAL-PATH (G10/D33) reproduction: exact `@ps_agent_source`
 * binding read over a genuine SSH exec channel to a host whose sshd exports no
 * UTF-8 locale.
 *
 * The JVM sibling (`Issue2160NonUtf8LocaleSourceBindingTest`) models tmux's
 * sanitiser; this one uses the real thing. The Docker `agents` fixture IS the
 * non-happy host the issue describes — its sshd hands the exec channel no
 * `LANG`/`LC_*` at all, so its tmux client is not in UTF-8 mode and
 * `utf8_sanitize()` rewrites the TAB in `@ps_agent_source` to `_` on read. That
 * is what the issue means by "the fixture must be able to enter the failing
 * state"; a UTF-8-locale host (the maintainer's box) cannot, and proves nothing.
 *
 * [nonUtf8LocaleFixturePrecondition] HARD-ASSERTS that property before anything
 * else runs — if the fixture ever gains a locale, this class fails loudly rather
 * than silently degrading into a vacuous pass (no `assumeTrue` self-skip).
 *
 * The load-bearing assertion is
 * [AgentConversationRepository.resolveRecordedSessionOpen]'s `recordedSource` —
 * the production single-round-trip conversation open, the exact call the
 * Conversation view makes. A BUSIER same-kind sibling that flushed more recently
 * shares the project directory, so the mtime selector and the recorded-source
 * binding give different answers (G6): binding the sibling is the #819/#825
 * wrong-source symptom this bug silently restores.
 *
 * RED on base (`44c93855`): `recordedSource` is `null` and the open binds the
 * busier sibling. GREEN with `tmux -u`.
 */
@RunWith(AndroidJUnit4::class)
class Issue2160RecordedSourceLocaleProofDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private val cleanupCommands = mutableListOf<String>()

    private val suffix = System.currentTimeMillis().toString().takeLast(8)
    private val sessionName = "issue2160-$suffix"
    private val home = "/home/$DEFAULT_USER"
    private val agentCwd = "$home/issue2160-proj-$suffix"
    private val projectDir = "$home/.claude/projects/" + "$home/issue2160-proj-$suffix".replace('/', '-')
    private val recordedSource = "$projectDir/recorded-$suffix.jsonl"
    private val busierSibling = "$projectDir/busier-$suffix.jsonl"
    private val generation = "gen2160$suffix"

    @Before
    fun setUp(): Unit { runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation().context.assets
            .open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(20_000) {
                    withSession { it.exec(cleanupCommands.joinToString("\n")) }
                }
            }
            Unit
        }
    } }

    /**
     * The fixture precondition, asserted rather than assumed. A host that exports
     * a UTF-8 locale to its SSH exec channel CANNOT reproduce this bug, so if this
     * ever starts failing the rest of the class has stopped being a reproduction
     * and must be re-anchored — not quietly skipped.
     */
    @Test
    fun nonUtf8LocaleFixturePrecondition(): Unit { runBlocking {
        val env = withTimeout(20_000) {
            withSession { it.exec("env | sort").stdout }
        }
        val localeVars = env.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("LANG=") || it.startsWith("LC_") }
            .toList()
        assertTrue(
            "#2160 depends on the `agents` fixture handing its SSH exec channel NO " +
                "UTF-8 locale — that is what puts tmux into the sanitising mode. The " +
                "fixture now exports $localeVars, so this class is no longer a " +
                "reproduction. Re-anchor it (e.g. `env -i` the tmux invocations) " +
                "instead of skipping.",
            localeVars.isEmpty(),
        )

        // And the mechanism itself, measured on this host in this run: the same
        // option read bare vs with `-u`.
        val probeSession = "issue2160-probe-$suffix"
        cleanupCommands += "tmux kill-session -t '=$probeSession' 2>/dev/null || true"
        val probe = withTimeout(30_000) {
            withSession { session ->
                session.exec(
                    buildString {
                        appendLine("tmux kill-session -t '=$probeSession' 2>/dev/null || true")
                        appendLine("tmux new-session -d -s '$probeSession' 'sleep 60'")
                        appendLine(
                            "tmux set-option -t '=$probeSession:' @ps_probe " +
                                "\"\$(printf 'A\\tB')\"",
                        )
                        appendLine(
                            "printf 'BARE=%s\\n' " +
                                "\"\$(tmux show-options -v -t '=$probeSession:' @ps_probe | od -An -c | tr -s ' ')\"",
                        )
                        appendLine(
                            "printf 'DASHU=%s\\n' " +
                                "\"\$(tmux -u show-options -v -t '=$probeSession:' @ps_probe | od -An -c | tr -s ' ')\"",
                        )
                    },
                ).stdout
            }
        }
        val bare = probe.lineSequence().first { it.startsWith("BARE=") }
        val dashU = probe.lineSequence().first { it.startsWith("DASHU=") }
        assertTrue(
            "the bare read must show the sanitised delimiter on this fixture; got $bare",
            bare.contains(" _ "),
        )
        assertTrue(
            "`tmux -u` must preserve the TAB on this fixture; got $dashU",
            dashU.contains("\\t"),
        )
    } }

    /**
     * THE REPORTED SYMPTOM, on the real path. Seeds the full host-side launch
     * record the `pocketshell agent` wrapper writes — `@ps_agent_kind=claude`,
     * `@ps_agent_source_generation=<gen>`, `@ps_agent_source="<gen>\t<path>"` —
     * plus a BUSIER same-cwd sibling transcript, then drives the production
     * conversation open and asserts which transcript it binds.
     */
    @Test
    fun recordedSourceBindsExactlyOnThisNonUtf8LocaleHost(): Unit { runBlocking {
        cleanupCommands += "tmux kill-session -t '=$sessionName' 2>/dev/null || true"
        cleanupCommands += "rm -rf '$agentCwd' '$projectDir' 2>/dev/null || true"

        val seedOutput = withTimeout(90_000) {
            withSession { session ->
                session.exec(
                    buildString {
                        // `set -eu` so a broken seed FAILS the test rather than
                        // silently producing a fixture that cannot enter the
                        // failing state. Every command below is BusyBox-safe (the
                        // fixture image has no GNU coreutils — `touch -d "10
                        // minutes ago"` is rejected there, which is why the mtime
                        // ordering is created by write order + `sleep`).
                        appendLine("set -eu")
                        appendLine("mkdir -p '$agentCwd' '$projectDir'")
                        // The recorded transcript — this is what @ps_agent_source names.
                        appendLine("cat > '$recordedSource' <<'JSONL_EOF'")
                        appendLine(
                            """{"uuid":"u2160-1","timestamp":"2026-08-16T10:00:00Z",""" +
                                """"message":{"role":"user","content":"recorded transcript"}}""",
                        )
                        appendLine("JSONL_EOF")
                        appendLine("sleep 2")
                        // A BUSIER same-kind sibling in the SAME project dir that
                        // flushed LATER (strictly newer mtime). The mtime selector
                        // picks THIS one, so "recorded source honoured" and
                        // "recorded source ignored" have different answers (G6).
                        appendLine("cat > '$busierSibling' <<'JSONL_EOF'")
                        appendLine(
                            """{"uuid":"u2160-2","timestamp":"2026-08-16T10:05:00Z",""" +
                                """"message":{"role":"user","content":"busier sibling"}}""",
                        )
                        appendLine("JSONL_EOF")
                        appendLine("tmux kill-session -t '=$sessionName' 2>/dev/null || true")
                        appendLine(
                            "tmux new-session -d -x 80 -y 24 -s '$sessionName' " +
                                "-c '$agentCwd' 'sleep 300'",
                        )
                        // Exactly what `record_agent_kind` / `record_agent_source`
                        // write on a real launch.
                        appendLine("tmux set-option -t '=$sessionName:' @ps_agent_kind claude")
                        appendLine(
                            "tmux set-option -t '=$sessionName:' " +
                                "@ps_agent_source_generation '$generation'",
                        )
                        appendLine(
                            "tmux set-option -t '=$sessionName:' @ps_agent_source " +
                                "\"\$(printf '$generation\\t$recordedSource')\"",
                        )
                        appendLine(
                            "printf 'MTIMES=%s\\n' " +
                                "\"\$(ls -t '$projectDir' | tr '\\n' ',')\"",
                        )
                        appendLine(
                            "printf 'TTY=%s\\n' " +
                                "\"\$(tmux list-panes -t '=$sessionName:' -F '#{pane_tty}' | head -1)\"",
                        )
                    },
                ).stdout
            }
        }
        val paneTty = seedOutput.lineSequence()
            .firstOrNull { it.startsWith("TTY=") }?.removePrefix("TTY=")?.trim().orEmpty()
        assertTrue(
            "the seed must produce a pane tty for '$sessionName'; seed output was:\n$seedOutput",
            paneTty.startsWith("/dev/"),
        )
        val newestFirst = seedOutput.lineSequence()
            .firstOrNull { it.startsWith("MTIMES=") }?.removePrefix("MTIMES=")?.trim().orEmpty()
        assertTrue(
            "the busier sibling must be the NEWEST file in the project dir, otherwise " +
                "the mtime selector and the recorded-source binding would agree and this " +
                "test could not discriminate (G6); ls -t gave '$newestFirst'",
            newestFirst.startsWith(busierSibling.substringAfterLast('/')),
        )

        val open = withTimeout(60_000) {
            withSession { session ->
                AgentConversationRepository().resolveRecordedSessionOpen(
                    session = session,
                    sessionTarget = sessionName,
                    cwd = agentCwd,
                    paneTty = paneTty,
                    paneCommand = "claude",
                )
            }
        }

        assertEquals(
            "precondition: the recorded kind must read back as Claude",
            AgentKind.ClaudeCode,
            open.recordedKind,
        )
        // LOAD-BEARING (G6): the symptom-defining signal is WHICH transcript the
        // production open binds.
        assertEquals(
            "#2160: on a host whose SSH exec carries no UTF-8 locale, the exact " +
                "recorded transcript must bind. `null` means the TAB in " +
                "@ps_agent_source was sanitised to '_' on read and the value could " +
                "not be split against the live generation; the busier sibling " +
                "($busierSibling) means the mtime selector silently took over — the " +
                "#819/#825 wrong-source class.",
            recordedSource,
            open.recordedSource,
        )
        assertEquals(
            "the bound Conversation source must be the recorded transcript",
            recordedSource,
            open.detection?.sourcePath,
        )
    } }

    /**
     * The SECOND read site of the same class, on the same real host (round 2).
     * [SshHostTmuxSessionsGateway.listSessions] is the host session picker's
     * COLD path — the one that runs whenever there is no warm `-CC` client —
     * and it expands `#{session_name}`, which is free-form and routinely
     * Cyrillic for this maintainer.
     *
     * ## Why the discriminator injects the environment (the #780 model)
     *
     * Measured on this fixture during round 2, and worth recording because it
     * is not obvious: unlike the recorded-source read above, the picker's
     * command is wrapped by `ReposRemoteSource.pathAwareCommand`, i.e.
     * `/bin/sh -lc '…'`. `-l` makes it a LOGIN shell, so it sources
     * `/etc/profile`, and this Alpine image's profile exports `LANG=C.UTF-8`.
     * The wrapped command therefore gets a UTF-8 locale *on this fixture* even
     * without `-u` — so a plain end-to-end gateway call here passes with AND
     * without the fix, and would be a decorative assertion.
     *
     * A host whose `/etc/profile` exports nothing (a Debian slim image, a
     * hardened login shell, `sh` without a profile) hands the same wrapped
     * command no locale at all, which is the reported state. Per D33, that
     * state is injected SYNTHETICALLY here and hard-failed rather than skipped:
     * the PRODUCTION constant is executed on the real host with the environment
     * emptied, alongside the identical command with `-u` removed. The second
     * probe is the non-vacuity proof — if it ever stops mangling, this fixture
     * has stopped being able to enter the failing state and the test says so
     * instead of passing quietly.
     *
     * The gateway leg below is a plumbing smoke check only, and is labelled as
     * such: on this fixture it cannot discriminate, for the reason above.
     */
    @Test
    fun hostSessionPickerEnumerationCommandIsLocaleProofOnThisRealHost(): Unit { runBlocking {
        val cyrillicSession = "проект-2160-$suffix"
        // tmux `utf8_sanitize()`: every character that is not printable ASCII
        // becomes one `_` — per CHARACTER, not per byte.
        val mangledSession = cyrillicSession
            .map { ch -> if (ch.code in 0x20..0x7e) ch else '_' }
            .joinToString("")
        cleanupCommands += "tmux kill-session -t '=$cyrillicSession' 2>/dev/null || true"

        // The PRODUCTION command, byte-for-byte, and the same command with the
        // locale-proof flag removed — the only difference between them.
        val productionCommand = SshHostTmuxSessionsGateway.LIST_SESSIONS_COMMAND
        assertTrue(
            "the production picker command must carry `${TmuxRead.CLIENT}`; got $productionCommand",
            TmuxRead.isLocaleProofInvocation(productionCommand),
        )
        val withoutDashU = productionCommand.replaceFirst("${TmuxRead.CLIENT} ", "tmux ")
        val strippedEnv = "env -i PATH=/usr/local/bin:/usr/bin:/bin "

        val probe = withTimeout(90_000) {
            withSession { session ->
                session.exec(
                    buildString {
                        appendLine("set -eu")
                        appendLine("tmux kill-session -t '=$cyrillicSession' 2>/dev/null || true")
                        appendLine(
                            "tmux new-session -d -x 80 -y 24 -s '$cyrillicSession' 'sleep 300'",
                        )
                        // Prove the name is STORED intact, so a mangled read is
                        // a read-side defect and not a broken seed.
                        appendLine(
                            "printf 'STORED=%s\\n' " +
                                "\"\$(tmux -u list-sessions -F '#{session_name}' | " +
                                "grep -a -- '-2160-$suffix')\"",
                        )
                        appendLine(
                            "printf 'PRODUCTION=%s\\n' " +
                                "\"\$($strippedEnv$productionCommand | grep -a -- '-2160-$suffix')\"",
                        )
                        appendLine(
                            "printf 'WITHOUT_U=%s\\n' " +
                                "\"\$($strippedEnv$withoutDashU | grep -a -- '-2160-$suffix')\"",
                        )
                    },
                ).stdout
            }
        }
        fun field(name: String): String = probe.lineSequence()
            .firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")?.trim().orEmpty()

        assertEquals(
            "the seed must store the Cyrillic session name intact, otherwise this test " +
                "could not tell a read-side defect from a broken fixture; probe output:\n$probe",
            cyrillicSession,
            field("STORED").substringAfterLast("::"),
        )
        // NON-VACUITY: the state is reachable on this host, right now. If this
        // stops mangling, the discriminator is gone — fail, do not skip.
        assertTrue(
            "#2160: with the locale emptied, the SAME command WITHOUT `-u` must mangle the " +
                "session name to `$mangledSession` on this host — that is the reported " +
                "failure, and without it the assertion below proves nothing. Probe output:\n" +
                probe,
            field("WITHOUT_U").contains(mangledSession),
        )
        // LOAD-BEARING: the production command, on a real host with no locale,
        // must return the name the picker shows and navigates by.
        assertTrue(
            "#2160: the production host-picker enumeration command must read " +
                "`#{session_name}` intact on a host whose exec environment carries no " +
                "UTF-8 locale. Probe output:\n$probe",
            field("PRODUCTION").contains(cyrillicSession),
        )

        // Plumbing smoke ONLY (see the KDoc): the real gateway, parser and
        // lease borrow return the session. This leg CANNOT discriminate on this
        // fixture because `sh -lc` sources a profile that exports LANG.
        val manager = SshLeaseManager(
            connector = SshLeaseConnector {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = sshKey,
                    knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                    timeoutMs = 20_000,
                )
            },
        )
        try {
            val gateway = SshHostTmuxSessionsGateway(
                parser = HostTmuxSessionListParser(),
                // Empty: forces the COLD path, which is the site under test.
                activeTmuxClients = ActiveTmuxClients(),
                sshLeaseManager = manager,
            )
            val result = withTimeout(60_000) {
                gateway.listSessions(
                    host = HostEntity(
                        id = 2160L,
                        name = "agents-fixture",
                        hostname = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        username = DEFAULT_USER,
                        keyId = 1L,
                    ),
                    keyPath = "/unused-the-connector-holds-the-pem",
                    passphrase = null,
                )
            }
            assertTrue("expected Sessions, got $result", result is HostTmuxSessionListResult.Sessions)
            val names = (result as HostTmuxSessionListResult.Sessions).rows.map { it.name }
            assertTrue(
                "the production gateway must reach the fixture and list the seeded " +
                    "session; rows were $names",
                names.contains(cyrillicSession),
            )
        } finally {
            manager.close()
        }
    } }

    private suspend fun <T> withSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 20_000,
        ).getOrThrow()
        return session.use { block(it) }
    }
}
