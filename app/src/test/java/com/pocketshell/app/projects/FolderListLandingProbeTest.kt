package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderListLandingProbeTest {
    private val owner = FolderListLandingProbeOwner(ReposRemoteSource(ReposJsonParser()))

    @Test
    fun requiredEnumerationUsesOneSectionedExec() = runBlocking {
        val commands = mutableListOf<String>()
        val marker = SshFolderListGateway.ENUMERATION_MARKER
        val probe = owner.execute(
            watchedRoots = emptyList(),
            includeEnumeration = true,
            exec = { command ->
                commands += command
                ExecResult(
                    stdout = "sessions\n$marker 0\npanes\n$marker 0\n",
                    stderr = "",
                    exitCode = 0,
                )
            },
        )

        assertEquals(1, commands.size)
        assertTrue(commands.single().contains(SshFolderListGateway.LIST_SESSIONS_COMMAND))
        assertTrue(commands.single().contains(SshFolderListGateway.LIST_PANES_COMMAND))
        assertEquals("sessions", probe.listSessions.stdout)
        assertEquals("panes", probe.listPanes.stdout)
    }

    @Test
    fun slowOptionalDecorationDegradesWithoutFailingRequiredTree() = runBlocking {
        val commands = mutableListOf<String>()
        val marker = SshFolderListGateway.ENUMERATION_MARKER
        val roots = listOf(root("~/git"))
        val probe = owner.execute(
            watchedRoots = roots,
            includeEnumeration = false,
            exec = { command ->
                commands += command
                if (command.contains(SshFolderListGateway.POCKETSHELL_PROJECT_HISTORY_COMMAND)) {
                    throw FolderListExecTimeoutException(command, 3_500L)
                }
                ExecResult(
                    stdout = "/home/testuser\n$marker 0\n",
                    stderr = "",
                    exitCode = 0,
                )
            },
        )

        assertEquals(2, commands.size)
        assertTrue(
            "the required home batch must finish before optional decoration starts",
            commands[0].contains("printf '%s\\n'") &&
                !commands[0].contains(SshFolderListGateway.POCKETSHELL_PROJECT_HISTORY_COMMAND),
        )
        assertTrue(
            "the slow optional batch must run only after the required batch",
            commands[1].contains(SshFolderListGateway.POCKETSHELL_PROJECT_HISTORY_COMMAND),
        )
        assertEquals("/home/testuser", probe.remoteHome)
        assertTrue(probe.projectHistory.isEmpty())
        assertTrue(probe.rootPayloads.isEmpty())

        val expansion = owner.buildWatchedRootExpansion(HOST, roots, probe)
        assertEquals("/home/testuser/git", expansion.resolvedWatchedRootPaths["~/git"])
        assertEquals(emptyList<String>(), expansion.projectFoldersByRoot["~/git"])
    }

    @Test
    fun everyWatchedRootSharesOneOptionalExecChannel() = runBlocking {
        val commands = mutableListOf<String>()
        val marker = SshFolderListGateway.ENUMERATION_MARKER
        owner.execute(
            watchedRoots = listOf(root("~/a"), root("~/b"), root("/srv/c")),
            includeEnumeration = true,
            exec = { command ->
                commands += command
                val sectionCount = command.windowed(marker.length)
                    .count { it == marker }
                ExecResult(
                    stdout = buildString {
                        repeat(sectionCount) { append('\n').append(marker).append(" 0\n") }
                    },
                    stderr = "",
                    exitCode = 0,
                )
            },
        )

        assertEquals("required + optional batches only", 2, commands.size)
        val optional = commands.single {
            it.contains(SshFolderListGateway.POCKETSHELL_PROJECT_HISTORY_COMMAND)
        }
        assertEquals(
            "all per-root scans must share that one optional exec",
            3,
            Regex("pocketshell repos list --local --json --root").findAll(optional).count(),
        )
    }

    @Test
    fun historyExpansionKeepsTheOriginalImmediateChildPathSemantics() {
        val roots = listOf(root("/home/testuser/git"))
        val probe = FolderListLandingProbe(
            listSessions = ExecResult("", "", 0),
            listPanes = ExecResult("", "", 0),
            remoteHome = null,
            projectHistory = listOf(
                "/home/testuser/git",
                "/home/testuser/git/alpha/deeper",
                "/home/testuser/git/beta",
                "/home/testuser/elsewhere/ignored",
            ),
            rootPayloads = emptyMap(),
        )

        val expansion = owner.buildWatchedRootExpansion(HOST, roots, probe)

        assertEquals(
            listOf(
                "/home/testuser/git",
                "/home/testuser/git/alpha",
                "/home/testuser/git/beta",
            ),
            expansion.historyProjectFoldersByRoot["/home/testuser/git"],
        )
    }

    private fun root(path: String): ProjectRootEntity =
        ProjectRootEntity(
            id = 0L,
            hostId = HOST.id,
            label = path,
            path = path,
        )

    companion object {
        private val HOST = HostEntity(
            id = 7L,
            name = "mobile",
            hostname = "example.test",
            port = 22,
            username = "testuser",
            keyId = 3L,
        )
    }
}
