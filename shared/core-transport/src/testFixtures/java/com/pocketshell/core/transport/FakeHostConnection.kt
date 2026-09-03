package com.pocketshell.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException

/**
 * Scripted in-memory [HostConnection] for other modules' tests.
 *
 * It replaces the sshj transport entirely, so a ViewModel test can drive a full
 * host interaction on the JVM in milliseconds:
 *
 * ```kotlin
 * val host = FakeHostConnection()
 * host.onExecPrefix("pocketshell sessions list", ExecResult(0, sessionsJson, "", false))
 * host.enqueuePty(frames = listOf("hello\r\n".toByteArray()))
 * ...
 * host.markLost("network dropped")   // collectors of `state` react
 * ```
 *
 * Behaviour contract (asserted by `FakeHostConnectionTest`):
 * - [exec] answers the FIRST registered rule that matches the command; a rule
 *   registered with `once = true` is consumed by its first match, so several
 *   one-shot rules for the same command replay in registration order.
 *   Unmatched commands return [defaultExec] and are still recorded.
 * - [openPty] hands out the next script queued by [enqueuePty] (or
 *   [defaultPtyScript] when the queue is empty); a scripted channel replays its
 *   frames and then completes both `output` and `exit`.
 * - Once the state is [TransportState.Lost] or [TransportState.Closed], the
 *   connection is spent: [exec], [openPty] and [sftp] throw [IOException], the
 *   same way a real dead transport does.
 * - No timer ever runs: [scheduleGraceClose] only records a deadline (computed
 *   from the injected [nowMs] clock). Tests fire it explicitly with
 *   [fireGraceClose].
 *
 * Not thread-safe against concurrent *scripting*; concurrent calls from the
 * code under test are guarded by an internal lock.
 */
class FakeHostConnection(
    override val target: HostTarget = DEFAULT_TARGET,
    initialState: TransportState = TransportState.Connected,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : HostConnection {

    /** One recorded [exec] invocation. */
    data class ExecCall(val command: String, val timeoutMs: Long)

    /** One recorded [openPty] invocation. */
    data class PtyRequest(val command: String, val cols: Int, val rows: Int, val term: String)

    /** What a [FakePtyChannel] replays once opened. */
    data class PtyScript(
        val frames: List<ByteArray> = emptyList(),
        val exitCode: Int? = 0,
        /** When true the channel EOFs right after the frames; when false it stays live. */
        val completeAfterFrames: Boolean = true,
    )

    private class ExecRule(
        val description: String,
        val once: Boolean,
        val match: (String) -> Boolean,
        val reply: suspend (String) -> ExecResult,
    )

    private val lock = Any()

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val execRules = mutableListOf<ExecRule>()
    private val recordedExecCalls = mutableListOf<ExecCall>()
    private val queuedPtyScripts = ArrayDeque<PtyScript>()
    private val recordedPtyRequests = mutableListOf<PtyRequest>()
    private val openedPtyChannels = mutableListOf<FakePtyChannel>()
    private val recordedGraceHandles = mutableListOf<FakeGraceHandle>()
    private var pendingGraceHandle: FakeGraceHandle? = null
    private var sftpChannel: FakeSftpChannel? = null

    /** Reply for a command no rule matches. */
    var defaultExec: ExecResult = ExecResult(
        exitCode = 127,
        stdout = "",
        stderr = "FakeHostConnection: no exec script registered",
        timedOut = false,
    )

    /** Script used by [openPty] when nothing is queued: a live channel with no output. */
    var defaultPtyScript: PtyScript = PtyScript(
        frames = emptyList(),
        exitCode = null,
        completeAfterFrames = false,
    )

    // ---------------------------------------------------------------- scripting

    /** Scripts [result] for an exact command match. */
    fun onExec(command: String, result: ExecResult, once: Boolean = false): FakeHostConnection =
        onExecMatching("command == \"$command\"", once, { it == command }) { result }

    /** Scripts [result] for any command starting with [prefix]. */
    fun onExecPrefix(prefix: String, result: ExecResult, once: Boolean = false): FakeHostConnection =
        onExecMatching("command startsWith \"$prefix\"", once, { it.startsWith(prefix) }) { result }

    /**
     * Scripts a computed reply. [description] shows up in [scriptedExecRules] to
     * make an unmatched-command failure readable.
     */
    fun onExecMatching(
        description: String,
        once: Boolean = false,
        match: (String) -> Boolean,
        reply: suspend (String) -> ExecResult,
    ): FakeHostConnection {
        synchronized(lock) { execRules += ExecRule(description, once, match, reply) }
        return this
    }

    /** Queues the next PTY script handed out by [openPty]. */
    fun enqueuePty(
        frames: List<ByteArray> = emptyList(),
        exitCode: Int? = 0,
        completeAfterFrames: Boolean = true,
    ): FakeHostConnection {
        synchronized(lock) { queuedPtyScripts.addLast(PtyScript(frames, exitCode, completeAfterFrames)) }
        return this
    }

    /** [enqueuePty] with text frames, one frame per string. */
    fun enqueuePtyText(
        vararg frames: String,
        exitCode: Int? = 0,
        completeAfterFrames: Boolean = true,
    ): FakeHostConnection = enqueuePty(frames.map { it.toByteArray() }, exitCode, completeAfterFrames)

    // ------------------------------------------------------------- state control

    fun setState(state: TransportState) {
        _state.value = state
    }

    fun markConnecting() = setState(TransportState.Connecting)

    fun markConnected() = setState(TransportState.Connected)

    /** Flips the state to [TransportState.Lost]; collectors of [state] see it immediately. */
    fun markLost(cause: String) = setState(TransportState.Lost(cause))

    // -------------------------------------------------------------- observation

    val execCalls: List<ExecCall> get() = synchronized(lock) { recordedExecCalls.toList() }

    val executedCommands: List<String> get() = execCalls.map { it.command }

    val ptyRequests: List<PtyRequest> get() = synchronized(lock) { recordedPtyRequests.toList() }

    val openedPtys: List<FakePtyChannel> get() = synchronized(lock) { openedPtyChannels.toList() }

    val graceHandles: List<FakeGraceHandle>
        get() = synchronized(lock) { recordedGraceHandles.toList() }

    val pendingGrace: FakeGraceHandle? get() = synchronized(lock) { pendingGraceHandle }

    val scriptedExecRules: List<String>
        get() = synchronized(lock) { execRules.map { it.description } }

    val isClosed: Boolean get() = _state.value == TransportState.Closed

    // ------------------------------------------------------ HostConnection impl

    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        val rule = synchronized(lock) {
            requireUsable("exec")
            recordedExecCalls += ExecCall(command, timeoutMs)
            val index = execRules.indexOfFirst { it.match(command) }
            when {
                index < 0 -> null
                execRules[index].once -> execRules.removeAt(index)
                else -> execRules[index]
            }
        }
        return rule?.reply?.invoke(command) ?: defaultExec
    }

    override suspend fun openPty(
        command: String,
        cols: Int,
        rows: Int,
        term: String,
    ): PtyChannel = synchronized(lock) {
        requireUsable("openPty")
        val request = PtyRequest(command, cols, rows, term)
        recordedPtyRequests += request
        val script = queuedPtyScripts.removeFirstOrNull() ?: defaultPtyScript
        FakePtyChannel(request, script).also { openedPtyChannels += it }
    }

    override suspend fun sftp(): SftpChannel = synchronized(lock) {
        requireUsable("sftp")
        sftpChannel ?: FakeSftpChannel(nowMs).also { sftpChannel = it }
    }

    /** The in-memory SFTP channel, creating it if the code under test has not asked yet. */
    fun sftpFixture(): FakeSftpChannel = synchronized(lock) {
        sftpChannel ?: FakeSftpChannel(nowMs).also { sftpChannel = it }
    }

    override fun scheduleGraceClose(graceMs: Long): GraceHandle = synchronized(lock) {
        pendingGraceHandle?.markReplaced()
        FakeGraceHandle(nowMs() + graceMs).also {
            pendingGraceHandle = it
            recordedGraceHandles += it
        }
    }

    /**
     * Simulates the pending grace timer elapsing. Does nothing when there is no
     * pending close or when it was cancelled/replaced — that "nothing happens"
     * is the D21 assertion consumers want to make.
     */
    suspend fun fireGraceClose() {
        val handle = synchronized(lock) { pendingGraceHandle }
        if (handle != null && handle.isLive) {
            close()
        }
    }

    override suspend fun close() {
        val channels = synchronized(lock) {
            pendingGraceHandle = null
            openedPtyChannels.toList()
        }
        channels.forEach { it.close() }
        _state.value = TransportState.Closed
    }

    private fun requireUsable(operation: String) {
        when (val current = _state.value) {
            is TransportState.Lost -> throw IOException("$operation: connection lost (${current.cause})")
            TransportState.Closed -> throw IOException("$operation: connection closed")
            else -> Unit
        }
    }

    companion object {
        val DEFAULT_TARGET = HostTarget(
            hostId = 1L,
            hostname = "fake.invalid",
            port = 22,
            username = "tester",
            auth = AuthMaterial.KeyRef(1L),
        )
    }
}

/**
 * PTY channel handed out by [FakeHostConnection.openPty]. Replays its scripted
 * frames, records everything written to it, and lets a test push more output
 * ([emit]) or end the channel ([finish]) at will.
 *
 * [output] is single-consumer (it drains an internal channel), matching the real
 * PTY contract.
 */
class FakePtyChannel internal constructor(
    val request: FakeHostConnection.PtyRequest,
    script: FakeHostConnection.PtyScript,
) : PtyChannel {

    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val completion = CompletableDeferred<Int?>()
    private val recordedWrites = mutableListOf<ByteArray>()
    private val recordedResizes = mutableListOf<Pair<Int, Int>>()

    @Volatile
    private var ended = false

    override val output: Flow<ByteArray> = frames.receiveAsFlow()

    override val exit: Deferred<Int?> get() = completion

    /** Bytes the code under test sent to this PTY, in order. */
    val writes: List<ByteArray> get() = synchronized(this) { recordedWrites.toList() }

    /** All writes decoded as UTF-8 and concatenated. */
    val writtenText: String
        get() = writes.fold(ByteArray(0)) { acc, bytes -> acc + bytes }.toString(Charsets.UTF_8)

    /** Every [resize] as `cols to rows`, in order. */
    val resizes: List<Pair<Int, Int>> get() = synchronized(this) { recordedResizes.toList() }

    /** Current size, i.e. the open request size updated by every [resize]. */
    var cols: Int = request.cols
        private set

    var rows: Int = request.rows
        private set

    val isEnded: Boolean get() = ended

    init {
        script.frames.forEach { frames.trySend(it.copyOf()) }
        if (script.completeAfterFrames) {
            finish(script.exitCode)
        }
    }

    /** Pushes another output frame to collectors of [output]. */
    fun emit(bytes: ByteArray) {
        check(!ended) { "FakePtyChannel already ended" }
        frames.trySend(bytes.copyOf())
    }

    /** [emit] for UTF-8 text. */
    fun emitText(text: String) = emit(text.toByteArray())

    /** Ends the channel: [output] completes and [exit] resolves to [exitCode]. */
    fun finish(exitCode: Int?) {
        ended = true
        frames.close()
        completion.complete(exitCode)
    }

    override suspend fun write(bytes: ByteArray) {
        if (ended) throw IOException("write on an ended PTY channel")
        synchronized(this) { recordedWrites += bytes.copyOf() }
    }

    /** [write] for UTF-8 text. */
    suspend fun writeText(text: String) = write(text.toByteArray())

    override suspend fun resize(cols: Int, rows: Int) {
        if (ended) throw IOException("resize on an ended PTY channel")
        synchronized(this) { recordedResizes += cols to rows }
        this.cols = cols
        this.rows = rows
    }

    override suspend fun close() {
        if (!ended) finish(null)
    }
}

/**
 * In-memory [SftpChannel]. Paths are absolute and normalised (no trailing
 * slash except for the root); missing paths and over-size reads fail with
 * [IOException], the way a real server does.
 */
class FakeSftpChannel internal constructor(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : SftpChannel {

    private val files = linkedMapOf<String, ByteArray>()
    private val directories = linkedSetOf("/")
    private val modified = mutableMapOf<String, Long>()

    /** Pre-populates a file (and its parent directories). */
    fun seedFile(path: String, bytes: ByteArray): FakeSftpChannel {
        val normalized = normalize(path)
        seedParents(normalized)
        files[normalized] = bytes.copyOf()
        modified[normalized] = nowMs()
        return this
    }

    /** [seedFile] for UTF-8 text. */
    fun seedFile(path: String, text: String): FakeSftpChannel = seedFile(path, text.toByteArray())

    /** Pre-populates an (empty) directory. */
    fun seedDirectory(path: String): FakeSftpChannel {
        val normalized = normalize(path)
        seedParents(normalized)
        directories += normalized
        modified[normalized] = nowMs()
        return this
    }

    /** Raw bytes currently stored at [path], or null. Test-side inspection. */
    fun bytesAt(path: String): ByteArray? = files[normalize(path)]?.copyOf()

    /** UTF-8 text currently stored at [path], or null. */
    fun textAt(path: String): String? = bytesAt(path)?.toString(Charsets.UTF_8)

    override suspend fun list(path: String): List<SftpEntry> {
        val dir = normalize(path)
        if (dir !in directories) throw IOException("no such directory: $dir")
        val children = (directories + files.keys)
            .filter { it != dir && parentOf(it) == dir }
            .sorted()
        return children.map { entry(it) }
    }

    override suspend fun stat(path: String): SftpEntry? {
        val normalized = normalize(path)
        if (normalized !in directories && normalized !in files) return null
        return entry(normalized)
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        val normalized = normalize(path)
        val bytes = files[normalized] ?: throw IOException("no such file: $normalized")
        if (bytes.size > maxBytes) {
            // Same typed failure the sshj-backed SftpChannelImpl raises, so a
            // consumer's "too large to open" branch is exercised identically
            // against the fake and against a real host.
            throw SftpFileTooLargeException(normalized, bytes.size.toLong(), maxBytes)
        }
        return bytes.copyOf()
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val normalized = normalize(path)
        if (normalized in directories) throw IOException("$normalized is a directory")
        files[normalized] = bytes.copyOf()
        modified[normalized] = nowMs()
    }

    override suspend fun mkdir(path: String) {
        val normalized = normalize(path)
        if (normalized in directories || normalized in files) {
            throw IOException("already exists: $normalized")
        }
        directories += normalized
        modified[normalized] = nowMs()
    }

    override suspend fun rename(from: String, to: String) {
        val source = normalize(from)
        val destination = normalize(to)
        when (source) {
            in files -> {
                files[destination] = files.remove(source)!!
                modified[destination] = modified.remove(source) ?: nowMs()
            }

            in directories -> {
                directories -= source
                directories += destination
                modified[destination] = modified.remove(source) ?: nowMs()
            }

            else -> throw IOException("no such path: $source")
        }
    }

    override suspend fun delete(path: String) {
        val normalized = normalize(path)
        when (normalized) {
            in files -> files.remove(normalized)
            in directories -> {
                val hasChildren = (directories + files.keys).any { parentOf(it) == normalized }
                if (hasChildren) throw IOException("directory not empty: $normalized")
                directories -= normalized
            }

            else -> throw IOException("no such path: $normalized")
        }
        modified.remove(normalized)
    }

    private fun entry(path: String): SftpEntry = SftpEntry(
        path = path,
        isDirectory = path in directories,
        sizeBytes = files[path]?.size?.toLong() ?: 0L,
        modifiedEpochMs = modified[path] ?: 0L,
    )

    private fun seedParents(path: String) {
        var parent = parentOf(path)
        while (parent != null) {
            directories += parent
            parent = parentOf(parent)
        }
    }

    private fun parentOf(path: String): String? {
        if (path == "/") return null
        val cut = path.lastIndexOf('/')
        return if (cut <= 0) "/" else path.substring(0, cut)
    }

    private fun normalize(path: String): String {
        val absolute = if (path.startsWith("/")) path else "/$path"
        return absolute.trimEnd('/').ifEmpty { "/" }
    }
}

/**
 * Handle returned by [FakeHostConnection.scheduleGraceClose]. Nothing is
 * scheduled — the handle only records the deadline and whether it is still
 * live, so a test can assert "no close is pending" after a cancel.
 */
class FakeGraceHandle internal constructor(override val deadlineMs: Long) : GraceHandle {

    @Volatile
    var isCancelled: Boolean = false
        private set

    /** True when a later [FakeHostConnection.scheduleGraceClose] superseded this handle. */
    @Volatile
    var isReplaced: Boolean = false
        private set

    /** True while this handle would still fire. */
    val isLive: Boolean get() = !isCancelled && !isReplaced

    override fun cancel() {
        isCancelled = true
    }

    internal fun markReplaced() {
        isReplaced = true
    }
}
