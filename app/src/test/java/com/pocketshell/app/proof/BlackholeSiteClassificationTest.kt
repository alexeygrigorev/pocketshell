package com.pocketshell.app.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #2127 — durable classification of every `addBlackhole()` call site.
 *
 * Toxiproxy CLOSES every connection carrying a `timeout` toxic when that toxic
 * is removed, so `addBlackhole()` + `clearToxics()` is a stall followed by a
 * genuine FIN — not a recoverable half-open. Stall-intended journeys must use
 * [ToxiproxyControl.addHalfOpenStall] (`bandwidth` `rate=0`); wedge-intended
 * sites keep `addBlackhole()`.
 *
 * This inventory is the recorded classification (AC1/AC4) and the Unit-gate
 * pin that a new stall-then-clear site cannot silently reuse the FIN-on-clear
 * fixture.
 */
class BlackholeSiteClassificationTest {

    @Test
    fun remainingBlackholeCallsAreOnlyWedgeIntendedSites() {
        val calls = blackholeCalls()
        val unexpected = calls.filterNot { it.relativePath in WEDGE_INTENDED_FILES }
        assertTrue(
            "addBlackhole() is the permanent-wedge fixture; stall-then-clear " +
                "sites must use addHalfOpenStall(). Unexpected call(s) at " +
                unexpected.joinToString { "${it.relativePath}:${it.line}" },
            unexpected.isEmpty(),
        )
        assertEquals(
            "Update the #2127 wedge allowlist when adding/removing a permanent " +
                "addBlackhole() site — do not convert a wedge to a stall here.",
            WEDGE_INTENDED_FILES,
            calls.map { it.relativePath }.toSet(),
        )
    }

    @Test
    fun inventorySeesAndroidTestSitesWhenCheckoutLivesUnderBuildDirectory() {
        // Issue #2357: the release-gate isolated copy lives at
        // `build/pre-release-confidence-gate/.../worktree/`. Filtering
        // `File.walk` on the ABSOLUTE path's `/build/` drops every site and
        // the allowlist assertion compares expected vs `[]`.
        val tmp = kotlin.io.path.createTempDirectory("ps-blackhole-build-root")
        try {
            val checkout = File(
                tmp.toFile(),
                "build/pre-release-confidence-gate/run/worktree",
            )
            val siteRel =
                "app/src/androidTest/java/com/pocketshell/app/proof/FakeWedge.kt"
            val site = File(checkout, siteRel)
            site.parentFile.mkdirs()
            site.writeText("fun test() { addBlackhole() }\n")
            val generated = File(checkout, "app/build/generated/FakeGenerated.kt")
            generated.parentFile.mkdirs()
            generated.writeText("fun generated() { addBlackhole() }\n")

            assertTrue(
                "vacuity: the absolute /build/ filter matches this isolated checkout",
                site.invariantSeparatorsPath.contains("/build/"),
            )
            assertTrue(
                "relative walk must still inventory app/src/androidTest " +
                    "when the checkout itself lives under a build/ directory",
                isInventoriedKotlinSource(site, checkout),
            )
            assertFalse(
                "a generated file under the checkout's own build/ stays skipped",
                isInventoriedKotlinSource(generated, checkout),
            )
            assertEquals(
                listOf(siteRel),
                blackholeCalls(checkout).map { it.relativePath },
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun stallIntendedSitesUseHealableHalfOpenStallNotBlackhole() {
        val missingStall = mutableListOf<String>()
        val stillBlackhole = mutableListOf<String>()
        for (path in STALL_INTENDED_FILES) {
            val source = maskNonCode(sourceFile(path).readText())
            if (!ADD_HALF_OPEN_STALL.containsMatchIn(source)) {
                missingStall += path
            }
            if (ADD_BLACKHOLE_CALL.containsMatchIn(source) &&
                !FUN_ADD_BLACKHOLE.containsMatchIn(source)
            ) {
                stillBlackhole += path
            }
        }
        assertTrue(
            "stall-intended site(s) no longer call addHalfOpenStall(): $missingStall",
            missingStall.isEmpty(),
        )
        assertTrue(
            "stall-intended site(s) still call addBlackhole(): $stillBlackhole",
            stillBlackhole.isEmpty(),
        )
    }

    private fun blackholeCalls(): List<CallSite> = blackholeCalls(projectRoot())

    private fun blackholeCalls(root: File): List<CallSite> {
        return SOURCE_ROOTS
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { isInventoriedKotlinSource(it, root) }
                    .flatMap { file -> callsIn(file, root) }
            }
            .sortedWith(compareBy(CallSite::relativePath, CallSite::line))
    }

    /**
     * Issue #2357: skip generated `build/` trees relative to [root], never
     * by the file's absolute path. An isolated checkout whose absolute path
     * contains `/build/` (release-gate worktree) must still inventory
     * `app/src/androidTest`.
     */
    private fun isInventoriedKotlinSource(file: File, root: File): Boolean {
        if (!file.isFile || file.extension != "kt") return false
        val relative = file.relativeTo(root).invariantSeparatorsPath
        return relative.split('/').none { it == "build" }
    }

    private fun callsIn(file: File, root: File): List<CallSite> {
        val source = file.readText()
        val masked = maskNonCode(source)
        return ADD_BLACKHOLE_CALL.findAll(masked).mapNotNull { match ->
            val prefix = masked.substring(0, match.range.first)
            if (prefix.endsWith("fun ")) return@mapNotNull null
            CallSite(
                relativePath = file.relativeTo(root).invariantSeparatorsPath,
                line = masked.take(match.range.first).count { it == '\n' } + 1,
            )
        }.toList()
    }

    /** Masks strings/comments while preserving offsets and newlines. */
    private fun maskNonCode(source: String): String {
        val result = source.toCharArray()
        var index = 0
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
                LexState.BLOCK_COMMENT -> {
                    if (source.startsWith("*/", index)) {
                        mask(index)
                        mask(index + 1)
                        index += 2
                        state = LexState.CODE
                    } else {
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
        return String(result)
    }

    private fun projectRoot(): File {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(cursor, "settings.gradle.kts").isFile) return cursor
            cursor = cursor.parentFile ?: break
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }

    private fun sourceFile(relativePath: String): File = File(projectRoot(), relativePath)

    private data class CallSite(
        val relativePath: String,
        val line: Int,
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
        private val ADD_BLACKHOLE_CALL = Regex("""\baddBlackhole\s*\(""")
        private val FUN_ADD_BLACKHOLE = Regex("""\bfun\s+addBlackhole\s*\(""")
        private val ADD_HALF_OPEN_STALL = Regex("""\baddHalfOpenStall\s*\(""")

        private val SOURCE_ROOTS = listOf(
            "app/src/androidTest",
            "app/src/debug",
            "app/src/test",
            "app/src/testDebug",
            "shared",
        )

        /**
         * Permanent-wedge sites: the socket should stay half-open for as long as
         * the toxic is installed. A later `clearToxics()` (if any) is restore-
         * for-a-new-path / cleanup, not "heal the same socket".
         */
        private val WEDGE_INTENDED_FILES = setOf(
            "app/src/testDebug/java/com/pocketshell/app/proof/ToxiproxyControlTest.kt",
            "app/src/androidTest/java/com/pocketshell/app/proof/ColdDialUnderBandwidthLimitE2eTest.kt",
            "app/src/androidTest/java/com/pocketshell/app/proof/DisconnectBlackholeE2eTest.kt",
            "app/src/androidTest/java/com/pocketshell/app/proof/DisconnectFlapSoakE2eTest.kt",
            "app/src/androidTest/java/com/pocketshell/app/proof/NatIdleMappingSurvivalE2eTest.kt",
            "app/src/androidTest/java/com/pocketshell/app/proof/PushResumeDeadSocketMainResponsiveE2eTest.kt",
            "app/src/androidTest/java/com/pocketshell/app/proof/SilentMidSessionDropDetectionE2eTest.kt",
        )

        /**
         * Recoverable-stall sites: the SAME socket must survive toxic removal.
         * `addBlackhole()` FINs on clear; these must use `addHalfOpenStall()`.
         */
        private val STALL_INTENDED_FILES = setOf(
            "app/src/androidTest/java/com/pocketshell/app/proof/NetworkFaultProofBase.kt",
            "app/src/androidTest/java/com/pocketshell/app/proof/WithinGraceResumeRideThroughE2eTest.kt",
        )
    }
}
