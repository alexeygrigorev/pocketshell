package com.pocketshell.app.tmux

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #959 recurrence: keep the connected proof diagnostic-first until its
 * unchanged-production evidence selects one responsible lifecycle branch.
 */
class BackgroundGraceReconnectDiagnosticSourceGuardTest {

    @Test
    fun independentEvidenceIsCapturedImmediatelyAfterRealTerminalSessionWrite() {
        val source = journeySource()
        val method = source.substringBetween(
            start = "fun postGraceReattachLeavesTerminalLiveWithFreshInputEcho()",
            end = "private fun forcePreservePaneRuntimeOnBackgroundTeardown()",
        )
        val send = method.indexOf("sendLineToActivePane(\"echo \$postToken\")")
        val immediate = method.indexOf("phase = \"immediate-post-send\"")
        val visibleWait = method.indexOf(
            "waitForVisibleTerminal(\"post-grace fresh input echo\")",
        )

        assertTrue("the proof must write through TerminalView.currentSession", send >= 0)
        assertTrue(
            "independent evidence must be captured immediately after send and before " +
                "the unchanged visible-terminal wait",
            immediate > send && immediate < visibleWait,
        )
        assertTrue(
            "a timeout must still preserve terminal evidence and a single summary",
            method.contains("phase = if (visibleWaitFailure == null)") &&
                method.contains("writePostGraceDiagnosticSummary(") &&
                method.contains("throw AssertionError("),
        )
    }

    @Test
    fun serverCaptureRequiresTheExactPaneIdentityWithoutFallback() {
        val source = journeySource()
        val evidence = source.substringBetween(
            start = "private suspend fun capturePostGraceEvidence(",
            end = "private fun snapshotPostGraceIdentity(",
        ).compact()
        val capture = source.substringBetween(
            start = "private suspend fun capturePaneThroughIndependentSsh(",
            end = "private fun relevantPaneInputEvents()",
        ).compact()

        assertTrue(
            "the evidence path must hard-fail without an exact pane id and pass only " +
                "that non-null id to the independent capture",
            evidence.contains("valexactPaneId=requireNotNull(identity.paneId)") &&
                evidence.contains("capturePaneThroughIndependentSsh(exactPaneId)") &&
                !evidence.contains("capturePaneThroughIndependentSsh(identity.paneId)"),
        )
        assertTrue(
            "the independent capture must accept a non-null pane id and target it " +
                "directly, with no session-name or nullable fallback",
            capture.contains(
                "capturePaneThroughIndependentSsh(paneId:String):ServerPaneCapture",
            ) &&
                capture.contains(
                    "valcommand=\"tmuxcapture-pane-p-S--t${'$'}{shellQuote(paneId)}\"",
                ) &&
                !capture.contains("?:SESSION_NAME") &&
                !capture.contains("paneId:String?"),
        )
        assertTrue(
            "server capture must open its own SSH connection, not use the app clientRef",
            capture.contains("SshConnection.connect("),
        )
    }

    @Test
    fun captureValidityRejectsConnectionExecNonzeroAndUnusableEvidence() {
        val source = journeySource()
        val cases = listOf(
            CaptureCase("CONNECTION", 0, "token") to "CONNECTION_FAILED",
            CaptureCase("EXECUTION", 0, "token") to "EXEC_EXCEPTION",
            CaptureCase(null, null, "") to "UNUSABLE_RESULT",
            CaptureCase(null, 23, "") to "NONZERO_EXIT",
            CaptureCase(null, 0, "") to "VERIFIED_EXIT_ZERO_EMPTY",
            CaptureCase(null, 0, "pane text") to "VERIFIED_EXIT_ZERO_WITH_OUTPUT",
        )

        cases.forEach { (input, expected) ->
            val actual = evaluateCaptureValidityFromSource(source, input)
            assertTrue(
                "capture validity truth-table mismatch for $input: expected=$expected actual=$actual",
                actual == expected,
            )
        }

        val validityEnum = source.substringBetween(
            start = "private enum class ServerPaneCaptureValidity(",
            end = "private enum class PostGraceClassification(",
        )
        val authority = Regex("""(\w+)\("[^"]+",\s*(true|false)\)""")
            .findAll(validityEnum)
            .associate { it.groupValues[1] to it.groupValues[2].toBoolean() }
        val expectedAuthority = mapOf(
            "VERIFIED_EXIT_ZERO_WITH_OUTPUT" to true,
            "VERIFIED_EXIT_ZERO_EMPTY" to true,
            "CONNECTION_FAILED" to false,
            "EXEC_EXCEPTION" to false,
            "UNUSABLE_RESULT" to false,
            "NONZERO_EXIT" to false,
        )
        assertTrue(
            "only exact-target exit-zero captures may be authoritative: $authority",
            authority == expectedAuthority,
        )
    }

    @Test
    fun classifierHasExactTruthTablePrecedenceIncludingUnverifiedCapture() {
        val source = journeySource()
        val cases = listOf(
            ClassifierCase(false, true, true, 5, 0) to "UNVERIFIED_CAPTURE_FAILED",
            ClassifierCase(true, true, true, 0, 2) to "TOKEN_SERVER_SIDE_AND_VIEWPORT",
            ClassifierCase(true, true, false, 0, 2) to "TOKEN_SERVER_SIDE_VIEWPORT_ABSENT",
            ClassifierCase(true, false, false, 0, 2) to "TOKEN_ABSENT_NO_PANE_INPUT_BATCH",
            ClassifierCase(true, false, false, 1, 2) to
                "TOKEN_ABSENT_BATCH_SEND_FAILED_OR_SUPERSEDED",
            ClassifierCase(true, false, false, 1, 0) to
                "TOKEN_ABSENT_BATCH_NO_FAILURE_WRONG_TARGET_OR_FALSE_SUCCESS",
        )

        cases.forEach { (input, expected) ->
            val actual = evaluateClassifierFromSource(source, input)
            assertTrue(
                "classifier truth-table mismatch for $input: expected=$expected actual=$actual",
                actual == expected,
            )
        }
    }

    private fun journeySource(): String {
        val relative =
            "src/androidTest/java/com/pocketshell/app/proof/BackgroundGraceReconnectE2eTest.kt"
        val candidates = listOf(File("app/$relative"), File(relative))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun String.substringBetween(start: String, end: String): String {
        val startIndex = indexOf(start)
        check(startIndex >= 0) { "$start not found" }
        val endIndex = indexOf(end, startIndex)
        check(endIndex >= 0) { "$end not found after $start" }
        return substring(startIndex, endIndex)
    }

    private fun evaluateCaptureValidityFromSource(source: String, input: CaptureCase): String {
        val body = source.substringBetween(
            start = "private fun ServerPaneCapture.validity()",
            end = "private fun relevantPaneInputEvents()",
        )
        val branches = Regex(
            """(?s)(failureKind\s*==\s*ServerPaneCaptureFailure\.(?:CONNECTION|EXECUTION)|""" +
                """exitCode\s*==\s*null|exitCode\s*!=\s*0|stdout\.isEmpty\(\)|else)\s*->\s*""" +
                """ServerPaneCaptureValidity\.(\w+)""",
        ).findAll(body).map { it.groupValues[1].compact() to it.groupValues[2] }.toList()
        check(branches.size == 6) { "expected six capture-validity branches, got $branches" }
        return branches.first { (condition, _) ->
            when (condition) {
                "failureKind==ServerPaneCaptureFailure.CONNECTION" ->
                    input.failureKind == "CONNECTION"
                "failureKind==ServerPaneCaptureFailure.EXECUTION" ->
                    input.failureKind == "EXECUTION"
                "exitCode==null" -> input.exitCode == null
                "exitCode!=0" -> input.exitCode != 0
                "stdout.isEmpty()" -> input.stdout.isEmpty()
                "else" -> true
                else -> error("unknown capture-validity condition $condition")
            }
        }.second
    }

    private fun evaluateClassifierFromSource(source: String, input: ClassifierCase): String {
        val body = source.substringBetween(
            start = "private fun classifyPostGraceEvidence(",
            end = "private fun writePostGraceDiagnosticSummary(",
        )
        val branches = Regex(
            """(?s)(!captureValidity\.authoritative|serverHasToken\s*&&\s*viewportHasToken|""" +
                """serverHasToken|paneInputBatchCount\s*==\s*0|sendFailureCount\s*>\s*0|else)""" +
                """\s*->\s*PostGraceClassification\.(\w+)""",
        ).findAll(body).map { it.groupValues[1].compact() to it.groupValues[2] }.toList()
        check(branches.size == 6) { "expected six classifier branches, got $branches" }
        return branches.first { (condition, _) ->
            when (condition) {
                "!captureValidity.authoritative" -> !input.captureAuthoritative
                "serverHasToken&&viewportHasToken" ->
                    input.serverHasToken && input.viewportHasToken
                "serverHasToken" -> input.serverHasToken
                "paneInputBatchCount==0" -> input.paneInputBatchCount == 0
                "sendFailureCount>0" -> input.sendFailureCount > 0
                "else" -> true
                else -> error("unknown classifier condition $condition")
            }
        }.second
    }

    private fun String.compact(): String = replace(Regex("\\s+"), "")

    private data class CaptureCase(
        val failureKind: String?,
        val exitCode: Int?,
        val stdout: String,
    )

    private data class ClassifierCase(
        val captureAuthoritative: Boolean,
        val serverHasToken: Boolean,
        val viewportHasToken: Boolean,
        val paneInputBatchCount: Int,
        val sendFailureCount: Int,
    )
}
