package com.pocketshell.next.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the QR-import screen (rewrite task P-6): camera permission → scan →
 * accumulate parts → import.
 *
 * The camera library never touches this class. It hands over decoded strings,
 * and everything downstream — envelope parsing, multi-part accumulation, the
 * Room writes — is plain Kotlin driven by [onScanned], so the whole flow is
 * testable by feeding it the strings [QrChunkCodec.encode] produced.
 */
@HiltViewModel
class QrScannerViewModel @Inject constructor(
    private val importer: HostImporter,
) : ViewModel() {

    /** Where the scan flow is. */
    sealed interface State {
        /** Camera permission has not been resolved yet. */
        data object RequestingPermission : State

        /**
         * The user declined. [canRetry] is false once the platform stops
         * offering the prompt, which is when the pick-an-image fallback is the
         * only way forward.
         */
        data class PermissionDenied(val canRetry: Boolean) : State

        /** Camera live. [scanned]/[total] are 0 until the first part arrives. */
        data class Scanning(val scanned: Int = 0, val total: Int = 0) : State

        /** Every part arrived and the payload is being written. */
        data object Importing : State

        /** A decoded real payload is waiting for the user to approve import. */
        data class Review(
            val config: SshImportConfig,
            internal val payload: String,
            val existingHost: ExistingHost? = null,
            val duplicateChecked: Boolean = true,
        ) : State

        /** Terminal success. The screen reports [message] and opens this host. */
        data class Imported(val message: String, val hostId: Long) : State

        /** Terminal failure; the screen offers retry. */
        data class Failed(val message: String) : State
    }

    private val assembler = QrChunkAssembler()

    private val _state = MutableStateFlow<State>(State.RequestingPermission)
    val state: StateFlow<State> = _state.asStateFlow()

    fun onPermissionGranted() {
        if (_state.value is State.Scanning) return
        assembler.reset()
        _state.value = State.Scanning()
    }

    fun onPermissionDenied(canRetry: Boolean) {
        _state.value = State.PermissionDenied(canRetry = canRetry)
    }

    /**
     * Feed one decoded QR string.
     *
     * Ignored unless the machine is scanning, so a camera frame that lands
     * after the import already started cannot overwrite a terminal state.
     */
    fun onScanned(text: String) {
        if (_state.value !is State.Scanning) return
        val payload = text.trim()
        if (!QrChunkCodec.isEnvelope(payload)) {
            // A QR from a poster, a URL, someone else's app. Say what it is
            // rather than failing with a JSON parse error three layers down.
            _state.value = State.Failed("That QR is not a PocketShell host code")
            return
        }
        val part = QrChunkCodec.decodePart(payload).getOrElse {
            _state.value = State.Failed(it.message ?: "Could not read that QR")
            return
        }
        when (val outcome = assembler.accept(part)) {
            is QrChunkAssembler.Outcome.Complete -> reviewPayload(outcome.payload)
            is QrChunkAssembler.Outcome.Progress ->
                _state.value = State.Scanning(outcome.state.count, outcome.state.total)

            is QrChunkAssembler.Outcome.Duplicate ->
                _state.value = State.Scanning(outcome.state.count, outcome.state.total)
        }
    }

    /**
     * Import a payload obtained outside the camera — the QR image the user
     * picked when the camera is unavailable. Single-part only; [HostImporter]
     * says so if it is not.
     */
    fun onPayloadPicked(payload: String) {
        reviewPayload(payload)
    }

    /** Confirm the exact decoded host shown by the review screen. */
    fun confirmImport(action: DuplicateAction? = null) {
        val review = _state.value as? State.Review ?: return
        if (!review.duplicateChecked) return
        if (review.existingHost != null && action == null) return
        importPayload(
            payload = review.payload,
            duplicateAction = if (review.existingHost == null) DuplicateAction.AddNew else action!!,
        )
    }

    /** Return to the camera without writing the reviewed host or key. */
    fun cancelReview() {
        if (_state.value !is State.Review) return
        assembler.reset()
        _state.value = State.Scanning()
    }

    /** Report a failure raised by the screen's own plumbing (image decode, file read). */
    fun onScanFailed(message: String) {
        _state.value = State.Failed(message)
    }

    fun retry() {
        assembler.reset()
        _state.value = State.RequestingPermission
    }

    private fun importPayload(payload: String, duplicateAction: DuplicateAction) {
        _state.value = State.Importing
        viewModelScope.launch {
            _state.value = when (val outcome = importer.import(payload, duplicateAction)) {
                is ImportOutcome.Imported -> State.Imported("Imported ${outcome.name}", outcome.hostId)
                is ImportOutcome.AlreadyPresent -> State.Imported(
                    "Skipped; already added: ${outcome.name}",
                    outcome.hostId,
                )
                is ImportOutcome.Replaced -> State.Imported("Replaced ${outcome.name}", outcome.hostId)
                is ImportOutcome.Failed -> State.Failed(outcome.message)
            }
        }
    }

    /** Decode enough to render a review without ever displaying key material. */
    private fun reviewPayload(rawPayload: String) {
        val payload = if (QrChunkCodec.isEnvelope(rawPayload)) {
            val part = QrChunkCodec.decodePart(rawPayload).getOrElse {
                _state.value = State.Failed(it.message ?: "Could not read that QR")
                return
            }
            if (part.total != 1) {
                _state.value = State.Failed(
                    "This QR is part ${part.part} of ${part.total}. " +
                        "Use the camera so every part can be combined.",
                )
                return
            }
            String(part.chunk, Charsets.UTF_8)
        } else {
            rawPayload
        }

        val config = SshImportPayloadCodec.decode(payload).getOrElse {
            _state.value = State.Failed(it.message ?: "Could not read the shared host")
            return
        }
        val review = State.Review(
            config = config,
            payload = payload,
            duplicateChecked = false,
        )
        _state.value = review
        viewModelScope.launch {
            val existing = runCatching { importer.findExisting(config) }.getOrElse {
                _state.value = State.Failed(it.message ?: "Could not check existing hosts")
                return@launch
            }
            if (_state.value == review) {
                _state.value = review.copy(existingHost = existing, duplicateChecked = true)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        assembler.reset()
    }
}
