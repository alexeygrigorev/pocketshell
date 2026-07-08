package com.pocketshell.app.composer

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The document picker callback runs on the UI path. URI metadata probing can
 * block on remote content providers, so MIME/display-name work must stay in the
 * existing staging paths that already run on IO dispatchers.
 */
class PromptComposerSheetSourceGuardTest {

    @Test
    fun documentPickerCallbackDoesNotProbeMimeOnMain() {
        val src = locate("PromptComposerSheet.kt")
        val callback = src.substringFrom("val attachmentLauncher = rememberLauncherForActivityResult")

        assertFalse(
            "PromptComposerSheet picker callback must not touch ContentResolver; " +
                "resolve MIME/display-name/bytes inside attachment staging off Main.",
            callback.contains("contentResolver") ||
                callback.contains(".getType(") ||
                callback.contains(".query(") ||
                callback.contains(".openInputStream("),
        )
    }

    private fun String.substringFrom(marker: String): String {
        val start = indexOf(marker)
        check(start >= 0) { "$marker not found" }
        return substring(start, minOf(start + 900, length))
    }

    private fun locate(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/pocketshell/app/composer/$relative"),
            File("src/main/java/com/pocketshell/app/composer/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
