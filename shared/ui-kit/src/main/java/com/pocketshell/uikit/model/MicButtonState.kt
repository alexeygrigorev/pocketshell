package com.pocketshell.uikit.model

/**
 * State of the prompt-composer microphone button. Matches `.mic-btn`
 * in `docs/mockups/composer.html` and the accompanying waveform /
 * "Listening" label.
 *
 * - [Idle] — accent fill, no animation. Tap starts recording.
 * - [Recording] — accent fill with a slow pulse to signal active capture.
 * - [Disabled] — muted, non-interactive (no mic permission yet, etc.).
 */
enum class MicButtonState {
    Idle,
    Recording,
    Disabled,
}
