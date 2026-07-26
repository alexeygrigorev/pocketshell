package com.pocketshell.uikit.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #1772: Roborazzi drains Robolectric's paused main looper before capture.
 * A live Material indeterminate indicator continuously posts animation frames,
 * so the drain cannot reach quiescence. The two design fixtures that explicitly
 * showcase loading states must therefore paint a deterministic test-only frame.
 *
 * This source guard makes restoring either fixture to the production infinite
 * animation a hard failure while also protecting the opposite boundary:
 * production [com.pocketshell.uikit.components.LoadingIndicator] must stay live.
 */
class DesignRenderStaticLoadingPolicyTest {

    @Test
    fun loadingIndicatorShowcaseUsesOnlyStaticFixtureFrames() {
        val source = locateTestSource("DesignRenders.kt")
        val body = source.substringBetween(
            start = "fun loadingIndicators()",
            end = "fun tmuxConnectingStates()",
        )

        assertTrue(body.contains("StaticLoadingIndicator.Bar()"))
        assertEquals(4, body.countOccurrences("StaticLoadingIndicator.Spinner("))
        assertFalse(LIVE_BAR_CALL.containsMatchIn(body))
        assertFalse(LIVE_SPINNER_CALL.containsMatchIn(body))
    }

    @Test
    fun tmuxConnectingShowcaseUsesOnlyStaticFixtureFrames() {
        val source = locateTestSource("TerminalRenderFixtures.kt")
        val body = source.substringBetween(
            start = "internal fun TmuxConnectingStatesRender()",
            end = "internal fun TmuxDisconnectedStateRender()",
        )

        assertEquals(2, body.countOccurrences("StaticLoadingIndicator.Spinner("))
        assertFalse(LIVE_SPINNER_CALL.containsMatchIn(body))
    }

    @Test
    fun staticFixtureHasNoAnimationAndProductionIndicatorRemainsLive() {
        val fixture = locateTestSource("StaticLoadingIndicator.kt")
        assertTrue(fixture.contains("drawLine("))
        assertTrue(fixture.contains("drawArc("))
        assertTrue(fixture.contains("StaticSpinnerSweepDegrees = 270f"))
        assertFalse(fixture.contains("rememberInfiniteTransition"))
        assertFalse(fixture.contains("InfiniteTransition"))
        assertFalse(fixture.contains("LoadingIndicator.Bar("))
        assertFalse(fixture.contains("LoadingIndicator.Spinner("))

        val production = locateProductionSource("LoadingIndicator.kt")
        assertTrue(production.contains("LinearProgressIndicator("))
        assertTrue(production.contains("CircularProgressIndicator("))
        assertFalse(production.contains("StaticLoadingIndicator"))
    }

    private fun String.substringBetween(start: String, end: String): String {
        val startIndex = indexOf(start)
        check(startIndex >= 0) { "$start not found" }
        val endIndex = indexOf(end, startIndex)
        check(endIndex >= 0) { "$end not found after $start" }
        return substring(startIndex, endIndex)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(size = needle.length, step = 1).count { it == needle }

    private fun locateTestSource(name: String): String =
        locate(
            "shared/ui-kit/src/test/java/com/pocketshell/uikit/render/$name",
            "src/test/java/com/pocketshell/uikit/render/$name",
        )

    private fun locateProductionSource(name: String): String =
        locate(
            "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/$name",
            "src/main/java/com/pocketshell/uikit/components/$name",
        )

    private fun locate(vararg candidates: String): String {
        val file = candidates
            .asSequence()
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error("Could not locate ${candidates.joinToString()} from ${File(".").absolutePath}")
        return file.readText()
    }

    private companion object {
        val LIVE_BAR_CALL = Regex("""(?<!Static)LoadingIndicator\.Bar\(""")
        val LIVE_SPINNER_CALL = Regex("""(?<!Static)LoadingIndicator\.Spinner\(""")
    }
}
