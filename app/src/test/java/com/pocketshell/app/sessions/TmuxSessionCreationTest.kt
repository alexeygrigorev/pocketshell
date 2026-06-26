package com.pocketshell.app.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TmuxSessionCreationTest {
    @Test
    fun typedNameWinsAndStartDirectoryDefaultsToHome() {
        val creation = resolveTmuxSessionCreation(
            rawName = "test",
            rawStartDirectory = "",
            nowMillis = 0L,
        )

        assertEquals("test", creation.sessionName)
        assertEquals("~", creation.startDirectory)
    }

    @Test
    fun blankNameDerivesFromStartDirectoryBaseName() {
        val creation = resolveTmuxSessionCreation(
            rawName = " ",
            rawStartDirectory = "/home/alexey/git/pocketshell",
            nowMillis = 0L,
        )

        assertEquals("pocketshell", creation.sessionName)
        assertEquals("/home/alexey/git/pocketshell", creation.startDirectory)
    }

    @Test
    fun blankNameFallsBackToTimestampWhenStartDirectoryHasNoBaseName() {
        val creation = resolveTmuxSessionCreation(
            rawName = "",
            rawStartDirectory = "~",
            nowMillis = 0L,
        )

        assertEquals("pocketshell-19700101-0000", creation.sessionName)
        assertEquals("~", creation.startDirectory)
    }

    @Test
    fun derivedNameIsSanitizedForTmuxTargets() {
        val creation = resolveTmuxSessionCreation(
            rawName = "",
            rawStartDirectory = "/workspace/my app:api",
            nowMillis = 0L,
        )

        assertEquals("my-app-api", creation.sessionName)
    }

    @Test
    fun typedNameRemovesTmuxTargetSeparatorsButKeepsPlainWords() {
        val creation = resolveTmuxSessionCreation(
            rawName = "test:api",
            rawStartDirectory = "/workspace/app",
            nowMillis = 0L,
        )

        assertEquals("test-api", creation.sessionName)
    }

    @Test
    fun remoteStartDirectoryExistsCommandQuotesRequestedFolder() {
        val command = remoteStartDirectoryExistsCommand("/tmp/it's missing; rm -rf \$HOME")

        assertEquals(
            """
                pocketshell_start_dir='/tmp/it'\''s missing; rm -rf ${'$'}HOME'
                case "${'$'}pocketshell_start_dir" in
                  '~') pocketshell_start_dir=${'$'}HOME ;;
                  '~/'*) pocketshell_start_dir=${'$'}HOME/${'$'}{pocketshell_start_dir#"~/"} ;;
                  '${'$'}HOME') pocketshell_start_dir=${'$'}HOME ;;
                  '${'$'}HOME/'*) pocketshell_start_dir=${'$'}HOME/${'$'}{pocketshell_start_dir#${'$'}HOME/} ;;
                esac
                test -d "${'$'}pocketshell_start_dir"
            """.trimIndent(),
            command,
        )
    }

    /**
     * Issue #990 BEHAVIORAL regression proof. The old test above only asserts the
     * generated command STRING — it never executes it, which is why the tilde-strip
     * bug shipped: the `~/` in the `${'$'}{dir#~/}` strip PATTERN underwent tilde
     * expansion (to `${'$'}HOME/`) BEFORE the strip, so for value `~/git` nothing was
     * stripped and the result was `${'$'}HOME/~/git` (a path that does not exist) →
     * `test -d` returned non-zero → a false "start folder does not exist: ~/git".
     *
     * This test pipes the *generated* script into a real POSIX `/bin/sh` (the same
     * way `SshSession.exec` runs it on the remote, with `$HOME` set), against a temp
     * dir layout, and asserts the exit code matches whether the resolved directory
     * actually exists. It RED-fails on base (unquoted strip) for the `~/...` cases
     * and GREEN-passes with the quoted strip pattern. Class coverage: `~`, `~/git`,
     * `~/a/b`, an absolute path under `$HOME` (the '$HOME/'* arm), an absolute
     * `/tmp/...`, and the genuinely-missing variants of each (which must still
     * correctly report missing).
     */
    @Test
    fun remoteStartDirectoryExistsCommandResolvesTildeAndHomeWhenRunInAShell() {
        val home = Files.createTempDirectory("ps-home").toFile()
        try {
            // Build the directory layout the maintainer reported: ~/git exists.
            File(home, "git").mkdirs()
            File(home, "a/b").mkdirs()
            val absoluteExisting = Files.createTempDirectory("ps-abs").toFile()
            // An absolute path *under* $HOME exercises the '$HOME/'* case arm with the
            // real on-device value the user would supply (the resolved home prefix),
            // not a literal "$HOME/..." string a user would never type.
            val absoluteUnderHome = File(home, "git").absolutePath
            try {
                // Existing-directory cases: must report exit 0 (exists).
                assertTrue("~ (HOME itself) must resolve to an existing dir", existsViaShell(home, "~"))
                assertTrue("~/git must resolve to \$HOME/git which exists", existsViaShell(home, "~/git"))
                assertTrue("~/a/b must resolve to \$HOME/a/b which exists", existsViaShell(home, "~/a/b"))
                assertTrue(
                    "an absolute path under \$HOME must resolve to an existing dir",
                    existsViaShell(home, absoluteUnderHome),
                )
                assertTrue(
                    "an absolute path that exists must report present",
                    existsViaShell(home, absoluteExisting.absolutePath),
                )

                // Missing-directory cases: must correctly report exit != 0 (missing).
                assertFalse("~/nope must report missing", existsViaShell(home, "~/nope"))
                assertFalse(
                    "an absolute path under \$HOME that does not exist must report missing",
                    existsViaShell(home, File(home, "nope").absolutePath),
                )
                assertFalse(
                    "an absolute path that does not exist must report missing",
                    existsViaShell(home, "/tmp/pocketshell-definitely-missing-${System.nanoTime()}"),
                )
            } finally {
                absoluteExisting.deleteRecursively()
            }
        } finally {
            home.deleteRecursively()
        }
    }

    /**
     * Runs the generated existence-check script through a real `/bin/sh` with `HOME`
     * pointed at [home] (mirrors how `SshSession.exec` runs it remotely). Returns
     * whether the script reported the directory exists (exit code 0).
     */
    private fun existsViaShell(home: File, startDirectory: String): Boolean {
        val script = remoteStartDirectoryExistsCommand(startDirectory)
        val process = ProcessBuilder("/bin/sh", "-c", script)
            .apply {
                environment()["HOME"] = home.absolutePath
                redirectErrorStream(true)
            }
            .start()
        process.outputStream.close()
        process.inputStream.readBytes()
        return process.waitFor() == 0
    }
}
