package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the tmuxctl-style directory-derived session naming
 * (issue #429). These pin the exact names produced for the maintainer's
 * `tmuxctl` (`t`) convention: home-relative when under `$HOME`, absolute
 * components otherwise, agent prefix preserved, no random timestamp,
 * deterministic collision suffix.
 */
class SessionNameDerivationTest {

    private val home = "/home/alexey"

    // --- Acceptance criterion: agent session under home ---

    @Test
    fun agentUnderHomeYieldsPrefixedHomeRelativeName() {
        val name = SessionNameDerivation.derive(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            agentCommand = "claude",
        )
        assertEquals("claude-git-pocketshell", name)
    }

    @Test
    fun agentUnderHomeAbsolutePathMatchesTildeForm() {
        val name = SessionNameDerivation.derive(
            startDirectory = "/home/alexey/git/pocketshell",
            homeDirectory = home,
            agentCommand = "claude",
        )
        assertEquals("claude-git-pocketshell", name)
    }

    // --- Acceptance criterion: shell session outside home ---

    @Test
    fun shellOutsideHomeUsesAbsoluteComponents() {
        val name = SessionNameDerivation.derive(
            startDirectory = "/var/log",
            homeDirectory = home,
            agentCommand = null,
        )
        assertEquals("var-log", name)
    }

    @Test
    fun outsideHomeWithoutKnownHomeStillUsesAbsoluteComponents() {
        val name = SessionNameDerivation.derive(
            startDirectory = "/var/log",
            homeDirectory = null,
            agentCommand = null,
        )
        assertEquals("var-log", name)
    }

    // --- Acceptance criterion: in $HOME → home-<name> ---

    @Test
    fun directoryIsHomeYieldsHomeDashBasename() {
        assertEquals(
            "home-alexey",
            SessionNameDerivation.baseName("/home/alexey", home),
        )
    }

    @Test
    fun tildeAloneResolvesToHome() {
        assertEquals(
            "home-alexey",
            SessionNameDerivation.baseName("~", home),
        )
    }

    @Test
    fun trailingSlashOnHomeStillRecognisedAsHome() {
        assertEquals(
            "home-alexey",
            SessionNameDerivation.baseName("/home/alexey/", home),
        )
    }

    @Test
    fun rootUserHomeYieldsHomeDashRoot() {
        assertEquals(
            "home-root",
            SessionNameDerivation.baseName("/root", "/root"),
        )
    }

    // --- Nested under home ---

    @Test
    fun nestedUnderHomeJoinsAllComponents() {
        assertEquals(
            "work-clients-acme",
            SessionNameDerivation.baseName("~/work/clients/acme", home),
        )
    }

    @Test
    fun nestedAbsoluteUnderHomeJoinsAllComponents() {
        assertEquals(
            "work-clients-acme",
            SessionNameDerivation.baseName("/home/alexey/work/clients/acme", home),
        )
    }

    // --- Acceptance criterion: sanitization, no `.`/`:` in tmux names ---

    @Test
    fun dotsAndColonsBecomeUnderscores() {
        // `.config` and a `dir:with:colons` segment must lose `.`/`:`
        // entirely (tmux forbids them in session names).
        assertEquals(
            "_config-dir_with_colons",
            SessionNameDerivation.baseName("~/.config/dir:with:colons", home),
        )
    }

    @Test
    fun dottedProjectNameIsSanitised() {
        val name = SessionNameDerivation.derive(
            startDirectory = "~/my.project.v2",
            homeDirectory = home,
            agentCommand = "codex",
        )
        assertEquals("codex-my_project_v2", name)
        assertNoTmuxForbidden(name)
    }

    @Test
    fun otherSpecialCharactersCollapseToDash() {
        assertEquals(
            "weird-name",
            SessionNameDerivation.baseName("/weird name", null),
        )
    }

    @Test
    fun derivedNamesNeverContainDotOrColon() {
        val samples = listOf(
            SessionNameDerivation.derive("~/a.b:c", home, "claude"),
            SessionNameDerivation.derive("/etc/ssh.d", null, null),
            SessionNameDerivation.derive("~", home, "opencode"),
        )
        samples.forEach { assertNoTmuxForbidden(it) }
    }

    // --- Acceptance criterion: collision disambiguation ---

    @Test
    fun collisionAppendsDeterministicSuffix() {
        val name = SessionNameDerivation.derive(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            agentCommand = "claude",
            existingNames = setOf("claude-git-pocketshell"),
        )
        assertEquals("claude-git-pocketshell-2", name)
    }

    @Test
    fun collisionWalksUpUntilFreeSlot() {
        val name = SessionNameDerivation.derive(
            startDirectory = "/var/log",
            homeDirectory = home,
            agentCommand = null,
            existingNames = setOf("var-log", "var-log-2", "var-log-3"),
        )
        assertEquals("var-log-4", name)
    }

    @Test
    fun noCollisionKeepsBaseName() {
        val name = SessionNameDerivation.derive(
            startDirectory = "/var/log",
            homeDirectory = home,
            agentCommand = null,
            existingNames = setOf("something-else"),
        )
        assertEquals("var-log", name)
    }

    @Test
    fun noRandomTimestampSuffix() {
        // The old behaviour appended a 6-digit `currentTimeMillis()`
        // suffix; the name must now be fully deterministic.
        val a = SessionNameDerivation.derive("~/git/pocketshell", home, "claude")
        val b = SessionNameDerivation.derive("~/git/pocketshell", home, "claude")
        assertEquals(a, b)
        assertEquals("claude-git-pocketshell", a)
    }

    // --- conventionalRemoteHome / knownSessionNames helpers ---

    @Test
    fun conventionalHomeForNamedUser() {
        assertEquals("/home/alexey", conventionalRemoteHome("alexey"))
    }

    @Test
    fun conventionalHomeForRoot() {
        assertEquals("/root", conventionalRemoteHome("root"))
    }

    @Test
    fun conventionalHomeBlankUserIsNull() {
        assertEquals(null, conventionalRemoteHome("   "))
    }

    private fun assertNoTmuxForbidden(name: String) {
        if (name.contains('.') || name.contains(':')) {
            throw AssertionError("tmux session name must not contain '.' or ':': $name")
        }
    }
}
