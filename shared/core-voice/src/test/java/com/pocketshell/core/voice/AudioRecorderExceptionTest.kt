package com.pocketshell.core.voice

import android.media.AudioRecord
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the classification table in [mapAudioReadErrorCode] without
 * driving a real [AudioRecord] — the platform mic is unavailable in unit
 * tests, but the negative sentinel constants are accessible via the
 * Robolectric Android stubs.
 *
 * The runner is Robolectric only because `AudioRecord.ERROR_*` constants
 * live on the Android class. We're not actually instantiating an
 * AudioRecord here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AudioRecorderExceptionTest {

    @Test
    fun dead_object_maps_to_no_device() {
        val ex = mapAudioReadErrorCode(AudioRecord.ERROR_DEAD_OBJECT)
        assertTrue(
            "expected NoDevice, got ${ex::class.simpleName}",
            ex is AudioRecorderException.NoDevice,
        )
    }

    @Test
    fun invalid_operation_maps_to_underrun() {
        val ex = mapAudioReadErrorCode(AudioRecord.ERROR_INVALID_OPERATION)
        assertTrue(
            "expected Underrun, got ${ex::class.simpleName}",
            ex is AudioRecorderException.Underrun,
        )
    }

    @Test
    fun bad_value_maps_to_initialization() {
        val ex = mapAudioReadErrorCode(AudioRecord.ERROR_BAD_VALUE)
        assertTrue(
            "expected Initialization, got ${ex::class.simpleName}",
            ex is AudioRecorderException.Initialization,
        )
    }

    @Test
    fun unknown_error_code_maps_to_other() {
        val ex = mapAudioReadErrorCode(-999)
        assertTrue(
            "expected Other, got ${ex::class.simpleName}",
            ex is AudioRecorderException.Other,
        )
    }

    @Test
    fun exception_hierarchy_is_sealed_on_audio_recorder_exception() {
        // Compile-time guarantee that the variants compose under the sealed
        // type — if anyone added a new public variant outside the file this
        // test will still pass, but a removed variant would break us.
        val variants: List<AudioRecorderException> = listOf(
            AudioRecorderException.PermissionDenied("p"),
            AudioRecorderException.NoDevice("n"),
            AudioRecorderException.Underrun("u"),
            AudioRecorderException.Initialization("i"),
            AudioRecorderException.Other("o"),
        )
        assertTrue(variants.all { it is AudioRecorderException })
    }
}
