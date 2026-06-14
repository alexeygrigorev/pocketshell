package com.pocketshell.core.ssh

/**
 * POSIX single-quote-escape [value] for safe interpolation into a remote shell
 * command line.
 *
 * Wraps the whole value in single quotes and rewrites every embedded `'` as the
 * canonical close-escape-reopen sequence `'\''`, so the result is one inert
 * shell token no matter what [value] contains (spaces, `$`, `;`, `&`, `` ` ``,
 * a literal `'`, etc.). This is the single shared escaping primitive reused by
 * the upload path (`cat > '<path>'`) and the inbox `mkdir -p '<dir>'` directory
 * prefix so the whole remote-write chain — prefix and filename alike — is
 * escaped by one helper rather than relying on per-call-site `.replace(...)`.
 *
 * Examples:
 *  - `hello`            → `'hello'`
 *  - `/home/o'reilly`   → `'/home/o'\''reilly'`
 *  - `x';rm -rf ~;'`    → `'x'\'';rm -rf ~;'\'''`  (inert: a single argument)
 *
 * Note this fully quotes the value, so a leading `~` is NOT expanded by the
 * remote shell. For paths where `~` must still expand to `$HOME`, use
 * [quoteRemotePathForShell] instead.
 */
fun shellSingleQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
