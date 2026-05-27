package com.pocketshell.app.di

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.voice.ConnectivityObserver
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.app.voice.PendingTranscriptionStore
import com.pocketshell.core.storage.dao.AiApiCallLogDao
import com.pocketshell.core.storage.entity.AiApiCallEntry
import com.pocketshell.core.voice.AiCostRecord
import com.pocketshell.core.voice.AiCostRecorder
import com.pocketshell.core.voice.AndroidKeystoreApiKeyStorage
import com.pocketshell.core.voice.AudioRecorder
import com.pocketshell.core.voice.CommandPlannerClient
import com.pocketshell.core.voice.CommandPlannerConfig
import com.pocketshell.core.voice.OkHttpWhisperClient
import com.pocketshell.core.voice.OkHttpOpenAiCommandPlannerClient
import com.pocketshell.core.voice.PriceCatalogue
import com.pocketshell.core.voice.WhisperClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for `:shared:core-voice` consumed by the app module.
 *
 * The three pieces of state we expose to the prompt composer (#15):
 *
 *  - [AndroidKeystoreApiKeyStorage] — encrypted at-rest storage for the
 *    OpenAI API key, backed by `security-crypto`'s
 *    `EncryptedSharedPreferences`. Singleton because the Tink master-key
 *    derivation is expensive enough we don't want to repeat it.
 *  - [AudioRecorder] — owns the live mic. Singleton so the ViewModel can
 *    always read the latest amplitude even across configuration changes;
 *    the recorder serialises `start` / `stop` internally and tolerates
 *    repeated `start` calls.
 *  - [WhisperClient] — the OkHttp-backed implementation. The provider is
 *    *not* a Singleton: every transcription call needs the latest stored
 *    API key, and `OkHttpWhisperClient` snapshots the key at construction
 *    time. Re-creating the client on each request is cheap (OkHttp's
 *    default connection pool is shared by reflection-free clients with
 *    matching configs), and avoids a stale-key footgun if the user
 *    updates their key while the app is running.
 *
 * Issue #14 added the three classes to `:shared:core-voice`; this module
 * is the bridge that lets `PromptComposerViewModel` ask Hilt for them.
 *
 * `WhisperClient` is provided via a small factory closure rather than a
 * plain `@Provides` because the per-request key reload only makes sense
 * inside the ViewModel — building the client up front would freeze the
 * (possibly absent) key at app start. See [WhisperClientFactory] for the
 * factory interface; the implementation here returns `null` from
 * [WhisperClientFactory.create] when no key has been stored yet so the
 * UI can route the user through the one-field key entry dialog before
 * trying again.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {

    @Provides
    @Singleton
    fun provideApiKeyStorage(@ApplicationContext context: Context): AndroidKeystoreApiKeyStorage =
        AndroidKeystoreApiKeyStorage(context)

    @Provides
    @Singleton
    fun provideAudioRecorder(@ApplicationContext context: Context): AudioRecorder =
        AudioRecorder(context)

    /**
     * Bind the [PromptComposerViewModel.MicCapture] seam onto the real
     * [AudioRecorder]. Lives here (not as an `@Binds` abstract) so the
     * module can stay an `object` — there's only the one binding and
     * adding an abstract base class just to use `@Binds` is more
     * ceremony than the wiring deserves.
     */
    @Provides
    @Singleton
    fun provideMicCapture(recorder: AudioRecorder): PromptComposerViewModel.MicCapture =
        AudioRecorderMicCapture(recorder)

    /**
     * Bind the [PromptComposerViewModel.ApiKeyVault] seam onto the real
     * [AndroidKeystoreApiKeyStorage]. Same rationale as
     * [provideMicCapture] — a thin delegate keeps the ViewModel free of
     * Android KeyStore dependencies at unit-test time.
     */
    @Provides
    @Singleton
    fun provideApiKeyVault(storage: AndroidKeystoreApiKeyStorage): PromptComposerViewModel.ApiKeyVault =
        EncryptedApiKeyVault(storage)

    /**
     * Bind [PromptComposerViewModel.VoiceSettingsSnapshot] onto the
     * [SettingsRepository] persisted by issue #125. Singleton because
     * the underlying repository is one; the snapshot itself is stateless
     * and reads the latest StateFlow value on each call.
     */
    @Provides
    @Singleton
    fun provideVoiceSettingsSnapshot(
        repository: SettingsRepository,
    ): PromptComposerViewModel.VoiceSettingsSnapshot =
        SettingsRepositoryVoiceSnapshot(repository)

    /**
     * Issue #181: shared [PriceCatalogue] for the bundled `ai-pricing.json`.
     * Singleton because parsing the bundled JSON is a fixed cost we don't
     * want to repeat per Whisper call. Edits to the resource file ship as
     * a new app version — there's nothing to invalidate at runtime.
     */
    @Provides
    @Singleton
    fun providePriceCatalogue(): PriceCatalogue = PriceCatalogue.fromBundledResource()

    /**
     * Issue #181: client-side AI cost log sink. Forwards into the Room
     * [AiApiCallLogDao] so the costs screen can stream the rows. The
     * recorder is `@Singleton` so a single DAO reference is shared across
     * every recreated [WhisperClient]; the cost-recorder doesn't hold
     * mutable state.
     */
    @Provides
    @Singleton
    fun provideAiCostRecorder(dao: AiApiCallLogDao): AiCostRecorder =
        AiApiCallLogCostRecorder(dao)

    @Provides
    fun provideWhisperClientFactory(
        storage: AndroidKeystoreApiKeyStorage,
        priceCatalogue: PriceCatalogue,
        costRecorder: AiCostRecorder,
    ): WhisperClientFactory = WhisperClientFactory {
        reloadWhisperClient(storage, priceCatalogue, costRecorder)
    }

    /**
     * Issue #180: bind the [PromptComposerViewModel.PendingTranscriptionQueue]
     * seam onto the real [PendingTranscriptionStore]. Same delegation
     * shape as [provideMicCapture] / [provideApiKeyVault] — a thin
     * adapter so the ViewModel stays free of `PendingTranscriptionStore`'s
     * Room + filesystem dependencies at unit-test time.
     */
    @Provides
    @Singleton
    fun providePendingTranscriptionQueue(
        store: PendingTranscriptionStore,
    ): PromptComposerViewModel.PendingTranscriptionQueue =
        PendingTranscriptionStoreAdapter(store)

    /**
     * Issue #180: bind [PromptComposerViewModel.ConnectivityProbe] onto
     * the real [ConnectivityObserver]. The observer registers a long-
     * lived `ConnectivityManager.NetworkCallback` at construction time
     * (D21-compliant: the callback fires while the process is alive,
     * nothing keeps the JVM pinned), so we keep the binding as a
     * singleton.
     */
    @Provides
    @Singleton
    fun provideConnectivityProbe(
        observer: ConnectivityObserver,
    ): PromptComposerViewModel.ConnectivityProbe = ConnectivityObserverProbe(observer)

    @Provides
    fun provideCommandPlannerClientFactory(
        storage: AndroidKeystoreApiKeyStorage,
    ): CommandPlannerClientFactory = CommandPlannerClientFactory { reloadCommandPlannerClient(storage) }
}

/**
 * Default [PromptComposerViewModel.MicCapture] implementation — delegates
 * directly to the platform-backed [AudioRecorder]. Exposed at file scope
 * so tests can construct one against a stub `AudioRecorder` if they
 * really want to (most tests skip the delegate and substitute their own
 * `MicCapture` fake).
 */
internal class AudioRecorderMicCapture(
    private val recorder: AudioRecorder,
) : PromptComposerViewModel.MicCapture {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        recorder.start()
    }

    override fun stop(): ByteArray = recorder.stop()

    override fun currentAmplitude(): Float = recorder.currentAmplitude()
}

/**
 * Default [PromptComposerViewModel.ApiKeyVault] implementation —
 * delegates to [AndroidKeystoreApiKeyStorage] for at-rest encryption.
 * Lives at file scope so the production wiring stays declarative.
 */
internal class EncryptedApiKeyVault(
    private val storage: AndroidKeystoreApiKeyStorage,
) : PromptComposerViewModel.ApiKeyVault {
    override fun save(key: CharArray) = storage.save(key)
    override fun load(): CharArray? = storage.load()
    override fun clear() = storage.clear()
}

/**
 * Default [PromptComposerViewModel.VoiceSettingsSnapshot] — reads the
 * latest [SettingsRepository] snapshot on every call so a user-tap in
 * the Voice section takes effect on the next recording without any
 * cross-scope plumbing. The repository's StateFlow guarantees the read
 * sees the most recently persisted value.
 */
internal class SettingsRepositoryVoiceSnapshot(
    private val repository: SettingsRepository,
) : PromptComposerViewModel.VoiceSettingsSnapshot {
    override fun silenceWindowMs(): Long {
        val seconds = repository.settings.value.voiceSilenceThresholdSeconds
        return (seconds * 1000f).toLong()
    }

    override fun whisperLanguageHint(): String? {
        val code = repository.settings.value.voiceLanguage
        return if (code == AppSettings.VOICE_LANGUAGE_AUTO || code.isBlank()) null else code
    }

    override fun persistFailedTranscriptions(): Boolean =
        repository.settings.value.persistFailedTranscriptions
}

/**
 * Build a fresh [OkHttpWhisperClient] from whatever API key is currently
 * stored, or `null` if the user hasn't entered one yet. Lives at file
 * scope rather than inside [VoiceModule] so unit tests can substitute a
 * fake factory without touching Hilt's graph.
 *
 * The returned client owns its own defensive copy of the key (see
 * [OkHttpWhisperClient]'s constructor docs), so we zero the local
 * [CharArray] before returning — the caller never sees the plaintext key
 * after this function returns.
 */
private fun reloadWhisperClient(
    storage: AndroidKeystoreApiKeyStorage,
    priceCatalogue: PriceCatalogue,
    costRecorder: AiCostRecorder,
): WhisperClient? {
    val key = storage.load() ?: return null
    return try {
        OkHttpWhisperClient(
            apiKey = key,
            priceCatalogue = priceCatalogue,
            costRecorder = costRecorder,
        )
    } finally {
        // Zero our copy of the plaintext key. The Whisper client made its
        // own defensive copy on construction, so this does not affect
        // in-flight requests.
        java.util.Arrays.fill(key, ' ')
    }
}

/**
 * Adapter that forwards [AiCostRecord] entries into the Room
 * [AiApiCallLogDao]. Lives in the app layer so the voice module stays
 * free of Room/storage dependencies (the recorder seam is just a small
 * data class). Catches everything: a DB write failure here must never
 * propagate up into [OkHttpWhisperClient] — the surrounding transcription
 * already completed successfully and the user shouldn't see an error.
 */
internal class AiApiCallLogCostRecorder(
    private val dao: AiApiCallLogDao,
) : AiCostRecorder {
    override suspend fun record(record: AiCostRecord) {
        runCatching {
            dao.insert(
                AiApiCallEntry(
                    timestampMillis = record.timestampMillis,
                    provider = record.provider,
                    feature = record.feature,
                    inputUnits = record.inputUnits,
                    outputUnits = record.outputUnits,
                    unitCostUsdMillicents = record.unitCostUsdMillicents,
                    computedCostUsdMillicents = record.computedCostUsdMillicents,
                    metadataJson = record.metadataJson,
                ),
            )
        }
    }
}

/**
 * Issue #180: bridge [PendingTranscriptionStore] (the real DB + filesystem
 * store) onto the [PromptComposerViewModel.PendingTranscriptionQueue]
 * seam. Production wiring always uses this adapter; unit tests stay on
 * the [com.pocketshell.app.composer.DisabledPendingTranscriptionQueue]
 * no-op or a hand-built in-memory fake.
 */
internal class PendingTranscriptionStoreAdapter(
    private val store: PendingTranscriptionStore,
) : PromptComposerViewModel.PendingTranscriptionQueue {
    override val items = store.items
    override suspend fun enqueueAudio(
        audio: ByteArray,
        destinationContext: String,
        initialError: String?,
    ): PendingTranscriptionItem? = store.enqueueAudio(
        audio = audio,
        destinationContext = destinationContext,
        initialError = initialError,
    )

    override suspend fun snapshot(): List<PendingTranscriptionItem> = store.snapshot()
    override suspend fun loadAudio(id: String): ByteArray? = store.loadAudio(id)
    override suspend fun markSucceeded(id: String) = store.markSucceeded(id)
    override suspend fun markFailure(id: String, errorMessage: String): PendingTranscriptionItem? =
        store.markFailure(id, errorMessage)
    override suspend fun discard(id: String) = store.discard(id)
    override suspend fun saveAsAudioFile(id: String): String? = store.saveAsAudioFile(id)
    override suspend fun reconcile() = store.reconcile()
}

/**
 * Issue #180: bridge [ConnectivityObserver] onto the
 * [PromptComposerViewModel.ConnectivityProbe] seam. The observer keeps
 * its own callback registration alive for the singleton lifetime; this
 * adapter is purely a method-shape transform.
 */
internal class ConnectivityObserverProbe(
    private val observer: ConnectivityObserver,
) : PromptComposerViewModel.ConnectivityProbe {
    override fun refresh(): Boolean = observer.refresh()
}

private fun reloadCommandPlannerClient(storage: AndroidKeystoreApiKeyStorage): CommandPlannerClient? {
    val key = storage.load() ?: return null
    return try {
        OkHttpOpenAiCommandPlannerClient(
            config = CommandPlannerConfig(apiKey = key),
        )
    } finally {
        java.util.Arrays.fill(key, ' ')
    }
}

/**
 * Functional factory injected into [com.pocketshell.app.composer.PromptComposerViewModel].
 *
 * The composer re-creates its [WhisperClient] on every transcription so
 * the very latest stored key flows into the Authorization header — see
 * [VoiceModule.provideWhisperClientFactory] for the rationale.
 *
 * Returns `null` if no API key has been saved; the ViewModel surfaces
 * that as the "show the API key entry dialog" path.
 */
public fun interface WhisperClientFactory {
    public fun create(): WhisperClient?
}

public fun interface CommandPlannerClientFactory {
    public fun create(): CommandPlannerClient?
}
