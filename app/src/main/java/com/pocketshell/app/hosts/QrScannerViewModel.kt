package com.pocketshell.app.hosts

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Drives the [QrScannerScreen] state machine (issue #129).
 *
 * State transitions:
 *
 *  - [State.RequestingPermission] — initial / re-entry. The screen
 *    triggers the camera permission flow on `LaunchedEffect`; this VM
 *    flips to [State.Scanning] once the host calls
 *    [onPermissionGranted] / [State.PermissionDenied] once it calls
 *    [onPermissionDenied].
 *  - [State.Scanning] — the camera preview is live. Each decoded QR
 *    arrives via [onPayloadDecoded]; if it parses as a single-QR
 *    legacy payload OR a single envelope completes the assembly, we
 *    flip to [State.Decoded] and the screen finishes. Otherwise the
 *    progress counter on [State.Scanning] advances.
 *  - [State.Decoded] — terminal success state. The screen reads the
 *    payload and forwards to the host-list import path.
 *  - [State.Error] — terminal failure state. The screen renders a
 *    retry / close affordance; tapping retry flips back to
 *    [State.Scanning].
 *
 * The VM owns its own [QrChunkAssembler] so the multi-QR accumulation
 * lives outside the camera library; that keeps the testable surface
 * pure-Kotlin and means a Robolectric / unit test can drive the entire
 * state machine by feeding scanned strings to [onPayloadDecoded].
 */
@HiltViewModel
class QrScannerViewModel @Inject constructor() : ViewModel() {

    // Owned eagerly so the VM controls the accumulator lifecycle. Hilt
    // requires a single, parameterless `@Inject` constructor on a
    // `@HiltViewModel` — keeping the assembler as a private field
    // (rather than a constructor parameter with a default value) keeps
    // the KSP happy. Tests construct the VM directly and never go
    // through Hilt, so they can still observe the state machine.
    private val assembler: QrChunkAssembler = QrChunkAssembler()

    sealed interface State {
        /** Camera permission has not been resolved yet. */
        data object RequestingPermission : State

        /**
         * User denied camera access. [canRetry] is `true` if the
         * platform is willing to surface the prompt again; `false` if
         * the user has selected "don't ask again" and must visit
         * system settings.
         */
        data class PermissionDenied(val canRetry: Boolean) : State

        /**
         * Camera is live. [scanCount] / [scanTotal] track multi-QR
         * progress (both 0 until the first chunk arrives).
         */
        data class Scanning(
            val scanCount: Int = 0,
            val scanTotal: Int = 0,
        ) : State {
            val isMultiPart: Boolean get() = scanTotal > 1
        }

        /** A complete payload has been assembled. */
        data class Decoded(val payload: String) : State

        /** A decode / parse / checksum failure; the screen surfaces [message]. */
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.RequestingPermission)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Called by the screen once the runtime CAMERA permission is granted. */
    fun onPermissionGranted() {
        if (_state.value is State.Scanning || _state.value is State.Decoded) return
        assembler.reset()
        _state.value = State.Scanning()
    }

    /** Called by the screen if the user denied or revoked CAMERA access. */
    fun onPermissionDenied(canRetry: Boolean) {
        _state.value = State.PermissionDenied(canRetry = canRetry)
    }

    /**
     * Feed a freshly-decoded QR string into the state machine. May
     * advance progress, complete the scan, or move into the error
     * state.
     */
    fun onPayloadDecoded(text: String) {
        // We only act while in the scanning state. A late-arriving
        // duplicate after a Decoded/Error result is ignored so the
        // screen's terminal state can't be overwritten by a stray
        // camera frame.
        val current = _state.value
        if (current !is State.Scanning) return
        val payload = text.trim()
        if (QrChunkCodec.isEnvelope(payload)) {
            val part = QrChunkCodec.decodePart(payload).getOrElse {
                _state.value = State.Error(
                    it.message ?: "Could not decode QR envelope",
                )
                return
            }
            when (val outcome = assembler.accept(part)) {
                is QrChunkAssembler.Outcome.Complete -> {
                    _state.value = State.Decoded(payload = outcome.payload)
                }
                is QrChunkAssembler.Outcome.Progress -> {
                    _state.value = State.Scanning(
                        scanCount = outcome.state.count,
                        scanTotal = outcome.state.total,
                    )
                }
                is QrChunkAssembler.Outcome.Duplicate -> {
                    _state.value = State.Scanning(
                        scanCount = outcome.state.count,
                        scanTotal = outcome.state.total,
                    )
                }
                is QrChunkAssembler.Outcome.Reset -> {
                    _state.value = State.Scanning(
                        scanCount = outcome.state.count,
                        scanTotal = outcome.state.total,
                    )
                }
            }
            return
        }
        // Non-envelope path: a raw single-QR import payload
        // (`pocketshell.ssh-import.v1` JSON). Treat it as complete on
        // the first decode.
        _state.value = State.Decoded(payload = payload)
    }

    /** User tapped Retry on the error / permission-denied state. */
    fun retry() {
        assembler.reset()
        _state.value = State.RequestingPermission
    }

    /** Consume the [State.Decoded] payload — for unit-test inspection. */
    fun consumeDecoded(): String? {
        val current = _state.value
        return (current as? State.Decoded)?.payload
    }

    override fun onCleared() {
        super.onCleared()
        assembler.reset()
    }
}
