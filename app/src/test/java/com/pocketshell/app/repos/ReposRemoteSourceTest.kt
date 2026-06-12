package com.pocketshell.app.repos

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReposRemoteSourceTest {
    private val source = ReposRemoteSource(ReposJsonParser())

    @Test
    fun listRemote_runsPocketshellCommandAndParsesRepos() = runTest {
        val command = pathAware("pocketshell repos list --remote --json --limit 25")
        val session = FakeSshSession(
            mapOf(
                command to ExecResult(
                    """
                    [
                      {
                        "owner": "alexeygrigorev",
                        "name": "pocketshell",
                        "full_name": "alexeygrigorev/pocketshell",
                        "local": null,
                        "remote": {
                          "default_branch": "main",
                          "html_url": "https://github.com/alexeygrigorev/pocketshell",
                          "ssh_url": "git@github.com:alexeygrigorev/pocketshell.git",
                          "updated_at": "2026-05-27T00:00:00Z"
                        }
                      }
                    ]
                    """.trimIndent(),
                    "",
                    0,
                ),
            ),
        )

        val result = source.listRemote(session, limit = 25)

        assertTrue(result is ReposListResult.Success)
        assertEquals("alexeygrigorev/pocketshell", (result as ReposListResult.Success).repos.single().fullName)
        assertEquals(listOf(command), session.recorded)
    }

    @Test
    fun listLocal_runsLocalCommandAndParsesClonedRepos() = runTest {
        val command = pathAware("pocketshell repos list --local --json")
        val session = FakeSshSession(
            mapOf(
                command to ExecResult(
                    """
                    [
                      {
                        "owner": "alexeygrigorev",
                        "name": "pocketshell",
                        "full_name": "alexeygrigorev/pocketshell",
                        "local": {
                          "path": "/home/alexey/git/pocketshell",
                          "head": "main"
                        },
                        "remote": null
                      }
                    ]
                    """.trimIndent(),
                    "",
                    0,
                ),
            ),
        )

        val result = source.listLocal(session)

        assertTrue(result is ReposListResult.Success)
        val repo = (result as ReposListResult.Success).repos.single()
        assertEquals("alexeygrigorev/pocketshell", repo.fullName)
        assertEquals("/home/alexey/git/pocketshell", repo.local?.path)
        assertEquals(listOf(command), session.recorded)
    }

    @Test
    fun listLocalRoot_runsLocalCommandWithRootAndParsesProjectFolders() = runTest {
        val command = pathAware("pocketshell repos list --local --json --root '/home/alexey/git'")
        val session = FakeSshSession(
            mapOf(
                command to ExecResult(
                    """
                    [
                      {
                        "owner": "alexeygrigorev",
                        "name": "pocketshell",
                        "full_name": "alexeygrigorev/pocketshell",
                        "local": {
                          "path": "/home/alexey/git/pocketshell",
                          "head": "main"
                        },
                        "remote": null
                      },
                      {
                        "owner": null,
                        "name": "scratch",
                        "full_name": null,
                        "local": {
                          "path": "/home/alexey/git/scratch",
                          "head": null
                        },
                        "remote": null
                      }
                    ]
                    """.trimIndent(),
                    "",
                    0,
                ),
            ),
        )

        val result = source.listLocalRoot(
            session = session,
            root = "/home/alexey/git",
            cacheNamespace = "devbox",
        )

        assertTrue(result is ReposListResult.Success)
        val repos = (result as ReposListResult.Success).repos
        assertEquals(
            listOf("/home/alexey/git/pocketshell", "/home/alexey/git/scratch"),
            repos.map { it.local?.path },
        )
        assertEquals(listOf(command), session.recorded)
    }

    @Test
    fun listLocalRoot_shellQuotesWatchedRoot() = runTest {
        val command = pathAware("pocketshell repos list --local --json --root '/tmp/it'\"'\"'s apps'")
        val session = FakeSshSession(
            mapOf(command to ExecResult("[]", "", 0)),
        )

        assertEquals(
            ReposListResult.Success(emptyList()),
            source.listLocalRoot(
                session = session,
                root = "/tmp/it's apps",
                cacheNamespace = "devbox",
            ),
        )
        assertEquals(listOf(command), session.recorded)
    }

    @Test
    fun listLocalRoot_usesCacheForRepeatedRoot() = runTest {
        val command = pathAware("pocketshell repos list --local --json --root '/home/alexey/git'")
        val session = FakeSshSession(
            mapOf(
                command to ExecResult(
                    """
                    [
                      {
                        "owner": "alexeygrigorev",
                        "name": "pocketshell",
                        "full_name": "alexeygrigorev/pocketshell",
                        "local": {"path": "/home/alexey/git/pocketshell", "head": "main"},
                        "remote": null
                      }
                    ]
                    """.trimIndent(),
                    "",
                    0,
                ),
            ),
        )

        val first = source.listLocalRoot(session, "/home/alexey/git", cacheNamespace = "devbox")
        val second = source.listLocalRoot(session, "/home/alexey/git", cacheNamespace = "devbox")

        assertTrue(first is ReposListResult.Success)
        assertEquals(first, second)
        assertEquals(listOf(command), session.recorded)
    }

    @Test
    fun listLocalRoot_keepsCacheSeparateByNamespace() = runTest {
        val command = pathAware("pocketshell repos list --local --json --root '/home/alexey/git'")
        val session = FakeSshSession(
            mapOf(command to ExecResult("[]", "", 0)),
        )

        source.listLocalRoot(session, "/home/alexey/git", cacheNamespace = "devbox-a")
        source.listLocalRoot(session, "/home/alexey/git", cacheNamespace = "devbox-b")

        assertEquals(listOf(command, command), session.recorded)
    }

    @Test
    fun listLocalRoot_forceRefreshBypassesCache() = runTest {
        val command = pathAware("pocketshell repos list --local --json --root '/home/alexey/git'")
        val session = FakeSshSession(
            mapOf(command to ExecResult("[]", "", 0)),
        )

        source.listLocalRoot(session, "/home/alexey/git", cacheNamespace = "devbox")
        source.listLocalRoot(session, "/home/alexey/git", cacheNamespace = "devbox", forceRefresh = true)

        assertEquals(listOf(command, command), session.recorded)
    }

    @Test
    fun listLocalRoot_missingPocketshellReturnsEmptySuccess() = runTest {
        val command = pathAware("pocketshell repos list --local --json --root '/home/alexey/git'")
        val session = FakeSshSession(
            mapOf(command to ExecResult("", "pocketshell: not found", 127)),
        )

        assertEquals(
            ReposListResult.Success(emptyList()),
            source.listLocalRoot(
                session = session,
                root = "/home/alexey/git",
                cacheNamespace = "devbox",
            ),
        )
    }

    @Test
    fun listLocalRoot_missingReposSubcommandReturnsEmptySuccess() = runTest {
        val command = pathAware("pocketshell repos list --local --json --root '/home/alexey/git'")
        val session = FakeSshSession(
            mapOf(command to ExecResult("", "Error: No such command 'repos'.", 2)),
        )

        assertEquals(
            ReposListResult.Success(emptyList()),
            source.listLocalRoot(
                session = session,
                root = "/home/alexey/git",
                cacheNamespace = "devbox",
            ),
        )
    }

    @Test
    fun listLocal_exit127MapsToToolMissing() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("pocketshell repos list --local --json") to
                    ExecResult("", "pocketshell: not found", 127),
            ),
        )

        assertEquals(ReposListResult.ToolMissing, source.listLocal(session))
    }

    @Test
    fun open_quotesRepositoryAndReturnsPath() = runTest {
        val command = pathAware("pocketshell repos open 'alexeygrigorev/pocket'\"'\"'shell'")
        val session = FakeSshSession(
            mapOf(command to ExecResult("/home/alexey/git/pocketshell\n", "", 0)),
        )

        assertEquals(
            RepoPathResult.Success("/home/alexey/git/pocketshell"),
            source.open(session, "alexeygrigorev/pocket'shell"),
        )
        assertEquals(listOf(command), session.recorded)
    }

    @Test
    fun clone_passesRootAndProtocol() = runTest {
        val command = pathAware(
            "pocketshell repos clone 'alexeygrigorev/pocketshell' --root '~/git' --protocol 'ssh'",
        )
        val session = FakeSshSession(
            mapOf(command to ExecResult("/home/alexey/git/pocketshell\n", "", 0)),
        )

        assertEquals(
            RepoPathResult.Success("/home/alexey/git/pocketshell"),
            source.clone(session, "alexeygrigorev/pocketshell"),
        )
        assertEquals(listOf(command), session.recorded)
    }

    @Test
    fun exit127MapsToToolMissing() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("pocketshell repos list --remote --json") to
                    ExecResult("", "pocketshell: not found", 127),
            ),
        )

        assertEquals(ReposListResult.ToolMissing, source.listRemote(session))
    }

    @Test
    fun nonZeroExitMapsToFailed() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("pocketshell repos open 'alexeygrigorev/missing'") to
                    ExecResult("", "repository is not cloned", 1),
            ),
        )

        val result = source.open(session, "alexeygrigorev/missing")

        assertTrue(result is RepoPathResult.Failed)
        assertEquals("repository is not cloned", (result as RepoPathResult.Failed).reason)
    }

    @Test
    fun cancellationPropagates() = runTest {
        val session = ThrowingSshSession(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { source.listRemote(session) }
        }
    }

    private class FakeSshSession(
        private val canned: Map<String, ExecResult>,
    ) : SshSession {
        val recorded = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            return canned[command] ?: ExecResult("", "missing stub for $command", 127)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private class ThrowingSshSession(
        private val throwable: Throwable,
    ) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            throw throwable
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    // Issue #633: the PATH-augmented remote command MUST run under an explicit
    // POSIX `/bin/sh`, not the user's login shell. A fish login shell rejects the
    // bare `PATH="…"; cmd` form with `fish: Unsupported use of '='` (exit 127),
    // which silently broke every folder/session probe on a fish-login host so the
    // folder list never reached Ready. Wrapping in `/bin/sh -lc '…'` forces a
    // POSIX parse regardless of the login shell.
    @Test
    fun pathAwareCommand_wrapsInPosixSh_immuneToFishLoginShell() {
        val wrapped = ReposRemoteSource.pathAwareCommand("tmux list-sessions")

        // Runs under an explicit POSIX shell, so fish never parses the body.
        assertTrue(
            "expected /bin/sh -lc wrapper but was: $wrapped",
            wrapped.startsWith("/bin/sh -lc '"),
        )
        // The PATH augmentation and the real command are INSIDE the sh body, not
        // emitted as a bare login-shell one-liner.
        assertTrue(wrapped.contains("PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$PATH\""))
        assertTrue(wrapped.contains("tmux list-sessions"))
        // The old bare form (which fish rejects) must NOT be what we send: the
        // command must not START with the inline assignment.
        assertTrue(
            "must not emit a bare login-shell PATH assignment: $wrapped",
            !wrapped.startsWith("PATH="),
        )
    }

    // Single quotes embedded in the command body (tmux `-F` format strings,
    // `printf '%s\n'`) must survive the sh-quoting intact, or the wrapped command
    // would be malformed on every host (POSIX or fish).
    @Test
    fun pathAwareCommand_preservesEmbeddedSingleQuotes() {
        val wrapped = ReposRemoteSource.pathAwareCommand(
            "tmux list-sessions -F '#{session_name}'",
        )

        assertTrue(wrapped.startsWith("/bin/sh -lc '"))
        // The body's own single quotes are escaped with shellQuote's
        // close/double-quote-single/reopen style (`'` -> `'"'"'`), so the body
        // survives the outer single-quoting intact. The format string's quotes
        // become `'"'"'#{session_name}'"'"'`.
        assertTrue(
            "embedded single quotes should be sh-escaped: $wrapped",
            wrapped.contains("'\"'\"'#{session_name}'\"'\"'"),
        )
        // And the whole wrapper remains balanced /bin/sh -lc '…' (starts & ends
        // with the outer single quote).
        assertTrue("wrapper should end with the closing single quote: $wrapped", wrapped.endsWith("'"))
    }

    // Delegate to the production builder so the FakeSshSession command-map keys
    // always match what the source actually sends — including issue #633's
    // `/bin/sh -lc '…'` POSIX wrapper that makes the probe immune to a fish
    // login shell.
    private fun pathAware(command: String): String =
        ReposRemoteSource.pathAwareCommand(command)
}
