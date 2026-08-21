package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.input.BracketedPaste
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient

/**
 * Issue #1460: type a literal UTF-8 string into [paneId] via `send-keys -l` on
 * the interactive send EXEC lane (not the shared `-CC` channel), so it does not
 * head-of-line-block behind a live agent `%output` burst.
 *
 * Issue #1845: [text] MUST be quoted with [tmuxQuotedArgument], not merely
 * single-quoted. tmux deletes a TRAILING `;` from an `argv` element (it is a
 * command separator), so `send-keys -l -- 'abc;'` typed `abc` and reported
 * success — silently dropping a byte out of the user's typed command.
 */
internal suspend fun sendLiteralTextKeys(
    client: TmuxClient,
    paneId: String,
    text: String,
): CommandResponse =
    client.sendKeysViaExec("send-keys -l -t $paneId -- ${tmuxQuotedArgument(text)}")

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
    // behind a live agent `%output` burst. One command carries the complete
    // byte sequence, preserving the single commit boundary for this route.
    // Issue #2266: tmux 3.7c's `paste-buffer -r` visual-encodes ESC as `^[`;
    // already-framed terminal input must therefore stay on this exact raw-byte
    // route instead of going through a tmux paste buffer.
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
    beforeCommit: suspend () -> Unit = {},
) {
    if (bytes.isEmpty()) return
    sendPasteBlock(client, paneId, BracketedPaste.frame(bytes), beforeCommit)
}

/**
 * Issue #1636: atomically fill and commit a bracketed-paste block.
 *
 * [sendBracketedPaste] frames its payload before calling this function. An
 * already-framed block produced by `TerminalView` does NOT use this function:
 * tmux 3.7c rewrites ESC bytes during `paste-buffer -r`, so that path uses
 * [sendRawInputBytes] in [deliverPaneInputBytes] instead (issue #2266).
 */
internal suspend fun sendPasteBlock(
    client: TmuxClient,
    paneId: String?,
    framedBytes: ByteArray,
    beforeCommit: suspend () -> Unit = {},
) {
    if (framedBytes.isEmpty()) return
    val bufferName = pasteBufferNameFor(paneId)
    val chunks = BracketedPaste.textChunks(framedBytes, TMUX_PASTE_BODY_CHUNK_BYTES)
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
        // Issue #1845: [tmuxQuotedArgument], not bare single-quoting — a chunk
        // whose LAST byte is `;` would otherwise lose it to tmux's argv
        // command-separator rule, silently corrupting a pasted prompt at a
        // fixed-size chunk boundary.
        client.sendKeysViaExec("set-buffer $setFlags $bufferName -- ${tmuxQuotedArgument(chunk)}")
            .throwIfTmuxError("fill paste buffer for pane ${paneId ?: "(active)"}")
    }
    // THE commit point: one exec, all-or-nothing server-side.
    // Issue #1739: callers that own a durable delivery ledger record the
    // ambiguous wire attempt only here. A failed set-buffer fill cannot have
    // touched the pane, so it must remain definitively safe to refill/retry.
    beforeCommit()
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

/**
 * Issue #1854 (extracted from `TmuxSessionViewModel.sendInputBytesToPane`, D28):
 * route one batch of pane-input bytes onto the right tmux command lane. The
 * caller has already made the pane accept input (copy-mode exit).
 */
internal suspend fun deliverPaneInputBytes(
    client: TmuxClient,
    paneId: String,
    bytes: ByteArray,
) {
    // Issue #243: replies generated by the local terminal emulator
    // already contain the exact ESC-prefixed bytes the remote program
    // queried for (OSC colors, device attributes, etc.). Passing them
    // through inputTokens turns ESC into a tmux named key and leaks the
    // printable tail into the pane. Keep user input on the existing
    // paths, but preserve complete terminal reports byte-for-byte.
    if (isTerminalGeneratedResponse(bytes)) {
        sendRawInputBytes(client, paneId, bytes)
        return
    }

    // Issue #209: multi-line input (containing `\n` or `\r\n`) goes
    // through tmux's bracketed-paste route so the foreground program
    // (Claude Code CLI, modern bash, zsh, vim, …) treats the entire
    // block as ONE pasted prompt instead of submitting line-by-line.
    //
    // Why this matters: the previous shape split the input on `\n`
    // and emitted a `send-keys ... Enter` named-key per line break.
    // Claude Code CLI interprets each Enter as "submit this prompt",
    // so a multi-paragraph dictation transcript landed as N
    // independent messages — the bug the maintainer found in daily use
    // on 2026-05-27.
    //
    // Bracketed-paste mode is a terminal protocol: the program tells
    // its terminal "I want to know when text is pasted" via DECSET
    // 2004 (`\e[?2004h`), and the terminal then frames pastes with
    // `\e[200~` / `\e[201~`. Inside the markers, readline-based CLIs
    // (Claude Code, modern shells with `enable-bracketed-paste` on,
    // vim, …) treat embedded newlines as literal content, not as
    // submit signals. If a program does not enable bracketed paste
    // (handful of niche TUIs, plain `cat`), the markers appear as
    // literal text — acceptable degradation per #209's design.
    //
    // tmux exposes literal byte injection in two flavours:
    //   - `send-keys -l '<text>'` writes the UTF-8 bytes of the
    //     argument. tmux's command parser terminates commands at
    //     `\n`, so we cannot embed a literal newline inside the
    //     argument — and that's exactly the byte we need to send
    //     for the paste body.
    //   - `send-keys -H <hex pairs>` writes the bytes named by the
    //     space-separated hex pairs. This lets us include 0x0A
    //     freely. We keep each control-mode command bounded by
    //     sending one paste-start marker, a sequence of small body
    //     chunks, and one paste-end marker.
    //
    // Single-line input still goes through the existing
    // [inputTokens] path so named keys (arrows, Escape, Tab,
    // BSpace) keep round-tripping as named keys — bracketed paste
    // would just confuse the receiving program for a one-liner.
    //
    // Issue #1854: TerminalView has already classified and framed this block;
    // do not apply a second application-level frame. Issue #2266 adds the
    // transport boundary: tmux 3.7c's paste-buffer path is not byte-exact for
    // embedded ESC, so preserve the existing frame with one raw-byte command.
    if (BracketedPaste.isFramed(bytes)) {
        sendRawInputBytes(client, paneId, bytes)
        return
    }
    if (BracketedPaste.containsLineBreak(bytes)) {
        sendBracketedPaste(client, paneId, bytes)
        return
    }
    // Issue #1586 (H1a): a tmux `%error` (dead pane) must FAIL the send, not false-succeed.
    for (token in inputTokens(bytes)) {
        when (token) {
            is TmuxInputToken.Literal ->
                if (token.text.isNotEmpty()) {
                    sendLiteralTextKeys(client, paneId, token.text)
                        .throwIfTmuxError("type into $paneId")
                }
            is TmuxInputToken.NamedKey ->
                sendNamedKeyToPane(client, paneId, token.name)
                    .throwIfTmuxError("send ${token.name} to $paneId")
        }
    }
}
