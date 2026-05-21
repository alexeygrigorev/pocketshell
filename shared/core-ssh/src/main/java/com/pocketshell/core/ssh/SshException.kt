package com.pocketshell.core.ssh

/**
 * Single exception type surfaced from the `core-ssh` module.
 *
 * sshj throws a variety of checked and unchecked exceptions
 * ([net.schmizz.sshj.userauth.UserAuthException],
 * [net.schmizz.sshj.transport.TransportException],
 * [java.io.IOException], ...). Callers shouldn't have to fan out on the full
 * sshj exception hierarchy — we wrap them all into [SshException] with the
 * underlying cause preserved on [Throwable.cause].
 */
public class SshException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
