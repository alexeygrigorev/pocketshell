package com.pocketshell.app.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.prefs.DeferredPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Durable, versioned presentation ledger for the docked launcher's voice
 * gesture education. The lesson is app-global rather than tied to a host,
 * session, pane, or tab: switching surfaces must not teach it again.
 *
 * [DeferredPrefs] keeps the first preferences-file open off the UI thread. The
 * controller calls [commitPresentedVersion] from its IO dispatcher and uses
 * `commit()` so a successful presentation is durable before the UI suppresses
 * the lesson.
 */
internal interface VoiceGestureHintStore {
    suspend fun presentedVersion(): Int
    suspend fun commitPresentedVersion(version: Int): Boolean
}

internal class VoiceGestureCoachmarkStore private constructor(
    private val deferredPrefs: DeferredPrefs,
) : VoiceGestureHintStore {

    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(DeferredPrefs(context, VOICE_EDUCATION_PREFS_NAME, ioDispatcher))

    @VisibleForTesting
    internal constructor(prefs: SharedPreferences) : this(
        DeferredPrefs(opener = { prefs }),
    )

    override suspend fun presentedVersion(): Int = runCatching {
        deferredPrefs.get().getInt(VOICE_GESTURE_HINT_VERSION_KEY, 0)
    }.getOrDefault(0)

    override suspend fun commitPresentedVersion(version: Int): Boolean = runCatching {
        deferredPrefs.get()
            .edit()
            .putInt(VOICE_GESTURE_HINT_VERSION_KEY, version)
            .commit()
    }.getOrDefault(false)
}

internal const val VOICE_EDUCATION_PREFS_NAME: String = "voice_education"

internal const val VOICE_GESTURE_HINT_VERSION_KEY: String =
    "launcher_dictation_hint_presented_version"

internal const val VOICE_GESTURE_HINT_VERSION: Int = 1
