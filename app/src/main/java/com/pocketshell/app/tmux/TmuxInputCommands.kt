package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.input.BracketedPaste
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient

internal suspend fun sendRawInputBytes(
    client: TmuxClient,
    paneId: String,
    bytes: ByteArray,
) {
    val hex = BracketedPaste.hex(bytes)
    if (hex.isEmpty()) return
    client.sendCommand("send-keys -H -t $paneId $hex")
}

/**
 * Issue #209 / #398: send [bytes] to [paneId] as a bracketed-paste
 * block via bounded `send-keys -H` commands.
 *
 * The hex payload is `1b 5b 32 30 30 7e` (`\e[200~`) + the UTF-8
 * bytes of the input + `1b 5b 32 30 31 7e` (`\e[201~`). `\r\n`
 * pairs are normalised to `\n` so the inner content uses LF only;
 * lone `\r` bytes are passed through (they are not paragraph
 * separators in dictation transcripts and we have no reason to
 * mangle them).
 *
 * If any chunk fails, either by throwing or by tmux returning
 * `%error`, the exception propagates to the caller so composer
 * surfaces can keep the unsent draft.
 */
internal suspend fun sendBracketedPaste(
    client: TmuxClient,
    paneId: String,
    bytes: ByteArray,
) {
    if (bytes.isEmpty()) return
    for (hex in BracketedPaste.hexChunks(bytes, TMUX_PASTE_BODY_CHUNK_BYTES)) {
        client.sendCommand("send-keys -H -t $paneId $hex")
            .throwIfTmuxError("paste chunk into pane $paneId")
    }
}

internal fun CommandResponse.throwIfTmuxError(action: String) {
    if (!isError) return
    val detail = output.joinToString(separator = " ").trim()
    throw IllegalStateException(
        "tmux rejected $action${if (detail.isNotEmpty()) ": $detail" else ""}",
    )
}

internal fun inputTokens(bytes: ByteArray): List<TmuxInputToken> {
    val text = String(bytes, Charsets.UTF_8)
    val tokens = mutableListOf<TmuxInputToken>()
    val literal = StringBuilder()

    fun flushLiteral() {
        if (literal.isNotEmpty()) {
            tokens += TmuxInputToken.Literal(literal.toString())
            literal.clear()
        }
    }

    var index = 0
    while (index < text.length) {
        val ch = text[index]
        when (ch) {
            '\r', '\n' -> {
                flushLiteral()
                tokens += TmuxInputToken.NamedKey("Enter")
                if (ch == '\r' && text.getOrNull(index + 1) == '\n') index += 1
            }
            '\t' -> {
                flushLiteral()
                tokens += TmuxInputToken.NamedKey("Tab")
            }
            '\b', '\u007f' -> {
                flushLiteral()
                tokens += TmuxInputToken.NamedKey("BSpace")
            }
            '\u001b' -> {
                flushLiteral()
                val mapped = when {
                    text.startsWith("\u001b[A", index) -> "Up" to 2
                    text.startsWith("\u001b[B", index) -> "Down" to 2
                    text.startsWith("\u001b[C", index) -> "Right" to 2
                    text.startsWith("\u001b[D", index) -> "Left" to 2
                    else -> "Escape" to 0
                }
                tokens += TmuxInputToken.NamedKey(mapped.first)
                index += mapped.second
            }
            else -> literal.append(ch)
        }
        index += 1
    }
    flushLiteral()
    return tokens
}

internal sealed interface TmuxInputToken {
    data class Literal(val text: String) : TmuxInputToken
    data class NamedKey(val name: String) : TmuxInputToken
}
