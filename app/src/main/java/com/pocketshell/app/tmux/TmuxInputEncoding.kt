package com.pocketshell.app.tmux

/**
 * Issue #243: true when [bytes] contains only complete terminal emulator
 * report sequences that should be forwarded to the remote pane as raw
 * bytes. These are local replies to remote terminal queries, not typed
 * user text: OSC color/title reports, DCS capability reports, CSI
 * device/status/window reports, and SGR mouse reports.
 */
internal fun isTerminalGeneratedResponse(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return false
    var index = 0
    while (index < bytes.size) {
        val next = consumeTerminalResponse(bytes, index)
        if (next <= index) return false
        index = next
    }
    return true
}

private fun consumeTerminalResponse(bytes: ByteArray, start: Int): Int {
    if (bytes.getOrNull(start) != ESC) return -1
    return when (bytes.getOrNull(start + 1)) {
        CSI -> consumeCsiTerminalResponse(bytes, start + 2)
        OSC -> consumeOscTerminalResponse(bytes, start + 2)
        DCS -> consumeDcsTerminalResponse(bytes, start + 2)
        else -> -1
    }
}

private fun consumeCsiTerminalResponse(bytes: ByteArray, start: Int): Int {
    var index = start
    val prefix = when (bytes.getOrNull(index)) {
        QUESTION, GREATER_THAN, LESS_THAN -> {
            val value = bytes[index]
            index += 1
            value
        }
        else -> null
    }
    var sawDigit = false
    while (index < bytes.size) {
        val b = bytes[index]
        when {
            isDigitByte(b) -> {
                sawDigit = true
                index += 1
            }
            b == SEMICOLON -> index += 1
            b == DOLLAR -> {
                return if (bytes.getOrNull(index + 1) == LOWER_Y && sawDigit) index + 2 else -1
            }
            isCsiTerminalResponseFinal(prefix, b, sawDigit) -> return index + 1
            else -> return -1
        }
    }
    return -1
}

private fun isCsiTerminalResponseFinal(prefix: Byte?, final: Byte, sawDigit: Boolean): Boolean =
    when (final) {
        LOWER_C -> sawDigit && (prefix == QUESTION || prefix == GREATER_THAN)
        LOWER_N, UPPER_R, LOWER_T -> sawDigit && prefix == null
        UPPER_M, LOWER_M -> sawDigit && prefix == LESS_THAN
        else -> false
    }

private fun consumeOscTerminalResponse(bytes: ByteArray, start: Int): Int {
    val end = findStringTerminator(bytes, start)
    if (end <= start) return -1
    return if (isKnownTerminalOscResponse(bytes, start, end)) {
        terminatorEnd(bytes, end)
    } else {
        -1
    }
}

private fun isKnownTerminalOscResponse(bytes: ByteArray, start: Int, end: Int): Boolean {
    if (bytes.getOrNull(start) == UPPER_L || bytes.getOrNull(start) == LOWER_L) return true

    var index = start
    var sawDigit = false
    while (index < end && isDigitByte(bytes[index])) {
        sawDigit = true
        index += 1
    }
    if (!sawDigit || bytes.getOrNull(index) != SEMICOLON) return false
    index += 1

    val rgbPrefix = byteArrayOf(LOWER_R, LOWER_G, LOWER_B, COLON)
    if (index + rgbPrefix.size > end) return false
    for (offset in rgbPrefix.indices) {
        if (bytes[index + offset] != rgbPrefix[offset]) return false
    }
    return true
}

private fun consumeDcsTerminalResponse(bytes: ByteArray, start: Int): Int {
    val end = findStringTerminator(bytes, start)
    if (end <= start) return -1
    return if (isKnownTerminalDcsResponse(bytes, start, end)) {
        terminatorEnd(bytes, end)
    } else {
        -1
    }
}

private fun isKnownTerminalDcsResponse(bytes: ByteArray, start: Int, end: Int): Boolean {
    if (end - start < 3) return false
    return (bytes[start] == DIGIT_ONE && bytes[start + 1] == DOLLAR && bytes[start + 2] == LOWER_R) ||
        ((bytes[start] == DIGIT_ZERO || bytes[start] == DIGIT_ONE) &&
            bytes[start + 1] == PLUS &&
            bytes[start + 2] == LOWER_R)
}

private fun findStringTerminator(bytes: ByteArray, start: Int): Int {
    var index = start
    while (index < bytes.size) {
        when (bytes[index]) {
            BEL -> return index
            ESC -> {
                if (bytes.getOrNull(index + 1) == BACKSLASH) return index
            }
        }
        index += 1
    }
    return -1
}

private fun terminatorEnd(bytes: ByteArray, terminatorStart: Int): Int =
    if (bytes[terminatorStart] == BEL) terminatorStart + 1 else terminatorStart + 2

private fun isDigitByte(byte: Byte): Boolean = byte >= DIGIT_ZERO && byte <= DIGIT_NINE

private val ESC: Byte = 0x1B
private val BEL: Byte = 0x07
private val CSI: Byte = '['.code.toByte()
private val OSC: Byte = ']'.code.toByte()
private val DCS: Byte = 'P'.code.toByte()
private val BACKSLASH: Byte = '\\'.code.toByte()
private val QUESTION: Byte = '?'.code.toByte()
private val GREATER_THAN: Byte = '>'.code.toByte()
private val LESS_THAN: Byte = '<'.code.toByte()
private val SEMICOLON: Byte = ';'.code.toByte()
private val COLON: Byte = ':'.code.toByte()
private val DOLLAR: Byte = '$'.code.toByte()
private val PLUS: Byte = '+'.code.toByte()
private val DIGIT_ZERO: Byte = '0'.code.toByte()
private val DIGIT_ONE: Byte = '1'.code.toByte()
private val DIGIT_NINE: Byte = '9'.code.toByte()
private val LOWER_B: Byte = 'b'.code.toByte()
private val LOWER_C: Byte = 'c'.code.toByte()
private val LOWER_G: Byte = 'g'.code.toByte()
private val LOWER_L: Byte = 'l'.code.toByte()
private val LOWER_M: Byte = 'm'.code.toByte()
private val LOWER_N: Byte = 'n'.code.toByte()
private val LOWER_R: Byte = 'r'.code.toByte()
private val LOWER_T: Byte = 't'.code.toByte()
private val LOWER_Y: Byte = 'y'.code.toByte()
private val UPPER_L: Byte = 'L'.code.toByte()
private val UPPER_M: Byte = 'M'.code.toByte()
private val UPPER_R: Byte = 'R'.code.toByte()
