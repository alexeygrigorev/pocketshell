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
import com.pocketshell.next.di.VoiceModule
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.tree.SESSION_TREE_TAG
import com.pocketshell.next.tree.sessionRowTag
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
 * same shape J01-J07 use for the parts a device cannot exercise (there is no
 * fake tmux, sshd or SFTP anywhere in this suite either).
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
        seedTmuxSession()

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

    private fun seedTmuxSession() {
        AgentsFixture.exec("tmux -S $SOCKET kill-session -t '=$SESSION' 2>/dev/null || true")
        AgentsFixture.exec("mkdir -p $SOCKET_DIR && chmod 700 $SOCKET_DIR")
        AgentsFixture.exec(
            "tmux -S $SOCKET new-session -d -s $SESSION -c /home/testuser -x 80 -y 24",
        )
        AgentsFixture.exec("tmux -S $SOCKET send-keys -t '=$SESSION:' 'PS1=\"$PROMPT \"' Enter")
        AgentsFixture.exec("tmux -S $SOCKET send-keys -t '=$SESSION:' 'clear; echo $BANNER' Enter")
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

    // --- helpers ----------------------------------------------------------

    private fun openSession() {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG)
        awaitTag(sessionRowTag(SESSION))
        compose.onNodeWithTag(sessionRowTag(SESSION)).performClick()
        awaitTag(SESSION_SCREEN_TAG)
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

        const val SESSION = "j08-shell"
        const val SOCKET_DIR = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""
        const val SOCKET = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/tmuxctl-$SESSION\""
        const val PROMPT = "J08READY\$"
        const val BANNER = "J08-FIXTURE-PANE"

        const val DEFAULT_OFFLINE_TRANSCRIPT = "unused-default-transcript"
        const val OFFLINE_TRANSCRIPT = "queued while the subway had no signal"

        val HOST_IDS: Map<String, Long> = mapOf(
            "micTapDictatesIntoTheComposerDraft" to 9_801L,
            "aQueuedOfflineDictationDeliversOnForegroundResume" to 9_802L,
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
