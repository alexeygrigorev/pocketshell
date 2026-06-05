package com.pocketshell.app.fileviewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the audio size guard (issue #499) — the threshold that
 * keeps an oversized audio file from being cached and played.
 */
class FileViewerAudioGuardTest {

    @Test
    fun `audio at the cap is allowed`() {
        assertFalse(FileViewerViewModel.audioExceedsCap(FileViewerViewModel.MAX_AUDIO_BYTES))
    }

    @Test
    fun `audio one byte over the cap is rejected`() {
        assertTrue(FileViewerViewModel.audioExceedsCap(FileViewerViewModel.MAX_AUDIO_BYTES + 1))
    }

    @Test
    fun `small audio is allowed`() {
        assertFalse(FileViewerViewModel.audioExceedsCap(256L * 1024))
    }

    @Test
    fun `audio cap does not exceed the overall preview fetch cap`() {
        // The fetch is bounded by MAX_PREVIEW_BYTES, so the audio cap must be
        // at most that or files would be rejected by the fetch before the
        // audio-specific guard ever runs.
        assertTrue(FileViewerViewModel.MAX_AUDIO_BYTES <= FileViewerViewModel.MAX_PREVIEW_BYTES)
    }
}
