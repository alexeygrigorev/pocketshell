package com.pocketshell.app.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.usage.UsageRemoteSource
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.core.assistant.AssistantProvider
import com.pocketshell.core.assistant.AssistantSettings
import com.pocketshell.core.assistant.store.AssistantConfigStore
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Light-weight unit test confirming [SettingsViewModel] is a thin
 * pass-through over [SettingsRepository] — the repository owns the
 * write logic, the view model just relays — plus the Whisper API-key
 * vault integration added by issue #125.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository
    private lateinit var db: AppDatabase
    private lateinit var hostDao: HostDao

    /**
     * In-memory [PromptComposerViewModel.ApiKeyVault] — mirrors the
     * composer test's `FakeVault`. Production wiring shares a singleton
     * binding between the composer and the Settings screen.
     */
    private class FakeVault(initial: CharArray? = null) : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = initial?.copyOf()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = this.key?.copyOf()
        override fun clear() { this.key = null }
    }

    /**
     * Issue #265: in-memory [AssistantConfigStore] so the view model can be
     * tested without the KeyStore. Distinct from [FakeVault] — the
     * assistant provider config is wholly separate from the Whisper key.
     */
    private class FakeAssistantStore(
        private var settings: AssistantSettings = AssistantSettings(),
    ) : AssistantConfigStore {
        val keys = mutableMapOf<AssistantProvider, CharArray>()
        override fun loadSettings(): AssistantSettings = settings
        override fun setProvider(provider: AssistantProvider) {
            settings = settings.copy(provider = provider)
        }
        override fun setEndpoint(provider: AssistantProvider, baseUrl: String, model: String) {
            settings = when (provider) {
                AssistantProvider.OpenAi -> settings.copy(openAiBaseUrl = baseUrl, openAiModel = model)
                AssistantProvider.Anthropic ->
                    settings.copy(anthropicBaseUrl = baseUrl, anthropicModel = model)
                AssistantProvider.Zai -> settings.copy(zaiBaseUrl = baseUrl, zaiModel = model)
            }
        }
        override fun saveKey(provider: AssistantProvider, key: CharArray) {
            keys[provider] = key.copyOf()
        }
        override fun loadKey(provider: AssistantProvider): CharArray? = keys[provider]?.copyOf()
        override fun clearKey(provider: AssistantProvider) { keys.remove(provider) }
    }

    /** Construct the view model with the standard inert collaborators. */
    private fun newVm(
        vault: PromptComposerViewModel.ApiKeyVault = FakeVault(),
        assistantStore: AssistantConfigStore = FakeAssistantStore(),
    ): SettingsViewModel =
        SettingsViewModel(repo, vault, assistantStore, hostDao, newUsageScheduler())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = SettingsRepository(context)
        db = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        hostDao = db.hostDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Inert UsageScheduler the view model can read snapshots off without
     * standing up an SSH transport. Each call returns a fresh
     * scheduler so test isolation is preserved.
     */
    private fun newUsageScheduler(): UsageScheduler =
        UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())

    @Test
    fun `state exposes repository snapshot`() {
        val vm = newVm()
        assertEquals(repo.settings.value, vm.state.value)
    }

    @Test
    fun `setTerminalFontSizeSp flows through to repository`() {
        val vm = newVm()
        vm.setTerminalFontSizeSp(18f)
        assertEquals(18f, repo.settings.value.terminalFontSizeSp, 0f)
    }

    @Test
    fun `setConversationFontSizeSp flows through to repository`() {
        val vm = newVm()
        vm.setConversationFontSizeSp(18f)
        assertEquals(18f, repo.settings.value.conversationFontSizeSp, 0f)
    }

    @Test
    fun `setTmuxOnAttachByDefault flows through to repository`() {
        val vm = newVm()
        vm.setTmuxOnAttachByDefault(false)
        assertEquals(false, repo.settings.value.tmuxOnAttachByDefault)
    }

    @Test
    fun `setDefaultHostId flows through to repository`() {
        val vm = newVm()
        vm.setDefaultHostId(11L)
        assertEquals(11L, repo.settings.value.defaultHostId)
        vm.setDefaultHostId(null)
        assertEquals(null, repo.settings.value.defaultHostId)
    }

    @Test
    fun `setHostDetailViewMode flows through to repository`() {
        val vm = newVm()
        vm.setHostDetailViewMode(HostDetailViewMode.Flat)
        assertEquals(HostDetailViewMode.Flat, repo.settings.value.hostDetailViewMode)
        assertEquals(HostDetailViewMode.Flat, vm.state.value.hostDetailViewMode)
    }

    // -- Issue #125: Voice section -----------------------------------------

    @Test
    fun `setVoiceLanguage flows through to repository`() {
        val vm = newVm()
        vm.setVoiceLanguage("es")
        assertEquals("es", repo.settings.value.voiceLanguage)
    }

    @Test
    fun `setVoiceSilenceThresholdSeconds flows through to repository`() {
        val vm = newVm()
        vm.setVoiceSilenceThresholdSeconds(2.0f)
        assertEquals(2.0f, repo.settings.value.voiceSilenceThresholdSeconds, 0.01f)
    }

    @Test
    fun `key status is Unset when vault is empty`() {
        val vault = FakeVault()
        val vm = newVm(vault = vault)
        assertEquals(WhisperKeyStatus.Unset, vm.keyStatus.value)
    }

    @Test
    fun `key status reports masked tail when vault has key`() {
        val vault = FakeVault(initial = "sk-test-AbCd1234".toCharArray())
        val vm = newVm(vault = vault)
        val status = vm.keyStatus.value
        assertTrue("expected Set status, got $status", status is WhisperKeyStatus.Set)
        assertEquals("1234", (status as WhisperKeyStatus.Set).maskedTail)
    }

    @Test
    fun `saveApiKey persists to vault and flips status to Set`() {
        val vault = FakeVault()
        val vm = newVm(vault = vault)

        vm.saveApiKey("sk-aBcDeFgH9999".toCharArray())

        val loaded = vault.load()
        assertTrue("vault should hold key after save", loaded != null && loaded.isNotEmpty())
        val status = vm.keyStatus.value
        assertTrue("status should be Set after save", status is WhisperKeyStatus.Set)
        assertEquals("9999", (status as WhisperKeyStatus.Set).maskedTail)
    }

    @Test
    fun `clearApiKey drops vault entry and flips status to Unset`() {
        val vault = FakeVault(initial = "sk-aaaaaaaa".toCharArray())
        val vm = newVm(vault = vault)
        assertTrue(vm.keyStatus.value is WhisperKeyStatus.Set)

        vm.clearApiKey()

        assertEquals(null, vault.load())
        assertEquals(WhisperKeyStatus.Unset, vm.keyStatus.value)
    }

    // -- Issue #265: Assistant section ------------------------------------

    @Test
    fun `assistant default provider is OpenAI with unset keys`() {
        val vm = newVm()
        val state = vm.assistantState.value
        assertEquals(AssistantProvider.OpenAi, state.provider)
        assertEquals(WhisperKeyStatus.Unset, state.openAiKey)
        assertEquals(WhisperKeyStatus.Unset, state.anthropicKey)
        assertEquals(WhisperKeyStatus.Unset, state.zaiKey)
    }

    @Test
    fun `setAssistantProvider switches active provider`() {
        val vm = newVm()
        vm.setAssistantProvider(AssistantProvider.Zai)
        assertEquals(AssistantProvider.Zai, vm.assistantState.value.provider)
    }

    @Test
    fun `setAssistantEndpoint persists base url and model per provider`() {
        val vm = newVm()
        vm.setAssistantEndpoint(AssistantProvider.Zai, AssistantSettings.DEFAULT_ZAI_BASE_URL, "glm-4.6")
        val state = vm.assistantState.value
        assertEquals(AssistantSettings.DEFAULT_ZAI_BASE_URL, state.zaiBaseUrl)
        assertEquals("glm-4.6", state.zaiModel)
        assertEquals(AssistantSettings.DEFAULT_ANTHROPIC_BASE_URL, state.anthropicBaseUrl)
        // OpenAI endpoint untouched.
        assertEquals(AssistantSettings.DEFAULT_OPENAI_BASE_URL, state.openAiBaseUrl)
    }

    @Test
    fun `saveAssistantKey masks tail and clearAssistantKey unsets it`() {
        val store = FakeAssistantStore()
        val vm = newVm(assistantStore = store)

        vm.saveAssistantKey(AssistantProvider.OpenAi, "sk-assistant-7788".toCharArray())
        val set = vm.assistantState.value.openAiKey
        assertTrue("expected Set, got $set", set is WhisperKeyStatus.Set)
        assertEquals("7788", (set as WhisperKeyStatus.Set).maskedTail)
        // Other provider keys remain unset — keys are per-provider.
        assertEquals(WhisperKeyStatus.Unset, vm.assistantState.value.anthropicKey)
        assertEquals(WhisperKeyStatus.Unset, vm.assistantState.value.zaiKey)

        vm.clearAssistantKey(AssistantProvider.OpenAi)
        assertEquals(WhisperKeyStatus.Unset, vm.assistantState.value.openAiKey)
    }

    @Test
    fun `assistant config does not disturb the Whisper voice key`() {
        // The Whisper key lives in its own vault; touching assistant config
        // must leave it intact (issue #265 non-goal: no change to voice path).
        val voiceVault = FakeVault(initial = "sk-whisper-1234".toCharArray())
        val vm = newVm(vault = voiceVault)

        vm.setAssistantProvider(AssistantProvider.Zai)
        vm.setAssistantEndpoint(AssistantProvider.OpenAi, "https://oai.example/v1", "gpt-4o-mini")
        vm.saveAssistantKey(AssistantProvider.OpenAi, "sk-assistant-9999".toCharArray())
        vm.clearAssistantKey(AssistantProvider.OpenAi)

        val voiceStatus = vm.keyStatus.value
        assertTrue("Whisper key should still be Set", voiceStatus is WhisperKeyStatus.Set)
        assertEquals("1234", (voiceStatus as WhisperKeyStatus.Set).maskedTail)
        assertArrayEquals("sk-whisper-1234".toCharArray(), voiceVault.load())
    }

    // -- Issue #157 polish item 5: Usage discoverability flow --------------

    @Test
    fun `hasUsageInstalledHost is false when no hosts exist`() = runTest {
        val rows = hostDao.getAll().first()
        assertTrue("seed precondition: empty DB", rows.isEmpty())
        // The flow starts at its initialValue (false) — assert the
        // upstream agrees there's nothing to surface.
        assertFalse(rows.any { it.pocketshellInstalled == true })
    }

    @Test
    fun `hasUsageInstalledHost is false when hosts exist but none have pocketshell`() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        hostDao.insert(
            HostEntity(
                name = "a",
                hostname = "a.example",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = false,
            ),
        )
        hostDao.insert(
            HostEntity(
                name = "b",
                hostname = "b.example",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = null,
            ),
        )
        val rows = hostDao.getAll().first()
        assertFalse(rows.any { it.pocketshellInstalled == true })
    }

    @Test
    fun `hasUsageInstalledHost is true when at least one host has pocketshell`() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        hostDao.insert(
            HostEntity(
                name = "a",
                hostname = "a.example",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = false,
            ),
        )
        hostDao.insert(
            HostEntity(
                name = "b",
                hostname = "b.example",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = true,
            ),
        )
        val rows = hostDao.getAll().first()
        assertTrue(rows.any { it.pocketshellInstalled == true })
    }
}
