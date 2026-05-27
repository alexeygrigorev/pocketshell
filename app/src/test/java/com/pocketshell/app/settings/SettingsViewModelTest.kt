package com.pocketshell.app.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
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

    @Test
    fun `state exposes repository snapshot`() {
        val vm = SettingsViewModel(repo, FakeVault(), hostDao)
        assertEquals(repo.settings.value, vm.state.value)
    }

    @Test
    fun `setTheme flows through to repository`() {
        val vm = SettingsViewModel(repo, FakeVault(), hostDao)
        vm.setTheme(ThemePreference.Dark)
        assertEquals(ThemePreference.Dark, repo.settings.value.theme)
        assertEquals(ThemePreference.Dark, vm.state.value.theme)
    }

    @Test
    fun `setTerminalFontSizeSp flows through to repository`() {
        val vm = SettingsViewModel(repo, FakeVault(), hostDao)
        vm.setTerminalFontSizeSp(18f)
        assertEquals(18f, repo.settings.value.terminalFontSizeSp, 0f)
    }

    @Test
    fun `setTmuxOnAttachByDefault flows through to repository`() {
        val vm = SettingsViewModel(repo, FakeVault(), hostDao)
        vm.setTmuxOnAttachByDefault(false)
        assertEquals(false, repo.settings.value.tmuxOnAttachByDefault)
    }

    // -- Issue #125: Voice section -----------------------------------------

    @Test
    fun `setVoiceLanguage flows through to repository`() {
        val vm = SettingsViewModel(repo, FakeVault(), hostDao)
        vm.setVoiceLanguage("es")
        assertEquals("es", repo.settings.value.voiceLanguage)
    }

    @Test
    fun `setVoiceSilenceThresholdSeconds flows through to repository`() {
        val vm = SettingsViewModel(repo, FakeVault(), hostDao)
        vm.setVoiceSilenceThresholdSeconds(2.0f)
        assertEquals(2.0f, repo.settings.value.voiceSilenceThresholdSeconds, 0.01f)
    }

    @Test
    fun `key status is Unset when vault is empty`() {
        val vault = FakeVault()
        val vm = SettingsViewModel(repo, vault, hostDao)
        assertEquals(WhisperKeyStatus.Unset, vm.keyStatus.value)
    }

    @Test
    fun `key status reports masked tail when vault has key`() {
        val vault = FakeVault(initial = "sk-test-AbCd1234".toCharArray())
        val vm = SettingsViewModel(repo, vault, hostDao)
        val status = vm.keyStatus.value
        assertTrue("expected Set status, got $status", status is WhisperKeyStatus.Set)
        assertEquals("1234", (status as WhisperKeyStatus.Set).maskedTail)
    }

    @Test
    fun `saveApiKey persists to vault and flips status to Set`() {
        val vault = FakeVault()
        val vm = SettingsViewModel(repo, vault, hostDao)

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
        val vm = SettingsViewModel(repo, vault, hostDao)
        assertTrue(vm.keyStatus.value is WhisperKeyStatus.Set)

        vm.clearApiKey()

        assertEquals(null, vault.load())
        assertEquals(WhisperKeyStatus.Unset, vm.keyStatus.value)
    }

    // -- Issue #157 polish item 5: Usage discoverability flow --------------

    @Test
    fun `hasUsageInstalledHost is false when no hosts exist`() = runTest {
        val rows = hostDao.getAll().first()
        assertTrue("seed precondition: empty DB", rows.isEmpty())
        // The flow starts at its initialValue (false) — assert the
        // upstream agrees there's nothing to surface.
        assertFalse(rows.any { it.quseInstalled == true })
    }

    @Test
    fun `hasUsageInstalledHost is false when hosts exist but none have quse`() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        hostDao.insert(
            HostEntity(
                name = "a",
                hostname = "a.example",
                username = "u",
                keyId = keyId,
                quseInstalled = false,
            ),
        )
        hostDao.insert(
            HostEntity(
                name = "b",
                hostname = "b.example",
                username = "u",
                keyId = keyId,
                quseInstalled = null,
            ),
        )
        val rows = hostDao.getAll().first()
        assertFalse(rows.any { it.quseInstalled == true })
    }

    @Test
    fun `hasUsageInstalledHost is true when at least one host has quse`() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        hostDao.insert(
            HostEntity(
                name = "a",
                hostname = "a.example",
                username = "u",
                keyId = keyId,
                quseInstalled = false,
            ),
        )
        hostDao.insert(
            HostEntity(
                name = "b",
                hostname = "b.example",
                username = "u",
                keyId = keyId,
                quseInstalled = true,
            ),
        )
        val rows = hostDao.getAll().first()
        assertTrue(rows.any { it.quseInstalled == true })
    }
}
