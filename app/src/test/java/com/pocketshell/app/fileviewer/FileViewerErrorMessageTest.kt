package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class FileViewerErrorMessageTest {

    @Test
    fun `missing file message includes exact resolved attachment path`() {
        val resolved =
            "/home/alexey/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"

        assertEquals(
            "No such file on the server: $resolved",
            FileViewerViewModel.fileNotFoundMessage(resolved),
        )
    }
}
