package com.pocketshell.app.fileexplorer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests over the explorer's byte-size formatter — issue #528.
 */
class FileExplorerFormatTest {

    @Test
    fun `bytes under a kilobyte render raw`() {
        assertEquals("0 B", formatSize(0))
        assertEquals("512 B", formatSize(512))
        assertEquals("1023 B", formatSize(1023))
    }

    @Test
    fun `kilobytes render with one decimal`() {
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 KB", formatSize(1536))
    }

    @Test
    fun `megabytes and gigabytes scale up`() {
        assertEquals("1.0 MB", formatSize(1024L * 1024))
        assertEquals("2.0 GB", formatSize(2L * 1024 * 1024 * 1024))
    }
}
