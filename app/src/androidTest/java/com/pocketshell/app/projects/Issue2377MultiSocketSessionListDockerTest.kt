package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.sessions.HostTmuxSessionListResult
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.SshHostTmuxSessionsGateway
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #2377 — the phone's session list must match `pocketshell sessions list
 * --json` / `tmuxctl list`, not a single tmux socket.
 *
 * Reported on `hetzner`: "1 active · 0 idle · 1 session" against a host with 10
 * live sessions (7 tmuxctl-managed, one `tmuxctl-*` socket each, plus 3
 * aplexer-managed). The app had just created a session from the New Session
 * sheet and been attached into it, which registers a live `tmux -CC` control
 * client — and that client is attached to exactly ONE tmux server, so the
 * gateway's live-client shortcut published that one server's `list-sessions` as
 * the whole host. #2348 forbade exactly this ("do not revert to default-socket
 * `tmux list-sessions` — that was the 3-session bug") but only ever covered the
 * no-live-client branch.
 *
 * This journey reproduces that host shape on the real Docker `agents` fixture
 * (host port 2222) through the PRODUCTION [FolderListViewModel] +
 * [SshFolderListGateway] + a REAL `tmux -CC` client:
 *
 *  1. seed three `tmuxctl-*` sockets, one tmux server + session each, plus an
 *     aplexer snapshot the fixture's `a` binary reports as a second manager;
 *  2. seed ONE session on the DEFAULT socket — the "just created by the app"
 *     one — and attach a real `-CC` client to it;
 *  3. read the host's own answer (`pocketshell sessions list --json`) as the
 *     oracle, over a separate plain SSH connection;
 *  4. assert the RENDERED `flatSessions` contains every session the host CLI
 *     reports, on cold load, after a routine refresh, and after a create made
 *     through the production view model.
 *
 * The load-bearing assertion is the oracle comparison, not "more than one row":
 * a fixture that lost its multi-socket seeding would fail the precondition
 * check rather than pass vacuously.
 *
 * The app has TWO session lists and both had this defect independently, so both
 * are driven here against the same fixture: the folder list
 * ([FolderListViewModel] + [SshFolderListGateway]) and the session picker sheet
 * ([com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel] +
 * [com.pocketshell.app.sessions.SshHostTmuxSessionsGateway], see
 * [sessionPickerListMatchesHostCliWithLiveClientAttached]).
 */
@RunWith(AndroidJUnit4::class)
class Issue2377MultiSocketSessionListDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    // Issue #2445 — `waitForSshFixtureReady` now probes with
    // `KnownHostsPolicy.VerifiedFingerprint(null)` and returns the real
    // presented fingerprint (see AndroidSshTestFixtures.kt, issue #2444/#2433's
    // "Enforce SSH host key trust and rekey flows"). The production
    // `SshFolderListGateway` used by [bindProductionViewModel] enforces
    // `HostEntity.hostKeyTrustBinding()` on every connect, so this fixture's
    // `HostEntity` must carry the trusted fingerprint or every connect throws
    // `UnknownHostKeyException`.
    private lateinit var trustedHostKeySha256: String
    private val viewModelStore = ViewModelStore()
    private val tmuxClientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ccSession: SshSession? = null
    private var ccClient: TmuxClient? = null
    private val socketSessions = mutableListOf<String>()
    private val defaultSocketSessions = mutableListOf<String>()
    private var seededAplexer = false

    @Before
    fun setUp(): Unit { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyText = instrumentation.context.assets.open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        keyFile = File(instrumentation.targetContext.cacheDir, "issue2377-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        db = Room.inMemoryDatabaseBuilder(
            instrumentation.targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        trustedHostKeySha256 = waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        viewModelStore.clear()
        runCatching { ccClient?.close() }
        runCatching { ccSession?.close() }
        runCatching { tmuxClientScope.cancel() }
        runCatching {
            withTimeout(25_000L) {
                connect().use { session ->
                    socketSessions.forEach { name ->
                        runCatching {
                            // kill-server leaves the socket FILE behind; remove
                            // it too so a shared long-lived fixture container
                            // does not accumulate dead `tmuxctl-*` entries.
                            session.exec(
                                "tmux -L $TMUXCTL_PREFIX$name kill-server 2>/dev/null || true; " +
                                    "rm -f \"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/$TMUXCTL_PREFIX$name\"",
                            )
                        }
                    }
                    defaultSocketSessions.forEach { name ->
                        runCatching { session.exec("tmux kill-session -t $name 2>/dev/null || true") }
                    }
                    if (seededAplexer) {
                        runCatching { session.exec("rm -f $APLEXER_SNAPSHOT") }
                    }
                }
            }
        }
        runCatching { db.close() }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun renderedSessionListMatchesHostCliAcrossSocketsAndManagers(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val seeded = seedHost(suffix)
        val bound = bindProductionViewModel(seeded.anchorSession)
        val vm = bound.vm

        // Cold load.
        val cold = awaitReadyContaining(vm, seeded.hostCliNames)
        println(
            "ISSUE2377_COLD rendered=${cold.size} hostCli=${seeded.hostCliNames.size} " +
                "names=$cold",
        )
        assertMatchesHostCli("cold load", seeded, cold)

        // Routine reconcile (pull-to-refresh through the production view model).
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.refresh() }
        val refreshed = awaitReadyContaining(vm, seeded.hostCliNames)
        println("ISSUE2377_REFRESH rendered=${refreshed.size} names=$refreshed")
        assertMatchesHostCli("routine reconcile", seeded, refreshed)
    } }

    @Test
    fun listAfterCreatingASessionInTheAppStillMatchesHostCli(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val seeded = seedHost(suffix)
        val bound = bindProductionViewModel(seeded.anchorSession)
        val vm = bound.vm
        assertMatchesHostCli("cold load", seeded, awaitReadyContaining(vm, seeded.hostCliNames))

        // The reported trigger: a session created from the New Session sheet.
        val createdFolder = "/tmp/issue2377-created-$suffix"
        val requested = "issue2377-created-$suffix"
        connect().use { it.exec("mkdir -p $createdFolder") }
        val resolved = CompletableDeferred<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.createSession(
                sessionName = requested,
                cwd = createdFolder,
                startCommand = null,
                onResolved = { name -> resolved.complete(name) },
            )
        }
        val createdName = withTimeout(60_000L) { resolved.await() }
        defaultSocketSessions += createdName

        // The maintained tree keeps the rows it already had, so the tree alone
        // cannot prove the POST-CREATE probe is still whole — a probe that
        // returned just the new session would be masked by the durable tree.
        // Ask the production gateway directly, in the exact state the maintainer
        // hit (live -CC client attached, a session just created), and require
        // its OWN rows to match the host CLI. This is the load-bearing
        // post-create assertion; the tree check below is the user-visible half.
        val probe = withTimeout(JOURNEY_BOUND_MS) {
            bound.gateway.listSessionsWithFolder(
                host = bound.host,
                keyPath = keyFile.absolutePath,
                passphrase = null,
            )
        }
        assertTrue("post-create probe must be a session list, got $probe", probe is FolderListResult.Sessions)
        val probeNames = (probe as FolderListResult.Sessions).rows.map { it.sessionName }.toSet()
        println("ISSUE2377_AFTER_CREATE_PROBE created=$createdName rows=${probeNames.size} names=$probeNames")
        assertTrue(
            "ISSUE2377_UNDERCOUNT post-create gateway probe collapsed to the attached " +
                "socket. rows=${probeNames.size} $probeNames missing=${seeded.hostCliNames - probeNames}",
            probeNames.containsAll(seeded.hostCliNames),
        )
        assertTrue(
            "the just-created session must be in the post-create probe; got $probeNames",
            createdName in probeNames,
        )

        val afterCreate = awaitReadyContaining(vm, seeded.hostCliNames + createdName)
        println("ISSUE2377_AFTER_CREATE created=$createdName rendered=${afterCreate.size} names=$afterCreate")
        assertMatchesHostCli("post-create reconcile", seeded, afterCreate)
        assertTrue(
            "the created session must be listed too; got $afterCreate",
            createdName in afterCreate,
        )
    } }

    private fun assertMatchesHostCli(
        phase: String,
        seeded: SeededHost,
        rendered: Set<String>,
    ) {
        assertTrue(
            "$phase: the rendered list must contain every session " +
                "`pocketshell sessions list --json` reports. missing=" +
                "${seeded.hostCliNames - rendered} rendered=$rendered",
            rendered.containsAll(seeded.hostCliNames),
        )
        assertTrue(
            "$phase: every seeded tmuxctl-* SOCKET session must be listed — this is the " +
                "default-socket-only undercount (#2348's 3-session bug, #2377's 1-session " +
                "recurrence). missing=${seeded.socketSessionNames - rendered}",
            rendered.containsAll(seeded.socketSessionNames),
        )
        assertTrue(
            "$phase: the aplexer-managed sessions must be listed. missing=" +
                "${seeded.aplexerNames - rendered}",
            rendered.containsAll(seeded.aplexerNames),
        )
        assertTrue(
            "$phase: the attached (live -CC) session must remain listed",
            seeded.anchorSession in rendered,
        )
    }

    private data class SeededHost(
        val anchorSession: String,
        val socketSessionNames: Set<String>,
        val aplexerNames: Set<String>,
        val hostCliNames: Set<String>,
    )

    private suspend fun seedHost(suffix: String): SeededHost {
        val socketNames = (1..SOCKET_COUNT).map { "issue2377-sock$it-$suffix" }
        val aplexerNames = (1..APLEXER_COUNT).map { "aplexer-follow:issue2377-$suffix-$it" }
        val anchorSession = "issue2377-anchor-$suffix"
        withTimeout(60_000L) {
            connect().use { session ->
                // One tmux SERVER per session, each on its own `tmuxctl-*`
                // socket — the real tmuxctl layout, and precisely what a bare
                // default-socket `tmux list-sessions` (or a `-CC` client) cannot
                // see.
                socketNames.forEach { name ->
                    val result = session.exec(
                        "tmux -L $TMUXCTL_PREFIX$name new-session -d -s $name -c /tmp",
                    )
                    check(result.exitCode == 0) {
                        "failed to seed tmuxctl socket $name: ${result.stderr}"
                    }
                    socketSessions += name
                }
                // The aplexer manager, via the fixture `a` binary's snapshot.
                val snapshot = aplexerNames.mapIndexed { index, name ->
                    """{"name":"$name","id":"issue2377-aplexer-$index",""" +
                        """"workspace":"/tmp/aplexer-$suffix-$index"}"""
                }.joinToString(",")
                val write = session.exec(
                    "printf '%s' '{\"sessions\":[$snapshot]}' > $APLEXER_SNAPSHOT",
                )
                check(write.exitCode == 0) { "failed to seed aplexer snapshot: ${write.stderr}" }
                seededAplexer = true
                // The DEFAULT-socket session the app attaches to.
                session.exec("tmux new-session -d -s $anchorSession -c /tmp")
                defaultSocketSessions += anchorSession
            }
        }

        // Oracle: the host's own answer, read over a separate plain connection.
        val hostCliNames = withTimeout(45_000L) {
            connect().use { session ->
                val json = session.exec("pocketshell sessions list --json")
                check(json.exitCode == 0) { "host CLI enumerator failed: ${json.stderr}" }
                val array = JSONObject(json.stdout.trim()).getJSONArray("sessions")
                (0 until array.length())
                    .map { array.getJSONObject(it).getString("name") }
                    .toSet()
            }
        }
        // Fixture precondition — a fixture that stopped enumerating the sockets
        // or the aplexer manager must FAIL here, never pass vacuously.
        check(hostCliNames.containsAll(socketNames)) {
            "fixture regression: `pocketshell sessions list --json` did not report the " +
                "seeded tmuxctl-* sockets. reported=$hostCliNames"
        }
        check(hostCliNames.containsAll(aplexerNames)) {
            "fixture regression: `pocketshell sessions list --json` did not report the " +
                "seeded aplexer sessions. reported=$hostCliNames"
        }
        // …and the default socket must be the narrow view the bug published.
        withTimeout(30_000L) {
            connect().use { session ->
                val defaultSocket = session.exec("tmux list-sessions -F '#{session_name}'")
                val defaultNames = defaultSocket.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
                check(defaultNames.none { it in socketNames }) {
                    "fixture regression: the tmuxctl-* sessions leaked onto the DEFAULT socket, " +
                        "so this journey could not reproduce the undercount. default=$defaultNames"
                }
                println("ISSUE2377_DEFAULT_SOCKET names=$defaultNames hostCli=${hostCliNames.size}")
            }
        }
        return SeededHost(
            anchorSession = anchorSession,
            socketSessionNames = socketNames.toSet(),
            aplexerNames = aplexerNames.toSet(),
            hostCliNames = hostCliNames,
        )
    }

    /**
     * The SECOND session list: the session picker sheet. It reads its own
     * gateway ([SshHostTmuxSessionsGateway]) and had the identical single-socket
     * `-CC` short-circuit — `listSessionsFromLiveClient(...)?.let { return it }`
     * — so on the reported host it showed 1 of 10 even after the folder list was
     * fixed. Same fixture, same real live client, same host-CLI oracle.
     */
    @Test
    fun sessionPickerListMatchesHostCliWithLiveClientAttached(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val seeded = seedHost(suffix)
        val bound = bindProductionViewModel(seeded.anchorSession)

        val pickerGateway = SshHostTmuxSessionsGateway(
            parser = HostTmuxSessionListParser(),
            activeTmuxClients = bound.registry,
        )

        // Precondition (anti-vacuous): the live `-CC` client MUST be the narrow
        // single-socket view — otherwise this journey would not be exercising
        // the bugged path at all and a green would prove nothing.
        val liveOnly = withTimeout(30_000L) {
            pickerGateway.listSessionsFromLiveClient(bound.host, keyFile.absolutePath)
        }
        check(liveOnly is HostTmuxSessionListResult.Sessions) {
            "no live -CC client registered for the picker journey; got $liveOnly"
        }
        val liveNames = (liveOnly as HostTmuxSessionListResult.Sessions).rows.map { it.name }.toSet()
        println("ISSUE2377_PICKER_LIVE_ONLY rows=${liveNames.size} names=$liveNames")
        check(liveNames.none { it in seeded.socketSessionNames }) {
            "fixture regression: the live -CC client already sees the tmuxctl-* sockets, " +
                "so the picker undercount cannot reproduce. live=$liveNames"
        }

        // The user-visible surface: HostTmuxSessionPickerState.Ready.rows.
        val pickerVm = HostTmuxSessionPickerViewModel(pickerGateway)
            .also { viewModelStore.put("issue2377-session-picker", it) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            pickerVm.load(
                HostTmuxSessionPickerRequest(
                    host = bound.host,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                ),
            )
        }
        val rendered = awaitPickerRows(pickerVm, seeded.hostCliNames)
        println("ISSUE2377_PICKER rendered=${rendered.size} hostCli=${seeded.hostCliNames.size} names=$rendered")
        assertTrue(
            "ISSUE2377_UNDERCOUNT the session picker must list every session " +
                "`pocketshell sessions list --json` reports, not the one socket the live " +
                "-CC client is attached to. missing=${seeded.hostCliNames - rendered} " +
                "rendered=$rendered",
            rendered.containsAll(seeded.hostCliNames),
        )
        assertTrue(
            "every seeded tmuxctl-* SOCKET session must be in the picker. missing=" +
                "${seeded.socketSessionNames - rendered}",
            rendered.containsAll(seeded.socketSessionNames),
        )
        assertTrue(
            "the aplexer-managed sessions must be in the picker. missing=" +
                "${seeded.aplexerNames - rendered}",
            rendered.containsAll(seeded.aplexerNames),
        )
        assertTrue(
            "the attached (live -CC) session must remain listed",
            seeded.anchorSession in rendered,
        )
    } }

    /**
     * Issue #2387 — the companion mechanism: `TmuxClient.connect` attaching
     * with no `-S` can never reach a session that lives on its own dedicated
     * `tmuxctl-<name>` socket. On the reported host that meant an attach
     * silently minted a brand-new, empty, same-named session on the DEFAULT
     * socket (`new-session -A` is attach-OR-create) instead of reaching the
     * real one — the upstream half of the #2377 undercount (a live client
     * landing on the wrong socket in the first place).
     *
     * Drives the PRODUCTION [TmuxClientFactory]/[TmuxClient] against a real
     * pre-existing session on a dedicated `tmuxctl-*` socket (seeded the same
     * way [seedHost] seeds every other socket in this class) and asserts the
     * `-CC` client reaches THAT session — not a fresh empty one on default.
     */
    @Test
    fun connectAttachesToTheDedicatedSocketSessionNeverMintsAnOrphanOnDefault(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val target = "issue2387-target-$suffix"
        val marker = "issue2387-marker-$suffix"
        withTimeout(30_000L) {
            connect().use { session ->
                val created = session.exec(
                    "tmux -L $TMUXCTL_PREFIX$target new-session -d -s $target -c /tmp",
                )
                check(created.exitCode == 0) {
                    "failed to seed dedicated-socket session $target: ${created.stderr}"
                }
                socketSessions += target
                // A REAL pre-existing pane, typed BEFORE the app ever connects —
                // exactly like a session the maintainer had open on the host
                // already.
                val typed = session.exec(
                    "tmux -L $TMUXCTL_PREFIX$target send-keys -t '=$target:' 'echo $marker' Enter",
                )
                check(typed.exitCode == 0) { "failed to seed marker: ${typed.stderr}" }
                delay(300)
                // Precondition (anti-vacuous): confirm the DEFAULT socket does
                // NOT already know this name, so a later default-socket hit can
                // only be the fix's absence, not fixture leakage.
                val defaultBefore = session.exec("tmux has-session -t '=$target'")
                check(defaultBefore.exitCode != 0) {
                    "fixture regression: `$target` already exists on the default socket " +
                        "before connect() ran"
                }
            }
        }

        val session = withTimeout(30_000L) { connect(timeoutMs = 15_000) }
        val client: TmuxClient = TmuxClientFactory(tmuxClientScope).create(
            session = session,
            sessionName = target,
        )
        try {
            withTimeout(30_000L) { client.connect() }
            val response = withTimeout(15_000L) { client.sendCommand("list-sessions") }
            assertTrue(
                "ISSUE2387_ORPHAN list-sessions must show the real `$target`, got " +
                    "${response.output}",
                response.output.any { line -> line.startsWith("$target:") },
            )
            withTimeout(30_000L) {
                connect().use { probe ->
                    val capture = probe.exec(
                        "tmux -L $TMUXCTL_PREFIX$target capture-pane -p -t '=$target:'",
                    )
                    assertTrue(
                        "ISSUE2387_ORPHAN expected the pre-existing marker `$marker` in the " +
                            "attached pane (proves the REAL session was reached, not a fresh " +
                            "empty one); captured=`${capture.stdout}`",
                        capture.stdout.contains(marker),
                    )
                    val defaultAfter = probe.exec("tmux has-session -t '=$target'")
                    assertTrue(
                        "ISSUE2387_ORPHAN connect() minted an orphan `$target` on the DEFAULT " +
                            "socket instead of reaching the dedicated one",
                        defaultAfter.exitCode != 0,
                    )
                }
            }
        } finally {
            client.close()
            session.close()
        }
    } }

    private suspend fun awaitPickerRows(
        vm: HostTmuxSessionPickerViewModel,
        required: Set<String>,
        timeoutMs: Long = JOURNEY_BOUND_MS,
    ): Set<String> {
        var last: Set<String> = emptySet()
        var lastState = "none"
        try {
            return withTimeout(timeoutMs) {
                var settled: Set<String>? = null
                while (settled == null) {
                    when (val state = vm.state.value) {
                        is HostTmuxSessionPickerState.ConnectError ->
                            error("session picker surfaced ConnectError: ${state.summary}")
                        is HostTmuxSessionPickerState.Fallback ->
                            error("session picker fell back: ${state.message}")
                        is HostTmuxSessionPickerState.Ready -> {
                            last = state.rows.map { it.name }.toSet()
                            lastState = "Ready"
                            if (last.containsAll(required)) settled = last
                        }
                        else -> lastState = state::class.java.simpleName
                    }
                    if (settled == null) delay(200L)
                }
                requireNotNull(settled)
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "ISSUE2377_UNDERCOUNT the session picker never matched the host CLI within " +
                    "${timeoutMs}ms. state=$lastState rendered=${last.size} $last " +
                    "missing=${required - last} required=${required.size} $required",
            )
        }
    }

    private data class BoundApp(
        val vm: FolderListViewModel,
        val gateway: SshFolderListGateway,
        val host: HostEntity,
        val registry: ActiveTmuxClients,
    )

    private suspend fun bindProductionViewModel(anchorSession: String): BoundApp {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue2377-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue2377-host",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
                // Issue #2445: without an explicit trusted fingerprint the
                // production gateway's host-key verifier throws
                // UnknownHostKeyException on every connect (see the
                // trustedHostKeySha256 field doc above).
                trustedHostKeySha256 = trustedHostKeySha256,
            ),
        )
        val host = db.hostDao().getById(hostId)!!

        // A REAL `tmux -CC` control client on the DEFAULT socket, registered
        // exactly the way TmuxSessionViewModel does when the user is inside a
        // session. This is the state that made the list collapse.
        val session = withTimeout(30_000L) { connect(timeoutMs = 15_000) }
        ccSession = session
        val client = TmuxClientFactory(tmuxClientScope).create(
            session = session,
            sessionName = anchorSession,
        )
        ccClient = client
        withTimeout(30_000L) { client.connect() }
        val registry = ActiveTmuxClients()
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = keyFile.absolutePath,
            client = client,
        )

        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = registry,
            sessionListParser = HostTmuxSessionListParser(),
        )
        val vm = FolderListViewModel(
            gateway = gateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
            activeTmuxClients = registry,
            attachLifecycle = false,
        ).also { viewModelStore.put("issue2377-folder-list", it) }
        vm.setProcessStartedForTest(true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.bind(
                hostId = host.id,
                hostName = host.name,
                hostname = host.hostname,
                port = host.port,
                username = host.username,
                keyPath = keyFile.absolutePath,
                passphrase = null,
            )
        }
        return BoundApp(vm = vm, gateway = gateway, host = host, registry = registry)
    }

    private suspend fun awaitReadyContaining(
        vm: FolderListViewModel,
        required: Set<String>,
        timeoutMs: Long = JOURNEY_BOUND_MS,
    ): Set<String> {
        // `lastRendered` is deliberately hoisted OUT of the timeout scope: when
        // the undercount bug is present the wait expires, and a bare
        // TimeoutCancellationException would say nothing about WHY. The RED
        // evidence for this issue is "rendered=[…1 name…] missing=[…9 names…]",
        // so report it.
        var lastRendered: Set<String> = emptySet()
        try {
            return withTimeout(timeoutMs) {
                var settled: Set<String>? = null
                while (settled == null) {
                    when (val state = vm.state.value) {
                        is FolderListUiState.ConnectError ->
                            error("folder reconcile surfaced ConnectError: ${state.message}")
                        is FolderListUiState.Failed ->
                            error("folder reconcile failed: ${state.message}")
                        is FolderListUiState.Ready -> {
                            val names = state.flatSessions.map { it.sessionName }.toSet()
                            lastRendered = names
                            if (names.containsAll(required)) settled = names
                        }
                        else -> Unit
                    }
                    if (settled == null) delay(200L)
                }
                requireNotNull(settled)
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "ISSUE2377_UNDERCOUNT the rendered session list never matched the host CLI " +
                    "within ${timeoutMs}ms. rendered=${lastRendered.size} $lastRendered " +
                    "missing=${required - lastRendered} required=${required.size} $required",
            )
        }
    }

    private suspend fun connect(timeoutMs: Int = 10_000): SshSession =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = timeoutMs,
        ).getOrThrow()

    private companion object {
        const val SOCKET_COUNT: Int = 3
        const val APLEXER_COUNT: Int = 2
        const val TMUXCTL_PREFIX: String = "tmuxctl-"
        const val APLEXER_SNAPSHOT: String = "\$HOME/.pocketshell-fixture-aplexer.json"
        const val JOURNEY_BOUND_MS: Long = 60_000L
    }
}
