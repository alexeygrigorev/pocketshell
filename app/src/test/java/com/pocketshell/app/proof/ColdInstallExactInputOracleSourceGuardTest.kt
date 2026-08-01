package com.pocketshell.app.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #1871 source guard for the false-green journey-oracle class.
 *
 * The real behavioral proof remains `ColdInstallE2eTest` on emulator + Docker.
 * This both-variant Unit guard makes its receiver oracle load-bearing between
 * journey runs: it checks the oracle contracts and structurally verifies that
 * the cold-install test invokes the helper with values from that same run.
 */
class ColdInstallExactInputOracleSourceGuardTest {

    @Test
    fun `cold install invokes its exact receiver oracle with this run values`() {
        val path = COLD_INSTALL_PATH
        val source = source(path)

        assertEquals(
            "the cold-install journey must keep its exact receiver oracle attached",
            emptyList<String>(),
            exactOracleViolations(source),
        )
    }

    @Test
    fun `retained helper text cannot satisfy the gate when its invocation is disconnected`() {
        val source = source(COLD_INSTALL_PATH)
        val disconnected = disconnectOracleInvocation(source)

        assertTrue(
            "the regression mutation must retain the complete helper body",
            exactOracleContractViolations(disconnected).isEmpty(),
        )
        assertTrue(
            "deleting only the journey invocation must fail the attachment gate",
            exactOracleViolations(disconnected).any { it.contains("does not invoke") },
        )
        assertEquals(
            "G2 must reject a marker-only journey even when a dead oracle helper remains",
            listOf(COLD_INSTALL_PATH),
            g2Offenders(mapOf(COLD_INSTALL_PATH to disconnected)),
        )
    }

    @Test
    fun `no direct commitText journey repeats the combined-command marker-only oracle`() {
        val root = projectRoot()
        val sources = File(root, "app/src/androidTest")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path to it.readText() }

        assertEquals(
            "a journey commits a command variable directly but accepts only its marker " +
                "substring; terminal echo can satisfy that oracle without execution",
            emptyList<String>(),
            g2Offenders(sources),
        )
    }

    private fun exactOracleViolations(source: String): List<String> = buildList {
        addAll(exactOracleContractViolations(source))
        val journey = function(source, COLD_INSTALL_TEST)
        if (journey == null) {
            add("missing $COLD_INSTALL_TEST")
        } else if (!journey.hasExactOracleInvocation()) {
            add(
                "$COLD_INSTALL_TEST does not invoke $ORACLE_HELPER with " +
                    "key, sessionName, marker, and typedCommand from its run",
            )
        }
    }

    private fun exactOracleContractViolations(source: String): List<String> = buildList {
        val helper = function(source, ORACLE_HELPER)
        if (helper == null) {
            add("missing $ORACLE_HELPER")
            return@buildList
        }
        val body = helper.raw
        if (!Regex("line\\s*\\.\\s*trim\\s*\\(\\s*\\)\\s*==\\s*marker").containsMatchIn(body)) {
            add("oracle must require a pane line equal to marker")
        }
        if (!Regex(
                "exactEcho\\s*\\.\\s*trimEnd\\s*\\(\\s*\\)\\s*" +
                    "\\.\\s*endsWith\\s*\\(\\s*typedCommand\\s*\\)",
            ).containsMatchIn(body)
        ) {
            add("oracle must require the marker-scoped echo to end with typedCommand")
        }
        if (!Regex(
                "\"\\[200~\"\\s*!in\\s*exactEcho\\s*&&\\s*" +
                    "\"\\[201~\"\\s*!in\\s*exactEcho",
            ).containsMatchIn(body)
        ) {
            add("oracle must reject leaked bracketed-paste framing")
        }
        if (!helper.calls("capturePane").any { call ->
                call.positionalArguments == listOf("observer", "sessionName")
            }
        ) {
            add("oracle must capture the receiving pane through its observer session")
        }

        val capture = function(source, "capturePane")
        if (capture == null ||
            !Regex("tmux\\s+capture-pane\\s+-p\\s+-J\\s+-t").containsMatchIn(capture.raw)
        ) {
            add("pane observer must execute tmux capture-pane -p -J -t")
        }
    }

    private fun g2Offenders(sources: Map<String, String>): List<String> = sources.flatMap {
            (path, source) ->
        functions(source)
            .map { it.block }
            .filter { it.hasCombinedCommandMarkerOnlyGesture() }
            .filterNot { it.hasExactOracleInvocation() }
            .map { path }
    }.distinct().sorted()

    private fun FunctionBlock.hasCombinedCommandMarkerOnlyGesture(): Boolean =
        Regex("\\bcommitText\\s*\\(\\s*command\\s*,\\s*1\\s*\\)").containsMatchIn(masked) &&
            Regex(
                "\\bcontainsWrapTolerant\\s*\\(\\s*lastVisible\\s*,\\s*marker\\b",
            ).containsMatchIn(masked)

    private fun FunctionBlock.hasExactOracleInvocation(): Boolean =
        calls(ORACLE_HELPER).any { call ->
            call.namedArguments == mapOf(
                "key" to "key",
                "sessionName" to "sessionName",
                "marker" to "marker",
                "typedCommand" to "typedCommand",
            )
        }

    /** Remove only the live call; the helper and every contract string remain. */
    private fun disconnectOracleInvocation(source: String): String {
        val journey = requireNotNull(function(source, COLD_INSTALL_TEST))
        val call = journey.calls(ORACLE_HELPER).single()
        return source.replaceRange(call.startOffset, call.endOffsetExclusive, "Unit")
    }

    private inner class FunctionBlock(
        val raw: String,
        val masked: String,
        val bodyStartOffset: Int,
    ) {
        fun calls(name: String): List<Call> {
            val matcher = Regex("\\b${Regex.escape(name)}\\s*\\(").findAll(masked)
            return matcher.map { match ->
                val open = masked.indexOf('(', match.range.first)
                val close = matchingDelimiter(masked, open, '(', ')')
                val arguments = splitTopLevel(masked.substring(open + 1, close))
                val named = arguments.mapNotNull { argument ->
                    val equals = topLevelEquals(argument)
                    if (equals < 0) null else {
                        argument.substring(0, equals).trim() to
                            argument.substring(equals + 1).trim()
                    }
                }.toMap()
                Call(
                    startOffset = bodyStartOffset + match.range.first,
                    endOffsetExclusive = bodyStartOffset + close + 1,
                    namedArguments = named,
                    positionalArguments = arguments.filter { topLevelEquals(it) < 0 }
                        .map(String::trim),
                )
            }.toList()
        }
    }

    private data class Call(
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val namedArguments: Map<String, String>,
        val positionalArguments: List<String>,
    )

    private fun function(source: String, name: String): FunctionBlock? =
        functions(source).singleOrNull { it.name == name }?.block

    private data class NamedFunction(val name: String, val block: FunctionBlock)

    private fun functions(source: String): List<NamedFunction> {
        val masked = maskCommentsAndStrings(source)
        return Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
            .findAll(masked)
            .mapNotNull { match ->
                val name = match.groupValues[1]
                val parameterOpen = masked.indexOf('(', match.range.first)
                val parameterClose = matchingDelimiter(masked, parameterOpen, '(', ')')
                val bodyOpen = masked.indexOf('{', parameterClose + 1)
                if (bodyOpen < 0) return@mapNotNull null
                val bodyClose = matchingDelimiter(masked, bodyOpen, '{', '}')
                val start = bodyOpen + 1
                NamedFunction(
                    name,
                    FunctionBlock(
                        raw = source.substring(start, bodyClose),
                        masked = masked.substring(start, bodyClose),
                        bodyStartOffset = start,
                    ),
                )
            }.toList()
    }

    private fun matchingDelimiter(text: String, open: Int, left: Char, right: Char): Int {
        require(open >= 0 && text[open] == left)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                left -> depth++
                right -> if (--depth == 0) return index
            }
        }
        error("unclosed $left at $open")
    }

    private fun splitTopLevel(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var round = 0
        var square = 0
        var curly = 0
        value.forEachIndexed { index, char ->
            when (char) {
                '(' -> round++
                ')' -> round--
                '[' -> square++
                ']' -> square--
                '{' -> curly++
                '}' -> curly--
                ',' -> if (round == 0 && square == 0 && curly == 0) {
                    result += value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        val tail = value.substring(start).trim()
        if (tail.isNotEmpty()) result += tail
        return result
    }

    private fun topLevelEquals(value: String): Int {
        var round = 0
        var square = 0
        var curly = 0
        value.forEachIndexed { index, char ->
            when (char) {
                '(' -> round++
                ')' -> round--
                '[' -> square++
                ']' -> square--
                '{' -> curly++
                '}' -> curly--
                '=' -> if (round == 0 && square == 0 && curly == 0) return index
            }
        }
        return -1
    }

    /**
     * Preserve offsets and syntax delimiters while blanking comments and
     * strings, so call/function parsing cannot match documentation or literals.
     */
    private fun maskCommentsAndStrings(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        while (index < chars.size) {
            when {
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '/' -> {
                    while (index < chars.size && chars[index] != '\n') chars[index++] = ' '
                }
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '*' -> {
                    chars[index++] = ' '
                    chars[index++] = ' '
                    var depth = 1
                    while (index < chars.size && depth > 0) {
                        when {
                            index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '*' -> {
                                chars[index++] = ' '
                                chars[index++] = ' '
                                depth++
                            }
                            index + 1 < chars.size && chars[index] == '*' && chars[index + 1] == '/' -> {
                                chars[index++] = ' '
                                chars[index++] = ' '
                                depth--
                            }
                            chars[index] != '\n' -> chars[index++] = ' '
                            else -> index++
                        }
                    }
                }
                chars[index] == '"' -> {
                    val triple = index + 2 < chars.size &&
                        chars[index + 1] == '"' && chars[index + 2] == '"'
                    val quoteCount = if (triple) 3 else 1
                    repeat(quoteCount) { chars[index++] = ' ' }
                    while (index < chars.size) {
                        if (triple && index + 2 < chars.size &&
                            chars[index] == '"' && chars[index + 1] == '"' &&
                            chars[index + 2] == '"'
                        ) {
                            repeat(3) { chars[index++] = ' ' }
                            break
                        }
                        if (!triple && chars[index] == '"') {
                            chars[index++] = ' '
                            break
                        }
                        if (!triple && chars[index] == '\\' && index + 1 < chars.size) {
                            chars[index++] = ' '
                            if (chars[index] != '\n') chars[index] = ' '
                            index++
                        } else if (chars[index] != '\n') {
                            chars[index++] = ' '
                        } else {
                            index++
                        }
                    }
                }
                chars[index] == '\'' -> {
                    chars[index++] = ' '
                    while (index < chars.size) {
                        if (chars[index] == '\\' && index + 1 < chars.size) {
                            chars[index++] = ' '
                            chars[index++] = ' '
                        } else if (chars[index] == '\'') {
                            chars[index++] = ' '
                            break
                        } else if (chars[index] != '\n') {
                            chars[index++] = ' '
                        } else {
                            index++
                        }
                    }
                }
                else -> index++
            }
        }
        return String(chars)
    }

    private fun source(path: String): String = File(projectRoot(), path).readText()

    private fun projectRoot(): File {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val COLD_INSTALL_PATH =
            "app/src/androidTest/java/com/pocketshell/app/proof/ColdInstallE2eTest.kt"
        const val COLD_INSTALL_TEST =
            "coldInstallJourney_addsHost_attachesTmuxSession_runsCommand_andDefaultsAreSane"
        const val ORACLE_HELPER = "assertExactCommandReceivedAndRan"
    }
}
