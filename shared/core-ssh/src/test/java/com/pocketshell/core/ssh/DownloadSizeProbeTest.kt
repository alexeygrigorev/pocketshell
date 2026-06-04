package com.pocketshell.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests over the [SshSession.downloadFile] size-probe helpers
 * (issue #497). No network, no Docker — pins the size-cap parsing and the
 * shell command shape so a refactor can't quietly drift them.
 */
class DownloadSizeProbeTest {

    @Test
    fun `probe command tests for a regular file and prints wc -c`() {
        val cmd = buildSizeProbeCommand("/tmp/a.png")
        assertTrue(cmd, cmd.contains("[ -f '/tmp/a.png' ]"))
        assertTrue(cmd, cmd.contains("wc -c < '/tmp/a.png'"))
        assertTrue(cmd, cmd.contains(SIZE_PROBE_NO_FILE_SENTINEL))
    }

    @Test
    fun `probe command escapes single quotes in the path`() {
        val cmd = buildSizeProbeCommand("/tmp/it's a file.txt")
        // Single quote becomes the standard '\'' close-escape-reopen sequence.
        assertTrue(cmd, cmd.contains("""'/tmp/it'\''s a file.txt'"""))
    }

    @Test
    fun `parse plain byte count`() {
        assertEquals(1234L, parseSizeProbe("1234\n"))
    }

    @Test
    fun `parse busybox wc output with leading whitespace`() {
        // busybox `wc -c` can pad the count.
        assertEquals(42L, parseSizeProbe("      42\n"))
    }

    @Test
    fun `parse zero-byte file`() {
        assertEquals(0L, parseSizeProbe("0\n"))
    }

    @Test
    fun `parse no-file sentinel maps to NO_FILE`() {
        assertEquals(SIZE_PROBE_NO_FILE, parseSizeProbe("$SIZE_PROBE_NO_FILE_SENTINEL\n"))
    }

    @Test
    fun `parse takes the last numeric line when a banner precedes it`() {
        // A login shell that prints a banner before the count must not fool
        // the parser into UNPARSEABLE.
        assertEquals(7L, parseSizeProbe("MOTD: welcome\n7\n"))
    }

    @Test
    fun `parse garbage maps to UNPARSEABLE`() {
        assertEquals(SIZE_PROBE_UNPARSEABLE, parseSizeProbe("wc: not found\n"))
    }

    @Test
    fun `parse empty output maps to UNPARSEABLE`() {
        assertEquals(SIZE_PROBE_UNPARSEABLE, parseSizeProbe("   \n"))
    }
}
