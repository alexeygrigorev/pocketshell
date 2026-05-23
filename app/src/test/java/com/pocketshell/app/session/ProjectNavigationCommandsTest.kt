package com.pocketshell.app.session

import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectNavigationCommandsTest {
    @Test
    fun cdQuotesSpacesAndSingleQuotes() {
        val command = ProjectNavigationCommands.cd("/home/test/it's here").getOrThrow()

        assertTrue(command.contains("cd '/home/test/it'\"'\"'s here'"))
        assertTrue(command.contains("[pocketshell] %s succeeded: %s"))
        assertTrue(command.contains("[pocketshell] %s failed with exit %s: %s"))
    }

    @Test
    fun mkdirAndCdExpandsHomeAndQuotesRemainder() {
        val command = ProjectNavigationCommands.mkdirAndCd("~/projects", "new app").getOrThrow()

        assertTrue(command.contains("mkdir -p ~/'projects/new app' && cd ~/'projects/new app'"))
    }

    @Test
    fun gitCloneDerivesFolderQuotesRepositoryAndExpandsHomeTarget() {
        val command = ProjectNavigationCommands.gitCloneAndCd(
            root = "~/src",
            repository = "git@github.com:example/pocket shell.git",
        ).getOrThrow()

        assertTrue(
            command.contains(
                "git clone 'git@github.com:example/pocket shell.git' ~/'src/pocket shell' && cd ~/'src/pocket shell'",
            ),
        )
    }

    @Test
    fun gitCloneUsesFolderOverride() {
        val command = ProjectNavigationCommands.gitCloneAndCd(
            root = "/work",
            repository = "https://github.com/example/repo.git",
            folderName = "client repo",
        ).getOrThrow()

        assertTrue(
            command.contains(
                "git clone 'https://github.com/example/repo.git' '/work/client repo' && cd '/work/client repo'",
            ),
        )
    }

    @Test
    fun rejectsRelativeRoots() {
        assertTrue(ProjectNavigationCommands.cd("projects/app").isFailure)
    }

    @Test
    fun rejectsNamedTildeUsers() {
        assertTrue(ProjectNavigationCommands.cd("~other/projects").isFailure)
    }

    @Test
    fun rejectsControlCharacters() {
        assertTrue(ProjectNavigationCommands.cd("/tmp/bad\npath").isFailure)
        assertTrue(ProjectNavigationCommands.gitCloneAndCd("~/src", "https://x/y.git\nrm -rf /").isFailure)
    }
}
