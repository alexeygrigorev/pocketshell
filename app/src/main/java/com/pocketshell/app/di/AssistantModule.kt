package com.pocketshell.app.di

import android.content.Context
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.core.assistant.store.AndroidKeystoreAssistantConfigStore
import com.pocketshell.core.assistant.store.AssistantConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for `:shared:core-assistant` (issue #265).
 *
 * Provides the KeyStore-backed [AssistantConfigStore] and the
 * [AssistantLlmClientFactory] that selects an Anthropic / OpenAI client
 * from the persisted provider config.
 *
 * The store is `@Singleton` because deriving the Tink master key is
 * expensive enough not to repeat. The factory is also a singleton — it
 * holds no per-request state and re-reads the store on each `create()` so
 * the latest key / base URL / model always flow into the next client (the
 * same fresh-config rationale as `VoiceModule.provideWhisperClientFactory`).
 *
 * This module is independent of [VoiceModule]: the assistant provider
 * config lives in its own encrypted prefs file
 * (`pocketshell-assistant-secrets`), so wiring the assistant does not
 * touch the Whisper key entry in `pocketshell-voice-secrets`.
 */
@Module
@InstallIn(SingletonComponent::class)
object AssistantModule {

    @Provides
    @Singleton
    fun provideAssistantConfigStore(
        @ApplicationContext context: Context,
    ): AssistantConfigStore = AndroidKeystoreAssistantConfigStore(context)

    @Provides
    @Singleton
    fun provideAssistantLlmClientFactory(
        store: AssistantConfigStore,
    ): AssistantLlmClientFactory = AssistantLlmClientFactory(store)
}
