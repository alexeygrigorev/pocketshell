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
        val name = SessionNameDerivation.baseName(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
        )
        assertEquals("git-pocketshell", name)
    }

    @Test
    fun agentUnderHomeAbsolutePathMatchesTildeForm() {
        val name = SessionNameDerivation.baseName(
            startDirectory = "/home/alexey/git/pocketshell",
            homeDirectory = home,
        )
        assertEquals("git-pocketshell", name)
    }

    @Test
    fun agentAndShellInSameDirDeriveSameBaseName() {
        // #642: the name is a pure path-prefix; the agent CLI no longer
        // decorates it, so agent + shell in the same dir share a base.
        val agent = SessionNameDerivation.baseName(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
        )
        val shell = SessionNameDerivation.baseName(
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
        )
        assertEquals("git-pocketshell", agent)
        assertEquals(agent, shell)
    }

    @Test
    fun dataEngineeringZoomcampAgentDropsAgentPrefix() {
        // The exact regression the maintainer reported (#642): an agent
        // session in `~/git/data-engineering-zoomcamp` must read
        // `git-data-engineering-zoomcamp`, NOT `claude-git-…`.
        val name = SessionNameDerivation.baseName(
            startDirectory = "~/git/data-engineering-zoomcamp",
            homeDirectory = home,
        )
        assertEquals("git-data-engineering-zoomcamp", name)
    }

    // --- Acceptance criterion: shell session outside home ---

    @Test
    fun shellOutsideHomeUsesAbsoluteComponents() {
        val name = SessionNameDerivation.baseName(
            startDirectory = "/var/log",
            homeDirectory = home,
        )
        assertEquals("var-log", name)
    }

    @Test
    fun outsideHomeWithoutKnownHomeStillUsesAbsoluteComponents() {
        val name = SessionNameDerivation.baseName(
            startDirectory = "/var/log",
            homeDirectory = null,
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
        val name = SessionNameDerivation.baseName(
            startDirectory = "~/my.project.v2",
            homeDirectory = home,
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
            SessionNameDerivation.baseName("~/a.b:c", home),
            SessionNameDerivation.baseName("/etc/ssh.d", null),
            SessionNameDerivation.baseName("~", home),
        )
        samples.forEach { assertNoTmuxForbidden(it) }
    }

    // --- Issue #1820, D22 hard cut: the client-side collision-disambiguation
    // cases are DELETED along with the code they covered
    // (`SessionNameDerivation.derive(…, existingNames)` and the private
    // `disambiguate(…)`). They asserted a behaviour that no longer exists and
    // must not come back: `collisionAppendsDeterministicSuffix`,
    // `agentCollidesWithExistingShellInSameDir`, `collisionWalksUpUntilFreeSlot`,
    // `noCollisionKeepsBaseName`, `customNameCollidingWithExistingIsDisambiguated`,
    // `customNameCollisionWalksUpUntilFreeSlot`.
    //
    // The `-2`/`-3` walk itself is NOT untested — it moved to where it now runs:
    // the host-side resolver (`FolderListGatewayFallbackTest`'s
    // `freeSessionNameCommand` + UniqueOnHost cases, incl. the exact-match
    // assertion) and the connected `TmuxInSessionNewSessionCollisionDockerTest`.
    //
    // `emptyExistingNamesDerivesTheBareCollidingBase` (#976) survives below as
    // `derivationNeverDisambiguatesEvenForAKnownLiveBase`: its point — the
    // deriver hands back the bare colliding base — is now the ONLY behaviour
    // rather than the empty-cache edge, so it is worth pinning permanently. ---

    @Test
    fun derivationNeverDisambiguatesEvenForAKnownLiveBase() {
        // Issue #976 -> #1820. The old bug: the de-dupe input came from a UI
        // cache that collapsed to ∅ whenever the picker was not `Ready`, so the
        // deriver returned the bare base, which COLLIDED with the live
        // same-folder session. #1820 removed the client-side opinion entirely —
        // so the bare base is now the deliberate, always-taken output, and the
        // HOST turns it into `-2` at create time. Pinning it here means anyone
        // reintroducing a client-side `existingNames` parameter breaks a test.
        assertEquals(
            "git-pocketshell",
            SessionNameDerivation.baseName("~/git/pocketshell", home),
        )
        assertEquals(
            "git-pocketshell",
            SessionNameDerivation.resolveSessionName(
                customName = null,
                startDirectory = "~/git/pocketshell",
                homeDirectory = home,
            ),
        )
    }

    @Test
    fun noRandomTimestampSuffix() {
        // The old behaviour appended a 6-digit `currentTimeMillis()`
        // suffix; the name must now be fully deterministic.
        val a = SessionNameDerivation.baseName("~/git/pocketshell", home)
        val b = SessionNameDerivation.baseName("~/git/pocketshell", home)
        assertEquals(a, b)
        assertEquals("git-pocketshell", a)
    }

    // --- Issue #1820: the public derivedSessionName(choice, …) wrapper — what
    // BOTH the host screen AND the in-session "+ New session" sheet call — now
    // returns the BASE name and nothing else.
    //
    // It used to take an `existingNames` set from the screen's own UI cache and
    // subtract it, which is how a stale/empty cache produced a colliding name
    // (#1820: the picker published `Ready` with a list that omitted the session
    // the app was attached to, so the "second session in this folder" asked for
    // the name that was already taken). Collision resolution moved to the host,
    // at create time, in FolderListGateway with SessionNamePolicy.UniqueOnHost.
    //
    // So these pin the SPLIT, which is the property that keeps the two deciders
    // from coming back: the wrapper is purely a name DERIVER, it never claims a
    // name is free. The suffix behaviour itself is covered where it now lives
    // (FolderListGatewayFallbackTest's UniqueOnHost cases + the connected
    // TmuxInSessionNewSessionCollisionDockerTest). ---

    @Test
    fun derivedSessionNameWrapperReturnsTheBaseAndNeverDisambiguates() {
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "/tmp/issue898",
            skipPermissions = false,
        )
        // Even for a folder whose base name is already live on the host, the
        // wrapper hands back the bare base: it has no way to know, and pretending
        // otherwise is exactly the defect. The gateway suffixes it.
        val name = derivedSessionName(
            choice = choice,
            homeDirectory = "/home/testuser",
        )
        assertEquals("tmp-issue898", name)
    }

    @Test
    fun derivedSessionNameWrapperIsStableAcrossRepeatedCallsForTheSameFolder() {
        // Two "+ New session" taps in the same folder derive the IDENTICAL base.
        // That is now correct-by-design rather than the bug it used to be: the
        // host turns the second one into `-2`. Pinning the equality stops anyone
        // reintroducing a client-side "remember what I just created" set.
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "/tmp/issue898",
            skipPermissions = false,
        )
        val first = derivedSessionName(choice = choice, homeDirectory = "/home/testuser")
        val second = derivedSessionName(choice = choice, homeDirectory = "/home/testuser")
        assertEquals(first, second)
        assertEquals("tmp-issue898", first)
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
    fun customLabelIsReturnedVerbatimWithNoCollisionSuffix() {
        // Issue #1184's "a duplicate label must never silently attach to another
        // session's tmux" acceptance still holds — but #1820 moved WHERE it is
        // enforced. `resolveSessionName` no longer takes `existingNames` and no
        // longer suffixes; the host does, at create time, against its live
        // session list. Repeated calls therefore return the SAME label, and that
        // is correct-by-design.
        val first = SessionNameDerivation.resolveSessionName(
            customName = "review",
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
        )
        val second = SessionNameDerivation.resolveSessionName(
            customName = "review",
            startDirectory = "~/git/pocketshell",
            homeDirectory = home,
        )
        assertEquals("review", first)
        assertEquals(first, second)
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
    fun derivedSessionNameWrapperKeepsCustomLabelVerbatim() {
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "~/git/pocketshell",
            customName = "review",
        )
        val name = derivedSessionName(
            choice = choice,
            homeDirectory = home,
        )
        // Issue #1820: a custom label is sanitised and returned as-is; whether
        // "review" is already taken is the host's call at create time, not this
        // function's. `SessionNameDerivation.resolveSessionName` still carries
        // the disambiguation primitive (tested above) — the gateway is what
        // feeds it the live names now.
        assertEquals("review", name)
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

    // --- conventionalRemoteHome helper (issue #1820 deleted knownSessionNames) ---

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
