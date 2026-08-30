package com.pocketshell.app.projects

import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
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
 * WALL CLOCK of real `listSessionsWithFolder` calls over the shaped transport and
 * asserts against the PRODUCTION bound the view model actually enforces. That is
 * the symptom-defining signal — not "a seam fired", not "N execs were issued". It
 * is RED on the pre-fix serial chain and GREEN once the independent probes are
 * issued concurrently.
 *
 * Issue #2422 made that measurement a SAMPLE SET rather than a single reading,
 * and added the two arms that keep it honest: `mutationADoubledChainIsRejectedByTheSameBudget`
 * (the budget must still fail closed on a genuinely slower chain) and
 * [aTransientlySlowRequiredExecCostsNoFreshDialAndKeepsTheTree] (a required exec
 * that over-runs its per-exec bound must not cost a fresh SSH dial). See
 * [CHAIN_SAMPLES] and [CHAIN_BUDGET_MS] for the recorded distribution behind both.
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

        /**
         * Flag file that arms one slow `tmux list-sessions`.
         *
         * It lives in the user's home, not the sticky `/tmp`, so the shim — which
         * runs as `testuser` while the test writes the flag over `su` — can delete
         * it after consuming it.
         */
        private const val TMUX_ONCE_DELAY_FLAG = "/home/testuser/.ps2422-list-sessions-delay"

        /**
         * Where the fixture's `tmux` actually lives. `Dockerfile.agents` moves
         * Alpine's real binary to `/usr/bin/tmux.real` and installs the shared
         * fault-injection shim at `/usr/local/bin/tmux`, which is what the
         * production `PATH` resolves. `/usr/bin/tmux` does not exist.
         */
        private const val TMUX_SHIM_PATH = "/usr/local/bin/tmux"

        /** Where this test's wrapper moves the fixture shim before delegating. */
        private const val TMUX_DELEGATE_PATH = "/usr/local/bin/tmux.issue2422"

        /**
         * Seconds the armed `tmux list-sessions` sleeps. Must exceed
         * [SshFolderListGateway.EXEC_READ_TIMEOUT_MS] (3.5 s) so the required
         * landing batch is ABANDONED, which is the state under test.
         */
        private const val SLOW_LIST_SESSIONS_SECONDS = "5"

        /**
         * How many reconciles each timing assertion measures — issue #2422.
         *
         * One wall-clock sample over a shaped, lossy link is not a measurement of
         * the chain: TCP loss recovery (RTO, with exponential backoff on a lost
         * retransmit) and shared-runner CPU steal are STRICTLY ADDITIVE, so a
         * sample is `structural cost + non-negative noise`. Nothing makes a
         * reconcile finish faster than its structure allows. That makes
         * [minElapsedMs] the natural estimator of the structural cost, and the
         * single-sample assertion this test used to carry a coin flip: on the
         * scheduled run that filed this issue it read `elapsedMs=12545` against a
         * hard 12 000 ms bound while the very next measurement in the SAME JVM,
         * container and shaper read 6 496 ms.
         */
        private const val CHAIN_SAMPLES = 5

        /**
         * Samples for the slow-host-CLI arm. Each one carries a real 4 s host
         * probe, so it is the most expensive arm to repeat; 3 is enough for a
         * minimum that is not a single unlucky draw.
         */
        private const val SLOW_CLI_SAMPLES = 3

        /**
         * The measured budget for the un-amplified chain, in milliseconds.
         *
         * Recorded distribution of `listSessionsWithFolder` over this fixture
         * (18 sessions, 3 watched roots, ~400 ms RTT, 5 % loss), warm lease:
         *
         * ```text
         * local, 25 consecutive samples : min 5787  p50 6854  max 8273
         * local, 6 consecutive samples  : min 6195  p50 6845  max 7658
         * GitHub-hosted runner          : 6353, 6496, 7309, 7441   (un-amplified)
         * GitHub-hosted runner          : 12545                    (amplified, see below)
         * ```
         *
         * The base cost is RTT-bound, not CPU-bound, which is why the hosted
         * runner and this workstation agree to within ~10 % despite very different
         * machines. 9 000 ms sits ~9 % above the slowest un-amplified sample ever
         * recorded on either machine and ~30 % above the median, and it is applied
         * to the MINIMUM of [CHAIN_SAMPLES], which lands near the median.
         *
         * It is deliberately TIGHTER than the 12 000 ms production bound: this
         * assertion's job is to catch a STRUCTURAL regression (#1876's pre-fix
         * serial chain measured 11.2-14.1 s here), and a bound that only trips at
         * 12 s cannot see a 1.5x re-serialisation. `mutationADoubledChainIsRejected`
         * pins that the budget still fails closed.
         *
         * The 12 545 ms outlier is not ordinary runner noise. Forcing a required
         * landing exec to lose its race against the 3.5 s per-exec bound (see
         * [aTransientlySlowRequiredExecCostsNoFreshDialAndKeepsTheTree]) moves the
         * chain into a separate 12.3-13.5 s band with no overlap against the
         * 5.8-8.5 s un-amplified band — and 12 545 ms sits in the first one, while
         * the very next measurement on the same commit, JVM and shaper read
         * 6 496 ms. So the outlier is a distinct MODE, not a tail, which is
         * precisely why one sample is not a measurement and the minimum of
         * [CHAIN_SAMPLES] is.
         */
        private const val CHAIN_BUDGET_MS = 9_000L

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
            installOneShotSlowListSessionsShim(container)
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

        /**
         * Issue #2422 (G10 — "add the fixture that reproduces it"): wrap the
         * fixture's `tmux` so the NEXT `list-sessions` — and only that one —
         * sleeps, deterministically pushing the REQUIRED landing batch past
         * [SshFolderListGateway.EXEC_READ_TIMEOUT_MS].
         *
         * That is the exact non-happy host state the flake needs and no happy
         * fixture can enter: on the shaped mobile link 1-3 execs per reconcile
         * already run into their 3.5 s bound, and which one loses is luck. The
         * required batch losing is the expensive case, so it has to be forced
         * rather than waited for.
         *
         * The delay is armed by writing [TMUX_ONCE_DELAY_FLAG] and is consumed by
         * the FIRST matching invocation, so a single reconcile pays it once. The
         * flag lives in the user's home rather than the sticky `/tmp` so the shim
         * (running as `testuser`) can delete it.
         *
         * Scoped to THIS test's own container — the shared `agent-bin/tmux`
         * fault-injection shim and every sibling suite are untouched.
         */
        private fun installOneShotSlowListSessionsShim(container: GenericContainer<*>) {
            val shim = """
                set -e
                test -x $TMUX_SHIM_PATH
                mv $TMUX_SHIM_PATH $TMUX_DELEGATE_PATH
                cat > $TMUX_SHIM_PATH <<'SHIM'
                #!/bin/sh
                if [ -f "$TMUX_ONCE_DELAY_FLAG" ]; then
                  case " ${'$'}* " in
                    *@ps_agent_state_updated_at*)
                      ps2422_delay="${'$'}(cat "$TMUX_ONCE_DELAY_FLAG" 2>/dev/null || echo 0)"
                      rm -f "$TMUX_ONCE_DELAY_FLAG"
                      sleep "${'$'}ps2422_delay"
                      ;;
                  esac
                fi
                exec $TMUX_DELEGATE_PATH "${'$'}@"
                SHIM
                sed -i 's/^                //' $TMUX_SHIM_PATH
                chmod +x $TMUX_SHIM_PATH
            """.trimIndent()
            val result = container.execInContainer("sh", "-c", shim)
            check(result.exitCode == 0) {
                "Failed to install the one-shot slow list-sessions shim: " +
                    "${result.stdout}${result.stderr}"
            }
            // The wrapper is only useful if it sits on the path the PRODUCTION
            // command actually resolves. The reconcile runs `/bin/sh -lc 'PATH=…;
            // tmux …'` as `testuser`, so resolve `tmux` exactly that way and
            // require it to BE the wrapper — the first draft of this fixture
            // wrapped /usr/bin/tmux, which the fixture image does not even ship,
            // so the injection silently did nothing and the arm passed vacuously.
            val resolved = container.execInContainer(
                "su", "testuser", "-c",
                "sh -lc 'PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$PATH\"; command -v tmux'",
            )
            check(resolved.exitCode == 0 && resolved.stdout.trim() == TMUX_SHIM_PATH) {
                "the production PATH must resolve tmux to the issue #2422 wrapper, got " +
                    "'${resolved.stdout.trim()}' (rc=${resolved.exitCode} ${resolved.stderr})"
            }
            val verify = container.execInContainer("su", "testuser", "-c", "tmux list-sessions")
            check(verify.exitCode == 0 && verify.stdout.contains("s1")) {
                "one-shot tmux shim did not preserve the fixture tmux: " +
                    "rc=${verify.exitCode} out=${verify.stdout} err=${verify.stderr}"
            }
        }

        /**
         * Arm the one-shot required-exec over-run. [seconds] must exceed
         * [SshFolderListGateway.EXEC_READ_TIMEOUT_MS] so the required batch is
         * abandoned rather than merely slow.
         */
        private fun armOneShotSlowListSessions(seconds: String) {
            val result = agents!!.execInContainer(
                "su", "testuser", "-c",
                "printf '%s' '$seconds' > $TMUX_ONCE_DELAY_FLAG",
            )
            check(result.exitCode == 0) {
                "Failed to arm the one-shot slow list-sessions: ${result.stderr}"
            }
        }

        /**
         * Disarm, so an armed-but-unconsumed delay can never leak into a sibling
         * test in this class (JUnit method order is not guaranteed, and every
         * other arm measures wall clock).
         */
        private fun disarmSlowListSessions() {
            agents!!.execInContainer("su", "testuser", "-c", "rm -f $TMUX_ONCE_DELAY_FLAG")
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

    private fun newGateway(
        leaseManager: SshLeaseManager,
        execReadTimeoutMs: Long = SshFolderListGateway.EXEC_READ_TIMEOUT_MS,
    ): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = leaseManager,
            sessionListParser = HostTmuxSessionListParser(),
            execReadTimeoutMs = execReadTimeoutMs,
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
     * Measure [count] consecutive reconciles over ONE warm lease — issue #2422.
     *
     * This mirrors production: the folder screen POLLS over a lease that stays
     * warm, so consecutive measurements are the real unit of observation, and a
     * single one is a sample from a heavy-tailed distribution rather than "the"
     * chain duration (see [CHAIN_SAMPLES]).
     *
     * [beforeEachSample] runs on the fixture host between measurements, which is
     * how the one-shot required-exec over-run is re-armed per sample.
     * [reconcilesPerSample] > 1 measures that many BACK-TO-BACK reconciles as a
     * single sample — the deliberately-slowed chain the mutation proof feeds to
     * the same evaluator.
     */
    private fun measureWarmReconcileSamples(
        count: Int,
        reconcilesPerSample: Int = 1,
        beforeEachSample: () -> Unit = {},
    ): List<WarmReconcile> {
        val leaseManager = SshLeaseManager(
            connector = SshLeaseConnector { target -> DefaultSshLeaseConnector().connect(target) },
        )
        return leaseManager.use {
            runBlocking {
                val gateway = newGateway(leaseManager)
                val host = shapedHost()
                suspend fun reconcile(): FolderListResult = gateway.listSessionsWithFolder(
                    host = host,
                    keyPath = privateKeyFile.absolutePath,
                    passphrase = null,
                    watchedRoots = watchedRoots(),
                )
                // Warm-up: dials the transport + primes the fixture's page cache.
                // Untimed by design — the cold dial is NOT inside the reconcile
                // window in production either.
                reconcile()
                (1..count).map {
                    beforeEachSample()
                    val loginsBefore = sshLoginCount()
                    val startedAt = System.nanoTime()
                    var result = reconcile()
                    repeat(reconcilesPerSample - 1) { result = reconcile() }
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                    WarmReconcile(
                        elapsedMs = elapsedMs,
                        result = result,
                        extraSshLogins = sshLoginCount() - loginsBefore,
                    )
                }
            }
        }
    }

    private fun List<WarmReconcile>.minElapsedMs(): Long = minOf { it.elapsedMs }

    private fun List<WarmReconcile>.medianElapsedMs(): Long =
        map { it.elapsedMs }.sorted()[size / 2]

    private fun List<WarmReconcile>.report(label: String): String =
        "$label profile=${DELAY_MS}ms+-${JITTER_MS}ms/loss=$LOSS_RATE " +
            "(rtt~${2 * DELAY_MS}ms) roots=${WATCHED_ROOT_PATHS.size} " +
            "samples=${map { it.elapsedMs }} logins=${map { it.extraSshLogins }} " +
            "minMs=${minElapsedMs()} medianMs=${medianElapsedMs()} " +
            "budget=$CHAIN_BUDGET_MS bound=${FolderListViewModel.RECONCILE_TIMEOUT_MS}"

    /**
     * THE assertion, shared by the real measurement and the mutation proof so the
     * mutation cannot drift away from what production is judged by.
     *
     * Three things must hold, and each fails closed:
     *  1. EVERY sample returns a real tree — a `ConnectFailed`/`Failed` reconcile
     *     is the user-visible defect itself, and no statistic tolerates one.
     *  2. The MINIMUM sample fits [CHAIN_BUDGET_MS] — the structural-cost estimate
     *     (noise on this link is strictly additive, see [CHAIN_SAMPLES]).
     *  3. The MEDIAN fits the PRODUCTION [FolderListViewModel.RECONCILE_TIMEOUT_MS]
     *     — the user-visible bound whose breach becomes the "Couldn't refresh the
     *     project tree" panel, kept in the assertion set rather than replaced by
     *     the tighter internal budget.
     */
    private fun assertChainSamplesFitTheBudget(samples: List<WarmReconcile>, label: String) {
        val detail = samples.report(label)
        samples.forEachIndexed { index, sample ->
            assertTrue(
                "sample $index must return a real tree, got ${sample.result}; $detail",
                sample.result is FolderListResult.Sessions,
            )
        }
        assertTrue(
            "Issue #1876/#2422: the folder-tree reconcile's structural cost " +
                "(min of ${samples.size} samples) is ${samples.minElapsedMs()}ms over a " +
                "~${2 * DELAY_MS}ms-RTT / $LOSS_RATE-loss link, past the measured " +
                "${CHAIN_BUDGET_MS}ms budget. On the device a chain this serial is the " +
                "ConnectError panel whose Retry re-runs the same chain. $detail",
            samples.minElapsedMs() < CHAIN_BUDGET_MS,
        )
        assertTrue(
            "Issue #1876: the median reconcile is ${samples.medianElapsedMs()}ms against " +
                "the production ${FolderListViewModel.RECONCILE_TIMEOUT_MS}ms " +
                "RECONCILE_TIMEOUT_MS. $detail",
            samples.medianElapsedMs() < FolderListViewModel.RECONCILE_TIMEOUT_MS,
        )
    }

    /**
     * THE reproduction. RED on the pre-fix serial chain, GREEN with the fix.
     *
     * Issue #2422 replaced the single wall-clock sample with [CHAIN_SAMPLES]
     * measurements judged by [assertChainSamplesFitTheBudget]: the structural
     * cost (the minimum) against the measured [CHAIN_BUDGET_MS], and the median
     * against the PRODUCTION [FolderListViewModel.RECONCILE_TIMEOUT_MS] —
     * exceeding which is exactly what turns into
     * `FolderReconcileTimeoutException` -> `ConnectFailed` -> the "Couldn't
     * refresh the project tree — tap to retry" panel, whose Retry re-runs the
     * identical chain under the identical bound and therefore fails forever on a
     * stable mobile link.
     */
    @Test(timeout = 600_000)
    fun reconcileChainFitsTheProductionReconcileBoundOnAMobileLink() {
        val samples = measureWarmReconcileSamples(CHAIN_SAMPLES)
        println(samples.report("ISSUE1876_RECONCILE_CHAIN"))
        assertChainSamplesFitTheBudget(samples, "ISSUE1876_RECONCILE_CHAIN")
    }

    /**
     * Issue #2422 — the mutation proof that [assertChainSamplesFitTheBudget] is
     * not vacuous.
     *
     * A budget applied to the MINIMUM of N samples is only worth having if a
     * genuinely slower chain still fails it. The mutation is measured on the SAME
     * real transport rather than modelled: each sample times TWO back-to-back
     * reconciles, i.e. a chain with twice the serial round-trip depth — precisely
     * the #1876 regression shape (its pre-fix serial chain measured 11.2-14.1 s
     * here) and precisely what a future re-serialisation would look like.
     *
     * The same evaluator the real assertion uses must REJECT those samples.
     */
    @Test(timeout = 600_000)
    fun mutationADoubledChainIsRejectedByTheSameBudget() {
        val doubled = measureWarmReconcileSamples(count = 3, reconcilesPerSample = 2)
        println(doubled.report("ISSUE2422_MUTATION_DOUBLED_CHAIN"))
        val verdict = runCatching {
            assertChainSamplesFitTheBudget(doubled, "ISSUE2422_MUTATION_DOUBLED_CHAIN")
        }
        val failure = verdict.exceptionOrNull()
        assertTrue(
            "a chain with twice the serial depth must FAIL the budget, otherwise the " +
                "green in reconcileChainFitsTheProductionReconcileBoundOnAMobileLink " +
                "proves nothing; got $failure; " +
                doubled.report("ISSUE2422_MUTATION_DOUBLED_CHAIN"),
            failure is AssertionError,
        )
        assertTrue(
            "it must be the STRUCTURAL-COST budget that rejects the doubled chain, not " +
                "an incidental failure of one of the other assertions — otherwise the " +
                "mutation proves the wrong thing (G6); got ${failure?.message}",
            failure?.message.orEmpty().contains("past the measured ${CHAIN_BUDGET_MS}ms budget"),
        )
    }

    /**
     * Issue #2422 — a transiently slow REQUIRED landing exec must not cost a
     * fresh SSH dial.
     *
     * This is the real-transport half of the class regression, and the direct
     * reproduction of the scheduled-CI failure this issue was filed for
     * (`ISSUE1876_RECONCILE_CHAIN … elapsedMs=12545 bound=12000`, a run that
     * still returned a COMPLETE tree). At ~400 ms RTT with 5 % loss, 1-3 execs
     * per reconcile already run into their 3.5 s bound; every one of them is
     * fail-soft EXCEPT the required landing batch, whose over-run used to be
     * classified as a poisoned transport — evicting the warm lease, paying a
     * brand-new TCP+SSH handshake and re-running the whole chain. Measured on
     * this fixture with the required exec forced to lose, that cost 11.9-16.9 s
     * against the 12 s bound with 1-2 extra sshd logins.
     *
     * [armOneShotSlowListSessions] forces exactly that loss per sample, so the
     * load-bearing assertion is the SERVER-side login delta — a binary,
     * timing-free oracle read from sshd itself, not from an app seam.
     *
     * The injection is also PROVED to have landed rather than assumed: every
     * sample must carry the production `folder_list_required_exec_retry`
     * breadcrumb. Without that check the arm passes vacuously the moment the
     * fixture stops reaching the required exec — which is exactly what the first
     * draft of this fixture did (it wrapped a `/usr/bin/tmux` the image does not
     * ship, so nothing was ever slowed and the samples were indistinguishable
     * from an un-injected run).
     */
    @Test(timeout = 600_000)
    fun aTransientlySlowRequiredExecCostsNoFreshDialAndKeepsTheTree() {
        val retries = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val perSampleRetries = java.util.concurrent.atomic.AtomicInteger(0)
        DiagnosticEvents.install(
            object : DiagnosticEventSink {
                override fun record(category: String, event: String, fields: Map<String, Any?>) {
                    if (fields["stage"] == FolderListRequiredExec.TRAIL_STAGE_REQUIRED_EXEC_RETRY) {
                        perSampleRetries.incrementAndGet()
                    }
                }
            },
        )
        val samples = try {
            measureWarmReconcileSamples(count = 3) {
                retries += perSampleRetries.getAndSet(0)
                armOneShotSlowListSessions(SLOW_LIST_SESSIONS_SECONDS)
            }
        } finally {
            retries += perSampleRetries.get()
            DiagnosticEvents.install(DiagnosticEventSink.Noop)
            disarmSlowListSessions()
        }
        // `retries[i]` is the count observed BEFORE sample i was armed, i.e. the
        // retries of sample i-1; the trailing entry closes the last sample.
        val retriesPerSample = retries.drop(1)
        println(
            samples.report("ISSUE2422_SLOW_REQUIRED_EXEC") +
                " slowListSessions=${SLOW_LIST_SESSIONS_SECONDS}s " +
                "requiredExecRetries=$retriesPerSample",
        )
        retriesPerSample.forEachIndexed { index, count ->
            assertTrue(
                "sample $index: the fixture must actually have pushed the REQUIRED landing " +
                    "batch past its ${SshFolderListGateway.EXEC_READ_TIMEOUT_MS}ms bound — no " +
                    "`${FolderListRequiredExec.TRAIL_STAGE_REQUIRED_EXEC_RETRY}` breadcrumb means " +
                    "nothing was slowed and this arm proves nothing. " +
                    "requiredExecRetries=$retriesPerSample",
                count >= 1,
            )
        }
        samples.forEachIndexed { index, sample ->
            assertEquals(
                "sample $index: a required landing exec that over-ran its " +
                    "${SshFolderListGateway.EXEC_READ_TIMEOUT_MS}ms bound on a link that was " +
                    "ALIVE the whole time must be retried on the SAME warm lease. Evicting it " +
                    "and re-dialling re-runs the entire reconcile and is what pushed a healthy " +
                    "6.5s chain to 12.5s — the #1870/#1876 'Couldn't refresh the project tree' " +
                    "panel on mobile. ${samples.report("ISSUE2422_SLOW_REQUIRED_EXEC")}",
                0,
                sample.extraSshLogins,
            )
            val sessions = sample.result as? FolderListResult.Sessions
                ?: error("sample $index: a slow required exec must still land a tree, got ${sample.result}")
            val names = sessions.rows.map { it.sessionName }.toSet()
            assertTrue(
                "sample $index: the retried required batch must still enumerate every " +
                    "seeded session; names=$names seeded=$SEEDED_SESSIONS",
                names.containsAll(SEEDED_SESSIONS.toSet()),
            )
        }
        // Deliberately NO wall-clock assertion here, and the measurement says why:
        // a reconcile that abandons its required batch measures 12.3-13.2 s with
        // this fix and 12.5-13.5 s without it, because the wasted
        // EXEC_READ_TIMEOUT_MS plus the retried batch plus the remaining kind and
        // decoration work does not fit 12 s either way. Removing the re-dial is
        // worth ~1-2 s and one SSH handshake; it does NOT make an over-run fit the
        // user-visible bound. That residue is #1876's unfinished scope item —
        // render the tree from cache and reconcile in the background so no
        // user-visible action is gated on the chain at all — and belongs to its own
        // issue, not to a wall-clock assertion here that would only encode the
        // current cost as if it were acceptable.
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
        val names = sessions.rows.map { it.sessionName }.toSet()
        assertTrue(
            "every seeded tmux session must still be enumerated; extras from " +
                "tmuxctl/aplexer are allowed. names=$names seeded=$SEEDED_SESSIONS",
            names.containsAll(SEEDED_SESSIONS.toSet()),
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
            "the active-pane cwd merge must still run (seeded rows carry a cwd)",
            sessions.rows.filter { it.sessionName in SEEDED_SESSIONS }.all { it.cwd != null },
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
     *
     * Issue #2422: the timing half is measured over [SLOW_CLI_SAMPLES] samples
     * for the same reason as [reconcileChainFitsTheProductionReconcileBoundOnAMobileLink]
     * — this arm carried the least headroom of the three (10 467 ms against 12 000
     * ms locally). The per-sample login and content assertions stay per-sample:
     * they are binary, not timing-derived, and no sample may violate them.
     */
    @Test(timeout = 600_000)
    fun aSlowButAliveHostCliDoesNotCostAFreshDialOrTheWholeTree() {
        setSlowLogsDelay(SLOW_LOGS_SECONDS)
        try {
            val samples = measureWarmReconcileSamples(SLOW_CLI_SAMPLES)
            println(
                samples.report("ISSUE1876_SLOW_HOST_CLI") +
                    " slowLogs=${SLOW_LOGS_SECONDS}s",
            )
            samples.forEachIndexed { index, run ->
                assertEquals(
                    "sample $index: a slow-but-alive host CLI must NOT cost a fresh SSH " +
                        "dial — the warm lease was still connected, and re-dialling is what " +
                        "turned 'slow' into 'cannot connect' (#1870)",
                    0,
                    run.extraSshLogins,
                )
                val sessions = run.result as? FolderListResult.Sessions
                    ?: error("a slow optional probe must not fail the reconcile, got ${run.result}")
                val names = sessions.rows.map { it.sessionName }.toSet()
                assertTrue(
                    "sample $index: the tree must still contain every seeded session despite " +
                        "the slow probe; names=$names seeded=$SEEDED_SESSIONS",
                    names.containsAll(SEEDED_SESSIONS.toSet()),
                )
            }
            assertTrue(
                "the reconcile must still fit ${FolderListViewModel.RECONCILE_TIMEOUT_MS}ms " +
                    "with one ${SLOW_LOGS_SECONDS}s host-CLI probe in it. " +
                    samples.report("ISSUE1876_SLOW_HOST_CLI"),
                samples.minElapsedMs() < FolderListViewModel.RECONCILE_TIMEOUT_MS,
            )
        } finally {
            setSlowLogsDelay("0")
        }
    }
}
