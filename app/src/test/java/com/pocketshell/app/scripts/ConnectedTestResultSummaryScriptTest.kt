package com.pocketshell.app.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Issue #1872: evidence summaries may never turn "parsed nothing" into green evidence. */
class ConnectedTestResultSummaryScriptTest {

    @Test
    fun `summary parser preserves XML paths containing spaces and parentheses`() {
        val directory = Files.createTempDirectory("TEST-test(AVD) - 14-_app-").toFile()
        File(directory, "TEST-test(AVD) - 14-_app-.xml").writeText(
            """
            <testsuite tests="2" failures="1" errors="0" skipped="0">
              <testcase classname="example.InsetGeometryTest" name="liveStandalone"/>
              <testcase classname="example.InsetGeometryTest" name="inertInSheet">
                <failure message="measured geometry moved">details</failure>
              </testcase>
            </testsuite>
            """.trimIndent(),
        )

        val result = runParser(directory)

        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output, result.output.contains("parsed_files=1"))
        assertTrue(result.output, result.output.contains("tests=2 failures=1 errors=0 skipped=0"))
        assertTrue(result.output, result.output.contains("CASE InsetGeometryTest liveStandalone PASS"))
        assertTrue(result.output, result.output.contains("CASE InsetGeometryTest inertInSheet FAIL"))
    }

    @Test
    fun `summary parser fails loudly when it parses zero XML files`() {
        val emptyDirectory = Files.createTempDirectory("empty connected results").toFile()

        val result = runParser(emptyDirectory)

        assertNotEquals("zero parsed files must not produce a usable summary", 0, result.exitCode)
        assertTrue(result.output, result.output.contains("ERROR: parsed zero XML files"))
    }

    private fun runParser(input: File): Result {
        val script = projectRoot().resolve("scripts/summarize-connected-test-results.py")
        val process = ProcessBuilder("python3", script.absolutePath, input.absolutePath)
            .directory(projectRoot())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue("summary parser timed out\n$output", completed)
        return Result(process.exitValue(), output)
    }

    private fun projectRoot(): File {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }

    private data class Result(val exitCode: Int, val output: String)
}
