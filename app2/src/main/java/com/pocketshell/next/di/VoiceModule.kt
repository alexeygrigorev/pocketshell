package com.pocketshell.next.di

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.pocketshell.core.storage.dao.PendingTranscriptionDao
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.voice.AiCostRecorder
import com.pocketshell.core.voice.AndroidKeystoreApiKeyStorage
import com.pocketshell.core.voice.AudioRecorder
import com.pocketshell.core.voice.OkHttpWhisperClient
import com.pocketshell.core.voice.PriceCatalogue
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.next.composer.SpeechRecognitionProvider
import com.pocketshell.next.voice.AndroidSpeechRecognitionProvider
import com.pocketshell.next.voice.ConnectivityObserver
import com.pocketshell.next.voice.ConnectivityProbe
import com.pocketshell.next.voice.MicCapture
import com.pocketshell.next.voice.PendingTranscriptionDelivery
import com.pocketshell.next.voice.PendingTranscriptionStore
import com.pocketshell.next.settings.SettingsRepository
import com.pocketshell.next.voice.WhisperClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Arrays
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The dictation graph (rewrite task P-2) — the lifted voice stack wired to
 * P-1's composer seam.
 *
 * Its own module, not another dozen `@Provides` on [AppModule], for one
 * concrete reason: journey J08 replaces the recognizer with a scripted fake via
 * `@UninstallModules(VoiceModule::class)`, and uninstalling `AppModule` would
 * take the database and the connection stack with it.
 *
 * ## What is NOT here
 *
 * No cost recorder. The old client logged every Whisper call into an
 * `ai_api_call_log` table behind a costs screen; the rewrite's scope amendment
 * cut that surface, so the recorder is [AiCostRecorder.NoOp] and the table is
 * left alone rather than written to by nothing.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {

    /** The scope the Whisper arm's round trip runs on. */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class VoiceScope

    @Provides
    fun providePendingTranscriptionDao(db: AppDatabase): PendingTranscriptionDao =
        db.pendingTranscriptionDao()

    /**
     * `@Singleton` because the queue is a directory plus a table: two
     * instances would be two views of one filesystem, and the reconcile sweep
     * of one could delete the other's in-flight audio.
     */
    @Provides
    @Singleton
    fun providePendingTranscriptionStore(
        @ApplicationContext context: Context,
        dao: PendingTranscriptionDao,
    ): PendingTranscriptionStore = PendingTranscriptionStore(context, dao)

    @Provides
    @Singleton
    fun provideConnectivityProbe(observer: ConnectivityObserver): ConnectivityProbe = observer

    @Provides
    @Singleton
    fun provideApiKeyStorage(@ApplicationContext context: Context): AndroidKeystoreApiKeyStorage =
        AndroidKeystoreApiKeyStorage(context)

    /**
     * `@Singleton` because it owns the microphone: only one recording can be
     * in flight on the device, so only one recorder may exist.
     */
    @Provides
    @Singleton
    fun provideAudioRecorder(@ApplicationContext context: Context): AudioRecorder =
        AudioRecorder(context)

    @Provides
    @Singleton
    fun provideMicCapture(recorder: AudioRecorder): MicCapture = AudioRecorderMicCapture(recorder)

    @Provides
    @Singleton
    fun providePriceCatalogue(): PriceCatalogue = PriceCatalogue.fromBundledResource()

    /**
     * A factory rather than a client: [OkHttpWhisperClient] snapshots the API
     * key at construction, so a long-lived instance would keep using a key the
     * user has since replaced. Returns `null` when nothing is stored, which is
     * what makes the mic route to the system recognizer instead.
     */
    @Provides
    @Singleton
    fun provideWhisperClientFactory(
        storage: AndroidKeystoreApiKeyStorage,
        priceCatalogue: PriceCatalogue,
    ): WhisperClientFactory = WhisperClientFactory {
        reloadWhisperClient(storage, priceCatalogue)
    }

    /**
     * A process-lifetime scope, not a ViewModel's: a Whisper round trip that
     * outlives the screen still has somewhere to land (the queue), and
     * cancelling it mid-upload would leave a row the user has to retry for no
     * reason. `Dispatchers.Main.immediate` because
     * [com.pocketshell.next.composer.SpeechRecognitionListener] promises
     * main-thread callbacks; the HTTP call itself moves off it inside
     * [OkHttpWhisperClient].
     */
    @Provides
    @Singleton
    @VoiceScope
    fun provideVoiceScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Composer mic is Android `SpeechRecognizer` only (#2529). A stored
     * OpenAI key must not route this tap through Whisper / `AudioRecorder`.
     * Whisper remains wired for the offline-queue delivery path, not for
     * live dictation.
     */
    @Provides
    @Singleton
    fun provideSpeechRecognitionProvider(
        @ApplicationContext context: Context,
        repository: SettingsRepository,
    ): SpeechRecognitionProvider = AndroidSpeechRecognitionProvider(
        context = context,
        silenceWindowMsProvider = {
            (repository.settings.value.voiceSilenceThresholdSeconds * 1000f).toLong()
        },
    )

    @Provides
    @Singleton
    fun providePendingTranscriptionDelivery(
        store: PendingTranscriptionStore,
        whisper: WhisperClientFactory,
        connectivity: ConnectivityProbe,
    ): PendingTranscriptionDelivery = PendingTranscriptionDelivery(store, whisper, connectivity)
}

/**
 * Binds the [MicCapture] seam onto the real recorder. A three-line delegate
 * rather than making `AudioRecorder` implement the interface: `core-voice` is a
 * shared module and must not learn about app2's seams.
 */
internal class AudioRecorderMicCapture(
    private val recorder: AudioRecorder,
) : MicCapture {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        recorder.start()
    }

    override fun stop(): ByteArray = recorder.stop()

    override fun currentAmplitude(): Float = recorder.currentAmplitude()
}

/**
 * Builds a Whisper client from whatever key is stored right now, zeroing this
 * function's copy of the plaintext before returning — the client made its own
 * defensive copy on construction, so in-flight requests are unaffected.
 */
private fun reloadWhisperClient(
    storage: AndroidKeystoreApiKeyStorage,
    priceCatalogue: PriceCatalogue,
): WhisperClient? {
    val key = storage.load() ?: return null
    return try {
        OkHttpWhisperClient(
            apiKey = key,
            priceCatalogue = priceCatalogue,
            costRecorder = AiCostRecorder.NoOp,
        )
    } finally {
        Arrays.fill(key, ' ')
    }
}
