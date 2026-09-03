package com.pocketshell.next.composer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.transport.FakeSftpChannel
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.FakeHostConnectionFactory
import com.pocketshell.next.connect.RoomTrustStore
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The composer stack assembled for a host-JVM test: a real in-memory Room
 * database (so the sent-message log is the real DAO against real SQL), the real
 * [ConnectionsRegistry], the real [ComposerDraftStore] over Robolectric's
 * SharedPreferences, and the real [ComposerAttachmentStager] over the real
 * `ContentResolver` — with only the sshj dial swapped for `core-transport`'s
 * scripted [FakeHostConnection] and its in-memory [FakeSftpChannel].
 *
 * Everything except the socket is production code, for the reason
 * `TestFilesStack` gives: a test that stubbed the draft store could not tell
 * whether a kept draft actually reaches disk, and a test that stubbed
 * `SftpChannel` could not tell whether an attachment lands at the path the
 * message says it does.
 */
class TestComposerStack(homeDirectory: String = "/home/testuser") {

    val context: Context = ApplicationProvider.getApplicationContext()

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        // Same reason as TestFilesStack: keep Room off the ArchTaskExecutor IO
        // thread so `advanceUntilIdle()` means "the write finished".
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

    val factory = FakeHostConnectionFactory(null)

    val registry = ConnectionsRegistry(
        factory = factory,
        trustStore = RoomTrustStore(db.hostDao(), Dispatchers.Unconfined),
        hostDao = db.hostDao(),
        dispatcher = Dispatchers.Unconfined,
    )

    val drafts = ComposerDraftStore(context, Dispatchers.Unconfined)

    val speech = FakeSpeechRecognitionProvider()

    /** Fixed clock so attachment file names are deterministic. */
    var nowMs: Long = 1_700_000_000_000L

    /**
     * The dispatcher the stager reads picked bytes on.
     *
     * Runs inline by default. A test that calls [GatedDispatcher.hold] first
     * gets a genuinely suspended upload it can observe mid-flight, which is the
     * only honest way to prove "a send during an upload does not reach the
     * wire" — a hand-set `staging` field would prove the assertion against
     * itself.
     */
    val uploadGate = GatedDispatcher()

    val stager = ComposerAttachmentStager(
        resolver = context.contentResolver,
        dispatcher = uploadGate,
        now = { nowMs },
    )

    private var live: FakeHostConnection? = null

    init {
        factory.script = { connection ->
            connection.onExec("pwd", ExecResult(0, "$homeDirectory\n", "", false))
            live = connection
        }
    }

    /** The connection the registry dialled. Fails loudly before the first dial. */
    val connection: FakeHostConnection
        get() = requireNotNull(live) { "nothing has dialled yet" }

    val sftp: FakeSftpChannel get() = connection.sftpFixture()

    fun seedHost(): Long = runBlocking {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "composer-key", privateKeyPath = "/dev/null"))
        db.hostDao().insert(
            HostEntity(
                name = "fixture",
                hostname = "10.0.2.2",
                port = 2222,
                username = "testuser",
                keyId = keyId,
            ),
        )
    }

    fun viewModel(): ComposerViewModel = ComposerViewModel(
        registry = registry,
        drafts = drafts,
        history = db.sentMessageDao(),
        stager = stager,
        speech = speech,
    )

    fun close() {
        db.close()
        // SharedPreferences are process-global under Robolectric, so a draft
        // written by one test would otherwise be loaded by the next.
        context.getSharedPreferences("composer_drafts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}

/**
 * A [SessionSink] whose liveness the test owns.
 *
 * [isLive] is a `var` read at call time — the same shape the production sink
 * has, so a test can flip the session dead between two sends and prove the
 * composer asked again rather than caching the answer.
 */
class RecordingSessionSink(override var isLive: Boolean = true) : SessionSink {

    val sent = mutableListOf<ByteArray>()

    override fun sendBytes(bytes: ByteArray) {
        sent += bytes
    }

    /** Everything written so far, decoded — what the remote PTY would have read. */
    fun sentText(): List<String> = sent.map { it.toString(Charsets.UTF_8) }
}

/**
 * A dispatcher that runs work inline until a test [hold]s it, then queues
 * everything until [release].
 *
 * Deliberately not a `StandardTestDispatcher` sharing the test scheduler: that
 * would resume on the next `runCurrent()`, which is precisely the call a test
 * needs in order to observe the mid-flight state.
 */
class GatedDispatcher : CoroutineDispatcher() {

    private val queued = mutableListOf<Runnable>()
    private var held = false

    fun hold() {
        held = true
    }

    fun release() {
        held = false
        val pending = queued.toList()
        queued.clear()
        pending.forEach { it.run() }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (held) queued += block else block.run()
    }
}

/** A scripted recognizer: the test decides what "the user said". */
class FakeSpeechRecognitionProvider(var available: Boolean = true) : SpeechRecognitionProvider {

    var listener: SpeechRecognitionListener? = null
    var stopped: Boolean = false
    var cancelled: Boolean = false

    override fun isAvailable(): Boolean = available

    override fun start(
        language: String?,
        listener: SpeechRecognitionListener,
    ): SpeechRecognitionSession? {
        if (!available) return null
        this.listener = listener
        return object : SpeechRecognitionSession {
            override fun stopListening() {
                stopped = true
            }

            override fun cancel() {
                cancelled = true
            }
        }
    }

    fun partial(text: String) = requireNotNull(listener).onPartial(text)

    fun final(text: String) = requireNotNull(listener).onFinal(text)

    fun error(message: String) = requireNotNull(listener).onError(message)
}
