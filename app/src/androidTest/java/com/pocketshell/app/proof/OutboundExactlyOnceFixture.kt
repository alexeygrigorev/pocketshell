package com.pocketshell.app.proof

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Remote/DB fixture for [OutboundExactlyOnceAcrossFlapE2eTest].
 *
 * Extracted from the test class in issue #2056 round 2: the class had grown past the
 * repository's 128 KiB file-size hygiene threshold, and this seeding block is a
 * self-contained concern (tmux sessions, the fake agent's environment, the seeded
 * transcript, the host row) with no dependency on Compose or the test's own waits.
 * `scripts/check-file-size-hygiene.sh` explicitly asks for a split rather than a raised
 * baseline.
 */
internal object OutboundExactlyOnceFixture {
    const val DATABASE_NAME: String = "pocketshell.db"
    const val SESSION_NAME: String = "issue1526-exactly-once"
    const val SESSION_B: String = "issue1944-switch-b"
    const val SESSION_B_MARKER: String = "ISSUE1944-B-READY"
    const val FAKE_AGENT_LEDGER_PATH: String = "/tmp/pocketshell-fake-agent-submits.log"
    const val LATE_ACK_STAGING_JSONL: String = "/tmp/pocketshell-issue2037-delayed.jsonl"

    /** Issue #2056: pid of the #2042 transcript relay, so it can never outlive its test. */
    const val TRANSCRIPT_RELAY_PID_PATH: String = "/tmp/pocketshell-issue2042-relay.pid"
    const val SEEDED_JSONL: String = "issue1526-live-claude.jsonl"
    const val FAKE_AGENT_READY: String = "FAKE-AGENT-READY"

    private const val CLAUDE_PROJECT_DIR: String = "/home/testuser/.claude/projects/-home-testuser"
    private val TRANSCRIPT_PATH: String = "$CLAUDE_PROJECT_DIR/$SEEDED_JSONL"

    /**
     * The three journeys whose subject is the LATE-AUTHORITY path. They get the delayed
     * transcript AND the deliberately undecidable input surface — see
     * [Issue2056InducedSubmitAmbiguity] for why the second half became necessary.
     */
    val LATE_AUTHORITY_TESTS: Set<String> = setOf(
        "manualRetryOfUnconfirmedSubmitWaitsForLateAuthority",
        "lateAckSurvivesCloseAndRecreateWithoutDuplicate",
        "busyAgentOnStableWritableWireQueuesFifoWithoutBurningAttemptBudget",
    )

    private const val HEARTBEAT_TEST: String =
        "busyAgentOnStableWritableWireQueuesFifoWithoutBurningAttemptBudget"

    fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1526-exactly-once-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1526 ExactlyOnce",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    suspend fun seedFakeAgentSession(key: String, methodName: String) {
        val fakeAgentTranscript =
            if (methodName in LATE_AUTHORITY_TESTS) LATE_ACK_STAGING_JSONL else TRANSCRIPT_PATH
        val renderModeEnv =
            Issue2056InducedSubmitAmbiguity.renderModeEnvFor(methodName, LATE_AUTHORITY_TESTS)
        val heartbeatEnv =
            if (methodName == HEARTBEAT_TEST) "POCKETSHELL_FAKE_AGENT_HEARTBEAT=1 " else ""
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true")
            appendLine("rm -f ${shellQuote(FAKE_AGENT_LEDGER_PATH)}")
            // #2056 round 2: a transcript relay left running by an earlier test in this
            // class shares this fixture, and would publish THIS test's staged submit
            // early — pruning the very row the test is waiting to observe parked. Kill
            // any survivor before touching the staging file (defence in depth: the
            // relay also self-terminates after one publish).
            appendLine(killStrayTranscriptRelay())
            appendLine("rm -f ${shellQuote(TRANSCRIPT_RELAY_PID_PATH)}")
            appendLine("rm -f ${shellQuote(LATE_ACK_STAGING_JSONL)}")
            appendLine("touch ${shellQuote(LATE_ACK_STAGING_JSONL)}")
            appendLine("mkdir -p $CLAUDE_PROJECT_DIR")
            appendLine(
                "cp /home/testuser/.claude/projects/-workspace-pocketshell/" +
                    "pocketshell-claude.jsonl $TRANSCRIPT_PATH",
            )
            appendLine("touch $TRANSCRIPT_PATH")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 40 " +
                    "-c /home/testuser " +
                    shellQuote(
                        "POCKETSHELL_FAKE_AGENT_SUBMIT_LEDGER=$FAKE_AGENT_LEDGER_PATH " +
                            "POCKETSHELL_FAKE_AGENT_TRANSCRIPT=$fakeAgentTranscript " +
                            renderModeEnv +
                            heartbeatEnv +
                            "exec /usr/local/bin/pocketshell-fake-agent",
                    ),
            )
            appendLine("tmux set-option -t ${shellQuote(SESSION_NAME)} @ps_agent_kind claude")
            appendLine(
                "tmux show-options -v -t ${shellQuote(SESSION_NAME)} " +
                    "@ps_agent_kind | grep -qx claude",
            )
            appendLine("test -s $TRANSCRIPT_PATH")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} -x 80 -y 40 " +
                    shellQuote("printf '$SESSION_B_MARKER\\n'; exec sh"),
            )
            appendLine("tmux set-option -t ${shellQuote(SESSION_B)} @ps_agent_kind shell")
            appendLine("sleep 1")
            appendLine(
                "pid=\$(tmux display-message -p -t ${shellQuote(SESSION_NAME)} '#{pane_pid}'); " +
                    "tr '\\0' '\\n' < /proc/\$pid/environ | grep -Fx " +
                    shellQuote("POCKETSHELL_FAKE_AGENT_TRANSCRIPT=$fakeAgentTranscript"),
            )
            if (renderModeEnv.isNotEmpty()) {
                // #2056: fail the SEED, not a later assertion, if the induced-ambiguity
                // render mode did not actually reach the fake agent's environment.
                appendLine(
                    "tr '\\0' '\\n' < /proc/\$pid/environ | grep -Fx " +
                        shellQuote(
                            "POCKETSHELL_FAKE_AGENT_RENDER_MODE=" +
                                Issue2056InducedSubmitAmbiguity.FRAMED_INPUT_RENDER_MODE,
                        ),
                )
            }
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue1526 fake-agent tmux seed session",
        )
        assertTrue("seed failed ${result.exitCode}: ${result.stderr}", result.exitCode == 0)
    }

    suspend fun enableIssue1944FramedFakeAgent(fixtureKey: String) {
        val command =
            "tmux respawn-pane -k -t ${shellQuote("=$SESSION_NAME:0.0")} " +
                shellQuote(
                    "POCKETSHELL_FAKE_AGENT_SUBMIT_LEDGER=$FAKE_AGENT_LEDGER_PATH " +
                        "POCKETSHELL_FAKE_AGENT_TRANSCRIPT=$TRANSCRIPT_PATH " +
                        "POCKETSHELL_FAKE_AGENT_RENDER_MODE=" +
                        Issue2056InducedSubmitAmbiguity.FRAMED_INPUT_RENDER_MODE + " " +
                        "exec /usr/local/bin/pocketshell-fake-agent",
                )
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(fixtureKey),
            command = command,
            description = "issue1944 framed fake-agent input surface",
        )
        assertEquals("framed fake-agent respawn must succeed: ${result.stderr}", 0, result.exitCode)
    }

    suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec(
                        "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true; " +
                            "tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true; " +
                            // #2056: never leave the #2042 transcript relay running for
                            // the next test in this class (see the relay's own comment).
                            killStrayTranscriptRelay() + " " +
                            "rm -f $TRANSCRIPT_PATH " +
                            "${shellQuote(FAKE_AGENT_LEDGER_PATH)} " +
                            "${shellQuote(LATE_ACK_STAGING_JSONL)} " +
                            "${shellQuote(TRANSCRIPT_RELAY_PID_PATH)} " +
                            "2>/dev/null || true",
                    )
                }
            }
        }
    }

    /** The #2042 transcript relay is a background process; kill it by its recorded pid. */
    private fun killStrayTranscriptRelay(): String =
        "if [ -f ${shellQuote(TRANSCRIPT_RELAY_PID_PATH)} ]; then " +
            "kill \"\$(cat ${shellQuote(TRANSCRIPT_RELAY_PID_PATH)})\" 2>/dev/null || true; fi;"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
