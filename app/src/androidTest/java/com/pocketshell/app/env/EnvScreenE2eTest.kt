package com.pocketshell.app.env

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.createBitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * Compose-level connected E2E test for [EnvScreen] — issue #264.
 *
 * Drives the screen against an in-memory DAO + a fake [EnvGateway] (so
 * it does not need the Docker `agents` host to render — the env CLI wire
 * contract is proven separately against the real `pocketshell env` CLI)
 * and asserts:
 *
 *  1. The screen mounts and the masked key list renders (`••••`, never
 *     the plain value).
 *  2. "Reveal" fetches the plain value via the gateway's `env get`
 *     surface and shows it.
 *  3. The "Add key" dialog accepts a write-only value field and the
 *     value reaches the gateway via the structured updates map (D24: the
 *     gateway alone owns the stdin transport).
 *  4. The "Copy from another folder" sheet lists discovered source
 *     folders and multi-selects keys.
 *  5. logcat captured across the whole flow never contains the secret
 *     value typed into the write-only field — the D24 "no plaintext in
 *     logcat" invariant.
 *
 * Viewport + full-device screenshots are written under
 * `additional_test_output/issue264-env/` for the reviewer's
 * artifact-driven check.
 */
@RunWith(AndroidJUnit4::class)
class EnvScreenE2eTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private val hostId: Long = 11L
    private val secret = "sk-ENVSCREEN-SECRET-264"

    @Before
    fun openDatabase(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue264-key", privateKeyPath = "/tmp/issue264"),
        )
        db.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "issue264-host",
                hostname = "h.example",
                port = 22,
                username = "u",
                keyId = keyId,
            ),
        )
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun envScreenListsRevealsAddsAndCopies() {
        clearLogcat()
        val gateway = FakeEnvGateway(
            keys = listOf(
                EnvKeyRow("API_KEY", ".env", true),
                EnvKeyRow("EMPTY_VAL", ".env", false),
                EnvKeyRow("EXPORTED", ".envrc", true),
            ),
            getValues = mapOf("API_KEY" to secret),
            sourceKeys = listOf(EnvKeyRow("DB_URL", ".env", true), EnvKeyRow("TOKEN", ".env", true)),
        )
        val viewModel = EnvViewModel(gateway = gateway, hostDao = db.hostDao())

        compose.setContent {
            PocketShellTheme {
                EnvScreen(
                    hostId = hostId,
                    hostName = "issue264-host",
                    keyPath = "/tmp/issue264",
                    passphrase = null,
                    directory = "/home/u/code/pocketshell",
                    folderLabel = "pocketshell",
                    copySources = listOf(
                        EnvCopySourceFolder("/home/u/code/pocketshell", "pocketshell"),
                        EnvCopySourceFolder("/home/u/code/llm-zoomcamp", "llm-zoomcamp"),
                    ),
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        // --- Assertion 1: screen mounts + masked key list renders.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(envKeyRowTestTag("API_KEY")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ENV_SCREEN_TAG).assertExists()
        compose.onNodeWithText("issue264-host").assertExists()
        compose.onNodeWithTag(envKeyRowTestTag("API_KEY")).assertExists()
        compose.onNodeWithTag(envKeyRowTestTag("EXPORTED")).assertExists()
        // Masked by default — the value nodes show the mask, not the secret
        // (both API_KEY and EXPORTED have values, so two masked cells).
        assertTrue(
            "masked value cells should render",
            compose.onAllNodesWithText("••••••••").fetchSemanticsNodes().isNotEmpty(),
        )
        captureViewport("issue264-env-list-masked-viewport.png")

        // --- Assertion 2: Reveal shows the plain value.
        // Reveal now lives in the per-row kebab (#479 §4): open the overflow
        // menu, then tap Reveal.
        compose.onNodeWithTag(envKeyMenuTestTag("API_KEY")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(envKeyRevealTestTag("API_KEY")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(envKeyRevealTestTag("API_KEY")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(secret).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(secret).assertExists()
        captureViewport("issue264-env-revealed-viewport.png")

        // --- Assertion 3: Add key dialog (write-only value field).
        compose.onNodeWithTag(ENV_ADD_FAB_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(ENV_ADD_KEY_FIELD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ENV_ADD_KEY_FIELD_TAG).performTextInput("NEW_SECRET")
        compose.onNodeWithTag(ENV_ADD_VALUE_FIELD_TAG).performTextInput("added-$secret")
        compose.onNodeWithTag(envFileTargetTestTag(EnvFileTarget.Envrc)).performClick()
        android.os.SystemClock.sleep(150)
        captureFullDevice("issue264-env-add-dialog-viewport.png")
        compose.onNodeWithTag(ENV_ADD_CONFIRM_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { gateway.lastSetUpdates != null }
        // The value reached the gateway via the structured updates map.
        assertEquals("added-$secret", gateway.lastSetUpdates?.get("NEW_SECRET"))
        assertEquals(EnvFileTarget.Envrc, gateway.lastSetFile)

        // --- Assertion 4: Copy-from-folder sheet.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(ENV_COPY_FROM_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ENV_COPY_FROM_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            // Only the OTHER folder is offered (current folder excluded).
            compose.onAllNodesWithTag(envCopySourceTestTag("/home/u/code/llm-zoomcamp"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Current folder must NOT be a copy source.
        compose.onAllNodesWithTag(envCopySourceTestTag("/home/u/code/pocketshell"))
            .fetchSemanticsNodes().also {
                assertTrue("current folder must not be offered as a copy source", it.isEmpty())
            }
        compose.onNodeWithTag(envCopySourceTestTag("/home/u/code/llm-zoomcamp")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(envCopyKeyTestTag("DB_URL")).fetchSemanticsNodes().isNotEmpty()
        }
        android.os.SystemClock.sleep(150)
        captureFullDevice("issue264-env-copy-sheet-viewport.png")
        compose.onNodeWithTag(envCopyKeyTestTag("DB_URL")).performClick()
        compose.onNodeWithTag(envCopyKeyTestTag("TOKEN")).performClick()
        compose.onNodeWithTag(ENV_COPY_CONFIRM_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { gateway.lastCopyKeys != null }
        assertEquals(listOf("DB_URL", "TOKEN"), gateway.lastCopyKeys)
        assertEquals("/home/u/code/llm-zoomcamp", gateway.lastCopySource)
        assertEquals("/home/u/code/pocketshell", gateway.lastCopyDest)

        // --- Assertion 5: logcat never carries the typed secret value.
        // (D24: write-only value field; nothing the app logs may leak it.)
        val logcat = dumpLogcat()
        assertFalse(
            "logcat must not contain the secret value typed into the write-only field",
            logcat.contains(secret),
        )
    }

    /**
     * #1092: the maintainer can now edit an existing key's value IN PLACE.
     * Drives the reported gap end-to-end on the real screen: open the per-row
     * kebab -> Edit -> the current value is fetched via the reveal/get path and
     * pre-loaded (proven by the Show toggle revealing the old secret, i.e. NOT
     * retyped blind) -> change it -> Save routes the new value through setKeys
     * (update-in-place). logcat is asserted to never carry either secret (D24).
     */
    @Test
    fun editExistingKeyInPlaceUpdatesValueViaSetKeys() {
        clearLogcat()
        val oldSecret = "sk-OLD-1092"
        val newSecret = "sk-NEW-1092"
        val gateway = FakeEnvGateway(
            keys = listOf(EnvKeyRow("API_KEY", ".env", true)),
            getValues = mapOf("API_KEY" to oldSecret),
            sourceKeys = emptyList(),
        )
        val viewModel = EnvViewModel(gateway = gateway, hostDao = db.hostDao())

        compose.setContent {
            PocketShellTheme {
                EnvScreen(
                    hostId = hostId,
                    hostName = "issue1092-host",
                    keyPath = "/tmp/issue1092",
                    passphrase = null,
                    directory = "/home/u/code/pocketshell",
                    folderLabel = "pocketshell",
                    copySources = emptyList(),
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(envKeyRowTestTag("API_KEY")).fetchSemanticsNodes().isNotEmpty()
        }
        // Open the kebab and tap Edit.
        compose.onNodeWithTag(envKeyMenuTestTag("API_KEY")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(envKeyEditTestTag("API_KEY")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(envKeyEditTestTag("API_KEY")).performClick()

        // The editor opens and pre-loads the current value (fetched via get).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(ENV_EDIT_VALUE_FIELD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // Reveal the field — the OLD secret is already there (NOT blank), which
        // is the whole point: edit, don't retype the secret from scratch.
        compose.onNodeWithTag(ENV_EDIT_TOGGLE_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(oldSecret).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(oldSecret).assertExists()
        captureFullDevice("issue1092-env-edit-dialog-viewport.png")

        // Change the value and Save.
        compose.onNodeWithTag(ENV_EDIT_VALUE_FIELD_TAG).performTextClearance()
        compose.onNodeWithTag(ENV_EDIT_VALUE_FIELD_TAG).performTextInput(newSecret)
        compose.onNodeWithTag(ENV_EDIT_CONFIRM_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { gateway.lastSetUpdates != null }

        // The NEW value reached setKeys for the SAME key + its file.
        assertEquals(newSecret, gateway.lastSetUpdates?.get("API_KEY"))
        assertEquals(EnvFileTarget.Env, gateway.lastSetFile)

        // D24: neither secret may appear in anything the app logs.
        val logcat = dumpLogcat()
        assertFalse("logcat must not contain the old secret", logcat.contains(oldSecret))
        assertFalse("logcat must not contain the new secret", logcat.contains(newSecret))
    }

    /**
     * #1092: an empty folder (no .env yet) must surface a clear "Add key" CTA,
     * since adding the first key is how the .env file gets created. Proves the
     * CTA is reachable and starts the create-first-key flow.
     */
    @Test
    fun emptyFolderSurfacesAddKeyCtaThatCreatesFirstKey() {
        val gateway = FakeEnvGateway(
            keys = emptyList(),
            getValues = emptyMap(),
            sourceKeys = emptyList(),
        )
        val viewModel = EnvViewModel(gateway = gateway, hostDao = db.hostDao())

        compose.setContent {
            PocketShellTheme {
                EnvScreen(
                    hostId = hostId,
                    hostName = "issue1092-host",
                    keyPath = "/tmp/issue1092",
                    passphrase = null,
                    directory = "/home/u/code/fresh",
                    folderLabel = "fresh",
                    copySources = emptyList(),
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        // The empty-state CTA is present and reachable.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(ENV_EMPTY_ADD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ENV_EMPTY_ADD_TAG).assertExists()
        captureFullDevice("issue1092-env-empty-cta-viewport.png")

        // Tapping it opens the add dialog so the first key can be created.
        compose.onNodeWithTag(ENV_EMPTY_ADD_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(ENV_ADD_KEY_FIELD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ENV_ADD_KEY_FIELD_TAG).performTextInput("FIRST_KEY")
        compose.onNodeWithTag(ENV_ADD_VALUE_FIELD_TAG).performTextInput("first-value")
        compose.onNodeWithTag(ENV_ADD_CONFIRM_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { gateway.lastSetUpdates != null }
        // Creating the first key writes through setKeys (server creates the file).
        assertEquals("first-value", gateway.lastSetUpdates?.get("FIRST_KEY"))
    }

    private fun clearLogcat() {
        runCatching { ProcessBuilder("logcat", "-c").start().waitFor() }
    }

    private fun dumpLogcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d").redirectErrorStream(true).start()
        BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
    }.getOrDefault("")

    private fun outDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        // Prefer the external `additional_test_output/...` bucket the
        // artifact pipeline scans; fall back to the always-writable
        // internal files dir so the reviewer still has artifacts even on
        // an AVD where external storage is unavailable.
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(ctx)
            ?: ctx.filesDir
        return File(mediaRoot, "additional_test_output/issue264-env").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(outDir(), name)
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        android.util.Log.i("EnvScreenE2eTest", "captured ${file.absolutePath}")
    }

    private fun captureViewport(name: String) {
        val image = try {
            compose.onNodeWithTag(ENV_SCREEN_TAG).captureToImage()
        } catch (t: Throwable) {
            android.util.Log.w("EnvScreenE2eTest", "viewport capture failed for $name", t)
            return
        }
        val bitmap: Bitmap = createBitmap(image.width, image.height)
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val file = File(outDir(), name)
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        android.util.Log.i("EnvScreenE2eTest", "captured ${file.absolutePath}")
    }

    private fun assertEquals(expected: Any?, actual: Any?) =
        org.junit.Assert.assertEquals(expected, actual)
}

/**
 * Fake [EnvGateway] for the connected UI test. Records the structured
 * arguments the screen forwards so the test can assert the D24 contract
 * (value reaches the gateway via the updates map, never argv) without a
 * Docker round-trip.
 */
private class FakeEnvGateway(
    private val keys: List<EnvKeyRow>,
    private val getValues: Map<String, String>,
    private val sourceKeys: List<EnvKeyRow>,
) : EnvGateway {
    @Volatile var lastSetUpdates: Map<String, String>? = null
    @Volatile var lastSetFile: EnvFileTarget? = null
    @Volatile var lastCopyKeys: List<String>? = null
    @Volatile var lastCopySource: String? = null
    @Volatile var lastCopyDest: String? = null

    private val mutableKeys = keys.toMutableList()

    override suspend fun listKeys(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
    ): EnvListResult =
        if (directory == "/home/u/code/llm-zoomcamp") {
            EnvListResult.Keys(sourceKeys)
        } else {
            EnvListResult.Keys(mutableKeys.toList())
        }

    override suspend fun getValue(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
        key: String,
    ): EnvOpResult = EnvOpResult.Values(getValues.filterKeys { it == key })

    override suspend fun setKeys(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
        file: EnvFileTarget,
        updates: Map<String, String>,
    ): EnvOpResult {
        lastSetUpdates = updates
        lastSetFile = file
        updates.forEach { (k, _) -> mutableKeys.add(EnvKeyRow(k, file.fileName, true)) }
        return EnvOpResult.Success
    }

    override suspend fun copyKeys(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sourceDirectory: String,
        destinationDirectory: String,
        file: EnvFileTarget,
        keys: List<String>,
    ): EnvOpResult {
        lastCopyKeys = keys
        lastCopySource = sourceDirectory
        lastCopyDest = destinationDirectory
        return EnvOpResult.Success
    }
}
