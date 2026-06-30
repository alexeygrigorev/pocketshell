package com.pocketshell.app.env

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnvViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val host = HostEntity(
        id = 1L,
        name = "Lab",
        hostname = "lab.local",
        port = 22,
        username = "alexey",
        keyId = 9L,
    )

    @Test
    fun bindLoadsMaskedKeyList() = runTest {
        val gateway = FakeEnvGateway(
            listResult = EnvListResult.Keys(
                listOf(
                    EnvKeyRow("API_KEY", ".env", true),
                    EnvKeyRow("EMPTY", ".env", false),
                ),
            ),
        )
        val vm = EnvViewModel(gateway, FakeHostDao(host))

        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        val list = vm.state.value.list as EnvListState.Ready
        assertEquals(listOf("API_KEY", "EMPTY"), list.keys.map { it.key })
        // No values are present until the user explicitly reveals.
        assertTrue(list.keys.all { it.revealedValue == null })
    }

    @Test
    fun setKeyPassesValueToGatewayAndRefreshes() = runTest {
        val gateway = FakeEnvGateway(listResult = EnvListResult.Keys(emptyList()))
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        vm.setKey("API_KEY", "sk-secret", EnvFileTarget.Env)
        advanceUntilIdle()

        // The value reached the gateway via the structured updates map
        // (the gateway is responsible for the stdin transport — D24).
        assertEquals(mapOf("API_KEY" to "sk-secret"), gateway.lastSetUpdates)
        assertEquals(EnvFileTarget.Env, gateway.lastSetFile)
        // A refresh (list) ran after the successful set.
        assertTrue(gateway.listCalls >= 2)
    }

    @Test
    fun setKeyRejectsInvalidIdentifierWithoutCallingGateway() = runTest {
        val gateway = FakeEnvGateway(listResult = EnvListResult.Keys(emptyList()))
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        vm.setKey("bad-key", "v", EnvFileTarget.Env)
        advanceUntilIdle()

        assertNull(gateway.lastSetUpdates)
        assertTrue(vm.state.value.transientMessage!!.contains("shell identifier"))
    }

    @Test
    fun revealKeyFetchesPlainValue() = runTest {
        val gateway = FakeEnvGateway(
            listResult = EnvListResult.Keys(listOf(EnvKeyRow("API_KEY", ".env", true))),
            getResult = EnvOpResult.Values(mapOf("API_KEY" to "sk-secret-123")),
        )
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        vm.revealKey("API_KEY")
        advanceUntilIdle()

        val row = (vm.state.value.list as EnvListState.Ready).keys.first { it.key == "API_KEY" }
        assertEquals("sk-secret-123", row.revealedValue)
    }

    @Test
    fun hideKeyClearsRevealedValue() = runTest {
        val gateway = FakeEnvGateway(
            listResult = EnvListResult.Keys(listOf(EnvKeyRow("API_KEY", ".env", true))),
            getResult = EnvOpResult.Values(mapOf("API_KEY" to "sk-secret-123")),
        )
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()
        vm.revealKey("API_KEY")
        advanceUntilIdle()

        vm.hideKey("API_KEY")

        val row = (vm.state.value.list as EnvListState.Ready).keys.first { it.key == "API_KEY" }
        assertNull(row.revealedValue)
    }

    @Test
    fun editingExistingKeyPreloadsValueThenSavesUpdateViaSetKeys() = runTest {
        // Reproduce-first (#1092 / D33): the maintainer can reveal + add but
        // CANNOT edit an existing key's value in place. This drives the
        // edit-in-place journey: tap Edit -> current value fetched via the
        // reveal/get path and pre-loaded -> change it -> Save routes the new
        // value back through setKeys (update-in-place). On the unfixed code
        // there is no editor entry point, so this fails to compile / run.
        val gateway = FakeEnvGateway(
            listResult = EnvListResult.Keys(listOf(EnvKeyRow("API_KEY", ".env", true))),
            getResult = EnvOpResult.Values(mapOf("API_KEY" to "old-secret")),
        )
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        // Open the in-place editor — the current value is fetched and shown.
        vm.beginEdit("API_KEY")
        advanceUntilIdle()
        val editor = vm.state.value.editor as EnvEditorState.Editing
        assertEquals("API_KEY", editor.key)
        assertEquals(EnvFileTarget.Env, editor.file)
        assertEquals("old-secret", editor.currentValue)

        // Edit the value and Save — it must reach setKeys for the same key.
        vm.saveEdit("new-secret")
        advanceUntilIdle()

        assertEquals(mapOf("API_KEY" to "new-secret"), gateway.lastSetUpdates)
        assertEquals(EnvFileTarget.Env, gateway.lastSetFile)
        // The editor closes and a refresh ran after the successful update.
        assertTrue(vm.state.value.editor is EnvEditorState.Hidden)
        assertTrue(gateway.listCalls >= 2)
    }

    @Test
    fun editingEnvrcKeyPreservesItsFileAndAllowsEmptyToBeFilled() = runTest {
        // Class coverage: a key that lives in .envrc edits back into .envrc
        // (not the default .env), and an empty key (has_value=false) opens
        // with a blank field and can be given a value.
        val gateway = FakeEnvGateway(
            listResult = EnvListResult.Keys(
                listOf(
                    EnvKeyRow("EXPORTED", ".envrc", true),
                    EnvKeyRow("EMPTY", ".envrc", false),
                ),
            ),
            getResult = EnvOpResult.Values(emptyMap()),
        )
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        vm.beginEdit("EMPTY")
        advanceUntilIdle()
        val editor = vm.state.value.editor as EnvEditorState.Editing
        assertEquals(EnvFileTarget.Envrc, editor.file)
        // No value came back for the empty key — the field opens blank.
        assertEquals("", editor.currentValue)

        vm.saveEdit("now-has-a-value")
        advanceUntilIdle()
        assertEquals(mapOf("EMPTY" to "now-has-a-value"), gateway.lastSetUpdates)
        assertEquals(EnvFileTarget.Envrc, gateway.lastSetFile)
    }

    @Test
    fun dismissEditorClosesWithoutCallingSetKeys() = runTest {
        val gateway = FakeEnvGateway(
            listResult = EnvListResult.Keys(listOf(EnvKeyRow("API_KEY", ".env", true))),
            getResult = EnvOpResult.Values(mapOf("API_KEY" to "old-secret")),
        )
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        vm.beginEdit("API_KEY")
        advanceUntilIdle()
        vm.dismissEditor()

        assertTrue(vm.state.value.editor is EnvEditorState.Hidden)
        assertNull(gateway.lastSetUpdates)
    }

    @Test
    fun duplicateFileKeyRowsGetUniqueLazyColumnItemKeys() = runTest {
        // Reproduce-first (crash regression from #1093): editing a key's value
        // in place transiently surfaces the old + edited entry for the SAME
        // (file, key), and a real .env may literally repeat a key. The env
        // LazyColumn keyed each item on "file:key", so two such rows collided
        // and Compose hard-crashed with
        //   IllegalArgumentException: Key '.env:API_KEY' was already used.
        // The load-bearing invariant: the item key the screen actually uses
        // (envRowItemKey) must be unique across the whole list.
        val gateway = FakeEnvGateway(
            listResult = EnvListResult.Keys(
                listOf(
                    EnvKeyRow("API_KEY", ".env", true),
                    EnvKeyRow("API_KEY", ".env", true),
                    EnvKeyRow("API_KEY", ".envrc", true),
                ),
            ),
        )
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        val rows = (vm.state.value.list as EnvListState.Ready).keys
        // All three rows survive (the duplicate is NOT silently dropped) …
        assertEquals(3, rows.size)
        // … and the keys the LazyColumn uses are all distinct (no crash).
        val itemKeys = rows.map { envRowItemKey(it) }
        assertEquals(
            "LazyColumn item keys must be unique to avoid the duplicate-key crash",
            itemKeys.size,
            itemKeys.toSet().size,
        )
    }

    @Test
    fun singleKeyKeepsBareFileKeyItemKey() = runTest {
        // The common no-duplicate case keeps the stable bare "file:key" id so
        // per-row state (reveal toggle, animations) survives a refresh.
        val gateway = FakeEnvGateway(
            listResult = EnvListResult.Keys(listOf(EnvKeyRow("API_KEY", ".env", true))),
        )
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        val row = (vm.state.value.list as EnvListState.Ready).keys.single()
        assertEquals(".env:API_KEY", envRowItemKey(row))
    }

    @Test
    fun copyKeysCallsGatewayAndRefreshes() = runTest {
        val gateway = FakeEnvGateway(listResult = EnvListResult.Keys(emptyList()))
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(
            1L, "/tmp/key", null, "/home/alexey/proj", "proj",
            listOf(EnvCopySourceFolder("/home/alexey/other", "other")),
        )
        advanceUntilIdle()

        vm.copyKeys("/home/alexey/other", listOf("API_KEY", "URL"), EnvFileTarget.Envrc)
        advanceUntilIdle()

        assertEquals("/home/alexey/other", gateway.lastCopySource)
        assertEquals("/home/alexey/proj", gateway.lastCopyDest)
        assertEquals(listOf("API_KEY", "URL"), gateway.lastCopyKeys)
        assertEquals(EnvFileTarget.Envrc, gateway.lastCopyFile)
    }

    @Test
    fun copySourcesExcludeCurrentFolder() = runTest {
        val gateway = FakeEnvGateway(listResult = EnvListResult.Keys(emptyList()))
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(
            1L, "/tmp/key", null, "/home/alexey/proj", "proj",
            listOf(
                EnvCopySourceFolder("/home/alexey/proj", "proj"),
                EnvCopySourceFolder("/home/alexey/other", "other"),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("/home/alexey/other"),
            vm.state.value.copySources.map { it.path },
        )
    }

    @Test
    fun toolMissingSurfacesUnavailableState() = runTest {
        val gateway = FakeEnvGateway(listResult = EnvListResult.ToolUnavailable)
        val vm = EnvViewModel(gateway, FakeHostDao(host))
        vm.bind(1L, "/tmp/key", null, "/home/alexey/proj", "proj", emptyList())
        advanceUntilIdle()

        assertTrue(vm.state.value.list is EnvListState.ToolUnavailable)
    }

    private class FakeEnvGateway(
        private val listResult: EnvListResult,
        private val getResult: EnvOpResult = EnvOpResult.Values(emptyMap()),
        private val setResult: EnvOpResult = EnvOpResult.Success,
        private val copyResult: EnvOpResult = EnvOpResult.Success,
    ) : EnvGateway {
        var listCalls = 0
        var lastSetUpdates: Map<String, String>? = null
        var lastSetFile: EnvFileTarget? = null
        var lastCopySource: String? = null
        var lastCopyDest: String? = null
        var lastCopyKeys: List<String>? = null
        var lastCopyFile: EnvFileTarget? = null

        override suspend fun listKeys(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            directory: String,
        ): EnvListResult {
            listCalls++
            return listResult
        }

        override suspend fun getValue(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            directory: String,
            key: String,
        ): EnvOpResult = getResult

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
            return setResult
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
            lastCopySource = sourceDirectory
            lastCopyDest = destinationDirectory
            lastCopyKeys = keys
            lastCopyFile = file
            return copyResult
        }
    }

    private class FakeHostDao(private val host: HostEntity?) : HostDao {
        override fun getAll(): Flow<List<HostEntity>> = flowOf(listOfNotNull(host))
        override suspend fun getById(id: Long): HostEntity? = host?.takeIf { it.id == id }
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(emptyList())
        override suspend fun insert(host: HostEntity): Long = host.id
        override suspend fun update(host: HostEntity) {}
        override suspend fun delete(host: HostEntity) {}
        override suspend fun deleteById(id: Long) {}
    }
}
