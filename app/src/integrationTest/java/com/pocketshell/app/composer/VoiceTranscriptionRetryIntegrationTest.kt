package com.pocketshell.app.composer

import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.core.voice.OkHttpWhisperClient
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Issue #688 — Docker-modeled connection break for the voice-transcription
 * retry path.
 *
 * Fronts the REAL [OkHttpWhisperClient] against a controllable-failure
 * transcription endpoint (`tests/docker/Dockerfile.flaky-transcription`)
 * that can fail twice then succeed, or hang past the client call timeout
 * then succeed. This is the maintainer's ask: model the flaky connection in
 * Docker, reproduce the duplicate-insert as the failing case, and prove the
 * fix gives exactly one insertion.
 *
 * Runs JVM-side under `:app:integrationTest` (Robolectric + Testcontainers),
 * so it lands in the regular CI "Integration tests (Docker)" job — no
 * emulator required. Docker-unavailable hosts skip via [assumeTrue].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class VoiceTranscriptionRetryIntegrationTest {

    // `viewModelScope` posts to Dispatchers.Main; Robolectric does not pump
    // the real main looper for these JVM tests. Back Main with a SINGLE-
    // thread dispatcher so the ViewModel sees the same single-threaded main
    // semantics it has in production (the in-flight retry-dedupe set is
    // mutated only on Main). The real OkHttp round-trip runs on
    // Dispatchers.IO inside `transcribe`, so it never blocks this thread.
    private val mainExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainDispatcher =
        mainExecutor.asCoroutineDispatcher()

    @Before
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    companion object {
        private const val CONTAINER_HTTP_PORT = 8089

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            if (!dockerAvailable) return

            val dockerDir = projectRoot.resolve("tests/docker")
            val image = ImageFromDockerfile("pocketshell-test-flaky-transcription", false)
                .withDockerfile(dockerDir.resolve("Dockerfile.flaky-transcription"))
            container = GenericContainer(image)
                .withExposedPorts(CONTAINER_HTTP_PORT)
                .waitingFor(
                    Wait.forHttp("/healthz").forPort(CONTAINER_HTTP_PORT).forStatusCode(200),
                )
                .withStartupTimeout(Duration.ofSeconds(90))
                .also { it.start() }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            container?.stop()
            container = null
        }

        private fun findProjectRoot(): Path {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (dir.resolve("tests/docker/Dockerfile.flaky-transcription").toFile().exists()) {
                    return dir
                }
                dir = dir.parent
            }
            error(
                "Could not locate tests/docker/Dockerfile.flaky-transcription from user.dir=" +
                    System.getProperty("user.dir"),
            )
        }
    }

    private val baseHttp: String
        get() = "http://${container!!.host}:${container!!.getMappedPort(CONTAINER_HTTP_PORT)}"

    /**
     * Reset the server's per-id attempt counters between scenarios so each
     * test gets a clean failure schedule.
     */
    private fun resetServer() {
        val conn = (URL("$baseHttp/reset").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write(ByteArray(0)) }
        }
        assertEquals(200, conn.responseCode)
        conn.disconnect()
    }

    private fun serverAttempts(retryId: String): Int {
        val conn = URL("$baseHttp/attempts/$retryId").openConnection() as HttpURLConnection
        conn.inputStream.use { stream ->
            val body = stream.readBytes().toString(Charsets.UTF_8)
            val match = Regex("\"attempts\"\\s*:\\s*(\\d+)").find(body)
            return match?.groupValues?.get(1)?.toInt() ?: 0
        }
    }

    /**
     * A real [OkHttpWhisperClient] pointed at the flaky container. The
     * per-recording retry-id and the failure schedule are carried as request
     * headers (the client owns the URL path, so query params can't be baked
     * into `baseUrl`). One container serves every scenario.
     */
    private fun flakyWhisper(
        retryId: String,
        headers: Map<String, String>,
        callTimeoutMs: Long,
    ): WhisperClient {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder().addHeader("X-Retry-Id", retryId)
                headers.forEach { (k, v) -> builder.addHeader(k, v) }
                chain.proceed(builder.build())
            }
            .build()
        return OkHttpWhisperClient(
            apiKey = "sk-flaky-test".toCharArray(),
            baseUrl = "$baseHttp/v1",
            client = client,
        )
    }

    private fun newViewModel(
        whisper: WhisperClient,
        queue: InMemoryRetryQueue,
    ): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = NoopMic,
        whisperClientFactory = WhisperClientFactory { whisper },
        apiKeyStorage = StubVault,
        voiceSettings = StubVoiceSettings,
        pendingTranscriptionStore = queue,
        connectivity = AlwaysOnline,
    )

    /**
     * Server-side break: the first attempt 503s, the second succeeds. The
     * ViewModel retries the SAME row twice (sequentially, as the user/
     * foreground-resume would) and the transcript is inserted EXACTLY ONCE.
     * Crucially, the recovery retry after a failure does NOT add a second
     * copy on top of any in-flight straggler — the late-success drop guards
     * that.
     */
    @Test
    fun flakyServerFailThenSucceedInsertsTranscriptExactlyOnce() {
        assumeTrue("Docker not available; skipping flaky-transcription test", container != null)
        resetServer()
        runBlocking {
            val retryId = "fail-then-succeed"
            val queue = InMemoryRetryQueue()
            queue.nextId = retryId
            queue.enqueueAudio(SpeechAudioGuard.speechWavForTesting(), "composer")
            queue.markFailure(retryId, "Network error: timeout")
            // failures=1 so the first attempt 503s and the next succeeds.
            val whisper = flakyWhisper(
                retryId,
                mapOf("X-Failures" to "1", "X-Text" to "exactly one"),
                callTimeoutMs = 10_000,
            )
            val vm = newViewModel(whisper, queue)

            // First retry hits the forced 503 and bumps the row — no insert.
            vm.retryPending(retryId)
            awaitRetryIdle(vm)
            assertEquals("server-error retry must not insert", "", vm.uiState.value.draft)
            assertFalse(queue.snapshot().isEmpty())

            // Recovery retry hits the success attempt → exactly one insert.
            vm.retryPending(retryId)
            awaitQueueEmpty(queue)

            assertEquals("exactly one", vm.uiState.value.draft)
            assertTrue(queue.snapshot().isEmpty())
            assertTrue(queue.succeededIds.contains(retryId))
            // The server saw exactly two attempts — no extra duplicate calls.
            assertEquals(2, serverAttempts(retryId))
        }
    }

    /**
     * Idempotency under a REAL overlap window: the first attempt hangs past
     * the client call timeout (so the round-trip is genuinely in flight for
     * a while), and a SECOND trigger arrives for the same row during that
     * window. The in-flight guard must collapse both to a single round-trip
     * — the server sees exactly one attempt — and after the hang resolves
     * the recovery retry inserts the transcript exactly once.
     */
    @Test
    fun overlappingTriggersDuringHangCollapseToSingleRoundTrip() {
        assumeTrue("Docker not available; skipping flaky-transcription test", container != null)
        resetServer()
        runBlocking {
            val retryId = "overlap-hang"
            val queue = InMemoryRetryQueue()
            queue.nextId = retryId
            queue.enqueueAudio(SpeechAudioGuard.speechWavForTesting(), "composer")
            queue.markFailure(retryId, "Network error: timeout")
            // failures=1, mode=timeout, hang 1.5s; client call timeout 600ms.
            val whisper = flakyWhisper(
                retryId,
                mapOf(
                    "X-Mode" to "timeout",
                    "X-Failures" to "1",
                    "X-Hang" to "1.5",
                    "X-Text" to "single insert",
                ),
                callTimeoutMs = 600,
            )
            val vm = newViewModel(whisper, queue)

            // Fire the first retry; while it is hung, fire several more for the
            // same row. The guard must dedupe them — only ONE attempt reaches
            // the server during this window.
            vm.retryPending(retryId)
            // Give the first round-trip time to reach the server and hang.
            kotlinx.coroutines.delay(250)
            repeat(5) { vm.retryPending(retryId) }
            awaitRetryIdle(vm)
            assertEquals("only one attempt may reach the server during the hang", 1, serverAttempts(retryId))
            assertEquals("a hung/timed-out attempt must not insert", "", vm.uiState.value.draft)

            // Now the recovery retry hits the success attempt → one insert.
            vm.retryPending(retryId)
            awaitQueueEmpty(queue)

            assertEquals("single insert", vm.uiState.value.draft)
            assertEquals("recovery attempt is the only additional call", 2, serverAttempts(retryId))
            assertTrue(queue.snapshot().isEmpty())
        }
    }

    /**
     * The "timeout that may have succeeded server-side" case: the first
     * attempt hangs past the client call timeout (Transport failure), the
     * retry succeeds. The transcript still lands exactly once and the row
     * clears — no stale pending state.
     */
    @Test
    fun timeoutThenRetrySucceedsWithSingleInsertAndClears() {
        assumeTrue("Docker not available; skipping flaky-transcription test", container != null)
        resetServer()
        runBlocking {
            val retryId = "timeout-then-succeed"
            val queue = InMemoryRetryQueue()
            queue.nextId = retryId
            queue.enqueueAudio(SpeechAudioGuard.speechWavForTesting(), "composer")
            queue.markFailure(retryId, "Network error: timeout")
            // mode=timeout, failures=1, hang=2s; client call timeout 800ms so
            // attempt 1 times out, attempt 2 succeeds.
            val whisper = flakyWhisper(
                retryId,
                mapOf(
                    "X-Mode" to "timeout",
                    "X-Failures" to "1",
                    "X-Hang" to "2",
                    "X-Text" to "recovered",
                ),
                callTimeoutMs = 800,
            )
            val vm = newViewModel(whisper, queue)

            // First retry times out and bumps the row.
            vm.retryPending(retryId)
            awaitRetryIdle(vm)
            assertEquals("first retry must not insert on timeout", "", vm.uiState.value.draft)
            assertFalse(queue.snapshot().isEmpty())

            // Second retry hits the success attempt.
            vm.retryPending(retryId)
            awaitQueueEmpty(queue)

            assertEquals("recovered", vm.uiState.value.draft)
            assertTrue(queue.snapshot().isEmpty())
        }
    }

    private suspend fun awaitQueueEmpty(queue: InMemoryRetryQueue) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (queue.snapshot().isEmpty()) return
            kotlinx.coroutines.delay(50)
        }
        error("queue did not drain within timeout; rows=${queue.snapshot()}")
    }

    private suspend fun awaitRetryIdle(vm: PromptComposerViewModel) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (vm.uiState.value.retryingIds.isEmpty()) return
            kotlinx.coroutines.delay(50)
        }
        error("retry did not settle within timeout")
    }

    // ---- minimal ViewModel collaborators (no Android Context needed) ----

    private object NoopMic : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = SpeechAudioGuard.speechWavForTesting()
        override fun currentAmplitude(): Float = 0f
    }

    private object StubVault : PromptComposerViewModel.ApiKeyVault {
        override fun save(key: CharArray) = Unit
        override fun load(): CharArray = "sk-flaky-test".toCharArray()
        override fun clear() = Unit
    }

    private object StubVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private object AlwaysOnline : PromptComposerViewModel.ConnectivityProbe {
        override fun refresh(): Boolean = true
    }

    /**
     * In-memory pending queue mirroring [PendingTranscriptionStore]'s
     * observable semantics without Room/filesystem, so the integration test
     * can run under Robolectric and focus on the retry idempotency.
     */
    private class InMemoryRetryQueue : PromptComposerViewModel.PendingTranscriptionQueue {
        private val flow = MutableStateFlow<List<PendingTranscriptionItem>>(emptyList())
        private val audio = mutableMapOf<String, ByteArray>()
        override val items = flow

        var nextId = "queued-1"
        val succeededIds: MutableList<String> = mutableListOf()
        val failureIds: MutableList<String> = mutableListOf()

        override suspend fun enqueueAudio(
            audio: ByteArray,
            destinationContext: String,
            initialError: String?,
        ): PendingTranscriptionItem {
            val id = nextId
            this.audio[id] = audio
            val item = PendingTranscriptionItem(
                id = id,
                recordingTimestampMs = 0,
                destinationContext = destinationContext,
                retryCount = 0,
                lastErrorMessage = initialError,
                audioByteSize = audio.size.toLong(),
            )
            flow.value = listOf(item) + flow.value
            return item
        }

        override suspend fun snapshot(): List<PendingTranscriptionItem> = flow.value
        override suspend fun loadAudio(id: String): ByteArray? = audio[id]

        override suspend fun markSucceeded(id: String) {
            succeededIds += id
            audio.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
        }

        override suspend fun markFailure(id: String, errorMessage: String): PendingTranscriptionItem? {
            failureIds += id
            val existing = flow.value.firstOrNull { it.id == id } ?: return null
            val updated = existing.copy(
                retryCount = existing.retryCount + 1,
                lastErrorMessage = errorMessage,
            )
            flow.value = flow.value.map { if (it.id == id) updated else it }
            return updated
        }

        override suspend fun discard(id: String) = markSucceeded(id)
        override suspend fun saveAsAudioFile(id: String): String? = null
        override suspend fun reconcile() = Unit
    }
}
