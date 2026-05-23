package com.pocketshell.app.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.createStdoutFlow
import com.pocketshell.app.proof.openShell
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator + Docker coverage for issue #59's project-root workflows.
 *
 * This test drives [SessionViewModel]'s project navigation commands through
 * the same Termux/SSH bridge as user input, then verifies the remote shell
 * actually created directories, changed cwd, and ran the generated
 * `git clone && cd` command.
 */
@RunWith(AndroidJUnit4::class)
class ProjectNavigationTerminalE2eTest {

    @Test
    fun projectNavigationCommandsRunAgainstDockerShell() = runBlocking {
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val marker = "project-nav-${System.currentTimeMillis()}"
        val root = "/tmp/pocketshell project nav-$marker"
        val binDir = "/tmp/pocketshell-project-nav-bin-$marker"
        val mkdirTarget = "$root/made dir"
        val cloneTarget = "$root/clone dir"
        val repository = "ssh://example/repo.git"

        cleanupRemote(key, root, binDir)
        installFakeGit(key, binDir)

        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val handle = withTimeout(20_000) {
            openShell(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
            )
        }
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val received = StringBuilder()
        val outputJob = launch(Dispatchers.Default) {
            viewModel.terminalState.output.collect { bytes ->
                synchronized(received) {
                    received.append(bytes.toString(Charsets.UTF_8))
                }
            }
        }
        val producerJob = viewModel.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = createStdoutFlow(handle.shell),
            remoteStdin = handle.shell.outputStream,
        )

        try {
            val pathReady = "$marker-path-ready"
            viewModel.sendText(
                "export PATH=${shellQuote(binDir)}:\$PATH; printf '%s\\n' ${shellQuote(pathReady)}",
                true,
            )
            waitForTranscript(received, pathReady)

            viewModel.createFolderAndCd(root, "made dir")
            waitForTranscript(received, "[pocketshell] mkdir + cd succeeded: $mkdirTarget")
            viewModel.sendText(
                "test \"\$(pwd)\" = ${shellQuote(mkdirTarget)} && printf '%s\\n' ${shellQuote("$marker-mkdir-ok")}",
                true,
            )
            waitForTranscript(received, "$marker-mkdir-ok")

            viewModel.navigateToDirectory(root)
            waitForTranscript(received, "[pocketshell] cd succeeded: $root")
            viewModel.sendText(
                "test \"\$(pwd)\" = ${shellQuote(root)} && printf '%s\\n' ${shellQuote("$marker-cd-ok")}",
                true,
            )
            waitForTranscript(received, "$marker-cd-ok")

            viewModel.cloneRepositoryAndCd(root, repository, "clone dir")
            waitForTranscript(received, "[pocketshell] git clone + cd succeeded: $cloneTarget")
            viewModel.sendText(
                "test \"\$(pwd)\" = ${shellQuote(cloneTarget)} && " +
                    "test \"\$(cat repository.txt)\" = ${shellQuote(repository)} && " +
                    "printf '%s\\n' ${shellQuote("$marker-clone-ok")}",
                true,
            )
            waitForTranscript(received, "$marker-clone-ok")
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            outputJob.cancel()
            viewModel.terminalState.detachExternalProducer()
            runCatching { handle.shell.close() }
            runCatching { handle.sessionChannel.close() }
            runCatching { handle.client.disconnect() }
            cleanupRemote(key, root, binDir)
        }
    }

    private suspend fun installFakeGit(key: String, binDir: String) {
        val script = """
            mkdir -p ${shellQuote(binDir)}
            cat > ${shellQuote("$binDir/git")} <<'POCKETSHELL_FAKE_GIT'
            #!/bin/sh
            if [ "${'$'}1" = "clone" ]; then
              repository="${'$'}2"
              target="${'$'}3"
              mkdir -p "${'$'}target" || exit 20
              printf '%s\n' "${'$'}repository" > "${'$'}target/repository.txt" || exit 21
              exit 0
            fi
            printf 'unsupported git command\n' >&2
            exit 2
            POCKETSHELL_FAKE_GIT
            chmod +x ${shellQuote("$binDir/git")}
        """.trimIndent()
        val result = connect(key).use { session ->
            session.exec(script)
        }
        assertTrue(
            "expected fake git install to succeed, got stdout='${result.stdout}' stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemote(key: String, root: String, binDir: String) {
        val result = runCatching {
            connect(key).use { session ->
                session.exec("rm -rf ${shellQuote(root)} ${shellQuote(binDir)}")
            }
        }
        assertTrue(
            "expected project navigation cleanup to succeed, got ${result.exceptionOrNull()}",
            result.getOrNull()?.exitCode == 0,
        )
    }

    private suspend fun connect(key: String) = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DEFAULT_PORT,
        user = DEFAULT_USER,
        key = SshKey.Pem(key),
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 15_000,
    ).getOrThrow()

    private suspend fun waitForTranscript(received: StringBuilder, expected: String) {
        var lastSnapshot = ""
        withTimeout(15_000) {
            while (true) {
                lastSnapshot = synchronized(received) { received.toString() }
                if (lastSnapshot.contains(expected)) return@withTimeout
                delay(100)
            }
        }
        assertTrue(
            "expected terminal transcript to contain '$expected', got:\n$lastSnapshot",
            lastSnapshot.contains(expected),
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
