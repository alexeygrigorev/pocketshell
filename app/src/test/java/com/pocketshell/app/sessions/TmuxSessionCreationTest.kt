package com.pocketshell.app.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

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
                  '~/'*) pocketshell_start_dir=${'$'}HOME/${'$'}{pocketshell_start_dir#~/} ;;
                  '${'$'}HOME') pocketshell_start_dir=${'$'}HOME ;;
                  '${'$'}HOME/'*) pocketshell_start_dir=${'$'}HOME/${'$'}{pocketshell_start_dir#${'$'}HOME/} ;;
                esac
                test -d "${'$'}pocketshell_start_dir"
            """.trimIndent(),
            command,
        )
    }
}
