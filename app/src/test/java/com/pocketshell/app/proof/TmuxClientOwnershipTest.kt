package com.pocketshell.app.proof

import com.pocketshell.testsupport.FOREIGN_TMUX_CLIENT_IGNORED_SIGNATURE
import com.pocketshell.testsupport.GRACE_HELD_BY_PORT_FORWARD_PIN_SIGNATURE
import com.pocketshell.testsupport.LEAKED_CONTROL_MODE_CLIENT_SIGNATURE
import com.pocketshell.testsupport.TMUX_CLIENT_FIELD_SEPARATOR
import com.pocketshell.testsupport.TMUX_CLIENT_OWNERSHIP_FORMAT
import com.pocketshell.testsupport.UNEXPECTED_NEW_TMUX_CLIENT_SIGNATURE
import com.pocketshell.testsupport.UNNAMED_TMUX_CLIENT
import com.pocketshell.testsupport.describeOwnedDetachFailure
import com.pocketshell.testsupport.evaluateOwnedClientDetach
import com.pocketshell.testsupport.parseTmuxClients
import com.pocketshell.testsupport.resolveOwnedClientNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1994 — per-push pin for the owned-vs-foreign tmux client decision.
 *
 * The nightly failure this guards is:
 *
 * ```
 * expected zero tmux clients on claude-main after app close grace elapsed;
 * raw=`/dev/pts/24: claude-main [62x xterm-256color] (attached,focused,control-mode,UTF-8)`
 * expected:<0> but was:<1>
 * ```
 *
 * A TOTAL count cannot say whether that client is PocketShell's orphan or a
 * client some earlier test on the same 300-test shard left behind (tmux migrates
 * a client to another session when its own session is killed, so a stray client
 * lands on the shared fixture session by itself). These tests pin that the
 * decision is made by client IDENTITY, and — critically — that it stays strict
 * in the direction of blaming the product.
 */
class TmuxClientOwnershipTest {

    private val sep = TMUX_CLIENT_FIELD_SEPARATOR

    @Test
    fun parsesTheOwnershipFormatIncludingControlModeAndFlags() {
        val clients = parseTmuxClients(
            "/dev/pts/24${sep}1${sep}attached,focused,control-mode,UTF-8\n" +
                "/dev/pts/9${sep}0${sep}attached,UTF-8\n",
        )

        assertEquals(2, clients.size)
        assertEquals("/dev/pts/24", clients[0].name)
        assertTrue(clients[0].controlMode)
        assertEquals("attached,focused,control-mode,UTF-8", clients[0].flags)
        assertEquals("/dev/pts/9", clients[1].name)
        assertFalse(clients[1].controlMode)
    }

    @Test
    fun blankAndTrailingLinesAreNotCountedAsClients() {
        // `tmux list-clients … || true` emits a trailing newline, and an empty
        // server prints nothing. Counting those as clients is how a passing
        // journey turns into a phantom orphan.
        assertEquals(0, parseTmuxClients("").size)
        assertEquals(0, parseTmuxClients("\n\n").size)
        assertEquals(1, parseTmuxClients("/dev/pts/3${sep}1${sep}attached\n\n").size)
    }

    @Test
    fun aRowThatDoesNotMatchTheFormatIsKeptRatherThanSilentlyDropped() {
        // Dropping an unparsable row would convert a real orphan into a green.
        val clients = parseTmuxClients("/dev/pts/24\n")

        assertEquals(1, clients.size)
        assertEquals("/dev/pts/24", clients[0].name)

        val unnamed = parseTmuxClients("${sep}1${sep}attached,control-mode\n")
        assertEquals(1, unnamed.size)
        assertEquals(UNNAMED_TMUX_CLIENT, unnamed[0].name)
        assertTrue(unnamed[0].controlMode)
    }

    @Test
    fun controlModeIsRecognisedFromTheFlagListWhenTheBooleanFieldIsMissing() {
        val clients = parseTmuxClients(
            "/dev/pts/24$sep${sep}attached,focused,control-mode,UTF-8\n",
        )

        assertTrue(clients[0].controlMode)
    }

    @Test
    fun theFieldSeparatorIsPrintableBecauseTmuxSanitisesControlCharacters() {
        // The first cut of #1994 used a literal TAB. tmux prints list-clients
        // output through its client message path, which replaces control
        // characters with `_` (observed on the fixture's tmux 3.6b:
        // `list-sessions -F '#{session_name}<TAB>X'` printed `fmtprobe2_X_$0`).
        // The row then never splits, every field collapses into the client NAME,
        // ownership matching silently matches nothing, and the proof degrades to
        // nonsense while still looking structured. A printable separator is the
        // fix, and this is the check that would have caught it.
        assertTrue(
            "the field separator must be printable ASCII; tmux replaces control " +
                "characters with '_' and the row would never split",
            TMUX_CLIENT_FIELD_SEPARATOR.isNotEmpty() &&
                TMUX_CLIENT_FIELD_SEPARATOR.all { it.code in 0x20..0x7E },
        )
        assertFalse(
            "the separator must not contain a comma — client_flags is comma-separated",
            TMUX_CLIENT_FIELD_SEPARATOR.contains(','),
        )
        assertEquals(
            "the format must carry exactly two separators (three fields)",
            2,
            TMUX_CLIENT_OWNERSHIP_FORMAT.split(TMUX_CLIENT_FIELD_SEPARATOR).size - 1,
        )
    }

    @Test
    fun aTmuxSanitisedRowDoesNotSilentlyProduceAMatchableClientName() {
        // Exactly what the broken TAB separator produced on the device. It must
        // not resolve to a real client name that could accidentally match a
        // recorded baseline or owned entry.
        val sanitised = parseTmuxClients("/dev/pts/1_0_attached,focused,UTF-8\n")

        assertEquals(1, sanitised.size)
        assertEquals("/dev/pts/1_0_attached,focused,UTF-8", sanitised[0].name)
        assertFalse(
            "a collapsed row must not be attributed to a real client name",
            sanitised[0].name == "/dev/pts/1",
        )
    }

    @Test
    fun ownedNamesAreTheClientsThatAppearedAfterTheForeignBaseline() {
        val owned = resolveOwnedClientNames(
            foreignBaseline = setOf("/dev/pts/9"),
            attachedSnapshot = parseTmuxClients(
                "/dev/pts/9${sep}0${sep}attached\n" +
                    "/dev/pts/24${sep}1${sep}attached,control-mode\n",
            ),
        )

        assertEquals(setOf("/dev/pts/24"), owned)
    }

    @Test
    fun theAppsOwnClientSurvivingIsStillAHardFailure() {
        // The genuine #215 orphan regression must not become tolerable.
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = emptySet(),
            ownedNames = setOf("/dev/pts/24"),
            current = parseTmuxClients(
                "/dev/pts/24${sep}1${sep}attached,focused,control-mode,UTF-8\n",
            ),
        )

        assertFalse(verdict.detached)
        assertEquals(1, verdict.ownedRemaining.size)
        assertTrue(verdict.diagnosis().contains("/dev/pts/24"))
    }

    @Test
    fun aForeignPlainClientLeftByAnEarlierTestDoesNotFailTheOwnedDetachProof() {
        // Exactly one client remains on the session, but it is a PLAIN sidecar
        // `tmux attach` this journey never created — the shape an earlier class
        // leaves behind. Only a plain client may be ignored.
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = setOf("/dev/pts/24"),
            ownedNames = setOf("/dev/pts/31"),
            current = parseTmuxClients(
                "/dev/pts/24${sep}0${sep}attached,focused,UTF-8\n",
            ),
        )

        assertTrue(verdict.detached)
        assertEquals(1, verdict.foreignRemaining.size)
        assertTrue(verdict.diagnosis().contains(FOREIGN_TMUX_CLIENT_IGNORED_SIGNATURE))
    }

    @Test
    fun aControlModeClientInTheBaselineIsNeverLaunderedIntoForeign() {
        // #1994 round 2 — the laundering hole. On nightly shard 2 the surviving
        // client was `[62x xterm-256color] (attached,focused,control-mode,UTF-8)`
        // — the app's own `-CC` geometry. A baseline-only definition of "foreign"
        // would file a `-CC` client leaked by an EARLIER class under "ignore" and
        // pass, which is strictly worse than the total-count oracle it replaced.
        // Nothing but PocketShell opens control mode on this fixture, so
        // control-mode implies app-owned regardless of when it attached.
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = setOf("/dev/pts/24"),
            ownedNames = setOf("/dev/pts/31"),
            current = parseTmuxClients(
                "/dev/pts/24${sep}1${sep}attached,focused,control-mode,UTF-8\n",
            ),
        )

        assertFalse(
            "a control-mode client present at baseline time is a leaked PocketShell " +
                "client, not a foreign one — it must fail",
            verdict.detached,
        )
        assertEquals(1, verdict.leakedControlRemaining.size)
        assertTrue(verdict.foreignRemaining.isEmpty())
        assertTrue(verdict.diagnosis().contains(LEAKED_CONTROL_MODE_CLIENT_SIGNATURE))
        assertFalse(
            "a leaked -CC client must not also be reported as ignored-foreign",
            verdict.diagnosis().contains(FOREIGN_TMUX_CLIENT_IGNORED_SIGNATURE),
        )
    }

    @Test
    fun aLeakedControlClientAndAPlainForeignClientAreClassifiedSeparately() {
        // Class coverage: both contaminations at once. The plain one is ignored,
        // the control-mode one is fatal, and the diagnosis names both.
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = setOf("/dev/pts/9", "/dev/pts/24"),
            ownedNames = setOf("/dev/pts/31"),
            current = parseTmuxClients(
                "/dev/pts/9${sep}0${sep}attached,UTF-8\n" +
                    "/dev/pts/24${sep}1${sep}attached,control-mode,UTF-8\n",
            ),
        )

        assertFalse(verdict.detached)
        assertEquals(listOf("/dev/pts/24"), verdict.leakedControlRemaining.map { it.name })
        assertEquals(listOf("/dev/pts/9"), verdict.foreignRemaining.map { it.name })
        assertTrue(verdict.diagnosis().contains(LEAKED_CONTROL_MODE_CLIENT_SIGNATURE))
        assertTrue(verdict.diagnosis().contains(FOREIGN_TMUX_CLIENT_IGNORED_SIGNATURE))
    }

    @Test
    fun anActivePortForwardPinIsNamedAsTheCauseAndTheVerdictStaysAFailure() {
        // The mechanism that actually produced the hosted red on run
        // 30961154855 shard 2: `PsAppBgGrace: grace-window-held-by-port-forward
        // (always-on)` fired at the grace deadline, so App.kt SUPPRESSED the
        // bounded teardown and the app's own -CC client stayed attached by
        // design. The oracle must name that cause instead of reporting a plain
        // product orphan — and must STILL fail (attribution is never amnesty).
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = emptySet(),
            ownedNames = setOf("/dev/pts/24"),
            current = parseTmuxClients(
                "/dev/pts/24${sep}1${sep}attached,focused,control-mode,UTF-8\n",
            ),
        )

        assertFalse(
            "a pin-held client is still a failure — naming the cause must never " +
                "convert a surviving client into a pass",
            verdict.detached,
        )

        val pinned = describeOwnedDetachFailure(
            verdict = verdict,
            portForwardPinActive = true,
            sessionName = "claude-main",
            rawClients = "/dev/pts/24: claude-main [62x xterm-256color] " +
                "(attached,focused,control-mode,UTF-8)",
        )
        assertTrue(
            "the failure must NAME the port-forward pin, not blame the product",
            pinned!!.contains(GRACE_HELD_BY_PORT_FORWARD_PIN_SIGNATURE),
        )
        assertTrue(pinned.contains("port_forward_pin_active=true"))
        assertFalse(
            "with a pin active the message must not call this a genuine detach regression",
            pinned.contains("genuine detach regression"),
        )
    }

    @Test
    fun withoutAPinTheSameSurvivingClientIsReportedAsAGenuineDetachRegression() {
        // The other half of the discrimination the issue asks for: with no pin
        // active there is nothing to excuse the surviving client, so the message
        // must point at the product.
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = emptySet(),
            ownedNames = setOf("/dev/pts/24"),
            current = parseTmuxClients(
                "/dev/pts/24${sep}1${sep}attached,focused,control-mode,UTF-8\n",
            ),
        )

        val unpinned = describeOwnedDetachFailure(
            verdict = verdict,
            portForwardPinActive = false,
            sessionName = "claude-main",
            rawClients = "/dev/pts/24…",
        )
        assertTrue(unpinned!!.contains("genuine detach regression"))
        assertTrue(unpinned.contains("port_forward_pin_active=false"))
        assertFalse(unpinned.contains(GRACE_HELD_BY_PORT_FORWARD_PIN_SIGNATURE))
    }

    @Test
    fun aCleanVerdictNeverProducesAFailureMessageEvenWhileAPinIsActive() {
        // Guard against the inverse mistake: an active pin must not manufacture
        // a red out of a journey whose clients really did detach.
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = setOf("/dev/pts/9"),
            ownedNames = setOf("/dev/pts/24"),
            current = parseTmuxClients("/dev/pts/9${sep}0${sep}attached\n"),
        )

        assertTrue(verdict.detached)
        assertNull(
            describeOwnedDetachFailure(
                verdict = verdict,
                portForwardPinActive = true,
                sessionName = "claude-main",
                rawClients = "…",
            ),
        )
    }

    @Test
    fun aClientThatAppearedAfterTheAppAttachedIsAHardFailure() {
        // A post-teardown re-dial is a D21 violation and must never be excused
        // by "well, it is not the client we recorded at attach time".
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = setOf("/dev/pts/9"),
            ownedNames = setOf("/dev/pts/24"),
            current = parseTmuxClients("/dev/pts/77${sep}1${sep}attached,control-mode\n"),
        )

        assertFalse(verdict.detached)
        assertEquals(1, verdict.unexpectedNew.size)
        assertTrue(verdict.diagnosis().contains(UNEXPECTED_NEW_TMUX_CLIENT_SIGNATURE))
    }

    @Test
    fun anEmptySessionIsDetachedAndSaysSoWithoutForeignNoise() {
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = setOf("/dev/pts/9"),
            ownedNames = setOf("/dev/pts/24"),
            current = parseTmuxClients(""),
        )

        assertTrue(verdict.detached)
        assertFalse(verdict.diagnosis().contains(FOREIGN_TMUX_CLIENT_IGNORED_SIGNATURE))
        assertFalse(verdict.diagnosis().contains(UNEXPECTED_NEW_TMUX_CLIENT_SIGNATURE))
    }

    @Test
    fun aForeignClientAndTheAppsOwnClientTogetherStillFailOnTheOwnedOne() {
        val verdict = evaluateOwnedClientDetach(
            foreignBaseline = setOf("/dev/pts/9"),
            ownedNames = setOf("/dev/pts/24"),
            current = parseTmuxClients(
                "/dev/pts/9${sep}0${sep}attached\n" +
                    "/dev/pts/24${sep}1${sep}attached,control-mode\n",
            ),
        )

        assertFalse(verdict.detached)
        assertEquals(1, verdict.ownedRemaining.size)
        assertEquals(1, verdict.foreignRemaining.size)
    }
}
