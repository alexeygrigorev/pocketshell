package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * `execSections` appends the marker to each section command. The two stable
 * enumeration command heads therefore disambiguate the required landing batch
 * from optional sectioned probes while the marker remains the selection anchor.
 */
private fun isRequiredLandingEnumeration(command: String): Boolean =
    command.contains(SshFolderListGateway.ENUMERATION_MARKER) &&
        command.contains(SshFolderListGateway.LIST_SESSIONS_COMMAND.substringBefore(" -F")) &&
        command.contains(SshFolderListGateway.LIST_PANES_COMMAND.substringBefore(" -F"))

/**
 * Issue #470: a session-enumeration SSH-exec probe whose output read never
 * reaches EOF (the heavier-seeded-tmux-state SLIRP wedge observed on the
 * emulator) must surface a BOUNDED [FolderListResult.ConnectFailed] instead
 * of leaving the folder screen stuck in `Loading` forever.
 *
 * These run on a real dispatcher (`runBlocking`) with a small injected
 * `execReadTimeoutMs`, so the assertion is deterministic: the healthy fake
 * `exec` returns instantly (far under the bound) and the wedged fake parks
 * via [awaitCancellation] (past the bound) — no virtual-vs-real time race,
 * and the only real wait is the short injected timeout on the wedge case.
 */
class FolderListGatewayExecTimeoutTest {

    /**
     * Issue #470's bounded-failure contract, preserved under #1641.
     *
     * The host is PERSISTENTLY wedged (every exec parks), so the #680 heal +
     * retry cannot rescue it and the bounded [FolderListResult.ConnectFailed]
     * must still surface rather than the screen hanging in `Loading` forever.
     * The bound costs several attempts instead of one — #1641's evict-and-re-dial
     * heal, and now #2422's same-transport retry inside each lease attempt — but it
     * is still a fixed multiple of [SshFolderListGateway.EXEC_READ_TIMEOUT_MS],
     * which is the property #470 actually guarantees.
     *
     * The single-transient-wedge case now HEALS instead of surfacing a scary
     * banner (that is #680's design, #1641 made the exec timeout reach it, and
     * #2422 made it heal without a fresh dial); see
     * `transientlyWedgedRequiredExecHealsOnTheSameWarmTransportWithoutAReDial` and
     * `persistentlyWedgedRequiredExecStillEvictsAndRetriesOnAFreshTransport`.
     */
    @Test
    fun persistentlyWedgedListSessionsReadStillSurfacesBoundedConnectFailed() = runBlocking {
        val session = WedgingSshSession(wedgeFirstExec = true, wedgeEveryExec = true)
        val gateway = newGateway(session)

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )

        assertTrue(
            "wedged enumeration read must surface ConnectFailed, got $result",
            result is FolderListResult.ConnectFailed,
        )
        val cause = (result as FolderListResult.ConnectFailed).cause
        assertTrue(
            "ConnectFailed cause must be the bounded timeout, got ${cause::class.java.name}: ${cause.message}",
            cause is FolderListExecTimeoutException,
        )
        // The gateway returned without blocking on the still-wedged read.
        assertTrue("the wedged exec was attempted", session.wedgedExecStarted.isCompleted)
        // Issue #1641 (D22 hard-cut): this test used to ALSO assert the gateway
        // CLOSED the wedged session here, "so no orphaned exec channel / blocking
        // read thread outlives the failed probe (cancellation alone can't
        // interrupt the in-flight JDK read)". Both halves of that premise are now
        // false — the session is the SHARED transport the live `-CC` reader rides
        // (closing it on a merely-SLOW exec is the #1610 entry trigger), and since
        // #1567 the read is interruptible and bounded CHANNEL-LOCALLY, so there is
        // no orphan to prevent.
        //
        // No close/no-close assertion belongs in THIS test: with no consumer
        // holding the lease, the eventual `evictIdle` legitimately closes this
        // idle corpse — that IS the recovery, and asserting either way here would
        // just re-pin an accident. The properties that matter are split into the
        // two tests that actually isolate them:
        //   - recovery still happens  -> wedgedExecStillEvictsAndRetriesOnAFreshTransport
        //   - the refcount guard holds -> wedgedExecMustNotYankATransportAnActiveConsumerHolds
        // This test's job is only the #470 bounded-failure contract asserted above.
    }

    @Test
    fun healthyReadDoesNotTripTheTimeoutAndSucceeds() = runBlocking {
        val session = WedgingSshSession(wedgeFirstExec = false)
        val gateway = newGateway(session)

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )

        assertTrue(
            "healthy sub-second enumeration must succeed, got $result",
            result is FolderListResult.Sessions,
        )
        // #1876 extends #692's one enumeration exec into a sectioned required
        // batch. Optional decoration runs concurrently, so command ordering is
        // deliberately not asserted.
        assertTrue(
            "one required batch must carry both tmux enumeration commands",
            session.execCommands.any { command ->
                // The production path wraps the batch in a single-quoted
                // POSIX shell command, so the format strings' embedded quotes
                // are escaped at this boundary. Match the stable command heads
                // here; FolderListLandingProbeTest pins the exact raw batch.
                // Issue #2160: derive the heads from the production constants
                // instead of re-typing `tmux list-…` — a literal silently stops
                // matching the moment the invocation changes (the `-u`
                // locale-proof client), which is how this assertion went red.
                command.contains(SshFolderListGateway.LIST_SESSIONS_COMMAND.substringBefore(" -F")) &&
                    command.contains(SshFolderListGateway.LIST_PANES_COMMAND.substringBefore(" -F")) &&
                    command.contains(SshFolderListGateway.ENUMERATION_MARKER)
            },
        )
    }

    /**
     * Issue #1641 (D22 hard-cut): this used to assert the session was CLOSED on a
     * create timeout "so no orphaned exec channel/thread" is left. That was the
     * bug pinned as intended behaviour — the session is the SHARED per-host lease
     * transport the live tmux `-CC` reader rides, and closing it because an exec
     * was merely SLOW self-inflicted the #1610 reconnect storm. It also bypassed
     * the #758 refcount guard, so it could yank a transport an ACTIVE session VM
     * still held.
     *
     * The bounded, retryable [FolderListExecTimeoutException] is still surfaced —
     * that is what drives recovery, now through the refcount-aware `evictIdle`
     * heal path (see `isWedgedReadTimeout` in the gateway) instead of an
     * unconditional close. The abandoned exec is bounded channel-locally by the
     * session's own no-progress budget (#1567), so there is no orphan either.
     */
    @Test
    fun wedgedCreateSessionReadSurfacesBoundedFailureWithoutClosingTheSharedSession() = runBlocking {
        val session = CreateSessionWedgingSshSession()
        val gateway = newGateway(session)

        val failure = runCatching {
            gateway.createSessionOnSession(
                session = session,
                sessionName = "telegram-writing-assistant",
                cwd = "/home/me/telegram-writing-assistant",
                startCommand = "pocketshell agent codex --dir '/home/me/telegram-writing-assistant'",
                namePolicy = SessionNamePolicy.ExactName,
            )
        }.exceptionOrNull()

        assertTrue(
            "wedged create must fail with the bounded exec timeout, got $failure",
            failure is FolderListExecTimeoutException,
        )
        assertTrue(
            "directory probe ran before the create wedge",
            session.execCommands.any { it.contains("test -d") },
        )
        assertTrue("capped create command was attempted", session.execCommands.any { it.contains("create-detached") })
        assertFalse(
            "a merely-SLOW create must NOT close the shared lease transport (#1641) — " +
                "the bounded failure above drives the refcount-aware evictIdle heal instead",
            session.closed,
        )
    }

    /**
     * Issue #1641 — the LOAD-BEARING NEGATIVE case (G6).
     *
     * Deleting the close-on-timeout must NOT over-guard into "nothing is ever
     * discarded". If a bounded-exec timeout stopped driving recovery, every poll
     * would re-grab the same corpse and the tree would stop recovering at all —
     * strictly worse than the storm this issue fixes.
     *
     * So: the timeout must still be classified as a stale-channel symptom, which
     * makes `runLeaseAttempt` EVICT the poisoned lease and RETRY ONCE on a fresh
     * transport. Proof = the connector dialled a SECOND, different session after
     * the first one wedged. Unlike the deleted `close()`, that eviction goes
     * through `evictIdle`, which is refcount-aware and leaves a transport an
     * ACTIVE session VM still holds alone (#758).
     *
     * Issue #2422 narrows WHEN that heal fires: the required landing batch is now
     * retried once on the SAME warm transport first, so reaching the re-dial takes
     * a PERSISTENTLY unresponsive required probe ([requiredEnumerationWedges] = 2),
     * not a single transient over-run. This is the load-bearing negative of #2422 —
     * over-guarding the retry (never re-dialling) would strand a genuinely dead
     * transport, which is strictly worse than the doubled reconcile it removed.
     */
    @Test
    fun persistentlyWedgedRequiredExecStillEvictsAndRetriesOnAFreshTransport() = runBlocking {
        // Issue #2359: #2348 starts the optional pocketshell enumerator in
        // parallel with the required landing batch. Wedge the required
        // command by its marker, not whichever concurrent child happens to
        // reach the fake first.
        val wedged = WedgingSshSession(
            wedgeRequiredEnumeration = true,
            requiredEnumerationWedges = 2,
            releaseRequiredEnumerationAfterOptional = true,
        )
        val healthy = WedgingSshSession(wedgeFirstExec = false)
        val connector = SequenceConnector(listOf(wedged, healthy))
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(connector = connector, idleTtlMillis = 0L),
            sessionListParser = com.pocketshell.app.sessions.HostTmuxSessionListParser(),
            execReadTimeoutMs = 250L,
        )

        gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )

        assertTrue("the wedged exec must have been attempted", wedged.wedgedExecStarted.isCompleted)
        val observedCommands = wedged.snapshotExecCommands()
        val requiredEnumerationCommands = observedCommands.filter(::isRequiredLandingEnumeration)
        assertEquals(
            "issue #2422: the poisoned transport must see the required landing " +
                "enumeration TWICE — one over-run plus the same-transport retry — " +
                "before the lease heal re-dials; observed exec commands=$observedCommands",
            2,
            requiredEnumerationCommands.size,
        )
        val optionalEnumeratorIndex = observedCommands.indexOfFirst { command ->
            command.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_JSON_COMMAND)
        }
        val requiredEnumerationIndex = observedCommands.indexOfFirst { command ->
            command.contains(SshFolderListGateway.ENUMERATION_MARKER)
        }
        assertTrue(
            "the deterministic fixture must record the concurrent optional JSON " +
                "exec before the required landing exec; observed exec commands=$observedCommands",
            optionalEnumeratorIndex >= 0 &&
                requiredEnumerationIndex >= 0 &&
                optionalEnumeratorIndex < requiredEnumerationIndex,
        )
        assertEquals(
            "the required landing enumeration, not the optional concurrent " +
                "pocketshell enumerator, must be the wedged command; " +
                "observed exec commands=$observedCommands",
            requiredEnumerationCommands.first(),
            wedged.wedgedCommand,
        )
        assertEquals(
            "a wedged bounded exec must still EVICT the poisoned lease and re-dial " +
                "a FRESH transport — recovery must not be over-guarded away (#1641 G6)",
            2,
            connector.dialCount,
        )
        assertTrue(
            "the heal retry must have run its enumeration on the FRESH transport",
            healthy.execCommands.isNotEmpty(),
        )
        // NOTE: `wedged.closed` is deliberately NOT asserted false here. Evicting
        // an IDLE corpse legitimately closes it — that is the recovery. The
        // property that matters is WHO may close and under WHAT guard: `evictIdle`
        // only ever takes a lease at refCount == 0. That guard is pinned by
        // `wedgedExecMustNotYankATransportAnActiveConsumerHolds` below, which is
        // the case the deleted raw `close()` violated.
    }

    /**
     * Issue #2422 — a TRANSIENT required-exec over-run must heal on the SAME warm
     * transport, with NO fresh dial and NO second full chain.
     *
     * This is the JVM half of the class regression. On a mobile link the required
     * landing batch losing its race against [SshFolderListGateway.EXEC_READ_TIMEOUT_MS]
     * is routine (the netem reproduction records 1-3 abandoned execs per reconcile
     * at ~400 ms RTT / 5 % loss). Before this fix that over-run was answered by
     * evicting the warm lease, paying a brand-new TCP+SSH handshake and re-running
     * every probe — measured on the netem fixture at 15.2-17.0 s with one extra
     * sshd login, versus 12.3-13.2 s and zero extra logins once the batch is
     * retried in place, against a 12 s user-visible `RECONCILE_TIMEOUT_MS` and an
     * un-amplified chain of 5.9-8.5 s. That is the "Couldn't refresh the project
     * tree" panel on a link that was working the whole time, and the
     * scheduled-CI failure (`elapsedMs=12545 bound=12000`) this issue was filed
     * for.
     *
     * The fixture wedges the required landing enumeration EXACTLY ONCE, so the
     * distinguishing observation is the DIAL COUNT: one dial (retry on the warm
     * transport) versus two (evict + fresh transport). Both variants return
     * `Sessions`, so the result type alone cannot tell them apart — the dial count
     * is the load-bearing assertion (G6).
     */
    @Test
    fun transientlyWedgedRequiredExecHealsOnTheSameWarmTransportWithoutAReDial() = runBlocking {
        val wedged = WedgingSshSession(
            wedgeRequiredEnumeration = true,
            requiredEnumerationWedges = 1,
            releaseRequiredEnumerationAfterOptional = true,
        )
        val fresh = WedgingSshSession(wedgeFirstExec = false)
        val connector = SequenceConnector(listOf(wedged, fresh))
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(connector = connector, idleTtlMillis = 0L),
            sessionListParser = com.pocketshell.app.sessions.HostTmuxSessionListParser(),
            execReadTimeoutMs = 250L,
        )

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )

        assertTrue("the required exec must have wedged once", wedged.wedgedExecStarted.isCompleted)
        assertEquals(
            "issue #2422: a transient required-exec over-run must be retried on the " +
                "SAME warm transport — evicting it and re-dialling is what doubled the " +
                "reconcile past its 12s bound on a link that was alive the whole time",
            1,
            connector.dialCount,
        )
        assertEquals(
            "the same warm transport must have carried BOTH the over-run and the retry; " +
                "observed exec commands=${wedged.snapshotExecCommands()}",
            2,
            wedged.snapshotExecCommands().count(::isRequiredLandingEnumeration),
        )
        assertTrue(
            "no work may be re-run on a fresh transport; the second session must be " +
                "untouched, observed=${fresh.snapshotExecCommands()}",
            fresh.execCommands.isEmpty(),
        )
        assertTrue(
            "the reconcile must still land a complete Sessions result, got $result",
            result is FolderListResult.Sessions,
        )
        // NOTE: `wedged.closed` is not asserted here. This lease manager runs with
        // `idleTtlMillis = 0`, so the pool legitimately closes the transport as
        // soon as the reconcile releases it — that is lease-pool lifecycle, not the
        // retry. "A slow exec must not close a transport a consumer holds" is
        // pinned by `wedgedExecMustNotYankATransportAnActiveConsumerHolds`.
    }

    /**
     * Issue #1641 — the refcount guard the deleted raw `close()` violated.
     *
     * The folder poll shares the SAME `SshLeaseKey` an ACTIVE `TmuxSessionViewModel`
     * rides. The old close-on-timeout closed that shared transport directly,
     * bypassing the #758 refcount guard entirely — so a slow poll exec yanked the
     * live session's transport out from under it, EOFing its `-CC` reader. That is
     * the #1610 storm entry trigger.
     *
     * With the close gone, recovery routes through `evictIdle`, which is a no-op
     * while a consumer still holds the lease (refCount > 0). So: hold a lease (the
     * session VM), wedge the poll, and the held transport must SURVIVE.
     */
    @Test
    fun wedgedExecMustNotYankATransportAnActiveConsumerHolds() = runBlocking {
        val shared = WedgingSshSession(wedgeFirstExec = true, wedgeEveryExec = true)
        val leaseManager = SshLeaseManager(
            connector = SingleSessionConnector(shared),
            idleTtlMillis = 60_000L,
        )
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = leaseManager,
            sessionListParser = com.pocketshell.app.sessions.HostTmuxSessionListParser(),
            execReadTimeoutMs = 250L,
        )
        // The live session VM holds the lease for the whole poll. The key must be
        // BYTE-IDENTICAL to the one the gateway builds, or this would hold a
        // different entry and prove nothing — so it is composed from the
        // gateway's own `buildCredentialId` (the poll passes no leasePurpose).
        val held = leaseManager.acquire(
            SshLeaseTarget(
                leaseKey = SshLeaseKey(
                    host = HOST.hostname,
                    port = HOST.port,
                    user = HOST.username,
                    credentialId = SshFolderListGateway.buildCredentialId(HOST.id, KEY_PATH, null),
                    knownHostsId = "host-key:unconfirmed",
                ),
                key = SshKey.Path(File(KEY_PATH)),
                passphrase = null,
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            ),
        ).getOrThrow()

        try {
            gateway.listSessionsWithFolder(
                host = HOST,
                keyPath = KEY_PATH,
                passphrase = null,
                watchedRoots = WATCHED_ROOTS,
            )

            assertTrue("the wedged poll exec must have been attempted", shared.wedgedExecStarted.isCompleted)
            assertFalse(
                "a wedged POLL exec must NOT close a shared transport an ACTIVE session " +
                    "still holds — that yank is the #1610 storm entry trigger (#1641/#758)",
                shared.closed,
            )
            assertTrue(
                "the live session's transport must still be connected after the wedged poll",
                held.session.isConnected,
            )
        } finally {
            held.release()
        }
    }

    private fun newGateway(session: SshSession): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = SingleSessionConnector(session),
                idleTtlMillis = 0L,
            ),
            sessionListParser = com.pocketshell.app.sessions.HostTmuxSessionListParser(),
            // Short, real bound: the healthy fake returns in microseconds,
            // the wedged fake parks far past this. Deterministic.
            execReadTimeoutMs = 250L,
        )

    /** Hands out a new session per dial, so a re-dial is observable (#1641). */
    private class SequenceConnector(
        private val sessions: List<SshSession>,
    ) : SshLeaseConnector {
        var dialCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val session = sessions.getOrElse(dialCount) { sessions.last() }
            dialCount++
            return Result.success(session)
        }
    }

    private class SingleSessionConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    /**
     * Fake session whose selected `exec` can wedge forever via
     * [awaitCancellation] — simulating a read that never reaches EOF.
     * Connect/auth "succeeded" (isConnected=true), exactly the #470 failure
     * shape. [wedgeFirstExec] preserves the original single-threaded fixture;
     * [wedgeRequiredEnumeration] is the deterministic #2359 variant because
     * #2348 now starts an optional enumerator concurrently with the required
     * landing batch. The marker selection is one-shot and atomic, so command
     * scheduling cannot change which required exec is wedged. Subsequent execs
     * return empty success so the healthy path enumerates to a `Sessions` result.
     */
    private class WedgingSshSession(
        private val wedgeFirstExec: Boolean = false,
        /**
         * Issue #1641: wedge EVERY exec, not just the first — a PERSISTENTLY
         * unresponsive host. Needed now that a bounded-exec timeout is a
         * stale-channel symptom that heals + retries once (#680): a fake that
         * wedges only its first exec lets the RETRY succeed, so it can no longer
         * express "this host stays wedged".
         */
        private val wedgeEveryExec: Boolean = false,
        /**
         * Issue #2359: wedge the required landing enumeration by its stable
         * section marker. The optional `pocketshell sessions --json` exec is
         * launched concurrently, so call order is not a valid fixture oracle.
         */
        private val wedgeRequiredEnumeration: Boolean = false,
        /**
         * Issue #2422: how many required landing enumerations wedge before the
         * fake starts answering. The required batch is now retried ONCE on the
         * SAME warm transport before the lease-level evict-and-re-dial heal, so
         * `1` is a TRANSIENT over-run (must heal in place, no re-dial) and `2` is
         * a PERSISTENTLY unresponsive required probe (must still reach the heal).
         */
        private val requiredEnumerationWedges: Int = 1,
        /**
         * Test-only barrier that records the optional JSON command before the
         * required command proceeds. This makes the old first-call mutant
         * deterministically select the wrong concurrent command without a
         * sleep or scheduler-dependent assertion.
         */
        private val releaseRequiredEnumerationAfterOptional: Boolean = false,
    ) : SshSession {
        val execCommands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        val wedgedExecStarted = CompletableDeferred<Unit>()
        private val requiredEnumerationWedgeCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val selectedWedgeCommand = AtomicReference<String?>(null)
        private val optionalEnumeratorStarted = CompletableDeferred<Unit>()
        val wedgedCommand: String?
            get() = selectedWedgeCommand.get()
        @Volatile
        var closed: Boolean = false
        private val execCount = java.util.concurrent.atomic.AtomicInteger(0)

        fun snapshotExecCommands(): List<String> = synchronized(execCommands) { execCommands.toList() }

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            val isRequiredEnumeration = isRequiredLandingEnumeration(command)
            val isOptionalEnumerator = command.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_JSON_COMMAND)
            if (releaseRequiredEnumerationAfterOptional && isRequiredEnumeration) {
                // The real gateway launches this optional JSON exec before
                // entering the required batch. Wait for that command to be
                // recorded so this fixture exercises both concurrent paths
                // without a sleep or scheduler-dependent ordering.
                optionalEnumeratorStarted.await()
            }
            val index = execCount.getAndIncrement()
            execCommands += command
            if (releaseRequiredEnumerationAfterOptional && isOptionalEnumerator) {
                optionalEnumeratorStarted.complete(Unit)
            }
            val selectedByRequiredMarker = wedgeRequiredEnumeration &&
                isRequiredEnumeration &&
                requiredEnumerationWedgeCount.getAndIncrement() < requiredEnumerationWedges
            val shouldWedge = wedgeEveryExec ||
                (index == 0 && wedgeFirstExec) ||
                selectedByRequiredMarker
            if (shouldWedge) {
                selectedWedgeCommand.compareAndSet(null, command)
                wedgedExecStarted.complete(Unit)
                // Never returns — the read is wedged. The gateway's
                // bounded timeout must abandon this and surface the
                // failure rather than hang.
                awaitCancellation()
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }

    /**
     * Create-session fake: directory exists, launch-target collision probe says
     * "absent", then the capped create read never reaches EOF.
     */
    private class CreateSessionWedgingSshSession : SshSession {
        val execCommands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return when {
                command.contains("test -d") -> ExecResult(stdout = "", stderr = "", exitCode = 0)
                command.contains("has-session") -> ExecResult(stdout = "", stderr = "", exitCode = 1)
                command.contains("create-detached") -> awaitCancellation()
                else -> ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val HOST: HostEntity = HostEntity(
            id = 7L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 1L,
        )
        // A watched root forces the SSH-exec probe path (the live-client
        // shortcut is skipped when watchedRoots is non-empty), so the
        // wedge is exercised against the real lease session.
        val WATCHED_ROOTS: List<ProjectRootEntity> = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "code", path = "~/code"),
        )
    }
}
