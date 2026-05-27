package com.pocketshell.app.proof.signals

import android.os.SystemClock

/**
 * Default upper-bound timeout for waiting on an SSH PTY to emit its
 * first interactive prompt on CI emulators.
 *
 * Local Linux developer setups normally see the first prompt inside
 * ~500 ms after the channel is up. On the GitHub Actions emulator the
 * docker fixture, SSH handshake, and PTY allocation collectively can
 * take 15–25 s. 30 s is a "this is broken, not slow" ceiling — if the
 * prompt has not arrived after that long, the PTY is not coming up at
 * all and the test should fail fast rather than burn the whole CI
 * deadline.
 */
internal const val PTY_READY_DEFAULT_TIMEOUT_MS: Long = 30_000L

/**
 * Default prompt regex used by [waitForSshPtyReady] when callers do not
 * provide their own.
 *
 * Matches any transcript line that ends with `$` or `#` optionally
 * followed by trailing whitespace, in `MULTILINE` mode. This is the
 * lowest-common-denominator interactive shell prompt across the
 * distributions PocketShell tests against:
 *
 *  - `testuser@host:~$ `   — default Ubuntu bash for the deterministic
 *    `tests/docker` fixture (PS1 inherited from `/etc/bash.bashrc`).
 *  - `[user@host ~]$ `     — RedHat / Fedora family.
 *  - `bash-5.1$ `          — minimal PS1 when `.bashrc` is bypassed.
 *  - `# `                  — root shells (also: dropbear/busybox default).
 *
 * `(?m)` enables multi-line matching so `$` anchors at the end of each
 * transcript line rather than the end of the whole buffer. `[$#]` is
 * inside a character class so the `$` is interpreted literally there,
 * and the trailing ` ?` accepts both `$ ` and `$` (some prompts emit a
 * trailing space, some don't, depending on PS1).
 *
 * The matching prompt character is required to be preceded by some
 * shell-prompt content on the same line (`.+?` non-greedy), so a raw
 * `$ echo hi` line in the middle of a shell script does not falsely
 * trigger readiness on its own — the test transcript is the full
 * collected output and may contain prior content.
 */
val DEFAULT_PROMPT: Regex = Regex("(?m)^.+?[\$#] ?$")

/**
 * Polls [transcriptProvider] at 100 ms intervals until [promptRegex]
 * matches the returned transcript, or [timeoutMs] elapses. Returns
 * `true` on first match, `false` on timeout.
 *
 * Distinguishes "PTY is slow" from "PTY is broken":
 *
 *  - If a prompt eventually arrives the helper returns `true` regardless
 *    of how long it took (so a generous CI deadline does not slow down
 *    a healthy local run that completes in 500 ms).
 *  - If the prompt never arrives, the helper returns `false`, which the
 *    caller should treat as a hard failure: the PTY channel never came
 *    up, the remote sshd auth path is wrong, or the fixture is not
 *    running. This is qualitatively different from a flaky timing miss.
 *
 * @param transcriptProvider lambda returning the current cumulative
 *   transcript snapshot. Implementations are typically a closure over a
 *   `StringBuilder` that the test's stdout collector appends to.
 *   Re-evaluated on every poll — the helper does no caching.
 * @param promptRegex pattern used to recognise a prompt. Defaults to
 *   [DEFAULT_PROMPT]; pass a tighter pattern (`Regex("testuser@.*\\$ ")`)
 *   when the test owns the remote PS1 and wants to fail-fast on noise.
 * @param timeoutMs upper bound; defaults to
 *   [PTY_READY_DEFAULT_TIMEOUT_MS]. Set tighter only when you have
 *   evidence the local-and-CI band can hit that bound on a slow run.
 * @return `true` on first prompt match, `false` on timeout.
 */
fun waitForSshPtyReady(
    transcriptProvider: () -> String,
    promptRegex: Regex = DEFAULT_PROMPT,
    timeoutMs: Long = PTY_READY_DEFAULT_TIMEOUT_MS,
): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (promptRegex.containsMatchIn(transcriptProvider())) return true
        SystemClock.sleep(100)
    }
    return promptRegex.containsMatchIn(transcriptProvider())
}
