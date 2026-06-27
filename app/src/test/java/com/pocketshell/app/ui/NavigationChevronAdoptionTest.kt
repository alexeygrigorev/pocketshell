package com.pocketshell.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #756 follow-up: drill-in/navigation rows use the shared
 * NavigationChevron, while DisclosureIcon remains expand/collapse-only.
 */
class NavigationChevronAdoptionTest {

    @Test
    fun fileExplorerUsesSharedNavigationChevronForEntryRows() {
        val src = locate("fileexplorer/FileExplorerScreen.kt")
        val rowChevron = src.substringFrom("private fun RowNavigationChevron()")

        assertTrue(src.contains("import com.pocketshell.uikit.components.NavigationChevron"))
        assertTrue(rowChevron.contains("NavigationChevron("))
        assertFalse("FileExplorer must not keep the old private Chevron helper", src.contains("private fun Chevron()"))
    }

    @Test
    fun settingsUsesSharedNavigationChevronForNavigationRows() {
        val src = locate("settings/SettingsScreen.kt")

        assertTrue(src.contains("import com.pocketshell.uikit.components.NavigationChevron"))
        assertTrue(
            "Settings navigation rows should use the shared chevron in trailing content",
            src.countNavigationChevronTrailing() >= 2,
        )
        assertFalse("Settings must not keep the old raw-glyph NavChevron helper", src.contains("fun NavChevron()"))
        assertFalse("Settings navigation rows must not use raw guillemet Text", src.hasRawRightGuillemetText())
    }

    @Test
    fun folderContextAndCostsRowsDoNotUseRawRightGuillemet() {
        val folderContext = locate("projects/FolderContextActionSheet.kt")
        val costs = locate("costs/CostsScreen.kt")

        assertTrue(folderContext.contains("import com.pocketshell.uikit.components.NavigationChevron"))
        assertTrue(folderContext.contains("NavigationChevron()"))
        assertFalse(folderContext.hasRawRightGuillemetText())

        assertTrue(costs.contains("import com.pocketshell.uikit.components.NavigationChevron"))
        assertTrue(costs.contains("NavigationChevron()"))
        assertFalse(costs.hasRawRightGuillemetText())
    }

    private fun String.countNavigationChevronTrailing(): Int =
        Regex("""trailing\s*=\s*\{\s*NavigationChevron\s*\(""").findAll(this).count()

    private fun String.hasRawRightGuillemetText(): Boolean =
        Regex("""Text\s*\(\s*(?:text\s*=\s*)?"›"""").containsMatchIn(this)

    private fun String.substringFrom(marker: String): String {
        val start = indexOf(marker)
        assertTrue("$marker not found", start >= 0)
        return substring(start, minOf(start + 800, length))
    }

    private fun locate(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/pocketshell/app/$relative"),
            File("src/main/java/com/pocketshell/app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
