package com.pocketshell.core.ssh

/**
 * The outcome of a single `exec` channel against an SSH session.
 *
 * Unlike the original `ssh-auto-forward-android` implementation, which threw
 * on non-zero exit codes and only returned stdout, this struct surfaces all
 * three pieces so the caller can decide what counts as failure. Many of the
 * tools PocketShell runs over SSH (`which`, `tmux has-session`, helper CLIs
 * with structured exit codes) communicate via exit status.
 */
public data class ExecResult(
    public val stdout: String,
    public val stderr: String,
    public val exitCode: Int,
)
