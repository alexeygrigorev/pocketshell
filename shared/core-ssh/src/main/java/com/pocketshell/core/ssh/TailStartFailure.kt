package com.pocketshell.core.ssh

/** sshj's untyped sentinel when startSession races a transport disconnect. */
internal fun isSshjDisconnect(error: Throwable): Boolean =
    error is IllegalStateException && error.message == "Not connected"
