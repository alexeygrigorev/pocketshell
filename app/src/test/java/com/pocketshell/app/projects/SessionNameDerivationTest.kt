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
    fun emptyExistingNamesDerivesTheBareCollidingBase() {
        // Issue #976: when the picker isn't `Ready` (a #974 drop / still-loading
        // list) the de-dupe input collapses to ∅, so the deriver CANNOT add a
        // `-2` suffix — it returns the bare base, which COLLIDES with the live
        // same-folder session. This is the input that triggers the misroute; the
        // server-side has-session guard in the gateway is what then refuses the
        // launch instead of typing it into the existing pane.
        val name = SessionNameDerivation.derive(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            agentCommand = "codex",
            existingNames = emptySet(),
        )
        assertEquals("git-pocketshell", name)
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

    // --- Issue #898 (Finding 1): the public derivedSessionName(choice, …)
    // wrapper is exactly what BOTH the host screen AND the in-session kebab "+
    // New session" sheet now call. These pin that the wrapper threads
    // `existingNames` into the deterministic `-2` suffix so a same-folder second
    // session does NOT collide (the in-session path previously omitted
    // `existingNames`, so a second "New session" in the current folder derived
    // the IDENTICAL name and the `tmux new-session -A` create silently no-op'd
    // onto the existing session). ---

    @Test
    fun derivedSessionNameWrapperSuffixesWhenFolderNameIsKnown() {
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "/tmp/issue898",
            skipPermissions = false,
        )
        // Same folder, but the base name is already taken on the host → the
        // wrapper must return the `-2` variant, not the colliding base.
        val name = derivedSessionName(
            choice = choice,
            homeDirectory = "/home/testuser",
            existingNames = setOf("tmp-issue898"),
        )
        assertEquals("tmp-issue898-2", name)
    }

    @Test
    fun derivedSessionNameWrapperWithoutKnownNamesCollidesOnBase() {
        // This is the PRE-FIX behaviour the in-session path had: no known names
        // passed in, so a second session in the same folder derives the SAME
        // base — which `new-session -A` collapses onto the existing session.
        // The fix is to pass the known names (the test above); this pins the
        // contrast so a regression that drops `existingNames` is visible.
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "/tmp/issue898",
            skipPermissions = false,
        )
        val name = derivedSessionName(
            choice = choice,
            homeDirectory = "/home/testuser",
        )
        assertEquals("tmp-issue898", name)
    }

    // --- Issue #1184: user-entered custom session label (resolveSessionName /
    // the derivedSessionName(choice, …) wrapper carrying choice.customName).
    // These pin every acceptance criterion: default accepted → derived name;
    // custom sanitised; collision disambiguated (never silently attaches to a
    // different session's tmux); blank → derived default. ---

    @Test
    fun customNameNullFallsBackToDerivedDefault() {
        // Acceptance: accepting the prefilled default unchanged reproduces
        // today's derived-name behaviour (no regression).
        val name = SessionNameDerivation.resolveSessionName(
            customName = null,
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
        )
        assertEquals("git-pocketshell", name)
    }

    @Test
    fun customNameEqualToDerivedDefaultIsUnchanged() {
        // The picker prefills the field with the derived base; submitting it
        // verbatim must yield exactly the derived name.
        val name = SessionNameDerivation.resolveSessionName(
            customName = "git-pocketshell",
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
        )
        assertEquals("git-pocketshell", name)
    }

    @Test
    fun customNameWithSpacesIsSanitisedToValidTmuxName() {
        val name = SessionNameDerivation.resolveSessionName(
            customName = "git pocketshell review",
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
        )
        assertEquals("git-pocketshell-review", name)
        assertNoTmuxForbidden(name)
    }

    @Test
    fun customNameWithDotsAndColonsIsSanitised() {
        // tmux forbids `.` and `:` — they must collapse to `_`.
        val name = SessionNameDerivation.resolveSessionName(
            customName = "my.session:name",
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
        )
        assertEquals("my_session_name", name)
        assertNoTmuxForbidden(name)
    }

    @Test
    fun customNameCollidingWithExistingIsDisambiguated() {
        // Acceptance: a custom label that collides with an existing session
        // must be disambiguated, never silently attach to a different
        // session's tmux (which `new-session -A` would otherwise do).
        val name = SessionNameDerivation.resolveSessionName(
            customName = "review",
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            existingNames = setOf("review"),
        )
        assertEquals("review-2", name)
    }

    @Test
    fun customNameCollisionWalksUpUntilFreeSlot() {
        val name = SessionNameDerivation.resolveSessionName(
            customName = "review",
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
            existingNames = setOf("review", "review-2", "review-3"),
        )
        assertEquals("review-4", name)
    }

    @Test
    fun blankCustomNameFallsBackToDerivedDefault() {
        // Acceptance: an empty/blank custom name falls back to the derived
        // default. Covers "", whitespace-only, and punctuation-only (which
        // sanitises to empty).
        listOf("", "   ", "...", ":::", "---").forEach { blank ->
            val name = SessionNameDerivation.resolveSessionName(
                customName = blank,
                startDirectory = "~/git/pocketshell",
                homeDirectory = home,
            )
            assertEquals("git-pocketshell", name)
        }
    }

    @Test
    fun sanitiseNameNeverContainsTmuxForbiddenCharacters() {
        listOf("a.b:c", "weird name!", "tab\tsep", "slash/path").forEach {
            assertNoTmuxForbidden(SessionNameDerivation.sanitiseName(it))
        }
    }

    @Test
    fun derivedSessionNameWrapperUsesCustomLabel() {
        // The UI carries the user's label on choice.customName; the wrapper
        // must sanitise + use it instead of the directory-derived default.
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "~/git/pocketshell",
            customName = "git pocketshell review",
        )
        val name = derivedSessionName(
            choice = choice,
            homeDirectory = home,
        )
        assertEquals("git-pocketshell-review", name)
    }

    @Test
    fun derivedSessionNameWrapperDisambiguatesCustomLabel() {
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "~/git/pocketshell",
            customName = "review",
        )
        val name = derivedSessionName(
            choice = choice,
            homeDirectory = home,
            existingNames = setOf("review"),
        )
        assertEquals("review-2", name)
    }

    @Test
    fun derivedSessionNameWrapperBlankCustomFallsBackToDerived() {
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "~/git/pocketshell",
            customName = "   ",
        )
        val name = derivedSessionName(
            choice = choice,
            homeDirectory = home,
        )
        assertEquals("git-pocketshell", name)
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
