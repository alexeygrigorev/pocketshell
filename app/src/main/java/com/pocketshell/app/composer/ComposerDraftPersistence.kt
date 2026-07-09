package com.pocketshell.app.composer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the ViewModel-facing draft store operations ordered and immediately
 * readable while SharedPreferences writes run off main.
 */
internal class ComposerDraftPersistence(
    private val store: ComposerDraftStore,
    private val scope: CoroutineScope,
    private val dispatcher: () -> CoroutineDispatcher,
) {
    private val writeMutex = Mutex()
    private val writeGenerations: ConcurrentHashMap<String, AtomicLong> =
        ConcurrentHashMap()
    private val overrides: ConcurrentHashMap<String, String> =
        ConcurrentHashMap()

    fun load(sessionKey: String): String? {
        val override = overrides[sessionKey]
        if (override != null || overrides.containsKey(sessionKey)) {
            return override?.takeIf { it.isNotEmpty() }
        }
        return store.load(sessionKey)
    }

    fun save(sessionKey: String?, draft: String) {
        val key = sessionKey?.takeIf { it.isNotBlank() } ?: return
        overrides[key] = draft
        val generation = nextWriteGeneration(key)
        if (store is SharedPrefsComposerDraftStore) {
            scope.launch(dispatcher()) {
                writeLatest(key, generation) {
                    store.save(key, draft)
                }
            }
        } else {
            store.save(key, draft)
        }
    }

    fun clear(sessionKey: String?) {
        val key = sessionKey?.takeIf { it.isNotBlank() } ?: return
        overrides[key] = ""
        val generation = nextWriteGeneration(key)
        if (store is SharedPrefsComposerDraftStore) {
            scope.launch(dispatcher()) {
                writeLatest(key, generation) {
                    store.clear(key)
                }
            }
        } else {
            store.clear(key)
        }
    }

    private fun nextWriteGeneration(sessionKey: String): Long =
        writeGenerations
            .computeIfAbsent(sessionKey) { AtomicLong() }
            .incrementAndGet()

    private suspend fun writeLatest(
        sessionKey: String,
        generation: Long,
        write: () -> Unit,
    ) {
        writeMutex.withLock {
            val latest = writeGenerations[sessionKey]?.get()
            if (latest == generation) {
                write()
            }
        }
    }
}
