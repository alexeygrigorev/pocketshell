package com.pocketshell.core.voice

/**
 * Sealed hierarchy describing every failure mode [AudioRecorder] can surface.
 *
 * Previously the recorder leaked raw [IllegalStateException] / [IllegalArgumentException]
 * / [SecurityException] from the underlying [android.media.AudioRecord], forcing
 * callers to either catch `Throwable` or hand-classify the platform's error
 * surface. The sealed type lets the UI `when`-branch over a discriminated union
 * just like [WhisperException] does for the network side.
 *
 * Variants:
 *  - [PermissionDenied] — caller did not hold `RECORD_AUDIO` when [AudioRecorder.start]
 *    ran. We re-throw the platform's [SecurityException] as this variant so the
 *    permission prompt can be triggered without inspecting reflection-loaded
 *    AudioRecord internals.
 *  - [NoDevice] — `AudioRecord.getMinBufferSize` returned `ERROR_BAD_VALUE` /
 *    `ERROR`, or the recording mid-flight saw `ERROR_DEAD_OBJECT` (e.g. the
 *    user yanked their bluetooth headset). Suggests the input device went away
 *    or never existed.
 *  - [Underrun] — the reader thread saw an `ERROR_INVALID_OPERATION` from
 *    `AudioRecord.read`, typically because the recorder was stopped or never
 *    entered RECORDING state. Distinct from [NoDevice] which is hardware loss.
 *  - [Initialization] — `AudioRecord.state` was not `STATE_INITIALIZED` after
 *    construction, or `startRecording()` threw with the recorder uninitialised.
 *    Catch-all for "we asked the platform for a recorder and it said no".
 *  - [Other] — anything we did not explicitly classify. Preserves the original
 *    [Throwable] on [cause] so the bug report still has the stack trace.
 *
 * The original exception is preserved on [cause] for every variant so logs
 * keep the full diagnostic chain.
 */
public sealed class AudioRecorderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The caller did not hold `RECORD_AUDIO` when recording started. */
    public class PermissionDenied(message: String, cause: Throwable? = null) :
        AudioRecorderException(message, cause)

    /**
     * The microphone is unavailable — either the platform returned no minimum
     * buffer size, or the recording was severed mid-stream (e.g. bluetooth
     * disconnect, USB mic unplug).
     */
    public class NoDevice(message: String, cause: Throwable? = null) :
        AudioRecorderException(message, cause)

    /**
     * The reader thread saw an `ERROR_INVALID_OPERATION` — the recorder
     * stopped or never entered RECORDING state. Surfaced from [AudioRecorder.stop]
     * if it occurred mid-capture.
     */
    public class Underrun(message: String, cause: Throwable? = null) :
        AudioRecorderException(message, cause)

    /**
     * `AudioRecord` failed to initialise — typically an unsupported sample
     * rate / channel / encoding combination on the device.
     */
    public class Initialization(message: String, cause: Throwable? = null) :
        AudioRecorderException(message, cause)

    /** Anything else — preserves the original [cause] for diagnosis. */
    public class Other(message: String, cause: Throwable? = null) :
        AudioRecorderException(message, cause)
}
