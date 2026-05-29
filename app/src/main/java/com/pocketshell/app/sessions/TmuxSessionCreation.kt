package com.pocketshell.app.sessions

import com.pocketshell.core.ssh.SshSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal const val DEFAULT_TMUX_START_DIRECTORY = "~"

internal data class TmuxSessionCreation(
    val sessionName: String,
    val startDirectory: String,
)

internal fun resolveTmuxSessionCreation(
    rawName: String,
    rawStartDirectory: String,
    nowMillis: Long = System.currentTimeMillis(),
): TmuxSessionCreation {
    val startDirectory = rawStartDirectory.trim().ifBlank { DEFAULT_TMUX_START_DIRECTORY }
    val typedName = sanitizeTypedTmuxSessionName(rawName.trim())
    val sessionName = typedName
        ?: derivedTmuxSessionName(startDirectory)
        ?: generatedTmuxSessionName(nowMillis)
    return TmuxSessionCreation(
        sessionName = sessionName,
        startDirectory = startDirectory,
    )
}

internal suspend fun remoteStartDirectoryExists(
    session: SshSession,
    startDirectory: String,
): Boolean =
    session.exec(remoteStartDirectoryExistsCommand(startDirectory)).exitCode == 0

internal fun startDirectoryMissingMessage(
    sessionName: String,
    startDirectory: String,
): String =
    "Couldn't create $sessionName: start folder does not exist: " +
        startDirectory.trim().ifBlank { DEFAULT_TMUX_START_DIRECTORY }

internal fun remoteStartDirectoryExistsCommand(startDirectory: String): String {
    val resolved = startDirectory.trim().ifBlank { DEFAULT_TMUX_START_DIRECTORY }
    return """
        pocketshell_start_dir=${shellQuote(resolved)}
        case "${'$'}pocketshell_start_dir" in
          '~') pocketshell_start_dir=${'$'}HOME ;;
          '~/'*) pocketshell_start_dir=${'$'}HOME/${'$'}{pocketshell_start_dir#~/} ;;
          '${'$'}HOME') pocketshell_start_dir=${'$'}HOME ;;
          '${'$'}HOME/'*) pocketshell_start_dir=${'$'}HOME/${'$'}{pocketshell_start_dir#${'$'}HOME/} ;;
        esac
        test -d "${'$'}pocketshell_start_dir"
    """.trimIndent()
}

private fun derivedTmuxSessionName(startDirectory: String): String? {
    val trimmed = startDirectory.trim().trimEnd('/')
    if (trimmed.isBlank() || trimmed == "~" || trimmed == "\$HOME") return null
    val baseName = trimmed.substringAfterLast('/').trim()
    if (baseName.isBlank() || baseName == "." || baseName == "..") return null
    return sanitizeDerivedTmuxSessionName(baseName)
}

private fun generatedTmuxSessionName(nowMillis: Long): String {
    val formatter = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val stamp = formatter.format(Date(nowMillis))
    return "pocketshell-$stamp"
}

private fun sanitizeTypedTmuxSessionName(input: String): String? {
    val cleaned = buildString(input.length) {
        for (ch in input) {
            when {
                ch == ':' || ch == '/' || ch.code < 32 || ch.code == 127 -> append('-')
                else -> append(ch)
            }
        }
    }.trim(' ', '-')
    return cleaned.takeIf { it.isNotBlank() }
}

private fun sanitizeDerivedTmuxSessionName(input: String): String? {
    val cleaned = buildString(input.length) {
        var previousDash = false
        for (ch in input) {
            val mapped = when {
                ch.isLetterOrDigit() || ch == '_' || ch == '.' -> ch
                else -> '-'
            }
            if (mapped == '-') {
                if (!previousDash) append(mapped)
                previousDash = true
            } else {
                append(mapped)
                previousDash = false
            }
        }
    }.trim('-', '.')
    return cleaned.takeIf { it.isNotBlank() }
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
