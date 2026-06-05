package com.pocketshell.app.fileviewer

import android.media.MediaPlayer
import java.io.Closeable
import java.io.File

/**
 * Thin lifecycle-managed wrapper over [android.media.MediaPlayer] (platform
 * decoder — no third-party audio dependency) for the in-app audio player
 * (issue #499).
 *
 * Plays a cached audio file (the viewer already caches the SFTP-fetched bytes
 * to disk) with play/pause and seek. The wrapper:
 *  - prepares asynchronously (`prepareAsync`) so a long file doesn't block,
 *  - reports lifecycle transitions (preparing → ready → playing/paused →
 *    completed) and errors (unsupported codec, bad file) through [Listener]
 *    so the panel can render clear states,
 *  - must be [release]d on dispose so the native MediaPlayer is never leaked.
 *
 * Not thread-safe; drive it from the main thread (Compose), which is where
 * MediaPlayer's callbacks are dispatched.
 */
class AudioPlayerController(
    private val file: File,
    private val listener: Listener,
    private val playerFactory: () -> MediaPlayer = ::MediaPlayer,
) : Closeable {

    /** Coarse playback phase the panel maps onto its controls. */
    enum class Phase { PREPARING, READY, PLAYING, PAUSED, COMPLETED, ERROR }

    interface Listener {
        fun onPhase(phase: Phase)

        /** Total track duration in ms once known (>= 0), else -1 while preparing. */
        fun onDuration(durationMs: Int)

        /** A user-facing message when playback can't start or fails mid-track. */
        fun onError(message: String)
    }

    private var player: MediaPlayer? = null
    private var prepared = false
    private var released = false

    /**
     * Open [file] and start preparing. Safe to call once; a second call is a
     * no-op after the first [prepare]/[release].
     */
    fun prepare() {
        if (player != null || released) return
        listener.onPhase(Phase.PREPARING)
        val mp = playerFactory()
        player = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                if (released) return@setOnPreparedListener
                prepared = true
                listener.onDuration(it.duration)
                listener.onPhase(Phase.READY)
            }
            mp.setOnCompletionListener {
                if (released) return@setOnCompletionListener
                listener.onPhase(Phase.COMPLETED)
            }
            mp.setOnErrorListener { _, what, extra ->
                if (!released) {
                    prepared = false
                    listener.onError(
                        "Couldn't play this audio (unsupported codec or corrupt file; " +
                            "code $what/$extra).",
                    )
                    listener.onPhase(Phase.ERROR)
                }
                true // handled — suppress the default reset-to-error transition
            }
            mp.prepareAsync()
        } catch (t: Throwable) {
            listener.onError("Couldn't open this audio file: ${t.message ?: t.javaClass.simpleName}")
            listener.onPhase(Phase.ERROR)
            runCatching { mp.release() }
            player = null
        }
    }

    /** Start/resume playback. No-op until prepared. */
    fun play() {
        val mp = player ?: return
        if (!prepared || released) return
        runCatching {
            mp.start()
            listener.onPhase(Phase.PLAYING)
        }
    }

    /** Pause playback. No-op until prepared. */
    fun pause() {
        val mp = player ?: return
        if (!prepared || released) return
        runCatching {
            if (mp.isPlaying) mp.pause()
            listener.onPhase(Phase.PAUSED)
        }
    }

    /** Seek to [positionMs], clamped to the track. No-op until prepared. */
    fun seekTo(positionMs: Int) {
        val mp = player ?: return
        if (!prepared || released) return
        val clamped = clampPosition(positionMs, mp.duration)
        runCatching { mp.seekTo(clamped) }
    }

    /** Current playback position in ms, or 0 before prepared/after release. */
    fun currentPositionMs(): Int {
        val mp = player ?: return 0
        if (!prepared || released) return 0
        return runCatching { mp.currentPosition }.getOrDefault(0)
    }

    /** True while audio is actively playing. */
    fun isPlaying(): Boolean {
        val mp = player ?: return false
        if (!prepared || released) return false
        return runCatching { mp.isPlaying }.getOrDefault(false)
    }

    /**
     * Release the native player. Idempotent. After this the controller is
     * inert — every control becomes a no-op so a late Compose callback can't
     * touch a freed MediaPlayer.
     */
    fun release() {
        if (released) return
        released = true
        prepared = false
        val mp = player
        player = null
        runCatching { mp?.release() }
    }

    override fun close() = release()

    companion object {
        /** Clamp a requested seek position into [0, duration]. Pure — unit-tested. */
        internal fun clampPosition(positionMs: Int, durationMs: Int): Int {
            if (durationMs <= 0) return positionMs.coerceAtLeast(0)
            return positionMs.coerceIn(0, durationMs)
        }
    }
}
