package com.pocketshell.app.projects

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Epic #821 slice C (issue #837): unit tests for [TreeRemoteSource], the client
 * seam over `pocketshell tree get|upsert|reconcile`. Mirrors
 * `AgentKindRemoteSource` / `PocketshellJobsRemoteSource`: exercises the real
 * JSON request build + envelope parse over a fake [SshSession], and the
 * degrade-gracefully contract (a tool-missing / parse failure never throws
 * except cancellation).
 */
class TreeRemoteSourceTest {

    private val source = TreeRemoteSource()

    @Test
    fun getTree_parsesNodesSortedByOrder() = runTest {
        val json = JSONObject()
            .put(
                "nodes",
                org.json.JSONArray()
                    .put(node("b", order = 1, folder = "/p/b", collapsed = false))
                    .put(node("a", order = 0, folder = "/p/a", collapsed = true, foreign = "codex")),
            )
            .put("version", 3)
            .put("cli_version", "0.4.12")
            .toString()
        val session = treeSession(getStdout = json)

        val result = source.getTree(session, host = "hetzner")
        val nodes = result.nodes

        // Sorted by persisted order, not arrival order.
        assertEquals(listOf("a", "b"), nodes.map { it.session })
        assertEquals(0, nodes[0].order)
        assertTrue(nodes[0].collapsed)
        assertEquals("/p/a", nodes[0].folderPath)
        assertEquals("codex", nodes[0].foreignKind)
        assertNull(nodes[1].foreignKind)
        // Issue #885: the payload's `cli_version` is parsed for the passive
        // version-mismatch check.
        assertEquals("0.4.12", result.cliVersion)
        // The request carried the host and was piped to `pocketshell tree get`.
        val sent = session.recorded.single()
        assertTrue(sent, sent.contains("tree get"))
        assertTrue(sent, sent.contains("hetzner"))
        // Issue #847: the pipe MUST be grouped (`| { … ; }`) so it reaches the
        // real `pocketshell tree get` inside wrap()'s multi-statement sequence,
        // not just the leading `export PATH=…`. The non-grouped form starved the
        // cold-start hydrate CLI of stdin → it blocked on `read(stdin)` forever
        // and the tree never loaded (the v0.4.10 connect hang). The real-shell
        // proof is AgentKindRemoteSourceRealShellPipeTest; this string check is
        // the fast guard that the tree path keeps the grouping.
        assertTrue("tree get pipe must be grouped: $sent", sent.contains("| { "))
        assertTrue("tree get group must be closed: $sent", sent.contains(" ; }"))
    }

    @Test
    fun getTree_emptyRegistryYieldsEmptyList() = runTest {
        val session = treeSession(getStdout = """{"nodes":[],"version":0,"cli_version":"0.4.12"}""")
        val result = source.getTree(session, host = "h")
        assertTrue(result.nodes.isEmpty())
        // Issue #885: an empty registry still carries the CLI version (so the
        // passive check works even on a fresh host with no persisted tree).
        assertEquals("0.4.12", result.cliVersion)
    }

    @Test
    fun getTree_omittedCliVersionIsNullNoSignal() = runTest {
        // Issue #885: an OLD CLI that predates the `cli_version` field omits it.
        // The client treats that as "no signal" (null), never a false mismatch.
        val session = treeSession(getStdout = """{"nodes":[],"version":0}""")
        assertNull(source.getTree(session, host = "h").cliVersion)
    }

    @Test
    fun getTree_nonZeroExitDegradesToEmpty() = runTest {
        val session = treeSession(getResult = ExecResult("", "boom", 127))
        val result = source.getTree(session, host = "h")
        assertTrue(result.nodes.isEmpty())
        assertNull(result.cliVersion)
    }

    @Test
    fun getTree_garbageStdoutDegradesToEmpty() = runTest {
        val session = treeSession(getStdout = "not json at all")
        val result = source.getTree(session, host = "h")
        assertTrue(result.nodes.isEmpty())
        assertNull(result.cliVersion)
    }

    @Test
    fun upsertTree_buildsRequestAndReturnsTrueOnOk() = runTest {
        val session = treeSession(upsertStdout = """{"status":"ok","version":4}""")

        val ok = source.upsertTree(
            session,
            host = "hetzner",
            nodes = listOf(
                TreeRemoteSource.TreeNode("a", order = 0, folderPath = "/p/a", collapsed = true),
                TreeRemoteSource.TreeNode("b", order = 1, folderPath = "/p/b", collapsed = false, foreignKind = "claude"),
            ),
        )

        assertTrue(ok)
        val sent = session.recorded.single()
        assertTrue(sent, sent.contains("tree upsert"))
        // The request JSON carries the nodes, collapse state, and the
        // foreign-guess hint — but NO confirmed-kind field.
        assertTrue(sent, sent.contains("\"session\":\"a\""))
        assertTrue(sent, sent.contains("\"collapsed\":true"))
        assertTrue(sent, sent.contains("\"foreign_kind\":\"claude\""))
        assertFalse("must not write a confirmed kind copy", sent.contains("\"kind\""))
    }

    @Test
    fun upsertTree_nonZeroExitReturnsFalse() = runTest {
        val session = treeSession(upsertResult = ExecResult("", "no", 1))
        assertFalse(source.upsertTree(session, host = "h", nodes = emptyList()))
    }

    @Test
    fun reconcileTree_parsesDeltas() = runTest {
        val session = treeSession(
            reconcileStdout =
                """{"alive":["a"],"gone":["dead"],"added":["fresh"],"cli_version":"0.4.12"}""",
        )

        val delta = source.reconcileTree(session, host = "h")

        assertEquals(listOf("a"), delta!!.alive)
        assertEquals(listOf("dead"), delta.gone)
        assertEquals(listOf("fresh"), delta.added)
        // Issue #885: the reconcile payload also carries the CLI version.
        assertEquals("0.4.12", delta.cliVersion)
        assertTrue(session.recorded.single().contains("tree reconcile"))
    }

    @Test
    fun reconcileTree_omittedCliVersionIsNull() = runTest {
        // Old CLI omits cli_version → null (no signal).
        val session = treeSession(
            reconcileStdout = """{"alive":["a"],"gone":[],"added":[]}""",
        )
        assertNull(source.reconcileTree(session, host = "h")!!.cliVersion)
    }

    @Test
    fun reconcileTree_nonZeroExitYieldsNull() = runTest {
        val session = treeSession(reconcileResult = ExecResult("", "", 127))
        assertNull(source.reconcileTree(session, host = "h"))
    }

    @Test
    fun reconcileTree_garbageYieldsNull() = runTest {
        val session = treeSession(reconcileStdout = "<<<")
        assertNull(source.reconcileTree(session, host = "h"))
    }

    @Test
    fun cancellationPropagates() = runTest {
        val session = ThrowingSshSession(CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { source.getTree(session, host = "h") }
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun node(
        name: String,
        order: Int,
        folder: String,
        collapsed: Boolean,
        foreign: String? = null,
    ): JSONObject = JSONObject()
        .put("session", name)
        .put("order", order)
        .put("folder_path", folder)
        .put("collapsed", collapsed)
        .apply { if (foreign != null) put("foreign_kind", foreign) }

    private fun treeSession(
        getStdout: String = "",
        getResult: ExecResult? = null,
        upsertStdout: String = "",
        upsertResult: ExecResult? = null,
        reconcileStdout: String = "",
        reconcileResult: ExecResult? = null,
    ): RoutingSshSession = RoutingSshSession(
        getResult = getResult ?: ExecResult(getStdout, "", 0),
        upsertResult = upsertResult ?: ExecResult(upsertStdout, "", 0),
        reconcileResult = reconcileResult ?: ExecResult(reconcileStdout, "", 0),
    )

    /** Routes exec by which `tree <verb>` appears in the wrapped command. */
    private class RoutingSshSession(
        private val getResult: ExecResult,
        private val upsertResult: ExecResult,
        private val reconcileResult: ExecResult,
    ) : SshSession {
        val recorded = mutableListOf<String>()
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            return when {
                command.contains("tree get") -> getResult
                command.contains("tree upsert") -> upsertResult
                command.contains("tree reconcile") -> reconcileResult
                else -> ExecResult("", "no route for $command", 127)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() = Unit
    }

    private class ThrowingSshSession(private val throwable: Throwable) : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = throw throwable
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() = Unit
    }
}
