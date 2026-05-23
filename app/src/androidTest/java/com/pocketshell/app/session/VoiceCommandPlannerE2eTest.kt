package com.pocketshell.app.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.CommandPlannerClientFactory
import com.pocketshell.core.voice.CommandPlannerConfig
import com.pocketshell.core.voice.OkHttpOpenAiCommandPlannerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Emulator-side fake endpoint coverage for issue #66.
 *
 * This avoids real OpenAI calls: the ViewModel receives the production
 * OkHttp planner client pointed at MockWebServer, then the approved plan is
 * written through TerminalSurfaceState's external-producer input bridge.
 */
@RunWith(AndroidJUnit4::class)
class VoiceCommandPlannerE2eTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName(LOOPBACK_HOST), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fakePlannerEndpointProducesReviewableCommandThatRunsThroughTerminalBridge() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    JSONObject()
                        .put(
                            "choices",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put(
                                            "message",
                                            JSONObject()
                                                .put(
                                                    "content",
                                                    """{"commands":[{"command":"git status --short"}]}""",
                                                ),
                                        ),
                                ),
                        )
                        .toString(),
                ),
        )
        val planner = OkHttpOpenAiCommandPlannerClient(
            config = CommandPlannerConfig(
                apiKey = "sk-e2e-test".toCharArray(),
                model = "planner-e2e-model",
                baseUrl = "http://$LOOPBACK_HOST:${server.port}/v1",
            ),
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
            commandPlannerClientFactory = CommandPlannerClientFactory { planner },
        )
        val stdin = RecordingOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerJob = viewModel.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = stdin,
        )

        try {
            viewModel.planVoiceCommand("show git status")
            waitForReviewableCommand(viewModel)
            viewModel.approvePendingVoiceCommand(withEnter = true)

            withTimeout(5_000) {
                while (stdin.snapshot() != "git status --short\r") {
                    delay(50)
                }
            }

            val recorded = server.takeRequest()
            assertEquals("/v1/chat/completions", recorded.path)
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("planner-e2e-model", body.getString("model"))
            val userContent = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals("show git status", userContent.getString("transcript"))
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            viewModel.terminalState.detachExternalProducer()
        }
    }

    private suspend fun waitForReviewableCommand(viewModel: SessionViewModel) {
        withTimeout(10_000) {
            while (true) {
                val review = viewModel.voiceCommandReview.value
                if (review.pendingPlan != null) return@withTimeout
                if (review.error != null) {
                    fail("Command planner failed before producing a reviewable command: ${review.error}")
                }
                delay(50)
            }
        }
    }

    private class RecordingOutputStream : OutputStream() {
        private val bytes = mutableListOf<Byte>()

        override fun write(b: Int) {
            synchronized(bytes) {
                bytes += b.toByte()
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            synchronized(bytes) {
                for (index in offset until offset + length) {
                    bytes += buffer[index]
                }
            }
        }

        fun snapshot(): String = synchronized(bytes) {
            bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}
