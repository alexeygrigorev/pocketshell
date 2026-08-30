package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.SessionId
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraceEffectsRecoveryOwnershipTest {

    private val effects = GraceEffects(
        object : GraceEffects.GraceIo {
            override fun launchBackgroundDetachTeardown() = Unit
            override fun launchForegroundReattachReseed() = Unit
            override fun launchForegroundHealWithinGrace() = Unit
        },
    )

    @Test
    fun `ownership is target and client keyed and stale release cannot clear successor`() {
        val target = SessionId("target")
        val first = WithinGraceRecoveryClaim(target, GraceClientId(1))
        val successor = WithinGraceRecoveryClaim(target, GraceClientId(2))

        effects.beginWithinGraceRecovery(first)
        assertTrue(effects.ownsRecovery(target, GraceClientId(1)))
        assertFalse(effects.ownsRecovery(target, GraceClientId(2)))
        assertFalse(effects.ownsRecovery(SessionId("other-target"), GraceClientId(1)))

        effects.beginWithinGraceRecovery(successor)
        effects.endWithinGraceRecovery(first)
        assertTrue("a stale owner timer must not release its successor", effects.ownsRecovery(target, GraceClientId(2)))

        effects.endWithinGraceRecovery(successor)
        assertFalse(effects.isWithinGraceRecoveryActive())
    }

    // ---- Issue #2415: retireIfOwnedByOtherSession, the superseding-open primitive ----

    @Test
    fun `retire if owned by other session retires a claim held by a different session`() {
        val abandoned = SessionId("claude-main")
        val tracked = Job()
        effects.beginWithinGraceRecovery(WithinGraceRecoveryClaim(abandoned, GraceClientId(1)))
        effects.trackRecoveryJob(tracked)

        assertTrue(
            "a connect that targets a DIFFERENT session supersedes the bounded owner",
            effects.retireIfOwnedByOtherSession(SessionId("codex")),
        )
        assertFalse("the claim must be released", effects.isWithinGraceRecoveryActive())
        assertTrue(
            "the retired claim's tracked coroutines (its heal loop + hold timer) must be cancelled, " +
                "not left churning the shared per-host transport (#2415)",
            tracked.isCancelled,
        )
    }

    @Test
    fun `retire if owned by other session leaves a same-session claim untouched`() {
        val work = SessionId("work")
        val tracked = Job()
        effects.beginWithinGraceRecovery(WithinGraceRecoveryClaim(work, GraceClientId(1)))
        effects.trackRecoveryJob(tracked)

        assertFalse(
            "a SAME-session re-entry is not a superseding owner",
            effects.retireIfOwnedByOtherSession(work),
        )
        assertTrue(
            "the #1538/#754/#1954 within-grace ride-through depends on the claim surviving its own " +
                "session's re-entry",
            effects.isWithinGraceRecoveryActive(),
        )
        assertTrue("its heal loop must keep running", tracked.isActive)
        assertTrue(
            "the surviving claim still owns the channel for its own target/client",
            effects.ownsRecovery(work, GraceClientId(1)),
        )
    }

    @Test
    fun `retire if owned by other session is a no-op on an idle window`() {
        assertFalse(
            "there is nothing to retire when no bounded recovery is running",
            effects.retireIfOwnedByOtherSession(SessionId("codex")),
        )
        assertFalse(effects.isWithinGraceRecoveryActive())
    }

    @Test
    fun `retire if owned by other session ignores the client identity and keys only on session`() {
        val abandoned = SessionId("claude-main")
        // A REPLACEMENT client for the same abandoned session (the heal loop re-opens `-CC`
        // clients as it retries), so the claim's clientId is not the one the caller ever sees.
        effects.beginWithinGraceRecovery(WithinGraceRecoveryClaim(abandoned, GraceClientId(99)))

        assertFalse(
            "the same session with a replacement control client is still the same owner",
            effects.retireIfOwnedByOtherSession(abandoned),
        )
        assertTrue(effects.isWithinGraceRecoveryActive())
        assertTrue(
            "a different session retires it regardless of which client the claim holds",
            effects.retireIfOwnedByOtherSession(SessionId("codex")),
        )
    }

    @Test
    fun `a claim that also holds no tracked jobs still retires cleanly`() {
        val abandoned = SessionId("claude-main")
        effects.beginWithinGraceRecovery(WithinGraceRecoveryClaim(abandoned, clientId = null))

        assertTrue(effects.retireIfOwnedByOtherSession(SessionId("codex")))
        assertFalse(effects.isWithinGraceRecoveryActive())
        assertFalse(
            "a retired window must not be retired twice",
            effects.retireIfOwnedByOtherSession(SessionId("codex")),
        )
    }

    // ---- Issue #2415: a RETIRED claim must never install the beyond-grace ladder ----

    @Test
    fun `a retired claim is superseded so its grace expiry cannot re-target the abandoned session`() {
        val abandoned = WithinGraceRecoveryClaim(SessionId("claude-main"), GraceClientId(1))
        effects.beginWithinGraceRecovery(abandoned)
        assertFalse(
            "an owning claim is not superseded",
            effects.recoveryWasSuperseded(abandoned),
        )

        effects.retireIfOwnedByOtherSession(SessionId("codex"))

        assertTrue(
            "after a superseding open the abandoned claim must be reported superseded even though " +
                "the window is now Idle — otherwise its grace-expiry handoff installs " +
                "`scheduleAutoReconnect(target = <the session the user left>)` and the dead " +
                "session's `Session “…” has ended.` lands on the sibling's screen (#2415)",
            effects.recoveryWasSuperseded(abandoned),
        )
    }

    @Test
    fun `a claim whose own bounded window expired is NOT superseded and still hands off`() {
        val claim = WithinGraceRecoveryClaim(SessionId("work"), GraceClientId(1))
        effects.beginWithinGraceRecovery(claim)

        // The #754 hold-release timer ends the window at `passiveDisconnectGraceMs`, the same
        // instant the heal retry loop times out. Legitimate exhaustion must still reach the loud
        // auto-reconnect ladder — the retirement marker must not swallow the beyond-grace retry.
        effects.endWithinGraceRecovery(claim)

        assertFalse(effects.isWithinGraceRecoveryActive())
        assertFalse(
            "a bounded window that simply ran out was NOT superseded; suppressing its handoff " +
                "would delete the beyond-grace reconnect entirely",
            effects.recoveryWasSuperseded(claim),
        )
    }

    @Test
    fun `a fresh claim for the same session clears the previous retirement marker`() {
        val claim = WithinGraceRecoveryClaim(SessionId("work"), GraceClientId(1))
        effects.beginWithinGraceRecovery(claim)
        effects.retireIfOwnedByOtherSession(SessionId("codex"))
        assertTrue(effects.recoveryWasSuperseded(claim))

        // The user comes back to `work`, drops within grace again: a NEW bounded window opens for
        // the same claim and owns its own handoff.
        effects.beginWithinGraceRecovery(claim)

        assertFalse(
            "a fresh bounded window must not inherit the previous window's retirement",
            effects.recoveryWasSuperseded(claim),
        )
    }

    @Test
    fun `rearming the identical claim retains ownership of its active heal`() {
        val claim = WithinGraceRecoveryClaim(SessionId("work"), GraceClientId(1))
        val activeHeal = Job()
        effects.beginWithinGraceRecovery(claim)
        effects.trackRecoveryJob(activeHeal)

        effects.beginWithinGraceRecovery(claim)
        effects.retireForSupersedingOwner()

        assertTrue(
            "same-claim foreground re-arm must retain the heal so a later owner can cancel it",
            activeHeal.isCancelled,
        )
    }

    @Test
    fun `replacing a different claim cancels every job owned by the prior claim`() {
        val first = WithinGraceRecoveryClaim(SessionId("work"), GraceClientId(1))
        val successor = WithinGraceRecoveryClaim(SessionId("other"), GraceClientId(2))
        val holdTimer = Job()
        val activeHeal = Job()
        effects.beginWithinGraceRecovery(first)
        effects.trackRecoveryJob(holdTimer)
        effects.trackRecoveryJob(activeHeal)

        effects.beginWithinGraceRecovery(successor)

        assertTrue(holdTimer.isCancelled)
        assertTrue(activeHeal.isCancelled)
        assertTrue(effects.ownsRecovery(successor.targetId, successor.clientId!!))
    }
}
