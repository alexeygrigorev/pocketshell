package com.pocketshell.app.voice

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VOICE_GESTURE_COACHMARK_LOG_TAG = "Issue1753Coachmark"

/**
 * Presentation state for the one-time voice gesture coachmark.
 *
 * `Claimed` is intentionally distinct from `Presented`: a launcher can be
 * composed and then disappear during a reconnect, tab switch, or IME change.
 * Only a placed bubble that survives one frame is allowed to become durable.
 *
 * [Persisting] reserves the claim until the durable write finishes. This is
 * important for a release/dismiss racing an in-flight write: a successor must
 * not claim the lesson while the old write can still consume it.
 */
internal sealed class VoiceGestureCoachmarkUiState {
    data object Loading : VoiceGestureCoachmarkUiState()
    data object Ready : VoiceGestureCoachmarkUiState()
    data class Claimed(val claimId: Long) : VoiceGestureCoachmarkUiState()
    data class Persisting(
        val claimId: Long,
        val showWhenCommitted: Boolean,
    ) : VoiceGestureCoachmarkUiState()
    data class Presented(val claimId: Long) : VoiceGestureCoachmarkUiState()
    data object Hidden : VoiceGestureCoachmarkUiState()
}

internal class VoiceGestureCoachmarkController(
    private val store: VoiceGestureHintStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow<VoiceGestureCoachmarkUiState>(
        VoiceGestureCoachmarkUiState.Loading,
    )
    val uiState: StateFlow<VoiceGestureCoachmarkUiState> = _uiState.asStateFlow()

    private val lock = Any()
    private var nextClaimId = 1L

    init {
        scope.launch(ioDispatcher) {
            val version = try {
                store.presentedVersion()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                0
            }
            withContext(mainDispatcher) {
                synchronized(lock) {
                    if (_uiState.value is VoiceGestureCoachmarkUiState.Loading) {
                        _uiState.value = if (version >= VOICE_GESTURE_HINT_VERSION) {
                            VoiceGestureCoachmarkUiState.Hidden
                        } else {
                            VoiceGestureCoachmarkUiState.Ready
                        }
                        Log.i(
                            VOICE_GESTURE_COACHMARK_LOG_TAG,
                            "loaded presentedVersion=$version state=${_uiState.value::class.simpleName}",
                        )
                    }
                }
            }
        }
    }

    fun tryClaim(): Long? = synchronized(lock) {
        if (_uiState.value !is VoiceGestureCoachmarkUiState.Ready) return null
        val claimId = nextClaimId++
        _uiState.value = VoiceGestureCoachmarkUiState.Claimed(claimId)
        claimId
    }

    /** Persist only after the host has observed the bubble in a placed frame. */
    fun markPresented(claimId: Long) {
        val shouldPersist = synchronized(lock) {
            val current = _uiState.value
            if (current is VoiceGestureCoachmarkUiState.Claimed &&
                current.claimId == claimId
            ) {
                _uiState.value = VoiceGestureCoachmarkUiState.Persisting(
                    claimId = claimId,
                    showWhenCommitted = true,
                )
                true
            } else {
                false
            }
        }
        if (shouldPersist) persistClaim(claimId)
    }

    /**
     * Release the current host's claim after eligibility/disposal. An
     * unpresented claim returns to [VoiceGestureCoachmarkUiState.Ready]; an
     * already-presented claim is hidden, and an in-flight write stays reserved
     * until its durable outcome is known.
     */
    fun release(claimId: Long) {
        synchronized(lock) {
            when (val current = _uiState.value) {
                is VoiceGestureCoachmarkUiState.Claimed -> {
                    if (current.claimId == claimId) {
                        _uiState.value = VoiceGestureCoachmarkUiState.Ready
                    }
                }
                is VoiceGestureCoachmarkUiState.Persisting -> {
                    if (current.claimId == claimId) {
                        // Keep the claim reserved until the write completes.
                        // If the write succeeds, the lesson is durably consumed;
                        // if it fails, the completion returns to Ready.
                        _uiState.value = current.copy(showWhenCommitted = false)
                    }
                }
                is VoiceGestureCoachmarkUiState.Presented -> {
                    if (current.claimId == claimId) {
                        _uiState.value = VoiceGestureCoachmarkUiState.Hidden
                    }
                }
                else -> Unit
            }
        }
    }

    /** Dismissal is also consumption when it happens before the next frame. */
    fun dismiss() {
        var claimIdToPersist: Long? = null
        synchronized(lock) {
            when (val current = _uiState.value) {
                is VoiceGestureCoachmarkUiState.Claimed -> {
                    _uiState.value = VoiceGestureCoachmarkUiState.Persisting(
                        claimId = current.claimId,
                        showWhenCommitted = false,
                    )
                    claimIdToPersist = current.claimId
                }
                is VoiceGestureCoachmarkUiState.Persisting -> {
                    _uiState.value = current.copy(showWhenCommitted = false)
                }
                is VoiceGestureCoachmarkUiState.Presented -> {
                    _uiState.value = VoiceGestureCoachmarkUiState.Hidden
                }
                else -> Unit
            }
        }
        claimIdToPersist?.let(::persistClaim)
    }

    private fun persistClaim(claimId: Long) {
        scope.launch(ioDispatcher) {
            var failure: Throwable? = null
            val persisted = try {
                store.commitPresentedVersion(VOICE_GESTURE_HINT_VERSION)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failure = error
                false
            }
            Log.i(
                VOICE_GESTURE_COACHMARK_LOG_TAG,
                "commit claimId=$claimId persisted=$persisted " +
                    "failure=${failure?.javaClass?.name ?: "none"}",
            )
            withContext(mainDispatcher) {
                synchronized(lock) {
                    val current = _uiState.value
                    if (current is VoiceGestureCoachmarkUiState.Persisting &&
                        current.claimId == claimId
                    ) {
                        _uiState.value = if (persisted) {
                            if (current.showWhenCommitted) {
                                VoiceGestureCoachmarkUiState.Presented(claimId)
                            } else {
                                VoiceGestureCoachmarkUiState.Hidden
                            }
                        } else {
                            VoiceGestureCoachmarkUiState.Ready
                        }
                    }
                }
            }
        }
    }
}

internal class VoiceGestureCoachmarkViewModel(
    store: VoiceGestureHintStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    internal val controller = VoiceGestureCoachmarkController(
        store = store,
        ioDispatcher = ioDispatcher,
        mainDispatcher = mainDispatcher,
        scope = viewModelScope,
    )
}

/**
 * The Activity-scoped owner is shared by the Terminal and Conversation bands,
 * so a switch cannot create two simultaneous education claims. The preference
 * store supplies process/restart durability; the ViewModel supplies the
 * in-flight lifecycle across ordinary recomposition and navigation.
 */
@Composable
internal fun rememberVoiceGestureCoachmarkViewModel(
    override: VoiceGestureCoachmarkViewModel? = null,
): VoiceGestureCoachmarkViewModel {
    if (override != null) return override

    val owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "Voice gesture coachmark requires a ViewModelStoreOwner"
    }
    val appContext = LocalContext.current.applicationContext
    return remember(owner, appContext) {
        ViewModelProvider(
            owner,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    check(modelClass.isAssignableFrom(VoiceGestureCoachmarkViewModel::class.java)) {
                        "Unsupported voice gesture coachmark ViewModel: ${modelClass.name}"
                    }
                    return VoiceGestureCoachmarkViewModel(
                        store = VoiceGestureCoachmarkStore(appContext),
                    ) as T
                }
            },
        )[VoiceGestureCoachmarkViewModel::class.java]
    }
}
