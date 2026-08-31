package com.pocketshell.app.tmux

import android.content.Context
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.session.LastSessionStore
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import androidx.test.platform.app.InstrumentationRegistry

internal fun resolveSshPort(): Int {
    return InstrumentationRegistry.getArguments()
        .getString("terminalWorkbenchSshPort")
        ?.toIntOrNull()
        ?: DEFAULT_PORT
}

internal fun clearIssue2087LastSessionState(context: Context) {
    LastSessionStore(context).clear()
}

internal suspend fun readIssue2087RemoteSessionIdentity(
    sshKey: SshKey.Pem,
    sshPort: Int,
    sessionName: String,
): String = SshConnection.connect(
    host = DEFAULT_HOST,
    port = sshPort,
    user = DEFAULT_USER,
    key = sshKey,
    knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
    timeoutMs = 15_000,
).mapCatching { session ->
    session.use {
        it.exec(
            """
            set +e
            printf '%s\\n' 'target-session'
            tmux display-message -p -t '=$sessionName:' '#{session_name}::#{session_id}::#{session_created}::#{@ps_agent_kind}'
            printf '%s\\n' 'show-option'
            tmux show-options -v -t '=$sessionName:' @ps_agent_kind 2>&1
            printf '%s\\n' 'pane'
            tmux list-panes -s -t '=$sessionName:' -F '#{session_name}::#{session_id}::#{window_id}::#{pane_id}::#{pane_pid}::#{pane_current_command}::#{@ps_agent_kind}'
            """.trimIndent(),
        ).let { result ->
            "exit=${result.exitCode}\\nstdout:\\n${result.stdout}\\nstderr:\\n${result.stderr}"
        }
    }
}.getOrElse { error ->
    "query_failure=${error::class.java.name}: ${error.message}"
}
