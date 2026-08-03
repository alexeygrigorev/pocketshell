package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.SessionId
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
}
