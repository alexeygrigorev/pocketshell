package com.pocketshell.next.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
import com.pocketshell.next.composer.SpeechRecognitionListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unit tests for [WhisperSpeechRecognitionProvider] (rewrite task P-2) — the
 * Whisper dictation arm this task extracted out of the old client's
 * `PromptComposerViewModel`, behind P-1's [SpeechRecognitionProvider] seam.
 *
 * The load-bearing behaviour is the subway case: audio is queued in
 * [PendingTranscriptionStore] BEFORE the network call, on every stop, and an
 * offline stop skips the network entirely. The store is real (Room,
 * in-memory) so the queued row is asserted from the actual table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WhisperSpeechRecognitionProviderTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: PendingTranscriptionStore
    private val mic = FakeMicCapture()
    private val whisperClient = FakeWhisperClient()
    private val whisperFactory = FakeWhisperClientFactory(whisperClient)
    private val connectivity = FakeConnectivityProbe()

    /**
     * A real (non-virtual-time) background scope, matching how production
     * wires this provider (`di/VoiceModule.provideVoiceScope`) — the
     * transcription round trip genuinely runs on Dispatchers.IO inside
     * [PendingTranscriptionStore], so a virtual-time `TestDispatcher` cannot
     * observe its completion; [RecordingListener.awaitTerminal] is what each
     * test waits on instead.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        store = PendingTranscriptionStore(context, db.pendingTranscriptionDao())
        File(context.filesDir, PendingTranscriptionStore.VOICE_PENDING_DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun provider() = WhisperSpeechRecognitionProvider(
        mic = mic,
        whisper = whisperFactory,
        pending = store,
        connectivity = connectivity,
        scope = scope,
    )

    @Test
    fun `unavailable with no stored api key`() {
        whisperFactory.client = null
        assertFalse(provider().isAvailable())
    }

    @Test
    fun `available once a client can be built`() {
        assertEquals(true, provider().isAvailable())
    }

    @Test
    fun `a successful transcription is delivered and nothing stays queued`() = runBlocking {
        val listener = RecordingListener()
        whisperClient.result = Result.success("run the tests")
        val session = provider().start(language = "en-US", listener = listener)
        assertNotNull(session)

        mic.wav = loudWav()
        session!!.stopListening()
        listener.awaitTerminal()

        assertEquals("run the tests", listener.finalText)
        assertNull(listener.error)
        assertEquals(0, db.pendingTranscriptionDao().getAllOnce().size)
    }

    /**
     * The subway case: audio is queued BEFORE the network round trip on every
     * stop, not only when offline. Persist-before-Whisper is the whole
     * feature (see the provider's class KDoc); this proves it happens even on
     * the success path.
     */
    @Test
    fun `audio is queued before the whisper round trip, then cleared on success`() = runBlocking {
        var queuedAtCallTime = -1
        whisperClient.onTranscribe = {
            queuedAtCallTime = db.pendingTranscriptionDao().getAllOnce().size
        }
        whisperClient.result = Result.success("hello")
        val listener = RecordingListener()
        val session = provider().start(language = null, listener = listener)
        mic.wav = loudWav()
        session!!.stopListening()
        listener.awaitTerminal()

        assertEquals("the row must exist before transcribe() is called", 1, queuedAtCallTime)
        assertEquals("a success clears the queue", 0, db.pendingTranscriptionDao().getAllOnce().size)
    }

    @Test
    fun `offline at stop time skips the network and parks the row waiting`() = runBlocking {
        connectivity.online = false
        val listener = RecordingListener()
        val session = provider().start(language = null, listener = listener)
        mic.wav = loudWav()
        session!!.stopListening()
        listener.awaitTerminal()

        assertEquals(0, whisperClient.callCount)
        assertNotNull(listener.error)
        val row = db.pendingTranscriptionDao().getAllOnce().single()
        assertEquals(PendingTranscriptionItem.NETWORK_WAITING_MESSAGE, row.lastErrorMessage)
    }

    @Test
    fun `a failed round trip keeps the row queued with the failure message`() = runBlocking {
        whisperClient.result = Result.failure(WhisperException.RateLimited("slow down"))
        val listener = RecordingListener()
        val session = provider().start(language = null, listener = listener)
        mic.wav = loudWav()
        session!!.stopListening()
        listener.awaitTerminal()

        assertEquals("Rate limited by OpenAI. Try again in a moment.", listener.error)
        val row = db.pendingTranscriptionDao().getAllOnce().single()
        assertEquals(1, row.retryCount)
    }

    @Test
    fun `silent audio never reaches the network`() = runBlocking {
        val listener = RecordingListener()
        val session = provider().start(language = null, listener = listener)
        mic.wav = silentWav()
        session!!.stopListening()
        listener.awaitTerminal()

        assertEquals(0, whisperClient.callCount)
        assertNotNull(listener.error)
        assertEquals(0, db.pendingTranscriptionDao().getAllOnce().size)
    }

    /** [cancel] never launches onto [scope], so this needs no wait at all. */
    @Test
    fun `cancel drops the recording without queuing anything`() = runBlocking {
        val session = provider().start(language = null, listener = RecordingListener())
        mic.wav = loudWav()
        session!!.cancel()

        assertEquals(0, whisperClient.callCount)
        assertEquals(0, db.pendingTranscriptionDao().getAllOnce().size)
    }

    @Test
    fun `a mic that cannot open reports unavailable`() {
        mic.failToStart = true
        val session = provider().start(language = null, listener = RecordingListener())
        assertNull(session)
    }

    // --------------------------------------------------------------- fixtures

    private class RecordingListener : SpeechRecognitionListener {
        var finalText: String? = null
        var error: String? = null
        private val terminal = CompletableDeferred<Unit>()

        override fun onPartial(text: String) {}
        override fun onFinal(text: String) {
            finalText = text
            terminal.complete(Unit)
        }

        override fun onError(message: String) {
            error = message
            terminal.complete(Unit)
        }

        /** Blocks until a terminal callback fires, or fails the test after 5s. */
        suspend fun awaitTerminal() = withTimeout(5_000) { terminal.await() }
    }

    private class FakeMicCapture : MicCapture {
        var wav: ByteArray = ByteArray(0)
        var failToStart: Boolean = false
        override fun start() {
            if (failToStart) throw AudioRecorderException.NoDevice("no mic")
        }

        override fun stop(): ByteArray = wav
        override fun currentAmplitude(): Float = 0f
    }

    private class FakeWhisperClient(var result: Result<String> = Result.success("")) : WhisperClient {
        var callCount = 0
        var onTranscribe: (suspend () -> Unit)? = null
        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
            callCount++
            onTranscribe?.invoke()
            return result
        }
    }

    private class FakeWhisperClientFactory(var client: WhisperClient?) : WhisperClientFactory {
        override fun create(): WhisperClient? = client
    }

    private class FakeConnectivityProbe(var online: Boolean = true) : ConnectivityProbe {
        override fun refresh(): Boolean = online
    }

    /** ~500ms of a loud tone — clears [com.pocketshell.core.voice.SpeechAudioGuard]'s energy bar. */
    private fun loudWav(): ByteArray = wav(tonePcm(durationMs = 500, amplitude = 0.5f))

    private fun silentWav(): ByteArray = wav(ByteArray(16_000 * 500 / 1000 * 2))

    private fun wav(pcm: ByteArray): ByteArray {
        val sampleRate = 16_000
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    private fun tonePcm(durationMs: Int, amplitude: Float): ByteArray {
        val sampleRate = 16_000
        val samples = sampleRate * durationMs / 1000
        val out = ByteArray(samples * 2)
        val peak = (amplitude * Short.MAX_VALUE).toInt()
        for (n in 0 until samples) {
            val v = (peak * sin(2.0 * PI * 220.0 * n / sampleRate)).toInt()
            val s = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[n * 2] = (s and 0xFF).toByte()
            out[n * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}
