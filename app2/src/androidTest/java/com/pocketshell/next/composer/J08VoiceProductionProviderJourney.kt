package com.pocketshell.next.composer

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.next.MainActivity
import com.pocketshell.next.voice.AndroidSpeechRecognitionProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Journey J08 successor (#2529) — starting the **production** Android
 * `SpeechRecognizer` provider must not crash the process.
 *
 * J08's `TestVoiceModule` replaces [com.pocketshell.next.di.VoiceModule] for
 * every `androidTest`, so tapping the composer mic still hits the scripted
 * recognizer. The crash the maintainer hits is in
 * [AndroidSpeechRecognitionProvider] itself (`createSpeechRecognizer` /
 * `startListening` throwing). This journey constructs that class and starts
 * it on the main thread with RECORD_AUDIO granted.
 *
 * If the AVD has no recognition service, `isAvailable()==false` or `start`
 * returning null is success — the bug is "start throws / process dies",
 * not "no speech service".
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J08VoiceProductionProviderJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(compose)

    @Test
    fun productionAndroidProviderStartDoesNotCrashTheProcess() {
        grantRecordAudio()
        val activity = compose.activity
        assertFalse("activity must be alive before start", activity.isDestroyed)

        val provider = AndroidSpeechRecognitionProvider(activity)
        var session: SpeechRecognitionSession? = null
        var threw: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                if (provider.isAvailable()) {
                    session = provider.start(
                        language = null,
                        listener = object : SpeechRecognitionListener {
                            override fun onPartial(text: String) = Unit
                            override fun onFinal(text: String) = Unit
                            override fun onError(message: String) = Unit
                        },
                    )
                }
            } catch (t: Throwable) {
                threw = t
            }
        }

        assertNull("AndroidSpeechRecognitionProvider.start must not throw", threw)
        assertFalse("activity must still be alive after start", activity.isFinishing)
        assertFalse("activity must still be alive after start", activity.isDestroyed)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            session?.cancel()
        }
        assertFalse(activity.isDestroyed)
    }

    private fun grantRecordAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
