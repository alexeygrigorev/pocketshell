package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * #889 (reopen) END-TO-END regression — the false z.ai chip on a session
 * relaunched as a DEFAULT Claude. The maintainer's `git-pocketshell` session
 * was launched once under the z.ai profile and then relaunched as a plain
 * Claude, yet the tree kept showing a `ZAI …` chip beside the `Claude` badge.
 *
 * This drives BOTH halves of the fix end-to-end against the deterministic
 * Docker `agents` fixture on host port `2222` (already wired into the CI
 * emulator job) plus the REAL [SshFolderListGateway] read-back AND the REAL
 * [HostTreeModel] reconcile (the same code the session tree uses):
 *
 *  1. HOST clear — the fixture `pocketshell agent` now mirrors the real
 *     wrapper's `record_agent_kind`: a `--profile` launch SETS
 *     `@ps_agent_profile`; a default (no `--profile`) launch UNSETS it. So a
 *     default relaunch in the SAME tmux session clears the option, and the
 *     gateway reads back `recordedProfile == null`.
 *  2. CLIENT reconcile — feeding that cleared read through [HostTreeModel]
 *     DROPS the held chip. The pre-fix sticky merge
 *     (`entry.recordedProfile ?: existing`) swallowed the blank read and the
 *     stale chip survived every reconcile on device — the durable client-side
 *     root cause the prior wrapper-only fix could not reach.
 *
 * The fixture state that REPRODUCES the bug (a session previously launched
 * under z.ai, so `@ps_agent_profile` is set) is established in launch-1 via the
 * real gateway create path with a z.ai profile (#889 G10: add the fixture that
 * reproduces the non-happy state). RED on base: after the default relaunch the
 * tree still reports `recordedProfile == "Claude (Z.AI)"`. GREEN: it is `null`.
 */
@RunWith(AndroidJUnit4::class)
class ProfileChipRelaunchDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val cleanupCommands = mutableListOf<String>()
    private val zaiProfileLabel = "Claude (Z.AI)"

    @After
    fun tearDown(): Unit { runBlocking {
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    withSshSession { s -> s.exec(cleanupCommands.joinToString("\n")) }
                }
            }
        }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun zaiSessionRelaunchedAsDefaultClaudeDropsTheStaleProfileChip(): Unit { runBlocking {
        bootstrapKey()
        waitForSshFixtureReady(sshKey)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val session = "issue889-git-pocketshell-$suffix"
        val cwd = "/tmp/issue889-$suffix"
        cleanupCommands += "tmux kill-session -t '$session' 2>/dev/null || true"
        cleanupCommands += "rm -rf '$cwd' 2>/dev/null || true"
        ensureRemoteDir(cwd)

        val gateway = SshFolderListGateway()
        val host = HostEntity(
            id = 889L,
            name = "issue889-agents",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = 1L,
        )
        val tree = HostTreeModel()
        tree.bindHost(host.id)

        // --- Launch 1: a z.ai-profile Claude (the reproduce fixture state) ----
        val zaiCommand = AgentCli.buildAgentCommand(
            kind = "claude",
            directory = cwd,
            noSkipPermissions = false,
            profileName = zaiProfileLabel,
        )
        withTimeout(30_000) {
            gateway.createSession(host, keyFile.absolutePath, null, session, cwd, zaiCommand).getOrThrow()
        }
        // The fake claude prints + exits; the option write happens before exec,
        // so poll the host option directly until the wrapper has recorded it.
        awaitProfileOption(session, expected = zaiProfileLabel)

        // Gateway read-back carries the z.ai profile; the reconciled tree shows
        // the chip — the on-device "ZAI Claude" double-label state.
        reconcileFromGateway(gateway, host, tree)
        assertEquals(
            "after a z.ai launch the gateway must read back the profile and the " +
                "tree must show the chip",
            zaiProfileLabel,
            tree.sessionEntries().first { it.sessionName == session }.recordedProfile,
        )
        assertEquals(
            SessionAgentKind.Claude,
            tree.sessionEntries().first { it.sessionName == session }.agentKind,
        )

        // --- Launch 2: relaunch the SAME session as a DEFAULT Claude ----------
        // No --profile, so the wrapper UNSETS @ps_agent_profile. send-keys it
        // into the same pane (the fake claude already exited back to the shell).
        val defaultCommand = AgentCli.buildAgentCommand(
            kind = "claude",
            directory = cwd,
            noSkipPermissions = false,
            profileName = null,
        )
        withSshSession { s ->
            s.exec("tmux send-keys -t '$session' '${defaultCommand.replace("'", "'\\''")}' Enter")
        }
        // Wait until the host actually cleared the option (default relaunch).
        awaitProfileOption(session, expected = "")

        // Gateway read-back is now blank, and the AUTHORITATIVE reconcile (real
        // claude kind, no profile) must DROP the stale chip — not keep it sticky.
        reconcileFromGateway(gateway, host, tree)
        val finalEntry = tree.sessionEntries().first { it.sessionName == session }
        assertEquals(
            "the relaunched session is still a Claude agent",
            SessionAgentKind.Claude,
            finalEntry.agentKind,
        )
        assertNull(
            "a default Claude relaunch in the same tmux session must DROP the " +
                "stale z.ai chip (issue #889 false ZAI label); recordedProfile " +
                "was '${finalEntry.recordedProfile}'",
            finalEntry.recordedProfile,
        )
    } }

    // ----------------------------------------------------------- Helpers

    /** Reconcile the maintained [tree] from a fresh gateway session read. */
    private suspend fun reconcileFromGateway(
        gateway: SshFolderListGateway,
        host: HostEntity,
        tree: HostTreeModel,
    ) {
        val result = withTimeout(30_000) {
            gateway.listSessionsWithFolder(host, keyFile.absolutePath, null)
        }
        assertTrue("expected Sessions result, got $result", result is FolderListResult.Sessions)
        val rows = (result as FolderListResult.Sessions).rows
        val entries = rows.map { row ->
            FolderSessionEntry(
                sessionName = row.sessionName,
                lastActivity = row.lastActivity,
                attached = row.attached,
                agentKind = row.agentKind,
                recordedProfile = row.recordedProfile,
            )
        }
        tree.reconcile(
            HostTreeModel.ProbeSnapshot(
                sessions = entries,
                folderPaths = entries.associate { it.sessionName to (rows.first { r -> r.sessionName == it.sessionName }.cwd ?: FolderListViewModel.UNTRACKED_PATH) },
                scannedProjectFoldersByRoot = emptyMap(),
                historyProjectFoldersByRoot = emptyMap(),
                resolvedWatchedRootPaths = emptyMap(),
            ),
            now = System.currentTimeMillis(),
        )
    }

    /**
     * Poll the host-side `@ps_agent_profile` option for [session] until it
     * equals [expected] (empty string = unset). The fake agent exits fast but
     * the wrapper write/clear races the send-keys, so we await it deterministically.
     */
    private suspend fun awaitProfileOption(session: String, expected: String) {
        withTimeout(20_000) {
            withSshSession { s ->
                var last = "<unread>"
                repeat(40) {
                    val raw = s.exec(
                        "tmux show-options -v -t '$session' @ps_agent_profile 2>/dev/null || true",
                    ).stdout.trim()
                    last = raw
                    if (raw == expected) return@withSshSession
                    Thread.sleep(250)
                }
                throw AssertionError(
                    "@ps_agent_profile for '$session' never became '$expected' (last='$last')",
                )
            }
        }
    }

    private suspend fun ensureRemoteDir(path: String) {
        withTimeout(15_000) { withSshSession { it.exec("mkdir -p '$path'") } }
    }

    private fun bootstrapKey() {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue889-relaunch-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
    }

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }
}
