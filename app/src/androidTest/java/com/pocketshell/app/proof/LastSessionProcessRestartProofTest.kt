package com.pocketshell.app.proof

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.resolveLastSessionForStop
import com.pocketshell.app.session.LastSessionStore
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.sessions.HostTmuxSessionListResult
import com.pocketshell.app.sessions.SshHostTmuxSessionsGateway
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.app.tmux.TmuxConnectTrigger
import com.pocketshell.app.tmux.TmuxRestoreIntentSnapshot
import com.pocketshell.app.tmux.navigationTargetOrNull
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device half of the host-owned two-process persistence proof (#2264).
 *
 * These methods MUST NOT be run together by connectedDebugAndroidTest. The
 * host harness invokes each method with a separate direct `am instrument`,
 * force-stopping the suffixed target and test packages between invocations.
 */
// ANDROIDTEST_GATE_JUSTIFIED: the host-owned two-phase harness invokes each
// phase through separate direct am instrument processes; the ordinary
// connected suite cannot provide the external force-stop/new-PID boundary.
@RunWith(AndroidJUnit4::class)
class LastSessionProcessRestartProofTest {

    @Test
    fun phaseOnePersistsExactSuccessorGeneration() {
        val context = targetContext()
        assertRunnerIsInTargetProcess(context.packageName)
        val namespace = runNamespace()
        // The host force-stop is the real process boundary. This wrapper makes
        // the old apply() behavior deterministic: it accepts the in-process
        // editor mutation but drops the asynchronous disk handoff. The control
        // commit() path still writes the real Android preferences file, which
        // phase 2 reads from a genuinely new process.
        val store = LastSessionStore(ExternalKillBoundaryContext(context))
        assertTrue("phase 1 must durably clear stale run state", store.clear())

        // The generation source is the real host-side tmux server used by the
        // production session picker. The predecessor and successor are the
        // same named session across a real kill/recreate boundary; the exact
        // id/created pair comes only from SshHostTmuxSessionsGateway.
        val production = createProductionGenerationFixture(context, namespace)
        val predecessor = production.predecessor
        val successor = production.successor

        // Seed the predecessor through the same onStop projection used by the
        // activity when no newer ViewModel intent exists.
        val predecessorRoute = destination(production.fixture, predecessor)
        val predecessorSnapshot = requireNotNull(
            resolveLastSessionForStop(
                currentDestination = predecessorRoute,
                tmuxIntent = null,
                savedAtMillis = System.currentTimeMillis(),
            ),
        )
        assertTrue("predecessor snapshot must be durably saved", store.save(predecessorSnapshot))

        // The load-bearing production persistence boundary: onStop must prefer
        // the current ViewModel intent's exact successor generation over the
        // stale route's predecessor generation, then LastSessionStore saves it.
        val successorTarget = production.successorTarget
        val successorSnapshot = requireNotNull(
            resolveLastSessionForStop(
                currentDestination = predecessorRoute,
                tmuxIntent = TmuxRestoreIntentSnapshot(
                    hostId = production.fixture.host.id,
                    hostName = production.fixture.host.name,
                    hostname = production.fixture.host.hostname,
                    port = production.fixture.host.port,
                    username = production.fixture.host.username,
                    keyPath = production.fixture.keyPath,
                    sessionName = successorTarget.sessionName,
                    startDirectory = null,
                    tmuxSessionId = successorTarget.tmuxSessionId,
                    sessionCreated = successorTarget.sessionCreated,
                    trigger = TmuxConnectTrigger.UserTap,
                    generation = 1L,
                ),
                savedAtMillis = System.currentTimeMillis(),
            ),
        )
        assertTrue("successor snapshot must be durably saved", store.save(successorSnapshot))

        // Do not read LastSessionStore here. A same-process read is allowed to
        // observe apply()'s in-memory state and lets an async mutant flush
        // before the host reaches the external force-stop. The phase artifact
        // records the exact payload handed to save(); only phase 2's new
        // process may serve as the persistence oracle.
        val persistedGeneration = ExactGeneration(
            tmuxSessionId = requireNotNull(successorSnapshot.tmuxSessionId),
            sessionCreated = requireNotNull(successorSnapshot.sessionCreated),
        )
        assertEquals(successor, persistedGeneration)
        assertNotEquals(
            "successor generation must differ from the predecessor as a pair",
            predecessor,
            persistedGeneration,
        )

        val phaseArtifact = writePhaseArtifact(
            phase = 1,
            generation = persistedGeneration,
            predecessor = predecessor,
            predecessorReappeared = false,
            fixture = production.fixture,
        )
        publishPhaseOneReadyMarker(phaseArtifact)
        awaitPhaseOneKeepalive()
    }

    @Test
    fun phaseTwoRestoresExactSuccessorGeneration() {
        val context = targetContext()
        assertRunnerIsInTargetProcess(context.packageName)
        val namespace = runNamespace()
        writePhaseProcessMarker(phase = 2)
        val phaseOne = readPhaseOneArtifact(namespace)
        assertNotEquals(
            "phase 2 must run in a new target process after external force-stop",
            phaseOne.pid,
            Process.myPid(),
        )

        try {
            val restoredCandidate = phaseTwoProductionRead(context)
            assertNotNull(
                "production LastSessionStore must survive external process death",
                restoredCandidate,
            )
            val restored = requireNotNull(restoredCandidate)
            assertNotNull(
                "restored tmuxSessionId must be present",
                restored.tmuxSessionId,
            )
            val restoredSessionId = requireNotNull(restored.tmuxSessionId)
            assertNotNull(
                "restored sessionCreated must be present",
                restored.sessionCreated,
            )
            val restoredSessionCreated = requireNotNull(restored.sessionCreated)

            assertEquals(phaseOne.generation.tmuxSessionId, restoredSessionId)
            assertEquals(phaseOne.generation.sessionCreated, restoredSessionCreated)
            assertNotEquals(
                "restored generation must differ from the predecessor as a pair",
                phaseOne.predecessor,
                ExactGeneration(restoredSessionId, restoredSessionCreated),
            )

            writePhaseArtifact(
                phase = 2,
                generation = ExactGeneration(restoredSessionId, restoredSessionCreated),
                predecessor = phaseOne.predecessor,
                predecessorReappeared = false,
                fixture = phaseOne.fixture,
            )
        } finally {
            cleanupProductionFixture(context, phaseOne.fixture)
        }
    }

    private fun targetContext() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun assertRunnerIsInTargetProcess(targetPackage: String) {
        assertEquals(
            "direct instrumentation must execute in the suffixed target process",
            targetPackage,
            Application.getProcessName(),
        )
        assertTrue("process PID must be a real Linux PID", Process.myPid() > 1)
    }

    private fun destination(fixture: ProductionFixture, generation: ExactGeneration) =
        AppDestination.TmuxSession(
            hostId = fixture.host.id,
            hostName = fixture.host.name,
            hostname = fixture.host.hostname,
            port = fixture.host.port,
            username = fixture.host.username,
            keyPath = fixture.keyPath,
            passphrase = null,
            sessionName = fixture.sessionName,
            startDirectory = null,
            tmuxSessionId = generation.tmuxSessionId,
            sessionCreated = generation.sessionCreated,
        )

    private fun phaseTwoProductionRead(context: Context): LastSessionStore.LastSession? =
        LastSessionStore(context).read(maxAgeMillis = Long.MAX_VALUE)

    private fun createProductionGenerationFixture(
        context: Context,
        namespace: String,
    ): ProductionGenerationFixture = runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(keyText)
        // Issue #2446: `listProductionNavigationTarget` below feeds this
        // `fixture.host` through `SshHostTmuxSessionsGateway.listSessions`,
        // the real production trust-checked path — capture the presented
        // fingerprint. (Phase 2's `readPhaseOneArtifact` reconstructs its OWN
        // `HostEntity` from the artifact file, but that copy only ever
        // reaches the raw `connectToFixture`/`TEST_ACCEPT_ALL_HOST_KEYS`
        // cleanup helper, never a trust-checked production path, so it needs
        // no fingerprint.)
        val trustedHostKeySha256 = waitForSshFixtureReady(sshKey, port = PRODUCER_PORT)

        val keyFile = File(context.cacheDir, "issue2264-$namespace-key").apply {
            parentFile?.mkdirs()
            writeText(keyText)
            setReadable(true, true)
        }
        val fixture = ProductionFixture(
            host = HostEntity(
                id = PRODUCER_HOST_ID,
                name = PRODUCER_HOST_NAME,
                hostname = DEFAULT_HOST,
                port = PRODUCER_PORT,
                username = DEFAULT_USER,
                keyId = PRODUCER_KEY_ID,
                trustedHostKeySha256 = trustedHostKeySha256,
            ),
            keyPath = keyFile.absolutePath,
            sessionName = "issue2264-$namespace",
            keeperSessionName = "issue2264-keeper-$namespace",
        )
        val gateway = SshHostTmuxSessionsGateway(
            parser = HostTmuxSessionListParser(),
            activeTmuxClients = ActiveTmuxClients(),
        )
        try {
            createFixtureSession(fixture, sshKey)
            val predecessor = listProductionGeneration(gateway, fixture)
            destroyAndRecreateFixtureSession(fixture, sshKey)
            val successorTarget = listProductionNavigationTarget(gateway, fixture)
            val successor = ExactGeneration(
                tmuxSessionId = requireNotNull(successorTarget.tmuxSessionId),
                sessionCreated = requireNotNull(successorTarget.sessionCreated),
            )
            require(successor != predecessor) {
                "real tmux fixture reused the entire generation pair for $fixture"
            }
            ProductionGenerationFixture(fixture, predecessor, successor, successorTarget)
        } catch (error: Throwable) {
            runCatching { cleanupFixtureSession(fixture, sshKey) }
            keyFile.delete()
            throw error
        }
    }

    private suspend fun listProductionGeneration(
        gateway: SshHostTmuxSessionsGateway,
        fixture: ProductionFixture,
    ): ExactGeneration {
        val target = listProductionNavigationTarget(gateway, fixture)
        return ExactGeneration(
            tmuxSessionId = requireNotNull(target.tmuxSessionId),
            sessionCreated = requireNotNull(target.sessionCreated),
        )
    }

    private suspend fun listProductionNavigationTarget(
        gateway: SshHostTmuxSessionsGateway,
        fixture: ProductionFixture,
    ) = when (val result = gateway.listSessions(fixture.host, fixture.keyPath, passphrase = null)) {
        is HostTmuxSessionListResult.Sessions -> {
            val row = result.rows.singleOrNull { it.name == fixture.sessionName }
                ?: error(
                    "production tmux list did not contain exactly one ${fixture.sessionName}: " +
                        result.rows,
                )
            requireNotNull(row.navigationTargetOrNull()) {
                "production tmux row lacked exact id/created generation: $row"
            }
        }
        is HostTmuxSessionListResult.ToolUnavailable ->
            error("agents-daemon fixture has no usable tmux tool")
        is HostTmuxSessionListResult.Failed -> error("production tmux list failed: ${result.message}")
        is HostTmuxSessionListResult.ConnectFailed ->
            throw IllegalStateException("production tmux list SSH connection failed", result.cause)
    }

    private suspend fun createFixtureSession(fixture: ProductionFixture, key: SshKey.Pem) {
        val result = connectToFixture(fixture, key).use { session ->
            session.exec(
                // Keep a second session alive so killing/recreating the target
                // cannot tear down the last tmux server and reuse $0 with the
                // same-second session_created timestamp.
                "set -e; " +
                "tmux new-session -A -d -s ${shellQuote(fixture.keeperSessionName)}; " +
                    "tmux kill-session -t ${shellQuote(fixture.sessionName)} " +
                    ">/dev/null 2>&1 || true; " +
                    "tmux new-session -A -d -s ${shellQuote(fixture.sessionName)}; " +
                    "tmux list-sessions -F '#{session_name}'; " +
                    "tmux has-session -t ${shellQuote(fixture.keeperSessionName)}; " +
                    "tmux has-session -t ${shellQuote(fixture.sessionName)}",
            )
        }
        check(result.exitCode == 0) {
            "could not create real tmux predecessor: stderr=${result.stderr} stdout=${result.stdout}"
        }
    }

    private suspend fun destroyAndRecreateFixtureSession(
        fixture: ProductionFixture,
        key: SshKey.Pem,
    ) {
        val result = connectToFixture(fixture, key).use { session ->
            session.exec(
                "set -e; " +
                    "tmux has-session -t ${shellQuote(fixture.keeperSessionName)}; " +
                    "tmux kill-session -t ${shellQuote(fixture.sessionName)} " +
                    ">/dev/null 2>&1 || true; " +
                    "tmux new-session -A -d -s ${shellQuote(fixture.sessionName)}; " +
                    "tmux list-sessions -F '#{session_name}'; " +
                    "tmux has-session -t ${shellQuote(fixture.keeperSessionName)}; " +
                    "tmux has-session -t ${shellQuote(fixture.sessionName)}",
            )
        }
        check(result.exitCode == 0) {
            "could not recreate real tmux successor: stderr=${result.stderr} stdout=${result.stdout}"
        }
    }

    private fun cleanupProductionFixture(context: Context, fixture: ProductionFixture) {
        runBlocking {
            val keyText = InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("test_key")
                .bufferedReader()
                .use { it.readText() }
            runCatching { cleanupFixtureSession(fixture, SshKey.Pem(keyText)) }
                .onFailure { println("issue2264 fixture cleanup failed: $it") }
            File(fixture.keyPath).delete()
        }
    }

    private suspend fun cleanupFixtureSession(fixture: ProductionFixture, key: SshKey.Pem) {
        connectToFixture(fixture, key).use { session ->
            session.exec(
                "tmux kill-session -t ${shellQuote(fixture.sessionName)} >/dev/null 2>&1 || true; " +
                    "tmux kill-session -t ${shellQuote(fixture.keeperSessionName)} >/dev/null 2>&1 || true",
            )
        }
    }

    private suspend fun connectToFixture(fixture: ProductionFixture, key: SshKey.Pem) =
        SshConnection.connect(
            host = fixture.host.hostname,
            port = fixture.host.port,
            user = fixture.host.username,
            key = key,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).getOrThrow()

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private fun runNamespace(): String {
        val namespace = requireNotNull(
            InstrumentationRegistry.getArguments().getString(ARG_RUN_NAMESPACE),
        ) { "$ARG_RUN_NAMESPACE is required" }
        require(namespace.matches(NAMESPACE_PATTERN)) {
            "$ARG_RUN_NAMESPACE must match ${NAMESPACE_PATTERN.pattern}"
        }
        return namespace
    }

    private fun readPhaseOneArtifact(namespace: String): PhaseOneArtifact {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val artifact = File(
            testArtifactsRoot(instrumentation.targetContext),
            "process-restart/$namespace/phase-1.txt",
        )
        check(artifact.isFile && artifact.length() > 0L) {
            "phase 1 device artifact is absent: $artifact"
        }
        check(artifact.value("schema") == "1") { "phase 1 artifact schema mismatch" }
        check(artifact.value("run_namespace") == namespace) { "phase 1 artifact namespace mismatch" }
        check(artifact.value("phase") == "1") { "phase 1 artifact phase mismatch" }
        val pid = artifact.value("pid").toIntOrNull()
            ?: error("phase 1 artifact PID is invalid")
        check(pid > 1) { "phase 1 artifact PID is invalid" }
        check(artifact.value("process_name") == targetContext().packageName) {
            "phase 1 artifact process mismatch"
        }
        check(artifact.value("target_package") == targetContext().packageName) {
            "phase 1 artifact target package mismatch"
        }
        check(artifact.value("test_package") ==
            InstrumentationRegistry.getInstrumentation().context.packageName) {
            "phase 1 artifact test package mismatch"
        }
        check(artifact.value("persistence_origin") == PHASE_ONE_PERSISTENCE_ORIGIN) {
            "phase 1 artifact did not come from LastSessionStore.save"
        }
        check(artifact.value("generation_origin") == GENERATION_ORIGIN) {
            "phase 1 artifact did not exercise the production generation boundary"
        }
        val fixture = ProductionFixture(
            host = HostEntity(
                id = PRODUCER_HOST_ID,
                name = artifact.value("producer_fixture_name"),
                hostname = artifact.value("producer_fixture_host"),
                port = artifact.value("producer_fixture_port").toInt(),
                username = artifact.value("producer_fixture_user"),
                keyId = PRODUCER_KEY_ID,
            ),
            keyPath = artifact.value("producer_key_path"),
            sessionName = artifact.value("producer_session_name"),
            keeperSessionName = "issue2264-keeper-${artifact.value("producer_session_name")}",
        )
        check(fixture.host.name == PRODUCER_HOST_NAME) { "phase 1 producer fixture name mismatch" }
        check(fixture.host.hostname == DEFAULT_HOST) { "phase 1 producer fixture host mismatch" }
        check(fixture.host.port == PRODUCER_PORT) { "phase 1 producer fixture port mismatch" }
        check(fixture.host.username == DEFAULT_USER) { "phase 1 producer fixture user mismatch" }
        check(fixture.sessionName == "issue2264-$namespace") {
            "phase 1 producer session name mismatch"
        }
        return PhaseOneArtifact(
            pid = pid,
            generation = ExactGeneration(
                tmuxSessionId = artifact.value("tmux_session_id"),
                sessionCreated = artifact.value("session_created").toLong(),
            ),
            predecessor = ExactGeneration(
                tmuxSessionId = artifact.value("predecessor_tmux_session_id"),
                sessionCreated = artifact.value("predecessor_session_created").toLong(),
            ),
            fixture = fixture,
        ).also {
            require(it.generation.isComplete()) { "phase 1 successor generation is incomplete" }
            require(it.predecessor.isComplete()) { "phase 1 predecessor generation is incomplete" }
            require(it.generation != it.predecessor) { "phase 1 generations alias" }
        }
    }

    private fun File.value(key: String): String {
        val prefix = "$key="
        val values = readLines().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        check(values.size == 1) { "$this must contain exactly one $key field" }
        return values.single()
    }

    private fun writePhaseArtifact(
        phase: Int,
        generation: ExactGeneration,
        predecessor: ExactGeneration,
        predecessorReappeared: Boolean,
        fixture: ProductionFixture,
    ): File {
        require(generation.isComplete())
        require(predecessor.isComplete())
        require(generation != predecessor)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val namespace = runNamespace()
        val targetPackage = instrumentation.targetContext.packageName
        val outputDir = File(testArtifactsRoot(instrumentation.targetContext), "process-restart/$namespace")
        check(outputDir.mkdirs() || outputDir.isDirectory) { "cannot create $outputDir" }
        val destination = File(outputDir, "phase-$phase.txt")
        val temporary = File(outputDir, ".phase-$phase-${Process.myPid()}.tmp")
        temporary.writeText(
            buildString {
                appendLine("schema=1")
                appendLine("run_namespace=$namespace")
                appendLine("phase=$phase")
                appendLine("pid=${Process.myPid()}")
                appendLine("process_name=${Application.getProcessName()}")
                appendLine("target_package=$targetPackage")
                appendLine("test_package=${instrumentation.context.packageName}")
                appendLine("generation_origin=$GENERATION_ORIGIN")
                appendLine(
                    "persistence_origin=" +
                        if (phase == 1) PHASE_ONE_PERSISTENCE_ORIGIN else PHASE_TWO_PERSISTENCE_ORIGIN,
                )
                appendLine("producer_fixture_name=${fixture.host.name}")
                appendLine("producer_fixture_host=${fixture.host.hostname}")
                appendLine("producer_fixture_port=${fixture.host.port}")
                appendLine("producer_fixture_user=${fixture.host.username}")
                appendLine("producer_key_path=${fixture.keyPath}")
                appendLine("producer_session_name=${fixture.sessionName}")
                appendLine("tmux_session_id=${generation.tmuxSessionId}")
                appendLine("session_created=${generation.sessionCreated}")
                appendLine("predecessor_tmux_session_id=${predecessor.tmuxSessionId}")
                appendLine("predecessor_session_created=${predecessor.sessionCreated}")
                appendLine("predecessor_reappeared=$predecessorReappeared")
            },
        )
        check(temporary.renameTo(destination)) { "cannot atomically publish $destination" }
        assertTrue("phase artifact must be non-empty", destination.length() > 0L)
        return destination
    }

    /**
     * Publish the phase-1 readiness boundary only after the complete artifact
     * has been atomically renamed into place. The host uses the digest and
     * byte count to reject a marker that raced an incomplete device write.
     */
    private fun publishPhaseOneReadyMarker(artifact: File) {
        check(artifact.isFile && artifact.length() > 0L) {
            "phase 1 artifact must exist before publishing readiness"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = artifact.readBytes()
        val sha256 = digest.digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val namespace = runNamespace()
        val targetPackage = instrumentation.targetContext.packageName
        val marker = File(artifact.parentFile, "phase-1.ready")
        val temporary = File(artifact.parentFile, ".phase-1.ready-${Process.myPid()}.tmp")
        temporary.writeText(
            buildString {
                appendLine("schema=1")
                appendLine("run_namespace=$namespace")
                appendLine("phase=1")
                appendLine("ready=true")
                appendLine("artifact=phase-1.txt")
                appendLine("artifact_complete=true")
                appendLine("pid=${Process.myPid()}")
                appendLine("process_name=${Application.getProcessName()}")
                appendLine("target_package=$targetPackage")
                appendLine("test_package=${instrumentation.context.packageName}")
                appendLine("artifact_bytes=${bytes.size}")
                appendLine("artifact_sha256=$sha256")
            },
        )
        check(temporary.renameTo(marker)) { "cannot atomically publish $marker" }
        assertTrue("phase 1 ready marker must be non-empty", marker.length() > 0L)
    }

    /**
     * Publish the phase-2 PID before touching the persistence oracle. The
     * marker lets a deliberately red mutant still prove that the second direct
     * instrumentation call entered a genuinely new target process.
     */
    private fun writePhaseProcessMarker(phase: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val namespace = runNamespace()
        val targetPackage = instrumentation.targetContext.packageName
        val outputDir = File(testArtifactsRoot(instrumentation.targetContext), "process-restart/$namespace")
        check(outputDir.mkdirs() || outputDir.isDirectory) { "cannot create $outputDir" }
        val marker = File(outputDir, "phase-$phase.started.txt")
        val temporary = File(outputDir, ".phase-$phase.started-${Process.myPid()}.tmp")
        temporary.writeText(
            buildString {
                appendLine("schema=1")
                appendLine("run_namespace=$namespace")
                appendLine("phase=$phase")
                appendLine("pid=${Process.myPid()}")
                appendLine("process_name=${Application.getProcessName()}")
                appendLine("target_package=$targetPackage")
                appendLine("test_package=${instrumentation.context.packageName}")
            },
        )
        check(temporary.renameTo(marker)) { "cannot atomically publish $marker" }
        assertTrue("phase $phase process marker must be non-empty", marker.length() > 0L)
    }

    /**
     * Keep this target/instrumentation process alive after the complete phase-1
     * proof. The host force-stops both packages during this interval; the
     * bounded ceiling prevents a broken host from leaving a stray test alive
     * forever while still making a natural instrumentation success impossible
     * at the process-restart boundary.
     */
    private fun awaitPhaseOneKeepalive() {
        val rawMillis = InstrumentationRegistry.getArguments()
            .getString(ARG_PHASE_ONE_KEEPALIVE_MILLIS)
        val keepaliveMillis = requireNotNull(rawMillis?.toLongOrNull()) {
            "$ARG_PHASE_ONE_KEEPALIVE_MILLIS is required and must be a positive integer"
        }
        require(keepaliveMillis in 1L..MAX_PHASE_ONE_KEEPALIVE_MILLIS) {
            "$ARG_PHASE_ONE_KEEPALIVE_MILLIS must be between 1 and " +
                "$MAX_PHASE_ONE_KEEPALIVE_MILLIS milliseconds"
        }
        val deadline = SystemClock.elapsedRealtime() + keepaliveMillis
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            SystemClock.sleep(minOf(remaining, PHASE_ONE_KEEPALIVE_POLL_MILLIS))
        }
    }

    companion object {
        private data class ExactGeneration(
            val tmuxSessionId: String,
            val sessionCreated: Long,
        ) {
            fun isComplete(): Boolean = tmuxSessionId.isNotBlank() && sessionCreated > 0L
        }

        private data class PhaseOneArtifact(
            val pid: Int,
            val generation: ExactGeneration,
            val predecessor: ExactGeneration,
            val fixture: ProductionFixture,
        )

        private data class ProductionFixture(
            val host: HostEntity,
            val keyPath: String,
            val sessionName: String,
            val keeperSessionName: String,
        )

        private data class ProductionGenerationFixture(
            val fixture: ProductionFixture,
            val predecessor: ExactGeneration,
            val successor: ExactGeneration,
            val successorTarget: com.pocketshell.app.tmux.TmuxSessionNavigationTarget,
        )

        private const val ARG_RUN_NAMESPACE = "pocketshellRunNamespace"
        private const val ARG_PHASE_ONE_KEEPALIVE_MILLIS =
            "pocketshellPhaseOneKeepaliveMillis"
        private const val MAX_PHASE_ONE_KEEPALIVE_MILLIS = 300_000L
        private const val PHASE_ONE_KEEPALIVE_POLL_MILLIS = 100L
        private val NAMESPACE_PATTERN = Regex("[A-Za-z0-9._-]+")
        private const val PRODUCER_HOST_ID = 2264L
        private const val PRODUCER_HOST_NAME = "agents-daemon-2239"
        private const val PRODUCER_KEY_ID = 2264L
        private const val PRODUCER_PORT = 2239
        private const val GENERATION_ORIGIN =
            "agents-daemon-2239-tmux-list-sessions-through-SshHostTmuxSessionsGateway-to-navigation-to-on-stop-to-last-session-store"
        private const val PHASE_ONE_PERSISTENCE_ORIGIN = "LastSessionStore.save"
        private const val PHASE_TWO_PERSISTENCE_ORIGIN = "LastSessionStore.read"
    }
}

/**
 * Deterministic external-kill boundary for the live APK mutation proof.
 *
 * Android documents [SharedPreferences.Editor.apply] as updating the current
 * process immediately while scheduling its disk write asynchronously. There
 * is no supported API that freezes that worker until an external `am
 * force-stop`; relying on the worker losing a host-side race is therefore a
 * flaky proof. This wrapper models the documented kill window by withholding
 * only [SharedPreferences.Editor.apply], while delegating [commit] to the
 * real Android preferences implementation. Phase 1 never reads the wrapped
 * store after save; phase 2 reads the real file in a new process.
 */
private class ExternalKillBoundaryContext(base: Context) : ContextWrapper(base) {
    private val crashWindowPrefs: SharedPreferences by lazy {
        DropAsyncApplySharedPreferences(
            base.getSharedPreferences("last_session", Context.MODE_PRIVATE),
        )
    }

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        if (name == "last_session") crashWindowPrefs else super.getSharedPreferences(name, mode)
}

private class DropAsyncApplySharedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor = Editor(delegate.edit())

    private inner class Editor(
        private val delegateEditor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) =
            apply { delegateEditor.putString(key, value) }

        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            apply { delegateEditor.putStringSet(key, values) }

        override fun putInt(key: String?, value: Int) =
            apply { delegateEditor.putInt(key, value) }

        override fun putLong(key: String?, value: Long) =
            apply { delegateEditor.putLong(key, value) }

        override fun putFloat(key: String?, value: Float) =
            apply { delegateEditor.putFloat(key, value) }

        override fun putBoolean(key: String?, value: Boolean) =
            apply { delegateEditor.putBoolean(key, value) }

        override fun remove(key: String?) = apply { delegateEditor.remove(key) }

        override fun clear() = apply { delegateEditor.clear() }

        override fun commit(): Boolean = delegateEditor.commit()

        override fun apply() {
            // Intentional no-op: the simulated external kill wins before the
            // asynchronous disk handoff. A same-process read is forbidden by
            // the phase-one proof, so no in-memory oracle can mask this.
        }
    }
}
