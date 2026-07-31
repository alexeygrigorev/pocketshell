package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Issue #1876 — the HEADLESS REAL-TRANSPORT reproduction of the folder-tree
 * reconcile chain blowing its 12 s bound on a mobile link (the leading
 * candidate for #1870: "I can connect with Termius but not with PocketShell,
 * on mobile internet").
 *
 * ## Why this test exists in this shape (D34 / G10)
 *
 * The defect is **invisible on a LAN**. The whole chain is a SERIAL sequence of
 * short-lived SSH `exec` channels, each costing several round trips; at ~0 ms
 * RTT it measures 0.4 s and at ~400 ms RTT it measures 11.2–11.6 s against
 * [FolderListViewModel.RECONCILE_TIMEOUT_MS] = 12 s — and the maintainer's real
 * host (18 sessions, watched roots) exceeds it deterministically. So a happy
 * LAN fixture cannot enter the failing state, and per **G10** the fixture that
 * *creates* the state is part of the reproduction: this class drives the
 * PRODUCTION [SshFolderListGateway] over a REAL sshj transport through the
 * repo's own `packet-loss-proxy` netem shaper, configured at the measured
 * mobile profile (~400 ms RTT, jitter, 5 % loss — see [DELAY_MS]/[JITTER_MS]/
 * [LOSS_RATE]).
 *
 * Locked decision **D34** makes an OBSERVED headless real-transport
 * reproduction first-class D33 proof for this class; the emulator journey stays
 * the batched backstop.
 *
 * ## The load-bearing assertion (G6)
 *
 * [reconcileChainFitsTheProductionReconcileBoundOnAMobileLink] measures the
 * WALL CLOCK of one real `listSessionsWithFolder` over the shaped transport and
 * asserts it against the PRODUCTION bound the view model actually enforces. That
 * is the symptom-defining signal — not "a seam fired", not "N execs were
 * issued". It is RED on the pre-fix serial chain and GREEN once the independent
 * probes are issued concurrently.
 *
 * [theReconcileStillReturnsTheFullTreeUnderTheSameMobileProfile] is the G6
 * negative: a chain can always be made fast by doing LESS work, so the same run
 * must still return the sessions, the resolved watched roots, and a
 * per-root expansion entry for EVERY configured root. A green timing over a
 * hollowed-out result is a rejected fix.
 *
 * ## Gate wiring
 *
 * Runs under `:app:integrationTest` (Testcontainers, needs Docker) — the
 * batched-on-`main` Docker lane in `.github/workflows/tests.yml`, alongside
 * [com.pocketshell.app.tmux.Issue1635StormRecoveryRealTransportIntegrationTest].
 * The per-push `./gradlew test` Unit job stays Docker-free (the
 * `*IntegrationTest.class` exclude in `app/build.gradle.kts`). The class-level
 * `assumeTrue` keeps a Docker-less machine green; the load-bearing assertions
 * themselves carry NO skip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1876FolderReconcileMobileRttIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22
        private const val SHAPER_PORT = 2229

        /**
         * The measured mobile profile. `netem` shapes the shaper container's
         * EGRESS and `socat` relays both directions through that one interface,
         * so each direction is delayed once and the observed client<->server RTT
         * is ~2 x [DELAY_MS] = ~400 ms — the profile the #1876 investigation
         * measured 11.2–11.6 s of reconcile chain at, and squarely inside the
         * range observed on the maintainer's own link (`ss -tni` on the host
         * during his failing attempt: `rtt:152/33 minrtt:101`, 1.9 % retransmit,
         * `cwnd:10 ssthresh:7`).
         */
        private const val DELAY_MS = 200
        private const val JITTER_MS = 40
        private const val LOSS_RATE = "5%"

        /**
         * Watched roots the reconcile must expand. The maintainer's host has
         * watched roots configured; each one costs its own serial `pocketshell
         * repos list` round trip on the pre-fix chain, and the `~` form
         * additionally forces a `$HOME` resolution exec first.
         */
        private val WATCHED_ROOT_PATHS = listOf("~/roots-a", "~/roots-b", "/home/testuser/roots-c")

        /**
         * The fixture is seeded with the SAME session count the maintainer's
         * host actually carries (18 live tmux sessions, per the #1870
         * server-side pull). A three-session fixture is the "happy fixture that
         * cannot enter the failing state" G10 warns about.
         */
        private val SEEDED_SESSIONS = (1..18).map { "s$it" }

        /**
         * Modelled cost of ONE host `pocketshell` invocation, in seconds.
         *
         * The Docker fixture's `pocketshell` is an instant shell script; the
         * maintainer's host runs the real `uv`-installed Python CLI, measured on
         * `RMTHZ` at 0.22 s warm for `pocketshell sessions list --by activity`.
         * A fixture whose CLI is free understates every reconcile that calls it
         * (`agents kind`, `logs tail`, one `repos list` per root), so the shim
         * installed by [seedFixture] charges a conservative 0.2 s.
         */
        private const val CLI_STARTUP_SECONDS = "0.2"

        /**
         * Extra delay applied to `pocketshell logs …` for the slow-host-CLI arm.
         *
         * 4 s deliberately exceeds the unchanged 3.5 s per-exec safety bound.
         * It is exactly the "slow but perfectly alive optional host CLI over
         * mobile RTT" case that must degrade without poisoning the transport or
         * widening the production timeout.
         */
        private const val SLOW_LOGS_SECONDS = "4"

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var network: Network? = null

        @Volatile
        private var agents: GenericContainer<*>? = null

        @Volatile
        private var shaper: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue(
                "Docker not available; skipping the #1876 mobile-RTT reconcile reproduction",
                dockerAvailable,
            )

            // `Dockerfile.agents` COPYs both `tests/docker/...` and
            // `app/build.gradle.kts`, so its context is the project root; the
            // shaper's Dockerfile COPYs `packet-loss-proxy/...`, so its context
            // is `tests/docker` (exactly as docker-compose.yml declares).
            buildImage(
                tag = "pocketshell-test:agents-issue1876",
                dockerfile = "tests/docker/Dockerfile.agents",
                context = ".",
            )
            buildImage(
                tag = "pocketshell-test:packet-loss-proxy-issue1876",
                dockerfile = "tests/docker/packet-loss-proxy/Dockerfile",
                context = "tests/docker",
            )

            val net = Network.newNetwork()
            network = net

            val agentsContainer = GenericContainer(
                DockerImageName.parse("pocketshell-test:agents-issue1876"),
            )
                .withNetwork(net)
                .withNetworkAliases("agents")
                .withExposedPorts(CONTAINER_SSH_PORT)
            agentsContainer.start()
            agents = agentsContainer

            seedFixture(agentsContainer)

            val shaperContainer = GenericContainer(
                DockerImageName.parse("pocketshell-test:packet-loss-proxy-issue1876"),
            )
                .withNetwork(net)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig?.withCapAdd(com.github.dockerjava.api.model.Capability.NET_ADMIN)
                }
                .withEnv("PACKET_LOSS_LISTEN_PORT", SHAPER_PORT.toString())
                .withEnv("PACKET_LOSS_TARGET_HOST", "agents")
                .withEnv("PACKET_LOSS_TARGET_PORT", CONTAINER_SSH_PORT.toString())
                .withEnv("PACKET_LOSS_RATE", LOSS_RATE)
                .withEnv("PACKET_LOSS_DELAY_MS", DELAY_MS.toString())
                .withEnv("PACKET_LOSS_JITTER_MS", JITTER_MS.toString())
                .withExposedPorts(SHAPER_PORT)
            shaperContainer.start()
            shaper = shaperContainer
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { shaper?.stop() }
            runCatching { agents?.stop() }
            runCatching { network?.close() }
            shaper = null
            agents = null
            network = null
        }

        private fun buildImage(tag: String, dockerfile: String, context: String) {
            val build = ProcessBuilder(
                "docker", "build", "-t", tag,
                "-f", projectRoot.resolve(dockerfile).toString(),
                projectRoot.resolve(context).normalize().toString(),
            ).redirectErrorStream(true).start()
            val out = build.inputStream.bufferedReader().readText()
            check(build.waitFor() == 0) { "Failed to build $tag:\n$out" }
        }

        /**
         * Give the fixture the shape the reconcile actually has to enumerate:
         * live tmux sessions with no recorded `@ps_agent_kind` (so they are
         * FOREIGN and the chain issues its `pocketshell agents kind` RPC), and
         * the watched-root directories the expansion resolves.
         */
        private fun seedFixture(container: GenericContainer<*>) {
            val seed = container.execInContainer(
                "su", "testuser", "-c",
                SEEDED_SESSIONS.joinToString(" && ") { name ->
                    "tmux new-session -d -s $name -c /home/testuser"
                },
            )
            check(seed.exitCode == 0) {
                "Failed to seed tmux sessions: ${seed.stdout}${seed.stderr}"
            }
            val mkdirs = container.execInContainer(
                "su", "testuser", "-c",
                "mkdir -p /home/testuser/roots-a/p1 /home/testuser/roots-b/p2 " +
                    "/home/testuser/roots-c/p3",
            )
            check(mkdirs.exitCode == 0) { "Failed to seed watched roots: ${mkdirs.stderr}" }
            installHostCliCostShim(container)
        }

        /**
         * G10 — "add the fixture that reproduces it".
         *
         * The stock `agents` fixture's `pocketshell` is a shell script that
         * returns instantly, so it cannot reproduce a host whose CLI costs real
         * time. This shim wraps it so every invocation pays
         * [CLI_STARTUP_SECONDS] (the measured `uv` Python startup on the
         * maintainer's host), and so the slow-host-CLI arm can additionally
         * charge `pocketshell logs …` by writing `/tmp/ps-cli-logs-delay`.
         *
         * Scoped to THIS test's own container — the shared fixture image and
         * every sibling suite are untouched.
         */
        private fun installHostCliCostShim(container: GenericContainer<*>) {
            val shim = """
                mv /usr/local/bin/pocketshell /usr/local/bin/pocketshell-real
                cat > /usr/local/bin/pocketshell <<'SHIM'
                #!/bin/sh
                sleep "$(cat /tmp/ps-cli-delay 2>/dev/null || echo 0)"
                if [ "${'$'}{1:-}" = "logs" ]; then
                  sleep "$(cat /tmp/ps-cli-logs-delay 2>/dev/null || echo 0)"
                fi
                exec /usr/local/bin/pocketshell-real "${'$'}@"
                SHIM
                sed -i 's/^                //' /usr/local/bin/pocketshell
                chmod +x /usr/local/bin/pocketshell
                printf '%s' '$CLI_STARTUP_SECONDS' > /tmp/ps-cli-delay
                printf '%s' '0' > /tmp/ps-cli-logs-delay
                chmod 666 /tmp/ps-cli-delay /tmp/ps-cli-logs-delay
            """.trimIndent()
            val result = container.execInContainer("sh", "-c", shim)
            check(result.exitCode == 0) {
                "Failed to install the host-CLI cost shim: ${result.stdout}${result.stderr}"
            }
            val verify = container.execInContainer(
                "su", "testuser", "-c", "pocketshell --version",
            )
            check(verify.exitCode == 0 && verify.stdout.contains("fixture")) {
                "host-CLI cost shim did not preserve the fixture CLI: " +
                    "rc=${verify.exitCode} out=${verify.stdout} err=${verify.stderr}"
            }
        }

        /** Charge `pocketshell logs …` an extra [seconds] on the fixture host. */
        private fun setSlowLogsDelay(seconds: String) {
            val container = agents!!
            val result = container.execInContainer(
                "sh", "-c", "printf '%s' '$seconds' > /tmp/ps-cli-logs-delay",
            )
            check(result.exitCode == 0) { "Failed to set the slow-logs delay: ${result.stderr}" }
        }

        /**
         * How many times sshd has completed a publickey authentication.
         *
         * This is the symptom-defining signal for the CLASSIFICATION half of
         * #1876, read from the SERVER: the pre-fix classifier answered a slow
         * exec by evicting the warm lease, so the reconcile paid a brand-new SSH
         * dial — visible on `RMTHZ` as "four successful publickey auths in two
         * minutes" while the user saw nothing load. A fix that keeps the warm
         * transport adds NO new login.
         */
        private fun sshLoginCount(): Int =
            agents!!.logs.split('\n').count { it.contains("Accepted publickey") }

        private fun findProjectRoot(): Path {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (dir.resolve("tests/docker/Dockerfile.agents").toFile().exists()) return dir
                dir = dir.parent
            }
            error(
                "Could not locate tests/docker/Dockerfile.agents from " +
                    "user.dir=${System.getProperty("user.dir")}",
            )
        }
    }

    private val privateKeyFile: File
        get() = projectRoot.resolve("tests/docker/test_key").toFile()

    private fun shapedHost(): HostEntity = HostEntity(
        id = 1L,
        name = "issue-1876-mobile",
        hostname = shaper!!.host,
        port = shaper!!.getMappedPort(SHAPER_PORT),
        username = "testuser",
        keyId = 1L,
    )

    private fun watchedRoots(): List<ProjectRootEntity> =
        WATCHED_ROOT_PATHS.mapIndexed { index, path ->
            ProjectRootEntity(id = index + 1L, hostId = 1L, label = "root$index", path = path)
        }

    private fun newGateway(leaseManager: SshLeaseManager): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = leaseManager,
            sessionListParser = HostTmuxSessionListParser(),
            execReadTimeoutMs = SshFolderListGateway.EXEC_READ_TIMEOUT_MS,
        )

    /**
     * Run ONE reconcile over an already-warm lease and return
     * `(elapsedMs, result)`. The lease is pre-warmed exactly as production does
     * it (`FolderListViewModel.ensureWarmConnectForReconcile`, issue #847) so
     * the measured window contains the ENUMERATION CHAIN only — never the cold
     * sshj dial, which has its own separate 35 s bound.
     */
    private fun measureWarmReconcile(): Pair<Long, FolderListResult> =
        measureWarmReconcileWithLogins().let { it.elapsedMs to it.result }

    private data class WarmReconcile(
        val elapsedMs: Long,
        val result: FolderListResult,
        val extraSshLogins: Int,
    )

    private fun measureWarmReconcileWithLogins(): WarmReconcile {
        val leaseManager = SshLeaseManager(
            connector = SshLeaseConnector { target -> DefaultSshLeaseConnector().connect(target) },
        )
        return leaseManager.use {
            runBlocking {
                val gateway = newGateway(leaseManager)
                val host = shapedHost()
                // Warm-up: dials the transport + primes the fixture's page cache.
                // Untimed by design — the cold dial is NOT inside the reconcile
                // window in production either.
                gateway.listSessionsWithFolder(
                    host = host,
                    keyPath = privateKeyFile.absolutePath,
                    passphrase = null,
                    watchedRoots = watchedRoots(),
                )
                val loginsBefore = sshLoginCount()
                val startedAt = System.nanoTime()
                val result = gateway.listSessionsWithFolder(
                    host = host,
                    keyPath = privateKeyFile.absolutePath,
                    passphrase = null,
                    watchedRoots = watchedRoots(),
                )
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                WarmReconcile(
                    elapsedMs = elapsedMs,
                    result = result,
                    extraSshLogins = sshLoginCount() - loginsBefore,
                )
            }
        }
    }

    /**
     * THE reproduction. RED on the pre-fix serial chain, GREEN with the fix.
     *
     * The bound asserted is the PRODUCTION one
     * ([FolderListViewModel.RECONCILE_TIMEOUT_MS]); exceeding it is exactly what
     * turns into `FolderReconcileTimeoutException` -> `ConnectFailed` -> the
     * "Couldn't refresh the project tree — tap to retry" panel, whose Retry
     * re-runs the identical chain under the identical bound and therefore fails
     * forever on a stable mobile link.
     */
    @Test(timeout = 600_000)
    fun reconcileChainFitsTheProductionReconcileBoundOnAMobileLink() {
        val (elapsedMs, result) = measureWarmReconcile()
        println(
            "ISSUE1876_RECONCILE_CHAIN profile=${DELAY_MS}ms+-${JITTER_MS}ms/loss=$LOSS_RATE " +
                "(rtt~${2 * DELAY_MS}ms) roots=${WATCHED_ROOT_PATHS.size} " +
                "elapsedMs=$elapsedMs bound=${FolderListViewModel.RECONCILE_TIMEOUT_MS}",
        )
        assertTrue(
            "The reconcile must not have failed outright: $result",
            result is FolderListResult.Sessions,
        )
        assertTrue(
            "Issue #1876: the folder-tree reconcile chain took ${elapsedMs}ms over a " +
                "~${2 * DELAY_MS}ms-RTT / $LOSS_RATE-loss link, against the production " +
                "${FolderListViewModel.RECONCILE_TIMEOUT_MS}ms RECONCILE_TIMEOUT_MS. On the " +
                "device that is the ConnectError panel whose Retry re-runs the same chain.",
            elapsedMs < FolderListViewModel.RECONCILE_TIMEOUT_MS,
        )
    }

    /**
     * G6 negative: the same shaped run must still produce the WHOLE tree. A
     * reconcile can always be made to fit its bound by dropping work; this pins
     * the result content so a hollowed-out "fast" chain cannot pass.
     */
    @Test(timeout = 600_000)
    fun theReconcileStillReturnsTheFullTreeUnderTheSameMobileProfile() {
        val (elapsedMs, result) = measureWarmReconcile()
        val sessions = result as? FolderListResult.Sessions
            ?: error("Expected Sessions, got $result")
        println(
            "ISSUE1876_RECONCILE_CONTENT elapsedMs=$elapsedMs rows=${sessions.rows.size} " +
                "resolvedRoots=${sessions.resolvedWatchedRootPaths} " +
                "expandedRoots=${sessions.projectFoldersByRoot.keys}",
        )
        assertEquals(
            "every seeded tmux session must still be enumerated",
            SEEDED_SESSIONS.toSet(),
            sessions.rows.map { it.sessionName }.toSet(),
        )
        assertEquals(
            "every configured watched root must still be resolved",
            WATCHED_ROOT_PATHS.toSet(),
            sessions.resolvedWatchedRootPaths.keys,
        )
        assertEquals(
            "the `~` shortcut must still be expanded against the remote \$HOME",
            "/home/testuser/roots-a",
            sessions.resolvedWatchedRootPaths["~/roots-a"],
        )
        assertEquals(
            "every configured watched root must still carry an expansion entry",
            WATCHED_ROOT_PATHS.toSet(),
            sessions.projectFoldersByRoot.keys,
        )
        assertTrue(
            "the active-pane cwd merge must still run (rows carry a cwd)",
            sessions.rows.all { it.cwd != null },
        )
    }

    /**
     * The CLASSIFICATION half of #1876, on the real transport.
     *
     * The host CLI is made to take [SLOW_LOGS_SECONDS] for one probe — slower
     * than the unchanged 3.5 s per-exec bound and exactly the state
     * `BoundedSessionExec`'s own doc calls routine on mobile ("a cold host
     * Python CLI over mobile RTT"). The optional decoration degrades at that
     * bound; the required tree continues over the same transport.
     *
     * On base the serial optional timeout escapes the chain, is classified as a
     * stale-channel symptom, and evicts the warm lease. The fixed batched probe
     * contains that optional failure, so the load-bearing assertions are BOTH
     * the extra login count (read from sshd itself, not from an app seam) and
     * the reconcile still landing inside its production bound with a real tree.
     */
    @Test(timeout = 600_000)
    fun aSlowButAliveHostCliDoesNotCostAFreshDialOrTheWholeTree() {
        setSlowLogsDelay(SLOW_LOGS_SECONDS)
        try {
            val run = measureWarmReconcileWithLogins()
            println(
                "ISSUE1876_SLOW_HOST_CLI slowLogs=${SLOW_LOGS_SECONDS}s " +
                    "elapsedMs=${run.elapsedMs} extraSshLogins=${run.extraSshLogins} " +
                    "bound=${FolderListViewModel.RECONCILE_TIMEOUT_MS}",
            )
            assertEquals(
                "a slow-but-alive host CLI must NOT cost a fresh SSH dial — the warm " +
                    "lease was still connected, and re-dialling is what turned 'slow' " +
                    "into 'cannot connect' (#1870)",
                0,
                run.extraSshLogins,
            )
            val sessions = run.result as? FolderListResult.Sessions
                ?: error("a slow optional probe must not fail the reconcile, got ${run.result}")
            assertEquals(
                "the tree must still be complete despite the slow probe",
                SEEDED_SESSIONS.toSet(),
                sessions.rows.map { it.sessionName }.toSet(),
            )
            assertTrue(
                "the reconcile must still fit ${FolderListViewModel.RECONCILE_TIMEOUT_MS}ms " +
                    "with one ${SLOW_LOGS_SECONDS}s host-CLI probe in it, got ${run.elapsedMs}ms",
                run.elapsedMs < FolderListViewModel.RECONCILE_TIMEOUT_MS,
            )
        } finally {
            setSlowLogsDelay("0")
        }
    }
}
