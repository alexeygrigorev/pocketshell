package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.input.BracketedPaste
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient

/**
 * Issue #1460: type a literal UTF-8 string into [paneId] via `send-keys -l` on
 * the interactive send EXEC lane (not the shared `-CC` channel), so it does not
 * head-of-line-block behind a live agent `%output` burst.
 */
internal suspend fun sendLiteralTextKeys(
    client: TmuxClient,
    paneId: String,
    text: String,
): CommandResponse =
    client.sendKeysViaExec("send-keys -l -t $paneId -- '${escapeSingleQuoted(text)}'")

/**
 * Issue #1460: send a tmux named key (`Enter`, `Tab`, `BSpace`, `Up`, a
 * `C-<letter>` modifier, …) to [paneId] on the interactive send exec lane.
 */
internal suspend fun sendNamedKeyToPane(
    client: TmuxClient,
    paneId: String,
    key: String,
): CommandResponse =
    client.sendKeysViaExec("send-keys -t $paneId $key")

/**
 * Issue #1460: exit copy-mode in [paneId] (`send-keys -X … cancel`) on the
 * interactive send exec lane so a pane in copy-mode can accept input without
 * wedging behind a burst.
 */
internal suspend fun sendCancelCopyMode(
    client: TmuxClient,
    paneId: String,
): CommandResponse =
    client.sendKeysViaExec("send-keys -X -t $paneId cancel")

internal suspend fun sendRawInputBytes(
    client: TmuxClient,
    paneId: String,
    bytes: ByteArray,
) {
    val hex = BracketedPaste.hex(bytes)
    if (hex.isEmpty()) return
    // Issue #1460: the `-H` raw-byte injection rides the interactive send exec
    // lane, not the shared `-CC` channel, so it does not head-of-line-block
    // behind a live agent `%output` burst.
    // Issue #1586 (H1a): surface a tmux `%error` (dead/closed pane) as a real
    // failure instead of swallowing it, so a control-byte / terminal-response
    // send to a dead pane fails visibly (agent-lane parity).
    client.sendKeysViaExec("send-keys -H -t $paneId $hex")
        .throwIfTmuxError("send raw bytes to pane $paneId")
}

/**
 * Issue #209 / #398 / #1636: send [bytes] to [paneId] (or the active pane, when
 * [paneId] is null) as ONE bracketed-paste block, committed ATOMICALLY.
 *
 * ## Why this is not a `send-keys -H` chain any more (issue #1636)
 *
 * The old shape was N sequential `send-keys -H <hex>` execs — a paste-start
 * marker, the body chunks, a paste-end marker. Every gap between those execs was
 * a COMMIT POINT: a transport teardown between chunk k and k+1 (the #1610
 * reconnect storm produces one every ~5 s, which is shorter than a multi-chunk
 * paste chain) left chunks 1..k sitting in the agent's input box server-side and
 * threw out of the chain. The retry's verify-before-resend probe
 * ([probeOutboundPayloadAlreadyLanded]) keys on the tail of the payload's LAST
 * line — which a partial prefix never contains — so it reported `NotLanded` and
 * re-pasted the FULL payload ON TOP of the partial. The agent then received
 * `<partial-prefix><full-payload>` and submitted it as ONE prompt: delivered
 * exactly once, content silently corrupted. Repeated cuts accreted SEVERAL
 * prefixes. Every occurrence-count assertion stayed green throughout, because
 * they count sends, not bytes.
 *
 * ## The shape that fixes it
 *
 *  1. **Fill** a named tmux paste buffer with `set-buffer`, chunked to keep each
 *     command bounded. The FIRST chunk uses `-b` (set), which TRUNCATES any
 *     existing buffer, and the rest use `-ab` (append). Nothing reaches the pane
 *     during the fill, so a teardown mid-fill is harmless AND self-correcting:
 *     the retry's truncating first chunk discards whatever partial the cut left
 *     behind. The fill is idempotent by restart.
 *  2. **Commit** with a single `paste-buffer` exec. That one exec is the only
 *     point at which bytes reach the pane, and tmux applies the whole buffer or
 *     nothing — so the pane can never hold a partial paste. The existing durable
 *     verify ledger keys onto this single commit cleanly: if its exec result is
 *     lost the payload either fully landed (probe ⇒ `AlreadyLanded` ⇒ Enter only)
 *     or never landed (probe ⇒ `NotLanded` ⇒ a clean full re-fill + re-commit).
 *
 * `-d` deletes the buffer after a successful paste, so the server keeps at most
 * one paste buffer per pane (a cut mid-fill leaves one behind; the next fill's
 * truncating `-b` reuses it rather than accumulating).
 *
 * ## Byte-exactness (the load-bearing property)
 *
 * The buffer is filled with the SAME framed bytes the `-H` chain sent —
 * `\e[200~` + the UTF-8 body + `\e[201~`, with `\r\n` normalised to `\n` and
 * lone `\r` passed through ([BracketedPaste.frame]) — so the bytes the receiving
 * program sees are unchanged by this rewrite. Two `paste-buffer` flags carry
 * that:
 *
 *  - `-r` — do NOT replace `\n` with `\r`. Without it tmux translates every line
 *    break into Enter, which an agent input box reads as SUBMIT: a multi-line
 *    prompt would be torn into one prompt per line.
 *  - no `-p` — tmux must NOT add its own bracketed-paste markers, because
 *    [BracketedPaste.frame] already wrote them into the buffer (and tmux's `-p`
 *    only emits them when the application requested the mode, so it is not a
 *    substitute).
 *
 * Chunks are single-quoted command arguments carrying the payload's raw bytes,
 * which is why the fill rides [TmuxClient.sendKeysViaExec]: that lane runs
 * `tmux <cmd>` through the remote shell, whose single quotes pass a literal `\n`
 * (and the `\e` marker bytes) through untouched. The `-CC` control channel would
 * terminate the command at the first `\n`.
 *
 * If any command fails, either by throwing or by tmux returning `%error`, the
 * exception propagates to the caller so composer surfaces keep the unsent draft.
 */
internal suspend fun sendBracketedPaste(
    client: TmuxClient,
    paneId: String?,
    bytes: ByteArray,
) {
    if (bytes.isEmpty()) return
    val bufferName = pasteBufferNameFor(paneId)
    val chunks = BracketedPaste.frameTextChunks(bytes, TMUX_PASTE_BODY_CHUNK_BYTES)
    chunks.forEachIndexed { index, chunk ->
        // Issue #1460: the fill rides the interactive send exec lane (awaited
        // sequentially, so ordering is preserved) instead of the shared `-CC`
        // channel — a multi-chunk paste to a live agent mid-burst no longer wedges
        // behind the burst's `%output` on the one sshj reader.
        // Issue #1636: `-b` on the FIRST chunk truncates, `-ab` appends — so a
        // retry after a mid-fill cut rebuilds the buffer from scratch instead of
        // appending to the partial the cut left.
        val setFlags = if (index == 0) "-b" else "-ab"
        PasteChunkSeams.consumeFailAtFillChunk(index)
        client.sendKeysViaExec("set-buffer $setFlags $bufferName -- '${escapeSingleQuoted(chunk)}'")
            .throwIfTmuxError("fill paste buffer for pane ${paneId ?: "(active)"}")
    }
    // THE commit point: one exec, all-or-nothing server-side.
    val target = if (paneId != null) " -t $paneId" else ""
    client.sendKeysViaExec("paste-buffer -d -r -b $bufferName$target")
        .throwIfTmuxError("paste buffer into pane ${paneId ?: "(active)"}")
}

/**
 * Issue #1636: the tmux paste-buffer name this pane's atomic paste fills. Stable
 * per pane (so a retry TRUNCATES the partial its predecessor left instead of
 * accreting a second one) and distinct across panes (so two panes' sends never
 * clobber each other's buffer). Sanitised to alphanumerics because the name is
 * an unquoted command argument.
 */
internal fun pasteBufferNameFor(paneId: String?): String {
    val suffix = paneId?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() } ?: "active"
    return "pspaste$suffix"
}

/**
 * Issue #1636 test seam (#780 synthetic-injection model). Production never arms
 * it (default -1); it is consumed once.
 *
 * [failAtFillChunkIndex] cuts the transport just BEFORE the paste buffer's fill
 * chunk at that index reaches the server — the #1526 S6 spec's cut point (b), a
 * teardown at a CHUNK BOUNDARY mid-paste, which no fixture reproduced before
 * (the existing [OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter]
 * models cut point (c), AFTER the whole paste). This is the exact cut the #1610
 * storm lands inside a multi-chunk paste, and the one that used to leave a
 * partial prefix in the agent's input box for the next verified resend to
 * accrete onto.
 *
 * [onCut] lets the arming test make the cut a REAL transport teardown (the
 * connected journey installs the view model's clean-passive-drop) rather than a
 * bare throw, so the resend that follows is the production reconnect path.
 */
internal object PasteChunkSeams {
    @Volatile
    var failAtFillChunkIndex: Int = -1

    @Volatile
    var onCut: (() -> Unit)? = null

    fun consumeFailAtFillChunk(index: Int) {
        if (failAtFillChunkIndex != index) return
        failAtFillChunkIndex = -1
        onCut?.invoke()
        throw IllegalStateException(
            "test-seam: transport torn down at paste fill chunk boundary $index",
        )
    }

    fun reset() {
        failAtFillChunkIndex = -1
        onCut = null
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
