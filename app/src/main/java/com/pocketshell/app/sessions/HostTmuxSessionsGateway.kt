package com.pocketshell.app.sessions

import android.util.Log
import com.pocketshell.app.projects.EnginesGateway
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.ssh.BoundedSessionExec
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

interface HostTmuxSessionsGateway {
    suspend fun listSessions(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): HostTmuxSessionListResult

    /**
     * Issue #463: list sessions using ONLY the warm live `-CC` control
     * client for [host]. Never opens a fresh SSH connection. Returns
     * `null` when there is no live client for the host (so the caller can
     * keep the previously-known list rather than blocking on a handshake).
     * This is the data source for the in-session project switcher — using
     * it instead of [listSessions] is what keeps the switch "instant".
     *
     * Issue #2377 — READ THIS BEFORE REUSING IT. A `tmux -CC` control client
     * is attached to exactly ONE tmux server, so these rows are ONE socket's
     * `list-sessions`, never the host's session SET. On a tmuxctl host (one
     * `tmuxctl-*` socket per session) plus aplexer, that is a severe
     * undercount — the maintainer saw "1 session" against a host with 10.
     * These rows are a metadata OVERLAY only; [listSessions] unions the
     * host-wide enumerator over them and is the only correct source for a
     * "these are the host's sessions" list.
     *
     * The in-session project switcher (`refreshProjectSiblings`) is the one
     * deliberate exemption: see the note on that function.
     */
    suspend fun listSessionsFromLiveClient(
        host: HostEntity,
        keyPath: String,
    ): HostTmuxSessionListResult?
}

class SshHostTmuxSessionsGateway internal constructor(
    private val parser: HostTmuxSessionListParser,
    private val activeTmuxClients: ActiveTmuxClients,
    private val sshLeaseManager: SshLeaseManager,
    private val leaseBlockTimeoutMs: Long,
    private val liveEnumTimeoutMs: Long,
    private val tmuxExecTimeoutMs: Long = TMUX_EXEC_TIMEOUT_MS,
    private val enginesGateway: EnginesGateway? = null,
) : HostTmuxSessionsGateway {
    constructor(
        parser: HostTmuxSessionListParser,
        activeTmuxClients: ActiveTmuxClients,
    ) : this(
        parser = parser,
        activeTmuxClients = activeTmuxClients,
        sshLeaseManager = defaultLeaseManager(),
        leaseBlockTimeoutMs = LEASE_BLOCK_TIMEOUT_MS,
        liveEnumTimeoutMs = LIVE_ENUM_TIMEOUT_MS,
    )

    /** Compatibility seam for direct callers that predate engine-family lookup. */
    constructor(
        parser: HostTmuxSessionListParser,
        activeTmuxClients: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager,
    ) : this(
        parser = parser,
        activeTmuxClients = activeTmuxClients,
        sshLeaseManager = sshLeaseManager,
        leaseBlockTimeoutMs = LEASE_BLOCK_TIMEOUT_MS,
        liveEnumTimeoutMs = LIVE_ENUM_TIMEOUT_MS,
        enginesGateway = null,
    )

    @Inject
    constructor(
        parser: HostTmuxSessionListParser,
        activeTmuxClients: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager,
        enginesGateway: EnginesGateway,
    ) : this(
        parser = parser,
        activeTmuxClients = activeTmuxClients,
        sshLeaseManager = sshLeaseManager,
        leaseBlockTimeoutMs = LEASE_BLOCK_TIMEOUT_MS,
        liveEnumTimeoutMs = LIVE_ENUM_TIMEOUT_MS,
        enginesGateway = enginesGateway,
    )

    override suspend fun listSessions(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): HostTmuxSessionListResult {
        // Issue #2377 (hard cut, D22): this used to be
        // `listSessionsFromLiveClient(host, keyPath)?.let { return it }` — the
        // live `-CC` client's rows published as the WHOLE session-picker list.
        // A control client is attached to exactly ONE tmux server, so on the
        // maintainer's host (7 tmuxctl-managed sessions, one `tmuxctl-*` socket
        // each, plus 3 aplexer-managed) the picker showed the 1 session on the
        // socket the app happened to be attached to. Identical defect, identical
        // trigger and identical class as the folder-list one this issue fixes:
        // the moment the user is inside a session, a warm client exists.
        //
        // The live rows keep their job — they are the richest read of the
        // ATTACHED server (session_path, recorded `@ps_agent_kind`, ids) and
        // they cost no SSH — but they are now an OVERLAY, exactly like the
        // `tmux list-sessions` overlay on the cold branch below. The tmuxctl +
        // aplexer enumerator is the authority for the session SET on every
        // branch. Having them means we can skip the cold branch's
        // `tmux list-sessions` exec, so the warm path still does strictly less
        // work than the cold one.
        val liveOverlay =
            (listSessionsFromLiveClient(host, keyPath) as? HostTmuxSessionListResult.Sessions)
                ?.rows

        SshOpenTelemetry.record(
            source = SSH_SOURCE_SESSION_PICKER_LIST,
            host = host.hostname,
            port = host.port,
            user = host.username,
        )
        // Keep the lease block ceiling, but run it on a wall-clock dispatcher.
        // The cold exec has its own, shorter [BoundedSessionExec] ceiling; if
        // this outer timeout inherits a runTest scheduler while the inner exec
        // is crossing to Dispatchers.IO, a healthy fake (or a typed inner
        // timeout) can be cancelled before exec starts. Production already
        // needs wall-clock bounds for the SSH read, so this preserves both
        // independent bounds instead of weakening either one.
        return withContext(Dispatchers.IO) {
            LeaseSessionExec.withSession(
                leaseManager = sshLeaseManager,
                target = host.toLeaseSessionTarget(keyPath, passphrase),
                blockTimeoutMs = leaseBlockTimeoutMs,
            ) { session ->
                if (liveOverlay != null) {
                    // Warm branch: the live client already read the attached
                    // server, so the only thing missing is the host-wide set.
                    return@withSession unionedResult(
                        enumerator = enumeratorFromPocketshell(session),
                        overlay = liveOverlay,
                    )
                }
                val tmux = session.execTmuxListSessions(pathAware(LIST_SESSIONS_COMMAND))
                when {
                    tmux.exitCode == 0 -> {
                        val overlay = parser.parseTmuxListSessions(tmux.stdout) { rawId ->
                            enginesGateway?.familyForRawId(host.id, rawId)
                        }
                        unionedResult(
                            enumerator = enumeratorFromPocketshell(session),
                            overlay = overlay,
                        )
                    }
                    tmux.exitCode == 127 || tmux.stderr.contains("not found", ignoreCase = true) ->
                        HostTmuxSessionListResult.ToolUnavailable
                    tmux.stderr.contains("no server running", ignoreCase = true) ->
                        // The DEFAULT socket has no server. That says nothing
                        // about the tmuxctl sockets or aplexer, so ask the
                        // enumerator rather than reporting "no sessions" (#2377).
                        unionedResult(
                            enumerator = enumeratorFromPocketshell(session),
                            overlay = emptyList(),
                        )
                    else -> HostTmuxSessionListResult.Failed(
                        tmux.stderr.ifBlank { tmux.stdout }.ifBlank { "tmux exited ${tmux.exitCode}" },
                    )
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { error -> HostTmuxSessionListResult.ConnectFailed(error) },
        )
    }

    private fun HostEntity.toLeaseSessionTarget(
        keyPath: String,
        passphrase: CharArray?,
    ): LeaseSessionTarget =
        LeaseSessionTarget(
            hostId = id,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = passphrase,
            trustedHostKeyAlgorithm = trustedHostKeyAlgorithm,
            trustedHostKeySha256 = trustedHostKeySha256,
        )

    private fun pathAware(command: String): String =
        ReposRemoteSource.pathAwareCommand(command)

    /**
     * Issue #2377: the shared host-wide enumerator, the same state machine the
     * folder list runs (see [HostSessionEnumerator]) instead of this file's old
     * private copy. The copy swallowed a transport throw into an empty list,
     * which is indistinguishable from "the host really has no other sessions" —
     * the conflation that lets a narrow list be published as the truth.
     */
    private suspend fun enumeratorFromPocketshell(
        session: SshSession,
    ): HostSessionEnumerator.Fetch =
        HostSessionEnumerator.fetch(
            parser = parser,
            exec = { command -> session.execTmuxListSessions(command) },
            jsonCommand = pathAware(
                HostSessionEnumerator.jsonExecBody(POCKETSHELL_SESSIONS_JSON_COMMAND),
            ),
            humanCommand = pathAware(POCKETSHELL_SESSIONS_COMMAND),
        )

    /**
     * Issue #2377: union the host-wide enumerator (AUTHORITY for which sessions
     * exist) over a single-socket [overlay] (richer metadata for the sessions it
     * can see) — the same rule, and the same [HostTmuxSessionListParser.unionLiveSessionRows]
     * call, on every branch of [listSessions].
     *
     * [HostSessionEnumerator.Fetch.Unavailable] is the one case that must not
     * fall back to the overlay: we did not read the host, so publishing the
     * narrower list would be a confidently-wrong count (the reported defect).
     * The picker renders [HostTmuxSessionListResult.Failed] as a retryable
     * fallback sheet with this message, which is honest. A
     * [HostSessionEnumerator.Fetch.Failed] (the host has no working `pocketshell`
     * CLI at all, so it has no tmuxctl sockets or aplexer either) legitimately
     * leaves the default socket as the whole truth.
     */
    private fun unionedResult(
        enumerator: HostSessionEnumerator.Fetch,
        overlay: List<HostTmuxSessionRow>,
    ): HostTmuxSessionListResult =
        if (enumerator is HostSessionEnumerator.Fetch.Unavailable) {
            HostTmuxSessionListResult.Failed(ENUMERATOR_UNAVAILABLE_MESSAGE)
        } else {
            HostTmuxSessionListResult.Sessions(
                parser.unionLiveSessionRows(enumerator.rows, overlay),
            )
        }

    override suspend fun listSessionsFromLiveClient(
        host: HostEntity,
        keyPath: String,
    ): HostTmuxSessionListResult? {
        val entry = activeTmuxClients.clients.value[host.id]
            ?.takeIf { it.matches(host, keyPath) }
            ?.takeUnless { it.client.disconnected.value }
            ?: return null
        return try {
            val response = withTimeoutOrNull(liveEnumTimeoutMs) {
                entry.client.sendCommand(LIVE_LIST_SESSIONS_COMMAND)
            } ?: run {
                Log.w(
                    LOG_TAG,
                    "live -CC session picker enumeration wedged >${liveEnumTimeoutMs}ms; " +
                        "falling through to bounded SSH-lease enumeration.",
                )
                return null
            }
            if (response.isError) {
                val message = response.output.joinToString("\n")
                if (message.contains("no server running", ignoreCase = true)) {
                    HostTmuxSessionListResult.Sessions(emptyList())
                } else {
                    null
                }
            } else {
                HostTmuxSessionListResult.Sessions(
                    parser.parseTmuxListSessions(
                        response.output.joinToString(separator = "\n"),
                    ) { rawId -> enginesGateway?.familyForRawId(host.id, rawId) },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Bound the cold list-sessions read independently of lease acquisition.
     * [SshSession.exec] can otherwise remain parked in a blocking channel read
     * after authentication succeeded, which is the in-emulator picker/tree
     * stall tracked by issue #2317. The shared transport remains open when the
     * channel is abandoned; only the typed result is surfaced to the caller.
     */
    private suspend fun SshSession.execTmuxListSessions(command: String): ExecResult {
        BoundedSessionExec.execBounded(
            session = this,
            command = command,
            timeoutMs = tmuxExecTimeoutMs,
            dispatcher = Dispatchers.IO,
            callerSite = TMUX_LIST_SESSIONS_CALLER_SITE,
        )?.let { return it }

        Log.w(
            LOG_TAG,
            "JOURNEY_ENUMERATION_STALL: tmux list-sessions " +
                "caller=$TMUX_LIST_SESSIONS_CALLER_SITE timeoutMs=$tmuxExecTimeoutMs",
        )
        throw TmuxSessionListExecTimeoutException(command, tmuxExecTimeoutMs)
    }

    private fun ActiveTmuxClients.Entry.matches(host: HostEntity, keyPath: String): Boolean =
        hostname == host.hostname &&
            port == host.port &&
            username == host.username &&
            this.keyPath == keyPath

    internal companion object {
        const val LOG_TAG: String = "HostTmuxSessions"

        /**
         * The cold (no warm `-CC` client) session-picker enumeration.
         *
         * Issue #1944: picker navigation must carry a correlated tmux
         * generation. A name-only proxy row is unsafe after runtime-cache
         * eviction, so query id + created directly.
         *
         * Issue #2160: `tmux -u`. `#{session_name}` is free-form — the
         * maintainer routinely names sessions in Russian — and a tmux client
         * that is not in UTF-8 mode replaces every non-printable-ASCII
         * character it prints with `_`. On a host whose sshd hands the SSH
         * exec channel no locale (a container, Alpine/BusyBox, a hardened
         * sshd with no `AcceptEnv`/PAM locale) a bare read returns `______`
         * for such a name, and the picker can no longer target the session it
         * is showing. Same command shape, same exec lane, same byte class as
         * [com.pocketshell.app.projects.SshFolderListGateway.LIST_SESSIONS_COMMAND].
         * See [com.pocketshell.core.tmux.TmuxRead].
         */
        const val POCKETSHELL_SESSIONS_COMMAND: String =
            "pocketshell sessions list --by activity"
        const val POCKETSHELL_SESSIONS_JSON_COMMAND: String =
            "pocketshell sessions list --json"

        /**
         * Issue #2377: user-visible reason for refusing to publish a session
         * list we know is incomplete. Same wording as the folder list's
         * `SshFolderListGateway.ENUMERATOR_UNAVAILABLE_MESSAGE` — one symptom,
         * one message, whichever list the user is looking at.
         */
        const val ENUMERATOR_UNAVAILABLE_MESSAGE: String =
            "Couldn't read the host session list (pocketshell sessions list did not respond). " +
                "Not showing a partial list."

        const val LIST_SESSIONS_COMMAND: String =
            "${TmuxRead.CLIENT} list-sessions -F " +
                "'#{session_id}::#{session_name}::#{session_created}::" +
                "#{session_activity}::#{session_attached}::#{@ps_agent_kind}::#{session_path}'"

        const val LEASE_BLOCK_TIMEOUT_MS: Long = 3_500L

        const val LIVE_ENUM_TIMEOUT_MS: Long = 3_500L

        /** Issue #2317: cold list-sessions read ceiling after SSH lease acquire. */
        const val TMUX_EXEC_TIMEOUT_MS: Long = 3_000L

        const val TMUX_LIST_SESSIONS_CALLER_SITE: String = "host_tmux_sessions_list"

        fun defaultLeaseManager(): SshLeaseManager =
            SshLeaseManager(
                connector = SshLeaseConnector { target ->
                    DefaultSshLeaseConnector().connect(target)
                },
            )

        // Issue #463: append `#{session_path}` so the warm live-client list
        // carries each session's working directory and the in-session
        // project switcher can group sessions by project/folder without a
        // second SSH connect.
        const val LIVE_LIST_SESSIONS_COMMAND: String =
                "list-sessions -F " +
                "'#{session_id}::#{session_name}::#{session_created}::#{session_activity}::" +
                "#{session_attached}::#{@ps_agent_kind}::#{session_path}'"
    }
}
