package com.pocketshell.next.composer

import android.Manifest
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.PendingTranscriptionDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.connect.openQuietSession
import com.pocketshell.next.di.VoiceModule
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.uikit.components.SESSION_COMPOSER_LAUNCHER_TAG
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.next.voice.ConnectivityProbe
import com.pocketshell.next.voice.PendingTranscriptionItem
import com.pocketshell.next.voice.PendingTranscriptionStore
import com.pocketshell.next.voice.WhisperClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import com.pocketshell.uikit.components.COMPOSER_MIC_TAG
import com.pocketshell.uikit.components.COMPOSER_SEND_TAG

/**
 * Journey J08 — voice dictation lands in the composer draft (rewrite task
 * P-2).
 *
 * ## Why a scripted recognizer, not a real microphone
 *
 * An instrumented test has no microphone and CI has no speech to feed one; the
 * `di/VoiceModule` KDoc calls this out as the intended seam — a
 * `@TestInstallIn` replacement swaps ONLY the [SpeechRecognitionProvider]
 * binding for [ScriptedSpeechRecognitionProvider], which the test drives
 * directly. Everything downstream of that seam — [AndroidSpeechRecognitionDelegate],
 * [ComposerViewModel]'s draft-merge rules, the mic button's own state machine —
 * is 100% production code; only the recognizer itself is a double, exactly the
 * same shape J01-J07 use for the parts a device cannot exercise (the SSH
 * fixture and SFTP path remain real).
 *
 * ## The offline half: a REAL queued row, not a flag
 *
 * The second scenario seeds a row directly into [PendingTranscriptionStore] —
 * the exact persistence [WhisperSpeechRecognitionProvider] uses when a
 * dictation is recorded with no signal — then opens the session and confirms
 * the production [SessionScreen] lifecycle wiring (`LifecycleEventEffect(ON_START)`
 * calling `ComposerViewModel.onForegroundResume()`) delivers it into the draft
 * on its own, with no user action. `ComposerViewModelTest` already proves the
 * ViewModel method merges correctly; what only a device journey can prove is
 * that the SCREEN actually calls it.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class J08VoiceDictationJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        seedAplexerSession()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j08_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j08-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_IDS.getValue(description.methodName)
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )
        graph.composerDraftStore().clear("$hostId/$SESSION")

        // Fresh recognizer + queue state per test: J08's own scripted doubles
        // are process-wide Hilt singletons, so a previous test's script or a
        // stray queued row must not leak into the next.
        ScriptedSpeechRecognitionProvider.reset()
        ScriptedWhisperClient.instance.transcript = DEFAULT_OFFLINE_TRANSCRIPT
        graph.pendingTranscriptionStore().clearAll()
    }

    private fun seedAplexerSession() {
        AgentsFixture.exec("pocketshell sessions kill -- '$SESSION' >/dev/null 2>&1 || true")
        AgentsFixture.exec(
            "pocketshell sessions create --cwd '$WORKSPACE' --mem none --json -- '$TAG' >/dev/null",
        )
        AgentsFixture.exec(
            "a send --workspace '$WORKSPACE' --tag '$TAG' --enter " +
                "'PS1=\"$PROMPT \"; clear; echo $BANNER'",
        )
        SystemClock.sleep(500)
    }

    /**
     * The headline journey: mic tap starts a dictation, a partial rewrites the
     * draft as a replacement (not an append), and the final transcript is what
     * is left once the recording stops.
     */
    @Test
    fun micTapDictatesIntoTheComposerDraft() {
        openSession()
        grantRecordAudio()
        openComposer()

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        awaitTag(COMPOSER_DISCARD_RECORDING_TAG, "the recording indicator")

        ScriptedSpeechRecognitionProvider.partial("run the")
        compose.awaitIdle("after a partial transcript")
        ScriptedSpeechRecognitionProvider.partial("run the tests")
        compose.awaitIdle("after a partial transcript")
        JourneyScreenshots.capture("01-recording", JOURNEY)

        ScriptedSpeechRecognitionProvider.final("run the tests now")
        compose.awaitIdle("after the final transcript")
        JourneyScreenshots.capture("02-transcribed", JOURNEY)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertTextContains("run the tests now", substring = true)
        // The recording indicator is gone — dictation returned the composer to
        // its ordinary idle/editing state.
        compose.onAllNodesWithTag(COMPOSER_DISCARD_RECORDING_TAG).assertCountEquals(0)
    }

    /**
     * The subway case: a dictation already queued (recorded with no signal) is
     * delivered into the draft the moment the session screen appears — no mic
     * tap, no user action, just the production foreground-resume wiring.
     */
    @Test
    fun aQueuedOfflineDictationDeliversOnForegroundResume() {
        val graph = appGraph()
        ScriptedWhisperClient.instance.transcript = OFFLINE_TRANSCRIPT
        runBlocking {
            val store = graph.pendingTranscriptionStore()
            store.idGenerator = { "j08-offline-take" }
            store.enqueueAudio(
                audio = ByteArray(64) { it.toByte() },
                destinationContext = PendingTranscriptionEntity.DESTINATION_COMPOSER,
                initialError = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
            )
        }

        openSession()
        openComposer()

        awaitTag(COMPOSER_NOTICE_TAG, "the offline-dictation-delivered notice")
        JourneyScreenshots.capture("03-offline-delivered", JOURNEY)
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .assertTextContains(OFFLINE_TRANSCRIPT, substring = true)

        // The queue is drained — nothing left waiting.
        val remaining = runBlocking { graph.pendingTranscriptionStore().snapshot() }
        assertEquals(emptyList<Any>(), remaining)
    }

    /**
     * #2598, the maintainer's report, end to end: dictate, tap Send while the
     * mic is still live, then reopen the composer.
     *
     * On the broken build the send dropped the recognizer with the SILENT
     * release meant for a composer that is going away, so the state stayed
     * [RecordingState.Recording] for good — every later open of the sheet
     * came up on a waveform with no recognizer behind it, over the draft
     * field, with Discard and the sheet's dismiss both no-ops. Here the
     * reopened composer must be an ordinary editable composer.
     */
    @Test
    fun sendingWhileDictatingLeavesTheComposerEditable() {
        openSession()
        grantRecordAudio()
        openComposer()

        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        awaitTag(COMPOSER_WAVEFORM_TAG, "the recording surface")
        ScriptedSpeechRecognitionProvider.partial(SENT_WHILE_RECORDING)
        compose.awaitIdle("after a partial transcript")
        JourneyScreenshots.capture("04-recording-before-send", JOURNEY)

        // Send is enabled mid-dictation — this is the ordinary voice flow.
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()
        compose.awaitIdle("after sending mid-dictation")
        awaitGone(COMPOSER_TAG, "the composer sheet after a send")

        // The bytes really left: the dictated text is on the remote pane.
        awaitPaneText(SENT_WHILE_RECORDING)

        openComposer()
        JourneyScreenshots.capture("05-reopened-after-voice-send", JOURNEY)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(COMPOSER_WAVEFORM_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(COMPOSER_TIMER_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(COMPOSER_DISCARD_RECORDING_TAG).assertCountEquals(0)
        // ...and the idle chrome is back, mic included.
        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed()

        // The composer is usable again: type and the field takes it.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(TYPED_AFTER_SEND)
        compose.awaitIdle("after typing into the reopened composer")
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .assertTextContains(TYPED_AFTER_SEND, substring = true)
    }

    /**
     * The other half of #2598 ("no way to stop it"): the recording surface's
     * Stop control ends the dictation and hands the transcript back as an
     * editable draft — Recording → Transcribing → Idle — without throwing the
     * text away the way Discard does.
     */
    @Test
    fun theStopControlEndsTheDictationAndKeepsTheText() {
        openSession()
        grantRecordAudio()
        openComposer()

        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        awaitTag(COMPOSER_WAVEFORM_TAG, "the recording surface")
        ScriptedSpeechRecognitionProvider.partial(STOPPED_TRANSCRIPT)
        compose.awaitIdle("after a partial transcript")

        compose.onNodeWithTag(COMPOSER_STOP_RECORDING_TAG).performClick()
        compose.awaitIdle("after tapping stop")
        // The recognizer was asked to transcribe, not abandoned.
        awaitTag(COMPOSER_TRANSCRIBING_TAG, "the transcribing surface")

        ScriptedSpeechRecognitionProvider.final(STOPPED_TRANSCRIPT)
        compose.awaitIdle("after the final transcript")
        JourneyScreenshots.capture("06-stopped-editable", JOURNEY)

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .assertTextContains(STOPPED_TRANSCRIPT, substring = true)
        compose.onAllNodesWithTag(COMPOSER_WAVEFORM_TAG).assertCountEquals(0)
        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed()
    }

    // --- helpers ----------------------------------------------------------

    private fun openSession() {
        compose.openQuietSession(hostId, SESSION, WORKSPACE, TIMEOUT_MS)
    }

    private fun openComposer() {
        awaitTag(SESSION_COMPOSER_LAUNCHER_TAG, "the Prompt Composer launcher")
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        awaitTag(COMPOSER_TAG, "the Prompt Composer sheet")
    }

    private fun grantRecordAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    /** Waits for [tag] to leave the tree (a sheet closing, a surface going away). */
    private fun awaitGone(tag: String, what: String = tag) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("gone poll: $what")
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-gone-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError("$what never went away within ${TIMEOUT_MS}ms. Screenshot: ${shot.absolutePath}")
    }

    /**
     * The independent oracle: what the REMOTE pane holds, read over its own
     * SSH connection rather than off the device's screen.
     */
    private fun awaitPaneText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var pane = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            pane = AgentsFixture.exec(
                "a capture --workspace '$WORKSPACE' --tag '$TAG' --screen --plain",
            )
            if (pane.contains(text)) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("the pane never showed \"$text\". Last capture:\n$pane")
    }

    private fun awaitTag(tag: String, what: String = tag) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("tag poll: $what")
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError("$what never appeared within ${TIMEOUT_MS}ms. Screenshot: ${shot.absolutePath}")
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val JOURNEY = "j08-voice-dictation"

        const val TAG = "j08-shell"
        const val SESSION = "testuser:j08-shell"
        const val WORKSPACE = "/home/testuser"
        const val PROMPT = "J08READY\$"
        const val BANNER = "J08-FIXTURE-PANE"

        const val DEFAULT_OFFLINE_TRANSCRIPT = "unused-default-transcript"
        const val OFFLINE_TRANSCRIPT = "queued while the subway had no signal"

        /** #2598: dictated, then sent while the mic was still live. */
        const val SENT_WHILE_RECORDING = "echo j08-sent-mid-dictation"
        const val TYPED_AFTER_SEND = "typed after the voice send"
        const val STOPPED_TRANSCRIPT = "stop but keep what I said"

        val HOST_IDS: Map<String, Long> = mapOf(
            "micTapDictatesIntoTheComposerDraft" to 9_801L,
            "aQueuedOfflineDictationDeliversOnForegroundResume" to 9_802L,
            "sendingWhileDictatingLeavesTheComposerEditable" to 9_803L,
            "theStopControlEndsTheDictationAndKeepsTheText" to 9_804L,
        )
    }
}

// -----------------------------------------------------------------------
// Test doubles + the Hilt module that swaps them in (task P-2, journey J08).
// -----------------------------------------------------------------------

/**
 * Replaces [VoiceModule] wholesale for every `androidTest` in this module —
 * the standard Hilt shape for swapping a binding, since Hilt has no
 * per-`@Provides` override. Every OTHER binding here is identical to
 * production's; only [provideSpeechRecognitionProvider] differs.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [VoiceModule::class])
object TestVoiceModule {

    @Provides
    @Singleton
    fun providePendingTranscriptionDao(db: AppDatabase): PendingTranscriptionDao =
        db.pendingTranscriptionDao()

    @Provides
    @Singleton
    fun providePendingTranscriptionStore(
        @ApplicationContext context: Context,
        dao: PendingTranscriptionDao,
    ): PendingTranscriptionStore = PendingTranscriptionStore(context, dao)

    /** Always online: J08 seeds the "recorded offline" state directly in the store. */
    @Provides
    @Singleton
    fun provideConnectivityProbe(): ConnectivityProbe = ConnectivityProbe { true }

    @Provides
    @Singleton
    fun provideWhisperClientFactory(): WhisperClientFactory =
        WhisperClientFactory { ScriptedWhisperClient.instance }

    @Provides
    @Singleton
    fun providePendingTranscriptionDelivery(
        store: PendingTranscriptionStore,
        whisper: WhisperClientFactory,
        connectivity: ConnectivityProbe,
    ) = com.pocketshell.next.voice.PendingTranscriptionDelivery(store, whisper, connectivity)

    @Provides
    @Singleton
    fun provideSpeechRecognitionProvider(): com.pocketshell.next.composer.SpeechRecognitionProvider =
        ScriptedSpeechRecognitionProvider
}

/**
 * A recognizer the TEST drives directly, standing in for a real microphone.
 *
 * A plain Kotlin `object` rather than a Hilt-injected class: the test needs to
 * reach the exact same instance the app's Hilt graph is using, and a
 * process-wide singleton is the simplest way to guarantee that without a
 * second `EntryPoint`.
 */
object ScriptedSpeechRecognitionProvider : com.pocketshell.next.composer.SpeechRecognitionProvider {

    @Volatile
    private var listener: com.pocketshell.next.composer.SpeechRecognitionListener? = null

    fun reset() {
        listener = null
    }

    override fun isAvailable(): Boolean = true

    override fun start(
        language: String?,
        listener: com.pocketshell.next.composer.SpeechRecognitionListener,
    ): com.pocketshell.next.composer.SpeechRecognitionSession? {
        this.listener = listener
        return object : com.pocketshell.next.composer.SpeechRecognitionSession {
            override fun stopListening() { /* the test drives final()/error() directly */ }
            override fun cancel() { reset() }
        }
    }

    /** Scripts a partial (a REPLACEMENT, matching the real recognizer's contract). */
    fun partial(text: String) = listener?.onPartial(text)

    /** Scripts the terminal transcript — ends the dictation. */
    fun final(text: String) {
        listener?.onFinal(text)
        listener = null
    }

    fun error(message: String) {
        listener?.onError(message)
        listener = null
    }
}

/** A [WhisperClient] the test scripts — stands in for the offline-delivery round trip. */
class ScriptedWhisperClient private constructor() : WhisperClient {

    @Volatile
    var transcript: String = "scripted-transcript"

    override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
        Result.success(transcript)

    companion object {
        val instance = ScriptedWhisperClient()
    }
}
