package com.pocketshell.app.insets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #1872 hard-cut guard for the two misleading inset shapes left by #1821.
 *
 * The behavioural geometry remains owned by the connected
 * `NestedImePaddingInSheetGeometryTest` / `StandaloneContentImePaddingLivenessTest`
 * pair. This cheap per-push guard pins the classification that geometry proved:
 * inline `ModalBottomSheet` content has already consumed navigation-bar insets,
 * while the four extracted contents are also composed standalone and need their
 * own padding.
 */
class Issue1872InsetsCleanupSourceGuardTest {

    @Test
    fun `the checklist journey renders through the production card feed only`() {
        val production = source("app/src/main/java/com/pocketshell/app/cards/SessionChecklistUi.kt")
        val journey = source(
            "app/src/androidTest/java/com/pocketshell/app/cards/SessionChecklistPushJourneyDockerTest.kt",
        )
        val interaction = source(
            "app/src/androidTest/java/com/pocketshell/app/cards/SessionChecklistUiInteractionTest.kt",
        )

        assertFalse(
            "D22: the zero-production-caller ChecklistCardsContent wrapper must be deleted",
            production.contains("fun ChecklistCardsContent("),
        )
        assertTrue(
            "the gate-wired host-push journey must mount the same SessionCardFeedContent as production",
            journey.contains("SessionCardFeedContent("),
        )
        assertFalse(
            "the host-push journey must not keep proving the deleted wrapper",
            journey.contains("ChecklistCardsContent("),
        )
        assertTrue(
            "the interaction proof must exercise the production feed",
            interaction.contains("SessionCardFeedContent("),
        )
        assertFalse(
            "no androidTest should retain the dead wrapper",
            androidTestSources().any { it.readText().contains("ChecklistCardsContent(") },
        )
    }

    @Test
    fun `navigation bar padding matches the measured sheet versus standalone classification`() {
        val inertInlineSheetFiles = listOf(
            "app/src/main/java/com/pocketshell/app/fileviewer/FileViewerReviewUi.kt",
            "app/src/main/java/com/pocketshell/app/git/GitHistoryScreen.kt",
        )
        inertInlineSheetFiles.forEach { path ->
            assertFalse(
                "$path writes navigationBarsPadding inline inside ModalBottomSheet; " +
                    "#1821 measured those five sites as geometry-identical without it",
                source(path).contains("navigationBarsPadding"),
            )
        }

        val liveStandaloneSites = mapOf(
            "app/src/main/java/com/pocketshell/app/cards/SessionChecklistUi.kt" to 1,
            "app/src/main/java/com/pocketshell/app/projects/FolderContextActionSheet.kt" to 1,
            "app/src/main/java/com/pocketshell/app/projects/SessionTypePickerSheet.kt" to 1,
            "app/src/main/java/com/pocketshell/app/snippets/SnippetPickerSheet.kt" to 1,
        )
        liveStandaloneSites.forEach { (path, expectedCalls) ->
            assertEquals(
                "$path is also composed standalone; its measured 126px keyboard-down clearance is live",
                expectedCalls,
                Regex("\\.navigationBarsPadding\\(\\)").findAll(source(path)).count(),
            )
        }

        val liveness = source(
            "app/src/androidTest/java/com/pocketshell/app/insets/StandaloneContentImePaddingLivenessTest.kt",
        )
        listOf(
            "sessionCardFeedContentLiftsItsLastCardAboveTheKeyboard",
            "folderContextActionContentLiftsItsLastRowAboveTheKeyboard",
            "sessionTypePickerContentLiftsCreateAboveTheKeyboard",
            "snippetPickerContentLiftsItsSendChipAboveTheKeyboard",
        ).forEach { testName ->
            assertTrue("missing live standalone geometry proof $testName", liveness.contains(testName))
        }
        assertFalse(
            "the deleted ChecklistCardsContent cannot remain a fake fifth live site",
            liveness.contains("checklistCardsContentLiftsItsLastItemAboveTheKeyboard"),
        )
        val journeyGate = source("scripts/ci-journey-suite.sh")
        listOf(
            "com.pocketshell.app.insets.NestedImePaddingInSheetGeometryTest",
            "com.pocketshell.app.insets.StandaloneContentImePaddingLivenessTest",
        ).forEach { className ->
            assertTrue("$className must run in the per-push emulator gate", journeyGate.contains(className))
        }
    }

    private fun androidTestSources(): Sequence<File> =
        projectRoot().resolve("app/src/androidTest").walkTopDown().filter { it.extension == "kt" }

    private fun source(path: String): String = projectRoot().resolve(path).readText()

    private fun projectRoot(): File {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }
}
