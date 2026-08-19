package com.pocketshell.app.composer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2139 — audit + conversion pins for real-system-IME dependence.
 *
 * CI flakes because gating journeys wait on the emulator's soft keyboard. The
 * #780 model injects a synthetic `Type.ime()` inset and hard-fails when that
 * state cannot be injected. This JVM guard is the per-AC test:
 *
 *  1. enumerate every androidTest that still raises / waits for a real IME;
 *  2. the converted Saturated class must not be on that list;
 *  3. remaining genuine real-IME journeys must name the environmental cause;
 *  4. #2126's class is already synthetic and is not re-folded here.
 */
class Issue2139RealImeDependenceAuditTest {

    @Test
    fun auditEnumeratesEveryAndroidTestThatRaisesOrWaitsOnRealSystemIme() {
        val dependents = scanRealImeRaiseSources()
        val names = dependents.map { it.name }.toSet()

        REMAINING_GENUINE_GATED.forEach { name ->
            assertTrue(
                "gated real-IME journey disappeared from the audit: $name. " +
                    "If it was converted to #780, remove it from REMAINING_GENUINE_GATED.",
                name in names,
            )
        }
        REMAINING_NON_GATED.forEach { name ->
            assertTrue(
                "catalogued leftover real-IME androidTest disappeared: $name. " +
                    "If it was converted or deleted, remove it from REMAINING_NON_GATED.",
                name in names,
            )
        }
        CONVERTED_MUST_NOT_RAISE.forEach { name ->
            assertFalse(
                "$name still raises or waits on the real system IME. " +
                    "Issue #2139 converts load-bearing IME waits to the #780 " +
                    "synthetic Type.ime() inset.",
                name in names,
            )
        }
        assertFalse(
            "OutboundExactlyOnceAcrossFlapE2eTest is not IME-dependent " +
                "(#2173 folded here, then re-examined: the sendsBefore=0 " +
                "signature is a tap/transport diagnosis, not a real-IME wait).",
            "OutboundExactlyOnceAcrossFlapE2eTest.kt" in names,
        )

        val unexpected = names - REMAINING_GENUINE_GATED - REMAINING_NON_GATED
        assertEquals(
            "New androidTest(s) wait on the real system IME becoming visible. " +
                "Convert them to Type.ime() (#780) or, if they genuinely must " +
                "exercise the real keyboard, add them to REMAINING_GENUINE_GATED " +
                "with an environmental failure message (AC3).",
            emptySet<String>(),
            unexpected,
        )
    }

    @Test
    fun saturatedImeAnchorJourneyNoLongerRaisesRealSystemIme() {
        val src = locateAndroidTest(
            "app/src/androidTest/java/com/pocketshell/app/composer/" +
                "PromptComposerSaturatedImeAnchorE2eTest.kt",
        )
        assertFalse(
            "Hard-cut the real-IME @Test (D22). The synthetic mirror already " +
                "owns reachability + hide restoration.",
            src.contains("WithRealIme"),
        )
        assertFalse(
            "requestRealImeAndAssertVisible is the CI flake at " +
                "PromptComposerSaturatedImeAnchorE2eTest.kt:1091.",
            src.contains("requestRealImeAndAssertVisible"),
        )
        assertFalse(
            "showSoftInput is the real-system-IME raise. Use Type.ime() instead.",
            src.contains("showSoftInput("),
        )
        assertFalse(
            "The #1800 signature sentence must not remain as a load-bearing path.",
            src.contains("The real system input-method window never became visible"),
        )
        assertFalse(
            "No Assume self-skip on the converted journey (F3).",
            ASSUME_CALL.containsMatchIn(src),
        )
        assertTrue(
            "Converted journey must still dispatch a synthetic Type.ime() inset.",
            src.contains("WindowInsetsCompat.Type.ime()"),
        )
        assertTrue(
            "Converted journey must keep the #1800 synthetic reachability method.",
            src.contains(
                "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide",
            ),
        )
        assertTrue(
            "A bare 5s waitUntil must name the condition (#2126 unsatisfiableWhen " +
                "lesson). Otherwise the next timeout costs another investigation.",
            src.contains("waitUntilNamed("),
        )
        assertTrue(
            "Residual unfocused IME windows must not block synthetic dispatch " +
                "(the inverse #2139 signature).",
            src.contains("servingPhysicalImeWindows"),
        )
    }

    @Test
    fun remainingGenuineRealImeJourneysNameTheEnvironmentalCause() {
        val chip = locateAndroidTest(
            "app/src/androidTest/java/com/pocketshell/app/session/ShowKeyboardChipE2eTest.kt",
        )
        assertTrue(
            "ShowKeyboardChipE2eTest must keep the #1879 environmental label.",
            chip.contains("FOREIGN_WINDOW_FOCUS_SIGNATURE"),
        )
        assertTrue(
            "ShowKeyboardChipE2eTest must name the IME-service world separately.",
            chip.contains("IME_SERVICE_UNAVAILABLE_SIGNATURE") ||
                chip.contains("describePostTapFailure"),
        )
        assertFalse(ASSUME_CALL.containsMatchIn(chip))

        val opencode = locateAndroidTest(
            "app/src/androidTest/java/com/pocketshell/app/tmux/" +
                "TmuxSessionOpencodeInputDockerTest.kt",
        )
        assertTrue(
            "OpenCode chip-tap IME failures must name the environmental cause, " +
                "not just 'IME did not appear'.",
            opencode.contains("FOREIGN_WINDOW_FOCUS_SIGNATURE"),
        )
        assertTrue(
            "OpenCode IME-raise failures must re-read focus after the wait.",
            opencode.contains("describeRealImeRaiseFailure"),
        )
        assertFalse(ASSUME_CALL.containsMatchIn(opencode))

        val occlusion = locateAndroidTest(
            "app/src/androidTest/java/com/pocketshell/app/tmux/" +
                "TmuxShellComposerOcclusionE2eTest.kt",
        )
        assertTrue(
            "Occlusion journey must keep the #1942 environmental label.",
            occlusion.contains("FOREIGN_WINDOW_FOCUS_SIGNATURE"),
        )
        assertFalse(ASSUME_CALL.containsMatchIn(occlusion))
    }

    @Test
    fun scannerIgnoresCommentOnlyShowSoftInputAndCatchesARealCall() {
        assertFalse(
            raisesOrWaitsOnRealIme(
                """
                /**
                 * `Ignoring showSoftInput() as view=… is not served`
                 */
                fun alreadySynthetic() {
                    dispatch(WindowInsetsCompat.Type.ime())
                }
                """.trimIndent(),
            ),
        )
        assertTrue(
            raisesOrWaitsOnRealIme(
                """
                fun stillRaises() {
                    inputMethodManager.showSoftInput(view, 0)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun issue2126ClassAlreadyUsesSyntheticInsetAndIsNotRefolded() {
        val src = locateAndroidTest(
            "app/src/androidTest/java/com/pocketshell/app/proof/" +
                "TmuxTerminalSurfaceFailureE2eTest.kt",
        )
        assertTrue(
            "#2126 already converted this class to SyntheticImeStage. " +
                "Do not re-fold it into #2139 without a new signature.",
            src.contains("enterKeyboardUpChromeWithSyntheticIme"),
        )
        assertTrue(src.contains("real_system_ime_used=false"))
        assertTrue(src.contains("keyboard_up_model=synthetic-inset"))
        assertFalse(
            "#2126 hard-cut the real-IME raise. Do not restore it.",
            src.contains("showProductionKeyboardAndProveStableIme"),
        )
        assertFalse(
            "#2126 must not grow a new showSoftInput call (comments about the " +
                "old signature are fine).",
            raisesOrWaitsOnRealIme(src),
        )
        assertTrue(
            "#2126's unsatisfiableWhen fast-fail stays the echo-timeout model; " +
                "this issue does not replace it.",
            src.contains("unsatisfiableWhen"),
        )
    }

    private fun scanRealImeRaiseSources(): List<File> {
        val roots = listOf(
            locateDir("app/src/androidTest/java"),
            locateDir("shared/core-terminal/src/androidTest/java"),
        )
        return roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val name = file.name
                    name.endsWith("Test.kt") || name.endsWith("Harness.kt")
                }
                .filter { raisesOrWaitsOnRealIme(it.readText()) }
                .toList()
        }.sortedBy { it.path }
    }

    companion object {
        private val ASSUME_CALL =
            Regex("""\bassume(True|False|NotNull|That)\s*\(""")

        /**
         * Gated journeys that genuinely must exercise the real keyboard
         * (the chip's contract is "one tap raises the system IME").
         */
        private val REMAINING_GENUINE_GATED = setOf(
            "ShowKeyboardChipE2eTest.kt",
            "TmuxSessionOpencodeInputDockerTest.kt",
            // Gated geometry + #1942 focus-thief diagnosis. The diagnosis
            // contract is "a real IME raise names FOREIGN_WINDOW_FOCUS_SIGNATURE";
            // converting it would delete that oracle. Already names the
            // environment (AC3). Harness is createEmptyComposeRule (known
            // #788 backlog) so SyntheticImeStage cannot be dropped in here.
            "TmuxShellComposerOcclusionE2eTest.kt",
        )

        /**
         * Real-IME androidTests that are not in the per-push journey gate.
         * Catalogued so a new dependent cannot hide next to them.
         */
        private val REMAINING_NON_GATED = setOf(
            "PromptComposerImeLayoutRegressionTest.kt",
            "PromptComposerImeDeadSpaceScreenshotHarness.kt",
            "SessionTypePickerSkipPermissionsUiTest.kt",
            "TerminalKeyboardStressTest.kt",
            "Issue2057AttachmentTilesBelowDraftProofTest.kt",
            "SignalsTest.kt",
        )

        private val CONVERTED_MUST_NOT_RAISE = setOf(
            "PromptComposerSaturatedImeAnchorE2eTest.kt",
            "TmuxTerminalSurfaceFailureE2eTest.kt",
        )
    }
}

internal fun raisesOrWaitsOnRealIme(source: String): Boolean {
    val code = stripKotlinComments(source)
    if (code.contains("showSoftInput(")) return true
    if (code.contains("WithRealIme")) return true
    if (code.contains("requestRealIme")) return true
    if (code.contains("waitForRealIme")) return true
    if (code.contains("waitForPhysicalImeWindow(expected = true)")) return true
    if (code.contains("waitForImeVisibility") && code.contains("expected = true")) {
        return true
    }
    if (
        Regex("""waitForInputMethodVisible\s*\(""").containsMatchIn(code) &&
        code.contains("expected = true")
    ) {
        return true
    }
    return false
}

internal fun stripKotlinComments(source: String): String {
    val noBlock = BLOCK_COMMENT.replace(source, " ")
    return LINE_COMMENT.replace(noBlock, " ")
}

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun locateAndroidTest(relative: String): String {
    val file = locateFile(relative)
    return file.readText()
}

private fun locateDir(relative: String): File {
    val file = locateFile(relative)
    check(file.isDirectory) { "Not a directory: ${file.absolutePath}" }
    return file
}

private fun locateFile(relative: String): File {
    var cursor = File(System.getProperty("user.dir")).absoluteFile
    repeat(8) {
        val candidate = cursor.resolve(relative)
        if (candidate.exists()) return candidate
        val parent = cursor.parentFile ?: return@repeat
        cursor = parent
    }
    error("Could not locate $relative from ${File(".").absolutePath}")
}
