package com.pocketshell.app.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #470 recurrence guard.
 *
 * Every connected caller of [waitForSessionInPicker] is exposed to the same
 * expanded/no-error/no-row stall. A nullable callback let individual journeys
 * silently disable the bounded watchdog, so this test inventories the whole
 * androidTest tree and pins both halves of the recovery route:
 *
 *  1. every direct shared-helper call supplies the bounded standard re-poke;
 *  2. that re-poke receives the caller's current `hostRowTag`, not a stale tag
 *     or a terminal-screen Back route.
 *
 * The test is in the ordinary JVM source set, so both Debug and Release unit
 * gates execute it without needing an emulator.
 */
class SessionPickerRepokeContractTest {

    @Test
    fun everyPickerWaitEnablesTheBoundedStandardRepoke() {
        val calls = pickerCalls()
        assertEquals(
            "Update the reviewed caller inventory when adding/removing a picker wait",
            EXPECTED_CALL_COUNTS,
            calls.groupingBy { it.relativePath }.eachCount().toSortedMap(),
        )

        val missing = calls.filterNot { call ->
            ON_REPOKE_BLOCK.containsMatchIn(call.maskedCall) &&
                STANDARD_REPOKE_CALL.containsMatchIn(call.maskedCall)
        }
        assertTrue(
            "Every waitForSessionInPicker caller must enable the bounded " +
                "repokeSessionPickerFromHostRow recovery; missing at " +
                missing.joinToString { "${it.relativePath}:${it.line}" },
            missing.isEmpty(),
        )
    }

    @Test
    fun everyRepokeRoutesThroughTheCallersExactHostRow() {
        // Deliberately inspect only callbacks that invoke the standard helper.
        // This keeps the wrong-row mutation selective: a missing callback is
        // owned by the first test, while a stale/wrong row fails this one.
        val wrongRows = pickerCalls()
            .filter { STANDARD_REPOKE_CALL.containsMatchIn(it.maskedCall) }
            .filterNot { EXACT_HOST_ROW_ARGUMENT.containsMatchIn(it.maskedCall) }

        assertTrue(
            "Every picker re-poke must pass the current hostRowTag exactly; " +
                "wrong/stale route at " +
                wrongRows.joinToString { "${it.relativePath}:${it.line}" },
            wrongRows.isEmpty(),
        )
    }

    @Test
    fun sharedRepokeContractStaysRequiredAndBounded() {
        val source = sourceFile(SIGNALS_PATH).readText()
        val masked = maskNonCode(source)

        assertTrue(
            "waitForSessionInPicker must require a non-null onRepoke callback",
            Regex("""onRepoke\s*:\s*\(\s*\)\s*->\s*Unit\s*,""")
                .containsMatchIn(masked),
        )
        assertTrue(
            "repokeSessionPickerFromHostRow must retain the bounded navigation default",
            Regex(
                """fun\s+repokeSessionPickerFromHostRow\s*\([\s\S]*?""" +
                    """timeoutMs\s*:\s*Long\s*=\s*""" +
                    """SESSION_PICKER_REPOKE_NAVIGATION_TIMEOUT_MS""",
            ).containsMatchIn(masked),
        )
        assertTrue(
            "the re-poke must hard-bound and verify Back, host row, and reopen",
            listOf(
                "folder_detail_back_reachable",
                "host_row_reachable",
                "folder_detail_reopened",
            ).all(source::contains),
        )
    }

    private fun pickerCalls(): List<PickerCall> {
        val root = projectRoot()
        val androidTestRoot = File(root, "app/src/androidTest/java")
        require(androidTestRoot.isDirectory) { "Missing $androidTestRoot" }

        return androidTestRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> callsIn(file, root).asSequence() }
            .toList()
            .sortedWith(compareBy(PickerCall::relativePath, PickerCall::line))
    }

    private fun callsIn(file: File, root: File): List<PickerCall> {
        val source = file.readText()
        val masked = maskNonCode(source)
        val calls = mutableListOf<PickerCall>()
        CALL_START.findAll(masked).forEach { match ->
            val prefix = masked.substring(maxOf(0, match.range.first - 32), match.range.first)
            if (FUNCTION_DECLARATION_SUFFIX.containsMatchIn(prefix)) return@forEach

            val openParen = masked.indexOf('(', startIndex = match.range.first)
            val closeParen = matchingParen(masked, openParen)
            val call = masked.substring(match.range.first, closeParen + 1)

            calls += PickerCall(
                relativePath = file.relativeTo(root).invariantSeparatorsPath,
                line = masked.take(match.range.first).count { it == '\n' } + 1,
                maskedCall = call,
            )
        }
        return calls
    }

    private fun matchingParen(source: String, openParen: Int): Int {
        require(openParen >= 0) { "call has no opening parenthesis" }
        var depth = 0
        for (index in openParen until source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        error("Unclosed waitForSessionInPicker call at offset $openParen")
    }

    /** Masks strings/comments while preserving offsets and newlines. */
    private fun maskNonCode(source: String): String {
        val result = source.toCharArray()
        var index = 0
        var blockDepth = 0
        var state = LexState.CODE

        fun mask(at: Int) {
            if (result[at] != '\n' && result[at] != '\r') result[at] = ' '
        }

        while (index < source.length) {
            when (state) {
                LexState.CODE -> when {
                    source.startsWith("//", index) -> {
                        mask(index)
                        mask(index + 1)
                        index += 2
                        state = LexState.LINE_COMMENT
                    }
                    source.startsWith("/*", index) -> {
                        mask(index)
                        mask(index + 1)
                        index += 2
                        blockDepth = 1
                        state = LexState.BLOCK_COMMENT
                    }
                    source.startsWith("\"\"\"", index) -> {
                        repeat(3) { mask(index + it) }
                        index += 3
                        state = LexState.TRIPLE_STRING
                    }
                    source[index] == '"' -> {
                        mask(index++)
                        state = LexState.STRING
                    }
                    source[index] == '\'' -> {
                        mask(index++)
                        state = LexState.CHAR
                    }
                    else -> index += 1
                }
                LexState.LINE_COMMENT -> {
                    if (source[index] == '\n') {
                        state = LexState.CODE
                    } else {
                        mask(index)
                    }
                    index += 1
                }
                LexState.BLOCK_COMMENT -> when {
                    source.startsWith("/*", index) -> {
                        mask(index)
                        mask(index + 1)
                        index += 2
                        blockDepth += 1
                    }
                    source.startsWith("*/", index) -> {
                        mask(index)
                        mask(index + 1)
                        index += 2
                        blockDepth -= 1
                        if (blockDepth == 0) state = LexState.CODE
                    }
                    else -> {
                        mask(index)
                        index += 1
                    }
                }
                LexState.STRING, LexState.CHAR -> {
                    val terminator = if (state == LexState.STRING) '"' else '\''
                    if (source[index] == '\\' && index + 1 < source.length) {
                        mask(index)
                        mask(index + 1)
                        index += 2
                    } else {
                        val done = source[index] == terminator
                        mask(index)
                        index += 1
                        if (done) state = LexState.CODE
                    }
                }
                LexState.TRIPLE_STRING -> {
                    if (source.startsWith("\"\"\"", index)) {
                        repeat(3) { mask(index + it) }
                        index += 3
                        state = LexState.CODE
                    } else {
                        mask(index)
                        index += 1
                    }
                }
            }
        }
        return result.concatToString()
    }

    private fun projectRoot(): File {
        System.getenv(ROOT_OVERRIDE_ENV)?.takeIf(String::isNotBlank)?.let { override ->
            return File(override).canonicalFile
        }
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(cursor, "settings.gradle.kts").isFile) return cursor
            cursor = cursor.parentFile ?: break
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }

    private fun sourceFile(relativePath: String): File = File(projectRoot(), relativePath)

    private data class PickerCall(
        val relativePath: String,
        val line: Int,
        val maskedCall: String,
    )

    private enum class LexState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        TRIPLE_STRING,
        CHAR,
    }

    companion object {
        private const val ROOT_OVERRIDE_ENV = "POCKETSHELL_SESSION_PICKER_CONTRACT_ROOT"
        private const val SIGNALS_PATH =
            "app/src/androidTest/java/com/pocketshell/app/proof/signals/SessionPickerSignals.kt"

        private val EXPECTED_CALL_COUNTS = sortedMapOf(
            "app/src/androidTest/java/com/pocketshell/app/proof/BackThenOpenSecondSessionReusesWarmLeaseE2eTest.kt" to 3,
            "app/src/androidTest/java/com/pocketshell/app/proof/ComposerAlwaysPresentSwitchJourneyE2eTest.kt" to 2,
            "app/src/androidTest/java/com/pocketshell/app/proof/Issue1206PrewarmEmptyCaptureSeedRetryJourneyE2eTest.kt" to 1,
            "app/src/androidTest/java/com/pocketshell/app/proof/Issue1831BackFromRestoredSessionJourneyE2eTest.kt" to 1,
            "app/src/androidTest/java/com/pocketshell/app/proof/MultiSessionSwitchJourneyE2eTest.kt" to 3,
            "app/src/androidTest/java/com/pocketshell/app/proof/ProjectSwitcherDropdownE2eTest.kt" to 1,
            "app/src/androidTest/java/com/pocketshell/app/proof/SwitchStaleCaptureSessionBodyJourneyE2eTest.kt" to 3,
            "app/src/androidTest/java/com/pocketshell/app/proof/SystemBackForegroundE2eTest.kt" to 1,
            "app/src/androidTest/java/com/pocketshell/app/proof/TmuxKeyBarCtrlComboE2eTest.kt" to 1,
            "app/src/androidTest/java/com/pocketshell/app/proof/TmuxSessionSwitchE2eTest.kt" to 1,
            "app/src/androidTest/java/com/pocketshell/app/proof/TmuxSessionSwitchSameHostReusesSshE2eTest.kt" to 1,
            "app/src/androidTest/java/com/pocketshell/app/tmux/Issue887TerminalFixedUnderImeE2eTest.kt" to 1,
            "app/src/androidTest/java/com/pocketshell/app/tmux/TmuxAttachPrefillDockerTest.kt" to 1,
        )
        private val CALL_START = Regex("""\bwaitForSessionInPicker\s*\(""")
        private val FUNCTION_DECLARATION_SUFFIX = Regex("""\bfun\s+$""")
        private val ON_REPOKE_BLOCK = Regex("""\bonRepoke\s*=\s*\{""")
        private val STANDARD_REPOKE_CALL =
            Regex("""\brepokeSessionPickerFromHostRow\s*\(""")
        private val EXACT_HOST_ROW_ARGUMENT =
            Regex("""\bhostRowTag\s*=\s*hostRowTag\b""")
    }
}
