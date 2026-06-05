package com.pocketshell.app.fileviewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the PDF size guard (issue #498) — the threshold that
 * keeps an oversized PDF from being fed to PdfRenderer and OOMing the phone.
 */
class FileViewerPdfGuardTest {

    @Test
    fun `pdf at the cap is allowed`() {
        assertFalse(FileViewerViewModel.pdfExceedsCap(FileViewerViewModel.MAX_PDF_BYTES))
    }

    @Test
    fun `pdf one byte over the cap is rejected`() {
        assertTrue(FileViewerViewModel.pdfExceedsCap(FileViewerViewModel.MAX_PDF_BYTES + 1))
    }

    @Test
    fun `small pdf is allowed`() {
        assertFalse(FileViewerViewModel.pdfExceedsCap(64L * 1024))
    }

    @Test
    fun `pdf cap is below the overall preview cap`() {
        // PDFs render page-by-page to bitmaps, so they get a tighter ceiling
        // than the generic 20 MB image/text preview cap.
        assertTrue(FileViewerViewModel.MAX_PDF_BYTES < FileViewerViewModel.MAX_PREVIEW_BYTES)
    }
}
