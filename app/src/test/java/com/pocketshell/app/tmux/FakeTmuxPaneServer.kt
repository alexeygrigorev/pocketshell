package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.CommandResponse

/**
 * Issue #1636 — a [FakeTmuxClient] that actually MODELS the server side of the
 * input-delivery path: it interprets the tmux commands the paste chain sends and
 * maintains, per pane, the bytes sitting in the agent's INPUT BOX plus the list
 * of prompts that were SUBMITTED.
 *
 * ## Why this exists (the assertion class the suite lacked)
 *
 * Every existing outbound proof asserts an OCCURRENCE COUNT — "the payload was
 * pasted once", "one `send-keys ... Enter`". Those assertions are structurally
 * blind to #1636, whose whole symptom is that the payload IS sent exactly once
 * and its CONTENT is wrong (`<partial-prefix><full-payload>`). Counting sends
 * cannot see corrupted bytes. This double makes the load-bearing property
 * assertable directly: [submittedPrompts] is the exact byte sequence the agent
 * received, so a test can say `assertEquals(payload, submitted.single())`.
 *
 * ## The model (documented tmux semantics, verified against tmux 3.4)
 *
 * The command set the paste path can emit, and what a real tmux server does with
 * each — deliberately including the flags whose LOSS would corrupt the payload,
 * so this double stays honest if the production command shape drifts:
 *
 *  - `send-keys -H -t <pane> <hex pairs>` — writes the named bytes to the pane.
 *  - `send-keys -l -t <pane> -- '<text>'` — writes the argument's UTF-8 bytes.
 *  - `send-keys -t <pane> Enter` — writes `\r`.
 *  - `set-buffer -b <name> -- '<text>'` — REPLACES buffer `<name>`.
 *  - `set-buffer -ab <name> -- '<text>'` — APPENDS to buffer `<name>`.
 *  - `paste-buffer [-d] [-r] [-p] -b <name> [-t <pane>]` — writes the buffer's
 *    bytes to the pane; errors when the buffer does not exist. WITHOUT `-r` tmux
 *    replaces every `\n` with `\r` (which an input box reads as SUBMIT — it
 *    would tear a multi-line prompt into one prompt per line). WITH `-p` tmux
 *    wraps the paste in its OWN bracketed-paste markers (which would double the
 *    markers we already framed in). `-d` deletes the buffer after pasting.
 *
 * The pane models a readline-style agent input box: bracketed-paste markers are
 * consumed, everything else accumulates, and a `\r` submits the box's contents
 * as one prompt and clears it. `capture-pane` renders the live input box, so the
 * production ack gate and the verify-before-resend probe read the same state a
 * real pane would show them.
 *
 * ## Injecting a teardown
 *
 * [failBeforeApplyAtCommand] cuts the link BEFORE the matching command reaches
 * the server (it throws and the server state is untouched) — a mid-chain
 * teardown, the #1610 storm's shape. [failAfterApplyAtCommand] lets the command
 * RUN and then loses its result — the ambiguous cut. Both are shape-agnostic:
 * they match on a predicate over the command text, so the SAME test drives the
 * old `send-keys -H` chain and the new `set-buffer`/`paste-buffer` route, which
 * is what makes the #1636 reproduction red on base and green with the fix.
 */
internal class FakeTmuxPaneServer : FakeTmuxClient() {

    /** Bytes currently sitting in each pane's agent input box. */
    private val inputBoxes: MutableMap<String, StringBuilder> = mutableMapOf()

    /** tmux paste buffers, by name. */
    private val buffers: MutableMap<String, StringBuilder> = mutableMapOf()

    private val submitted: MutableMap<String, MutableList<String>> = mutableMapOf()

    /** Every wire command the server actually APPLIED, in order. */
    val appliedCommands: MutableList<String> = mutableListOf()

    /**
     * The prompts [paneId]'s input box submitted, oldest first — the exact text
     * the agent received. THE #1636 assertion target.
     */
    fun submittedPrompts(paneId: String): List<String> = submitted[paneId].orEmpty().toList()

    /** The text currently pending in [paneId]'s input box (un-submitted). */
    fun inputBox(paneId: String): String = inputBoxes[paneId]?.toString().orEmpty()

    /** Cut the link just BEFORE the first command matching [predicate] is applied. */
    fun failBeforeApplyAtCommand(predicate: (String) -> Boolean) {
        failBeforeApply = predicate
    }

    /** Let the first command matching [predicate] RUN, then lose its result. */
    fun failAfterApplyAtCommand(predicate: (String) -> Boolean) {
        failAfterApply = predicate
    }

    private var failBeforeApply: ((String) -> Boolean)? = null
    private var failAfterApply: ((String) -> Boolean)? = null

    override suspend fun sendKeysViaExec(
        sendKeysCommand: String,
        timeoutMs: Long?,
    ): CommandResponse {
        sentCommands += sendKeysCommand
        failBeforeApply?.let { predicate ->
            if (predicate(sendKeysCommand)) {
                failBeforeApply = null
                // The link died on the way out: the server never saw this command.
                throw IllegalStateException("test: transport torn down before `$sendKeysCommand`")
            }
        }
        val response = apply(sendKeysCommand)
        failAfterApply?.let { predicate ->
            if (predicate(sendKeysCommand)) {
                failAfterApply = null
                // The command RAN; only its result was lost (the ambiguous cut).
                throw IllegalStateException("test: result lost after `$sendKeysCommand`")
            }
        }
        return response
    }

    /**
     * The probe (#1587) and the submit ack gate (#869) both read the pane through
     * this lane, so they see the live input box — exactly what a real pane shows.
     */
    override suspend fun capturePaneTextViaExec(
        paneId: String,
        timeoutMs: Long?,
        scrollbackLines: Int,
    ): CommandResponse {
        capturePaneTextViaExecCalls += paneId
        capturePaneTextViaExecScrollbackLines += scrollbackLines
        val lines = buildList {
            addAll(submitted[paneId].orEmpty().map { "> $it" })
            add("> ${inputBox(paneId)}")
        }
        return CommandResponse(number = 0L, output = lines, isError = false)
    }

    private fun apply(command: String): CommandResponse {
        val error = when {
            command.startsWith("set-buffer ") -> applySetBuffer(command)
            command.startsWith("paste-buffer ") -> applyPasteBuffer(command)
            command.startsWith("send-keys ") -> applySendKeys(command)
            else -> null
        }
        if (error != null) {
            return CommandResponse(number = 0L, output = listOf(error), isError = true)
        }
        appliedCommands += command
        return CommandResponse(number = 0L, output = emptyList(), isError = false)
    }

    private fun applySetBuffer(command: String): String? {
        val append = Regex("""^set-buffer -(\S+)""").find(command)
            ?.groupValues?.get(1)?.contains('a') == true
        val name = Regex("""-a?b (\S+)""").find(command)?.groupValues?.get(1)
            ?: return "set-buffer: no buffer name"
        val data = unquoteArgument(command) ?: return "set-buffer: no data"
        val buffer = buffers.getOrPut(name) { StringBuilder() }
        if (!append) buffer.setLength(0)
        buffer.append(data)
        return null
    }

    private fun applyPasteBuffer(command: String): String? {
        val name = Regex("""-b (\S+)""").find(command)?.groupValues?.get(1)
            ?: return "paste-buffer: no buffer name"
        val buffer = buffers[name] ?: return "no buffer $name"
        val flags = Regex("""^paste-buffer ((?:-\S+ )*)""").find(command)?.groupValues?.get(1).orEmpty()
        val paneId = Regex("""-t (\S+)""").find(command)?.groupValues?.get(1) ?: ACTIVE_PANE
        var data = buffer.toString()
        // Real tmux: without -r every LF becomes CR (= Enter = submit).
        if (!flags.contains("r")) data = data.replace('\n', '\r')
        // Real tmux: -p wraps the paste in tmux's OWN bracketed-paste markers.
        if (flags.contains("p")) data = "$PASTE_START$data$PASTE_END"
        if (flags.contains("d")) buffers.remove(name)
        writeToPane(paneId, data)
        return null
    }

    private fun applySendKeys(command: String): String? {
        val paneId = Regex("""-t (\S+)""").find(command)?.groupValues?.get(1) ?: ACTIVE_PANE
        return when {
            command.startsWith("send-keys -H ") -> {
                val hex = command.substringAfter(" $paneId ", "").ifEmpty {
                    command.removePrefix("send-keys -H ")
                }
                val bytes = hex.trim().split(' ').filter { it.isNotBlank() }
                    .map { it.toInt(16).toByte() }.toByteArray()
                writeToPane(paneId, String(bytes, Charsets.UTF_8))
                null
            }
            command.startsWith("send-keys -l ") -> {
                writeToPane(paneId, unquoteArgument(command) ?: return "send-keys -l: no data")
                null
            }
            command.endsWith(" Enter") -> {
                writeToPane(paneId, "\r")
                null
            }
            command.startsWith("send-keys -X ") -> null // copy-mode cancel: no input effect
            else -> null
        }
    }

    /** Whether each pane's receiver is currently inside a bracketed paste. */
    private val inPasteMode: MutableMap<String, Boolean> = mutableMapOf()

    /**
     * The agent's readline-style input box, modelled the way a real bracketed-paste
     * receiver behaves — which matters for staying honest about the marker framing:
     *
     *  - A receiver does NOT nest. It consumes ONE `\e[200~` to enter paste mode
     *    and the next `\e[201~` to leave it; any FURTHER marker seen inside the
     *    paste is ordinary content. So a paste whose markers got doubled (e.g. by
     *    handing tmux `paste-buffer -p` on a buffer we already framed ourselves)
     *    lands a literal `\e[200~` in the user's prompt. Stripping every marker
     *    occurrence instead would hide exactly that.
     *  - A marker can be split across two wire commands (the old `send-keys -H`
     *    chain sent the start marker as its own command), so the mode is per-pane
     *    state, not per-command.
     *  - `\r` submits the box and clears it, matching the `pocketshell-fake-agent`
     *    fixture the connected journey runs against. This is why the paste must
     *    reach tmux with `-r`: without it tmux rewrites every LF to CR and the
     *    fixture tears one prompt into one-per-line.
     */
    private fun writeToPane(paneId: String, data: String) {
        val box = inputBoxes.getOrPut(paneId) { StringBuilder() }
        var index = 0
        while (index < data.length) {
            val inPaste = inPasteMode[paneId] == true
            val marker = if (inPaste) PASTE_END else PASTE_START
            if (data.startsWith(marker, index)) {
                inPasteMode[paneId] = !inPaste
                index += marker.length
                continue
            }
            val ch = data[index]
            if (ch == '\r') {
                submitted.getOrPut(paneId) { mutableListOf() } += box.toString()
                box.setLength(0)
            } else {
                box.append(ch)
            }
            index += 1
        }
    }

    /** Undo the caller's shell single-quoting of the command's `-- '<data>'` argument. */
    private fun unquoteArgument(command: String): String? {
        val start = command.indexOf("-- '")
        if (start < 0) return null
        val body = command.substring(start + 4)
        if (!body.endsWith("'")) return null
        return body.dropLast(1).replace("'\\''", "'")
    }

    private companion object {
        const val ACTIVE_PANE = "%active"
        const val PASTE_START = "\u001B[200~"
        const val PASTE_END = "\u001B[201~"
    }
}
