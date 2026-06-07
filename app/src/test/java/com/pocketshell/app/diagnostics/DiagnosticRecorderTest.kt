package com.pocketshell.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiagnosticRecorderTest {
    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, "diagnostics").deleteRecursively()
        File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR).deleteRecursively()
        settingsRepository = SettingsRepository(context)
    }

    @Test
    fun `recorder is off by default and exports only after enabled`() = runTest {
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record("connection", "connect_start", mapOf("host" to "dev"))
        assertEquals(null, recorder.exportSnapshot())

        settingsRepository.setDiagnosticsRecordingEnabled(true)
        recorder.record("connection", "connect_start", mapOf("host" to "dev"))
        val exported = recorder.exportSnapshot()

        assertNotNull(exported)
        val text = exported!!.readText()
        assertTrue(text.contains("category=connection"))
        assertTrue(text.contains("event=connect_start"))
        assertTrue(text.contains("host=dev"))
    }
}
