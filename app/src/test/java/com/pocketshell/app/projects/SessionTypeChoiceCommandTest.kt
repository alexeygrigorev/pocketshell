package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Command and data tests for the registry-driven new-session picker. */
class SessionTypeChoiceCommandTest {

    private fun engine(
        id: String,
        family: SessionAgentKind,
        supportsSkipPermissions: Boolean = true,
    ) = RemoteEngine(
        id = id,
        familyId = family.name.lowercase(),
        family = family,
        label = id,
        launch = RemoteEngineLaunch(
            supportsSkipPermissions = supportsSkipPermissions,
        ),
    )

    @Test
    fun missingStartDirectoryCreationBuildsChildUnderCurrentFolder() {
        val offer = missingStartDirectoryCreation(
            baseFolderPath = "~/git",
            typedStartDirectory = "new.project",
            suggestions = emptyList(),
            loading = false,
        )

        assertEquals("~/git", offer?.parentPath)
        assertEquals("new.project", offer?.folderName)
        assertEquals("~/git/new.project", offer?.path)
    }

    @Test
    fun missingStartDirectoryCreationUsesTypedParentForExplicitPath() {
        val offer = missingStartDirectoryCreation(
            baseFolderPath = "~",
            typedStartDirectory = "~/git/new.project",
            suggestions = emptyList(),
            loading = false,
        )

        assertEquals("~/git", offer?.parentPath)
        assertEquals("new.project", offer?.folderName)
        assertEquals("~/git/new.project", offer?.path)
    }

    @Test
    fun missingStartDirectoryCreationSuppressesExistingAndUnsafeNames() {
        assertNull(
            missingStartDirectoryCreation(
                baseFolderPath = "~/git",
                typedStartDirectory = "~/git/pocketshell",
                suggestions = listOf("~/git/pocketshell/"),
                loading = false,
            ),
        )
        assertNull(
            missingStartDirectoryCreation(
                baseFolderPath = "~/git",
                typedStartDirectory = "../bad",
                suggestions = emptyList(),
                loading = false,
            ),
        )
        assertNull(
            missingStartDirectoryCreation(
                baseFolderPath = "~/git",
                typedStartDirectory = "new.project",
                suggestions = emptyList(),
                loading = true,
            ),
        )
    }

    @Test
    fun customRawEngineIdIsPassedUnchangedToHostWrapper() {
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            engine = engine("godex-custom", SessionAgentKind.Codex),
            startDirectory = "/home/alexey/git/my project",
        )

        assertEquals("godex-custom", choice.engineId)
        assertEquals(SessionAgentKind.Codex, choice.sessionAgentKind)
        assertEquals(
            "pocketshell agent godex-custom --dir '/home/alexey/git/my project'",
            choice.startCommand(),
        )
    }

    @Test
    fun skipPermissionsFlagUsesRegistryLaunchCapability() {
        val noFlag = SessionTypeChoice(
            type = SessionType.Agent,
            engine = engine("claude", SessionAgentKind.Claude),
            startDirectory = "/srv/app",
            skipPermissions = false,
        )
        val unsupported = SessionTypeChoice(
            type = SessionType.Agent,
            engine = engine(
                "custom-opencode",
                SessionAgentKind.OpenCode,
                supportsSkipPermissions = false,
            ),
            startDirectory = "/srv/app",
            skipPermissions = false,
        )

        assertTrue(noFlag.startCommand()!!.endsWith("--no-skip-permissions"))
        assertEquals(
            "pocketshell agent custom-opencode --dir '/srv/app'",
            unsupported.startCommand(),
        )
    }

    @Test
    fun selectedNonDefaultProfileIsMappedByFamilyAndQuoted() {
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            engine = engine("godex-custom", SessionAgentKind.Codex),
            startDirectory = "/srv/app",
            profileName = "team's profile",
        )

        val command = choice.startCommand(
            claudeProfiles = listOf(ClaudeProfile("wrong-family")),
            codexProfiles = listOf(CodexProfile("team's profile")),
        )

        assertEquals(
            "pocketshell agent godex-custom --dir '/srv/app' --profile 'team'\\''s profile'",
            command,
        )
    }

    @Test
    fun defaultOrUnknownProfileDoesNotBecomeAWrapperArgument() {
        val default = SessionTypeChoice(
            type = SessionType.Agent,
            engine = engine("claude", SessionAgentKind.Claude),
            startDirectory = "/srv/app",
            profileName = "default",
        )
        val unknown = default.copy(profileName = "not-discovered")
        val profiles = listOf(ClaudeProfile("default", default = true))

        assertEquals("pocketshell agent claude --dir '/srv/app'", default.startCommand(profiles))
        assertEquals("pocketshell agent claude --dir '/srv/app'", unknown.startCommand(profiles))
    }

    @Test
    fun shellChoiceHasNoEngineOrLaunchCommand() {
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            engine = null,
            startDirectory = "/srv/app",
        )

        assertNull(choice.engineId)
        assertNull(choice.sessionAgentKind)
        assertNull(choice.startCommand())
    }
}
