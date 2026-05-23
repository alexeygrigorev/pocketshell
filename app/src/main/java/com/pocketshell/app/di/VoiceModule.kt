package com.pocketshell.app.di

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.core.voice.AndroidKeystoreApiKeyStorage
import com.pocketshell.core.voice.AudioRecorder
import com.pocketshell.core.voice.CommandPlannerClient
import com.pocketshell.core.voice.CommandPlannerConfig
import com.pocketshell.core.voice.OkHttpWhisperClient
import com.pocketshell.core.voice.OkHttpOpenAiCommandPlannerClient
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

    @Provides
    fun provideWhisperClientFactory(
        storage: AndroidKeystoreApiKeyStorage,
    ): WhisperClientFactory = WhisperClientFactory { reloadWhisperClient(storage) }

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
private fun reloadWhisperClient(storage: AndroidKeystoreApiKeyStorage): WhisperClient? {
    val key = storage.load() ?: return null
    return try {
        OkHttpWhisperClient(apiKey = key)
    } finally {
        // Zero our copy of the plaintext key. The Whisper client made its
        // own defensive copy on construction, so this does not affect
        // in-flight requests.
        java.util.Arrays.fill(key, ' ')
    }
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
