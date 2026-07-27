package com.pocketshell.app.usage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Credit expiry is inventory metadata, not quota-reset state. This source
 * boundary is intentionally executable so future banner/event/push work cannot
 * accidentally interpret expiry as "limits reset" or a resume-work signal.
 */
class UsageResetCreditArchitectureGuardTest {

    @Test
    fun expiryFormattingAndUiStayNonActionableAndSeparateFromResetVocabulary() {
        val format = source("UsageFormat.kt")
        val formatter = format.slice(
            "internal fun formatCreditExpiry(",
            "/**\n * The soonest",
        )
        assertFalse(formatter.contains("formatReset"))
        assertFalse(formatter.contains("\"resets"))
        assertFalse(formatter.contains("resume"))

        val screen = source("UsageScreen.kt")
        val section = screen.slice(
            "private fun UsageResetCreditsSection(",
            "@Composable\nprivate fun UsageWindowRow(",
        )
        assertFalse(section.contains(".clickable"))
        assertFalse(section.contains("Markdown"))
        assertFalse(section.contains("Link"))
        assertFalse(section.contains("PocketShellButton"))
        assertFalse(section.contains("\"resets"))
        assertFalse(section.contains("resume"))
        assertTrue(section.contains("\"Reset credits · \${resetCredits.availableCount} available\""))
        assertTrue(section.contains("\"Reset credits unavailable\""))
    }

    @Test
    fun creditInventoryCannotFeedDashboardBannerEventNotificationOrPushDerivations() {
        val format = source("UsageFormat.kt")
        val soonest = format.slice(
            "internal fun soonestReset(",
            "/**\n * Issue #689",
        )
        assertFalse(soonest.contains("resetCredits"))
        assertFalse(soonest.contains("expiresAt"))
        assertTrue(soonest.contains("record.windows"))

        productionAppDir()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "UsageScreen.kt" }
            .forEach { file ->
                val text = file.readText()
                assertFalse(
                    "${file.relativeTo(productionAppDir())} must not consume credit inventory",
                    text.contains("resetCredits"),
                )
                assertFalse(
                    "${file.relativeTo(productionAppDir())} must not consume UsageResetCredit",
                    text.contains("UsageResetCredit"),
                )
            }
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        check(start >= 0) { "$startMarker not found" }
        val end = indexOf(endMarker, startIndex = start)
        check(end > start) { "$endMarker not found after $startMarker" }
        return substring(start, end)
    }

    private fun source(relative: String): String {
        val file = productionUsageDir().resolve(relative)
        check(file.isFile) { "Could not locate $relative from ${File(".").absolutePath}" }
        return file.readText()
    }

    private fun productionUsageDir(): File {
        val candidates = listOf(
            File("app/src/main/java/com/pocketshell/app/usage"),
            File("src/main/java/com/pocketshell/app/usage"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate production usage sources from ${File(".").absolutePath}")
    }

    private fun productionAppDir(): File =
        checkNotNull(productionUsageDir().parentFile) { "Usage source directory has no parent" }
}
