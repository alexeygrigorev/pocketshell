package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the tmuxctl-style directory-derived session naming
 * (issues #429, #642). These pin the exact names produced for the
 * maintainer's `tmuxctl` (`t`) / `pocketshell sessions` convention:
 * a **pure path-prefix** — home-relative when under `$HOME`, absolute
 * components otherwise — with NO agent-CLI prefix, no random timestamp,
 * and a deterministic collision suffix. Agent and shell sessions in the
 * same directory derive the same base name (distinguished by badge, not
 * by name).
 */
class SessionNameDerivationTest {

    private val home = "/home/alexey"

    // --- Acceptance criterion (#642): agent session under home gets the
    // pure path-prefix, NO `claude-` decoration ---

    @Test
    fun agentUnderHomeYieldsPurePathPrefixNoAgentDecoration() {
        val name = SessionNameDerivation.derive(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            agentCommand = "claude",
        )
        assertEquals("git-pocketshell", name)
    }

    @Test
    fun agentUnderHomeAbsolutePathMatchesTildeForm() {
        val name = SessionNameDerivation.derive(
            startDirectory = "/home/alexey/git/pocketshell",
            homeDirectory = home,
            agentCommand = "claude",
        )
        assertEquals("git-pocketshell", name)
    }

    @Test
    fun agentAndShellInSameDirDeriveSameBaseName() {
        // #642: the name is a pure path-prefix; the agent CLI no longer
        // decorates it, so agent + shell in the same dir share a base.
        val agent = SessionNameDerivation.derive(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            agentCommand = "claude",
        )
        val shell = SessionNameDerivation.derive(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            agentCommand = null,
        )
        assertEquals("git-pocketshell", agent)
        assertEquals(agent, shell)
    }

    @Test
    fun dataEngineeringZoomcampAgentDropsAgentPrefix() {
        // The exact regression the maintainer reported (#642): an agent
        // session in `~/git/data-engineering-zoomcamp` must read
        // `git-data-engineering-zoomcamp`, NOT `claude-git-…`.
        val name = SessionNameDerivation.derive(
            startDirectory = "~/git/data-engineering-zoomcamp",
            homeDirectory = home,
            agentCommand = "claude",
        )
        assertEquals("git-data-engineering-zoomcamp", name)
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
        // #642: even for an agent session, the name is the sanitised
        // path-prefix only — no `codex-` decoration.
        val name = SessionNameDerivation.derive(
            startDirectory = "~/my.project.v2",
            homeDirectory = home,
            agentCommand = "codex",
        )
        assertEquals("my_project_v2", name)
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
        // A genuine second session in the same dir (#642 keeps this):
        // `git-pocketshell` is taken, so the next one gets `-2`.
        val name = SessionNameDerivation.derive(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            agentCommand = "claude",
            existingNames = setOf("git-pocketshell"),
        )
        assertEquals("git-pocketshell-2", name)
    }

    @Test
    fun agentCollidesWithExistingShellInSameDir() {
        // Because the base no longer carries the agent CLI (#642), an
        // agent session lands on the SAME base as a shell in the same dir
        // and so disambiguates against it: shell took `git-pocketshell`,
        // the agent becomes `git-pocketshell-2`.
        val name = SessionNameDerivation.derive(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            agentCommand = "claude",
            existingNames = setOf("git-pocketshell"),
        )
        assertEquals("git-pocketshell-2", name)
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
        assertEquals("git-pocketshell", a)
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
