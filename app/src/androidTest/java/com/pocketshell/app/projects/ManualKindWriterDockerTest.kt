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
 * Epic #821 Slice 1 connected test — manual session classification end-to-end
 * against the deterministic Docker `agents` fixture (host port `2222`, already
 * wired into the CI emulator job). Proves the maintainer's Option B + change-
 * existing decision and the D31 durability AC:
 *
 *  1. A FOREIGN tmux session (no `@ps_agent_kind`) reads back with
 *     `recordedKind == null` — the "Unknown / surface the picker" signal, NOT
 *     a guess, NOT a crash.
 *  2. Picking a kind ([SshFolderListGateway.setRecordedKind] →
 *     `ManualKindWriter`) writes the host-side option and the session then
 *     reads back as THAT recorded kind.
 *  3. "Change kind" on an already-classified session rewrites it and it reads
 *     back changed.
 *  4. The recorded kind is DURABLE across a reconnect: a brand-new SSH session
 *     (the reconnect analogue — tmux session options live for the life of the
 *     session) still reads the set kind.
 *
 * Mirrors the structure of [FolderListGatewayAgentKindDockerTest] (same fixture
 * + key bootstrap), but exercises the WRITE/read-back path rather than
 * detection.
 */
@RunWith(AndroidJUnit4::class)
class ManualKindWriterDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val cleanupCommands = mutableListOf<String>()

    @After
    fun tearDown(): Unit = runBlocking {
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    withSshSession { session ->
                        session.exec(cleanupCommands.joinToString("\n"))
                    }
                }
            }
        }
        runCatching { keyFile.delete() }
    }

    @Test
    fun foreignSessionStartsUnknownThenManualPickAndChangeAreDurable(): Unit = runBlocking {
        bootstrapKey()
        waitForSshFixtureReady(sshKey)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val session = "issue821-manual-$suffix"
        val folder = "/tmp/issue821-manual-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(session)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(folder)} 2>/dev/null || true"

        // A plain foreign session — created by hand (not the `pocketshell agent`
        // wrapper), so it carries NO `@ps_agent_kind`.
        withSshSession { s ->
            val setup = buildString {
                append("set -eu; ")
                append("tmux kill-session -t ${shellQuote(session)} 2>/dev/null || true; ")
                append("mkdir -p ${shellQuote(folder)}; ")
                append(
                    "tmux new-session -d -s ${shellQuote(session)} " +
                        "-c ${shellQuote(folder)} ${shellQuote("sleep 600")}; ",
                )
                // Confirm the option is absent on a fresh foreign session.
                append("tmux show-options -v -t ${shellQuote(session)} @ps_agent_kind 2>/dev/null || true")
            }
            val r = s.exec(setup)
            assertEquals("setup failed: stderr='${r.stderr}' stdout='${r.stdout}'", 0, r.exitCode)
            assertTrue(
                "a fresh foreign session must have NO recorded @ps_agent_kind; got '${r.stdout}'",
                r.stdout.isBlank(),
            )
        }

        val gateway = SshFolderListGateway()
        val host = HostEntity(
            id = 1L,
            name = "issue821-manual",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = 1L,
        )

        // (1) Foreign session reads back with NO recorded kind — the Unknown
        // signal the picker keys on.
        assertNull(
            "a foreign session must read back recordedKind=null (Unknown), not a guess",
            readRecordedKind(gateway, host, session),
        )

        // (2) Pick Codex -> write -> reads back Codex.
        withTimeout(20_000) {
            gateway.setRecordedKind(host, keyFile.absolutePath, null, session, SessionAgentKind.Codex)
        }.getOrThrow()
        assertEquals(
            "after picking Codex the session must read back as recorded Codex",
            SessionAgentKind.Codex,
            readRecordedKind(gateway, host, session),
        )

        // (4a) Durable across a reconnect — a brand-new SSH session reads it.
        withSshSession { s ->
            val raw = s.exec(
                "tmux show-options -v -t ${shellQuote(session)} @ps_agent_kind 2>/dev/null || true",
            ).stdout.trim()
            assertEquals(
                "the recorded kind must persist host-side (survives reconnect/app restart)",
                "codex",
                raw,
            )
        }

        // (3) Change kind on an already-classified session -> OpenCode.
        withTimeout(20_000) {
            gateway.setRecordedKind(host, keyFile.absolutePath, null, session, SessionAgentKind.OpenCode)
        }.getOrThrow()
        assertEquals(
            "change-kind must rewrite the recorded kind to OpenCode",
            SessionAgentKind.OpenCode,
            readRecordedKind(gateway, host, session),
        )

        // (4b) Manually classify as a plain Shell -> reads back recorded Shell
        // (not null → it has a recorded kind now and won't re-prompt Unknown).
        withTimeout(20_000) {
            gateway.setRecordedKind(host, keyFile.absolutePath, null, session, SessionAgentKind.Shell)
        }.getOrThrow()
        assertEquals(
            "classifying as Shell must read back as a RECORDED Shell, not Unknown",
            SessionAgentKind.Shell,
            readRecordedKind(gateway, host, session),
        )
        withSshSession { s ->
            val raw = s.exec(
                "tmux show-options -v -t ${shellQuote(session)} @ps_agent_kind 2>/dev/null || true",
            ).stdout.trim()
            assertEquals("manual shell classification must persist host-side", "shell", raw)
        }
    }

    // ----------------------------------------------------------- Helpers

    /**
     * Read the recorded kind for [session] through the production gateway
     * enumeration path ([SshFolderListGateway.listSessionsWithFolder] →
     * `FolderSessionRow.recordedKind`) — the same authoritative read-back the
     * tree uses.
     */
    private suspend fun readRecordedKind(
        gateway: SshFolderListGateway,
        host: HostEntity,
        session: String,
    ): SessionAgentKind? {
        val result = withTimeout(30_000) {
            gateway.listSessionsWithFolder(host, keyFile.absolutePath, null)
        }
        assertTrue("expected Sessions result, got $result", result is FolderListResult.Sessions)
        val row = (result as FolderListResult.Sessions).rows
            .firstOrNull { it.sessionName == session }
            ?: error("gateway did not return session '$session'; rows=${result.rows}")
        return row.recordedKind
    }

    private fun bootstrapKey() {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue821-manual-key").apply {
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
