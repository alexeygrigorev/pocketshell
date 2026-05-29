package com.pocketshell.app.hosts

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SshKeysScreenUnlockTest {

    @Test
    fun inFlightGate_ignoresRepeatedUnlockTapsUntilPromptFinishes() {
        val gate = SshKeyUnlockInFlightGate()

        assertTrue(gate.tryMarkInFlight())
        assertTrue(gate.isInFlight)

        assertFalse(gate.tryMarkInFlight())
        assertTrue(gate.isInFlight)

        gate.clear()

        assertFalse(gate.isInFlight)
        assertTrue(gate.tryMarkInFlight())
    }

    @Test
    fun launchSshKeyUnlock_reportsNullActivityInsteadOfCrashing() {
        var successCalled = false
        var error: String? = null

        launchSshKeyUnlock(
            activity = null,
            onSuccess = { successCalled = true },
            onError = { error = it },
        )

        assertFalse(successCalled)
        assertEquals("Device unlock is unavailable from this screen", error)
    }

    @Test
    fun launchSshKeyUnlock_catchesPromptLauncherFailure() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .setup()
            .get()
        var successCalled = false
        var error: String? = null

        launchSshKeyUnlock(
            activity = activity,
            promptLauncher = object : SshKeyUnlockPromptLauncher {
                override fun launch(
                    activity: FragmentActivity,
                    promptInfo: BiometricPrompt.PromptInfo,
                    callback: BiometricPrompt.AuthenticationCallback,
                ) {
                    throw IllegalStateException("fragment transaction already pending")
                }
            },
            onSuccess = { successCalled = true },
            onError = { error = it },
        )

        assertFalse(successCalled)
        assertEquals(
            "Could not start device unlock: fragment transaction already pending",
            error,
        )
    }
}
