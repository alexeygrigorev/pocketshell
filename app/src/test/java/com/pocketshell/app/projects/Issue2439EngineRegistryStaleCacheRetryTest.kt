package com.pocketshell.app.projects

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Secondary hardening discovered while investigating issue #2439 — "New
 * Session picker drops Codex despite host reporting it enabled+available".
 *
 * ## This is NOT the fix for the maintainer's reported symptom
 *
 * A round-1 implementer pass on #2439 hypothesized a silent stale-cache
 * fallback (the same class as #2377) as the root cause and shipped the retry
 * this file tests. The reviewer then rebuilt that fix, installed it fresh
 * (cold cache) on the real host from the bug report, and reproduced the
 * SAME symptom (Codex missing) — proving the stale-cache theory was NOT the
 * mechanism live on the maintainer's box. The actual root cause is issue
 * #2276's class: `codex` is installed via nvm at
 * `~/.nvm/versions/node/<version>/bin`, which a non-interactive SSH `exec`
 * (exactly what [SshEnginesGateway] always uses) never sees on `PATH` — the
 * host CLI's own probe correctly (from its own PATH's perspective) reports
 * `codex.available: false`. That is fixed host-side, in
 * `tools/pocketshell/src/pocketshell/engines.py`'s `resolve_harnesses`
 * (PR #2441 / issue #2276: exec `PATH` -> login-shell `PATH` -> known
 * absolute install locations), NOT in this Kotlin gateway. See the #2439
 * issue thread for the full investigation and the real-host + Docker-fixture
 * red/green evidence for the #2276 fix.
 *
 * ## What this file DOES cover — a real, independent defect
 *
 * The app-side projection ([SshEnginesGateway.parseEnginesDocument] ->
 * [availableEnginesForCreate]) was already correct for the reported host
 * payload in isolation ([exactHostPayloadProjectsAllFourEligibleEngines]
 * below is GREEN even on the pre-retry code — it is not a parsing bug and
 * not a family-keyed collapse: `codex` and `zcodex` are kept as two distinct
 * rows throughout, because everything keys on the open [RemoteEngine.id],
 * never on [RemoteEngine.family]).
 *
 * Independently of the #2276 mechanism, [SshEnginesGateway.listEngines]
 * caches the last successful registry per host in `cacheByHost` and, on ANY
 * non-success result (timeout / malformed / tool-missing / connect-failed),
 * silently re-served that cache with no retry and no signal that the row it
 * is about to render might be outdated (`EnginesResult.Engines.fromCache`
 * was tracked but never even read by any caller before this fix) — a real
 * gap, corroborated by a timing coincidence on the maintainer's host (the
 * `pocketshell` CLI symlink on `hetzner` was rewritten by a live `uv tool`
 * upgrade four minutes before one report), even though it was ruled out as
 * THE trigger for the reported Codex-missing screenshot.
 * [staleCacheIsNotSilentlyServedAfterOneTransientFailure] reproduces that
 * separate scenario — RED on the pre-fix single-attempt gateway (a stale
 * cached snapshot is served, unchanged, forever) and GREEN once one bounded
 * retry is attempted before falling back to a cache that exists. Kept as
 * independent hardening for a genuinely transient exec blip racing a live
 * host-side deploy, not as a claim that it closes #2439.
 */
class Issue2439EngineRegistryStaleCacheRetryTest {

    /**
     * AC #1 — literal reproduction of the exact host registry payload
     * captured in the issue (`pocketshell engines list --json` on
     * `hetzner`, 5 engines, `codex` enabled+available+available_for_create,
     * `opencode` genuinely unavailable). Field-for-field faithful to the
     * captured values (ids, families, labels, enabled/available/
     * available_for_create/unavailable_reason); the large per-engine
     * `env.unset` provider-key-stripping arrays are trimmed since they are
     * not load-bearing for the picker projection under test.
     */
    private val exactHostPayload = """
        {
          "engines": [
            {"id":"claude","family":"claude","harness":"claude","label":"Claude",
             "provider_mark":"Anthropic","usage_provider":"claude",
             "enabled":true,"available":true,"available_for_create":true,
             "unavailable_reason":null,
             "launch":{"argv":["claude"],"skip_permissions_argv":["--dangerously-skip-permissions"],
                       "supports_skip_permissions":true,"env":{"set":{},"unset":[]},
                       "profile_env":"CLAUDE_CONFIG_DIR"}},
            {"id":"codex","family":"codex","harness":"codex","label":"Codex",
             "provider_mark":"OpenAI","usage_provider":"codex",
             "enabled":true,"available":true,"available_for_create":true,
             "unavailable_reason":null,
             "launch":{"argv":["codex","-c","check_for_update_on_startup=false"],
                       "skip_permissions_argv":["--dangerously-bypass-approvals-and-sandbox"],
                       "supports_skip_permissions":true,"env":{"set":{},"unset":[]},
                       "profile_env":"CODEX_HOME"}},
            {"id":"opencode","family":"opencode","harness":"opencode","label":"OpenCode",
             "provider_mark":"OpenCode","usage_provider":"go",
             "enabled":true,"available":false,"available_for_create":false,
             "unavailable_reason":"`opencode` is not installed on this host (not on PATH).",
             "launch":{"argv":["opencode"],"skip_permissions_argv":[],
                       "supports_skip_permissions":false,"env":{"set":{},"unset":[]},
                       "profile_env":null}},
            {"id":"grok","family":"grok","harness":"grok","label":"Grok",
             "provider_mark":"xAI","usage_provider":"grok",
             "enabled":true,"available":true,"available_for_create":true,
             "unavailable_reason":null,
             "launch":{"argv":["grok"],"skip_permissions_argv":["--always-approve"],
                       "supports_skip_permissions":true,"env":{"set":{},"unset":[]},
                       "profile_env":"GROK_HOME"}},
            {"id":"zcodex","family":"codex","harness":"zcodex","label":"ZCodex",
             "provider_mark":"Z.AI","usage_provider":"zai",
             "enabled":true,"available":true,"available_for_create":true,
             "unavailable_reason":null,
             "launch":{"argv":["zcodex"],"skip_permissions_argv":["--dangerously-bypass-approvals-and-sandbox"],
                       "supports_skip_permissions":true,"env":{"set":{},"unset":[]},
                       "profile_env":"CODEX_HOME"}}
          ]
        }
    """.trimIndent()

    /** The pre-upgrade registry the maintainer's app had cached: no Codex row at all. */
    private val staleThreeEnginePayload = """
        {"engines":[
          {"id":"claude","family":"claude","label":"Claude","enabled":true,"available":true,"available_for_create":true},
          {"id":"grok","family":"grok","label":"Grok","enabled":true,"available":true,"available_for_create":true},
          {"id":"zcodex","family":"codex","label":"ZCodex","enabled":true,"available":true,"available_for_create":true}
        ]}
    """.trimIndent()

    @Test
    fun exactHostPayloadProjectsAllFourEligibleEngines() {
        val rows = SshEnginesGateway.parseEnginesPayload(exactHostPayload)
        assertEquals(listOf("claude", "codex", "opencode", "grok", "zcodex"), rows.map { it.id })

        val chips = availableEnginesForCreate(rows)
        assertEquals(
            "codex must render as a chip alongside claude/grok/zcodex, not be dropped",
            listOf("claude", "codex", "grok", "zcodex"),
            chips.map { it.id },
        )
        assertFalse("opencode is genuinely unavailable and must stay hidden", chips.any { it.id == "opencode" })
    }

    /**
     * The reproduction: an earlier successful read cached the PRE-upgrade
     * 3-engine registry (matches the maintainer's screenshot exactly — Claude,
     * Grok, ZCodex, no Codex). The very next read — the one the picker fires
     * on open, racing the live `pocketshell` upgrade — fails once. Pre-fix,
     * that single failure falls straight back to the stale cache and silently
     * renders 3 chips forever. Post-fix, one bounded retry recovers the FULL,
     * current 5-engine registry within the same picker-open call.
     */
    @Test
    fun staleCacheIsNotSilentlyServedAfterOneTransientFailure() = runBlocking {
        var calls = 0
        val session = FakeSshSession {
            calls += 1
            when (calls) {
                1 -> ExecResult(stdout = staleThreeEnginePayload, stderr = "", exitCode = 0)
                2 -> ExecResult(stdout = "", stderr = "", exitCode = 1) // the mid-upgrade blip
                else -> ExecResult(stdout = exactHostPayload, stderr = "", exitCode = 0)
            }
        }
        val gateway = SshEnginesGateway(
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(session),
                idleTtlMillis = 30_000L,
            ),
        )

        val staleRead = gateway.listEngines(HOST, KEY_PATH, passphrase = null)
        assertTrue(staleRead is EnginesResult.Engines)
        assertEquals(
            listOf("claude", "grok", "zcodex"),
            (staleRead as EnginesResult.Engines).engines.map { it.id },
        )

        val pickerOpenRead = gateway.listEngines(HOST, KEY_PATH, passphrase = null)

        assertTrue(
            "a transient failure with a good cache on file must retry, not silently serve stale data",
            pickerOpenRead is EnginesResult.Engines,
        )
        val result = pickerOpenRead as EnginesResult.Engines
        assertFalse("the recovered read is fresh, not the pre-upgrade cache", result.fromCache)
        assertEquals(
            "the picker must show the CURRENT registry (codex included), not the pre-upgrade snapshot",
            listOf("claude", "codex", "opencode", "grok", "zcodex"),
            result.engines.map { it.id },
        )
        assertEquals(
            listOf("claude", "codex", "grok", "zcodex"),
            availableEnginesForCreate(result.engines).map { it.id },
        )
    }

    /**
     * Two consecutive failures must still fall back to the last good cache
     * (never surface nothing / crash the picker) — the retry is bounded to
     * one attempt, not an unbounded loop.
     */
    @Test
    fun repeatedFailuresStillFallBackToLastGoodCacheAfterOneRetry() = runBlocking {
        var calls = 0
        val session = FakeSshSession {
            calls += 1
            if (calls == 1) {
                ExecResult(stdout = staleThreeEnginePayload, stderr = "", exitCode = 0)
            } else {
                ExecResult(stdout = "", stderr = "", exitCode = 1)
            }
        }
        val gateway = SshEnginesGateway(
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(session),
                idleTtlMillis = 30_000L,
            ),
        )

        gateway.listEngines(HOST, KEY_PATH, passphrase = null)
        val result = gateway.listEngines(HOST, KEY_PATH, passphrase = null)

        assertEquals("exactly one retry: call #1 (good), #2 (fail), #3 (retry, fail)", 3, calls)
        assertTrue(result is EnginesResult.Engines)
        assertTrue("bounded retry still exhausts to the stale cache, not an error", (result as EnginesResult.Engines).fromCache)
        assertEquals(listOf("claude", "grok", "zcodex"), result.engines.map { it.id })
    }

    private class CountingConnector(private val session: FakeSshSession) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> = Result.success(session)
    }

    private class FakeSshSession(
        private val resultForCommand: suspend (String) -> ExecResult,
    ) : SshSession {
        override val isConnected: Boolean get() = true
        override suspend fun exec(command: String): ExecResult = resultForCommand(command)
        override fun tail(path: String, onLine: (String) -> Unit) = error("not used")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }

    private companion object {
        const val KEY_PATH = "/tmp/pocketshell-issue-2439-key"
        val HOST = HostEntity(
            id = 2439L,
            name = "hetzner",
            hostname = "10.0.2439.1",
            username = "alexey",
            keyId = 1L,
        )
    }
}
