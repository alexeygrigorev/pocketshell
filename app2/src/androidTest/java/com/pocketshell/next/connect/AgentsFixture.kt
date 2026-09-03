package com.pocketshell.next.connect

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * Where the connected tests find a real sshd.
 *
 * The emulator reaches the host machine's loopback at `10.0.2.2`, and the
 * `agents` / `sshd` compose service publishes container port 22 on host port
 * 2222 (`tests/docker/docker-compose.yml`, see `docs/testing.md`). The port is
 * overridable through the `agentsPort` instrumentation argument, which
 * `scripts/connected-test.sh --pool` sets to that lane's own claimed fixture —
 * so a single-lane run is the historical `10.0.2.2:2222` and a pooled run
 * targets its own container, with no second code path.
 *
 * A runner MUST bring the fixture up first; `scripts/connected-test.sh` does
 * not start it (it only claims the emulator and, with `--pool`, an agents port):
 *
 * ```
 * docker compose -f tests/docker/docker-compose.yml up -d --build agents
 * scripts/connected-test.sh --module app2 --suffix iapp2
 * ```
 *
 * Note `--module app2`, not a trailing `:app2:connectedDebugAndroidTest`: the
 * wrapper OWNS the task name (it appends `:connectedDebugAndroidTest` itself),
 * so passing the task as a gradle argument would also run the default `:app`
 * task, which no longer exists on this branch.
 */
object AgentsFixture {
    const val DEFAULT_HOST: String = "10.0.2.2"
    const val DEFAULT_PORT: Int = 2222
    const val USER: String = "testuser"

    /** The fixture private key, packaged as an androidTest asset (`tests/docker/test_key`). */
    const val KEY_ASSET: String = "test_key"

    val host: String
        get() = InstrumentationRegistry.getArguments()
            .getString("agentsHost")
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_HOST

    val port: Int
        get() = InstrumentationRegistry.getArguments()
            .getString("agentsPort")
            ?.trim()
            ?.toIntOrNull()
            ?: DEFAULT_PORT

    /** The fixture key's PEM text, read from the androidTest APK's assets. */
    fun privateKeyPem(): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(KEY_ASSET)
            .bufferedReader()
            .use { it.readText() }

    /**
     * Writes [privateKeyPem] into the app-under-test's private storage and
     * returns the path, which is what an `ssh_keys` row stores. The app reads
     * the file through [RoomAuthSecretResolver] exactly as it would a key the
     * user imported.
     */
    fun installPrivateKey(fileName: String = "j01_fixture_key"): String {
        val target = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            fileName,
        )
        target.writeText(privateKeyPem())
        return target.absolutePath
    }

    /**
     * Blocks until the fixture answers a TCP+KEX handshake and returns the
     * `SHA256:...` fingerprint of the host key it presented.
     *
     * Doubles as the fixture-readiness gate: a container that is up but not yet
     * serving fails here with a clear message instead of surfacing later as an
     * unexplained "Trust sheet never appeared".
     *
     * The fingerprint is the ORACLE for the journey — the prompt must show the
     * key this server actually presents, not a placeholder or a hard-coded
     * string, and the trust store must persist that same value.
     */
    fun probeHostKeyFingerprint(timeoutMs: Long = 90_000): String {
        ensureBouncyCastle()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val failures = mutableListOf<String>()
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempt += 1
            val captured = AtomicReference<String?>(null)
            val client = SSHClient(DefaultConfig())
            try {
                client.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(hostname: String, p: Int, key: PublicKey): Boolean {
                        captured.set(sha256Fingerprint(key))
                        return true
                    }

                    override fun findExistingAlgorithms(hostname: String, p: Int): List<String> =
                        emptyList()
                })
                client.connectTimeout = 10_000
                client.timeout = 10_000
                client.connect(host, port)
                captured.get()?.let { return it }
                failures += "attempt $attempt: connected but no host key was presented"
            } catch (failure: Throwable) {
                failures += "attempt $attempt: $failure"
            } finally {
                runCatching { client.disconnect() }
            }
            SystemClock.sleep(1_000)
        }
        error(
            "SSH fixture at $host:$port never presented a host key within ${timeoutMs}ms. " +
                "Bring it up with `docker compose -f tests/docker/docker-compose.yml up -d " +
                "--build agents` before running this suite. An `EPERM`/`EACCES` below " +
                "means the app under test is missing android.permission.INTERNET, not " +
                "that the fixture is down. Attempts:\n" +
                failures.takeLast(5).joinToString("\n"),
        )
    }

    /**
     * OpenSSH-style `SHA256:<base64-no-padding>` of a host key's wire encoding.
     *
     * Deliberately recomputed here rather than reused from
     * `RealHostConnectionFactory` (whose helper is `internal` to core-transport):
     * an oracle that calls the same function as the code under test proves the
     * function is self-consistent, not that it is right.
     */
    fun sha256Fingerprint(key: PublicKey): String {
        val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(wire)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun ensureBouncyCastle() {
        synchronized(Security::class.java) {
            val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (provider?.javaClass?.name == BouncyCastleProvider::class.java.name) return
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}

/**
 * Runs a per-test seed BEFORE the activity rule launches MainActivity.
 *
 * `createAndroidComposeRule<MainActivity>()` launches in its own `before()`
 * phase, which happens before any `@Before` method — so a host row written in
 * `@Before` would arrive after the host list already read an empty table. Wired
 * as the OUTER link of a `RuleChain`, this rule seeds first and the activity
 * launches into a populated database. (Same shape as the pre-rewrite app
 * module's `SeedBeforeLaunchRule`.)
 */
class SeedBeforeLaunchRule(private val seed: suspend (Description) -> Unit) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                runBlocking { seed(description) }
                base.evaluate()
            }
        }
}

/** Screenshot capture for journey evidence. Writes into the app's external files dir. */
object JourneyScreenshots {
    fun capture(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "j01-connect-trust",
        )
        check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "could not write ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("J01_SCREENSHOT ${file.absolutePath}")
        return file
    }
}
