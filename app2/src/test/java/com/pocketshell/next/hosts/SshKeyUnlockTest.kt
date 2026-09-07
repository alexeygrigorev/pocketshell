package com.pocketshell.next.hosts

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

/** The unlock surface is a real native-prompt handoff, not preview state. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SshKeyUnlockTest {

    @Test
    fun `in-flight gate blocks repeated prompt launches`() {
        val gate = SshKeyUnlockInFlightGate()

        assertTrue(gate.tryMarkInFlight())
        assertFalse(gate.tryMarkInFlight())
        gate.clear()
        assertTrue(gate.tryMarkInFlight())
    }

    @Test
    fun `null activity is reported without pretending to unlock`() {
        var success = false
        var error: String? = null

        launchSshKeyUnlock(
            activity = null,
            onSuccess = { success = true },
            onError = { error = it },
        )

        assertFalse(success)
        assertEquals("Device unlock is unavailable from this screen", error)
    }

    @Test
    fun `native prompt launcher receives the production callback contract`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .setup()
            .get()
        var promptTitle: CharSequence? = null
        var promptError: String? = null

        launchSshKeyUnlock(
            activity = activity,
            promptLauncher = object : SshKeyUnlockPromptLauncher {
                override fun launch(
                    activity: FragmentActivity,
                    promptInfo: BiometricPrompt.PromptInfo,
                    callback: BiometricPrompt.AuthenticationCallback,
                ) {
                    promptTitle = promptInfo.title
                }
            },
            onSuccess = {},
            onError = { promptError = it },
        )

        assertEquals("Unlock SSH keys", promptTitle)
        assertEquals(null, promptError)
    }

    @Test
    fun `prompt construction failures surface as errors`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .setup()
            .get()
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
            onSuccess = {},
            onError = { error = it },
        )

        assertEquals(
            "Could not start device unlock: fragment transaction already pending",
            error,
        )
    }
}
