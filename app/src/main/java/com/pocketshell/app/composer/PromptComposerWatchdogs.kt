package com.pocketshell.app.composer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the composer timeout jobs while the ViewModel keeps the timeout behavior.
 */
internal class PromptComposerWatchdogs(
    private val scope: CoroutineScope,
    private var sendTimeoutMs: Long?,
    private var handoffTimeoutMs: Long?,
    private var transcribeTimeoutMs: Long?,
    private val onSendExpired: () -> Unit,
    private val onHandoffExpired: () -> Unit,
    private val onTranscribeExpired: () -> Unit,
) {
    private var sendJob: Job? = null
    private var handoffJob: Job? = null
    private var transcribeJob: Job? = null

    fun setSendTimeoutForTest(timeoutMs: Long?) {
        sendTimeoutMs = timeoutMs
    }

    fun setTranscribeTimeoutForTest(timeoutMs: Long?) {
        transcribeTimeoutMs = timeoutMs
    }

    fun setHandoffTimeoutForTest(timeoutMs: Long?) {
        handoffTimeoutMs = timeoutMs
    }

    fun armSend() {
        sendJob?.cancel()
        val timeoutMs = sendTimeoutMs ?: return
        sendJob = scope.launch {
            delay(timeoutMs)
            sendJob = null
            onSendExpired()
        }
    }

    fun disarmSend() {
        sendJob?.cancel()
        sendJob = null
    }

    fun armHandoff() {
        handoffJob?.cancel()
        val timeoutMs = handoffTimeoutMs ?: return
        handoffJob = scope.launch {
            delay(timeoutMs)
            handoffJob = null
            onHandoffExpired()
        }
    }

    fun disarmHandoff() {
        handoffJob?.cancel()
        handoffJob = null
    }

    fun sendJobForTest(): Job? = sendJob

    fun handoffJobForTest(): Job? = handoffJob

    fun armTranscribe() {
        transcribeJob?.cancel()
        val timeoutMs = transcribeTimeoutMs ?: return
        transcribeJob = scope.launch {
            delay(timeoutMs)
            transcribeJob = null
            onTranscribeExpired()
        }
    }

    fun disarmTranscribe() {
        transcribeJob?.cancel()
        transcribeJob = null
    }

    fun cancelAll() {
        disarmSend()
        disarmHandoff()
        disarmTranscribe()
    }
}
