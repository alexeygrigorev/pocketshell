package com.pocketshell.app.tmux

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #2323 / #2295 — non-durable HostAck delivery tokens must not be a
 * process-local counter.
 *
 * The host journal (`$XDG_STATE_HOME/pocketshell/sends/<sha256(token)>.json`)
 * is durable and keyed only by the token hash. `d1`, `d2`, … restart at `d1`
 * after every app process recreation, so a genuinely new payload is
 * suppressed as `already-delivered` (CI shard 1:
 * `Issue2189HostAckSubmitJourneyE2eTest`).
 *
 * Mutation that must redden: restore
 * `"d${deliveryTokenCounter.incrementAndGet()}"`. [processLocalCounterFormIsRejected]
 * fails on `d1`, and [recreatingTheClientDoesNotReuseAPriorToken] collides
 * after the simulated restart reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2323NonDurableDeliveryTokenUniquenessTest {

    @Test
    fun processLocalCounterFormIsRejected() {
        repeat(32) {
            val token = newOutboundDeliveryToken()
            assertTrue(
                "non-durable HostAck tokens must not be process-local counters " +
                    "(d1, d2, … collide across restarts/reinstalls and suppress a " +
                    "new payload as already-delivered); token=$token",
                !PROCESS_LOCAL_COUNTER.matches(token),
            )
            UUID.fromString(token)
        }
    }

    @Test
    fun recreatingTheClientDoesNotReuseAPriorToken() {
        val firstClient = mintBatch()
        simulateProcessRestart()
        val secondClient = mintBatch()
        val overlap = firstClient.intersect(secondClient)
        assertTrue(
            "a recreated client reused tokens $overlap; the host journal is " +
                "durable and keyed only by token hash, so reuse suppresses a " +
                "new payload as already-delivered",
            overlap.isEmpty(),
        )
        val all = firstClient + secondClient
        assertEquals(all.size, all.toSet().size)
        all.forEach { token ->
            assertTrue(
                "token=$token must not be the process-local counter form",
                !PROCESS_LOCAL_COUNTER.matches(token),
            )
            UUID.fromString(token)
        }
    }

    private fun mintBatch(size: Int = 64): Set<String> =
        (1..size).map { newOutboundDeliveryToken() }.toSet()

    /**
     * A new app process starts the old AtomicLong at 0. If production still
     * has that counter, reset it so this test observes the restart collision.
     */
    private fun simulateProcessRestart() {
        val holder = runCatching {
            Class.forName("com.pocketshell.app.tmux.OutboundDeliveryGuardKt")
        }.getOrNull() ?: return
        holder.declaredFields
            .filter { it.type == AtomicLong::class.java }
            .forEach { field ->
                field.isAccessible = true
                (field.get(null) as AtomicLong).set(0)
            }
    }

    private companion object {
        val PROCESS_LOCAL_COUNTER = Regex("^d\\d+$")
    }
}
