package com.pocketshell.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.tmux.connection.ConnectionManager
import com.pocketshell.core.connection.ConnectionJournalSchema
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * App/storage half of issue #1709: the real manager's typed journal reaches its
 * own capped archive, carries no raw identity, and is absent from the automatic
 * 64 KiB host mirror by construction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1709ConnectionJournalStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

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
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    @Test
    fun `real manager journals typed privacy-safe checkpoints only to the second archive`() = runTest {
        val recorder = DiagnosticRecorder(context, settingsRepository)
        recorder.appVersionOverride = DiagnosticRecorder.AppVersion("0.4.39", 86L)
        DiagnosticEvents.install(recorder)
        val transport = WarmTransport(warm = true)
        val manager = ConnectionManager(transport = transport)
        val rawHost = "alexey@field-host.example:22"
        val rawSession = "/srv/private/client/project"

        manager.enter(HostKey(rawHost), SessionId(rawSession))
        manager.setReconnectLadder(listOf(0L, 1_000L, 2_000L))
        manager.observeBackground()

        val journal = recorder.connectionJournalArchive()
        assertEquals(
            listOf(
                ConnectionJournalSchema.CONSTRUCT,
                ConnectionJournalSchema.SUBMIT,
                ConnectionJournalSchema.LADDER_INSTALL,
                ConnectionJournalSchema.SUBMIT,
            ),
            journal.map { it.name },
        )
        assertTrue(journal.all { it.category == ConnectionJournalSchema.CATEGORY })
        assertTrue(
            "journal entries must be version-stamped by the #1669 envelope",
            journal.all { it.versionName == "0.4.39" && it.versionCode == 86L },
        )

        val enter = journal.first { it.name == ConnectionJournalSchema.SUBMIT }
        assertEquals("enter", enter.metadata["event"])
        assertEquals(true, enter.metadata["isWarm"])
        assertEquals(
            DiagnosticPrivacy.stableFingerprint(rawHost),
            enter.metadata["eventHostFingerprint"],
        )
        assertTrue(enter.metadata["eventSessionFingerprint"].toString().startsWith("sha256:"))
        assertEquals(
            "equal typed IDs must retain equality after sanitization",
            enter.metadata["eventHostFingerprint"],
            enter.metadata["postHostFingerprint"],
        )
        assertEquals("attaching", enter.metadata["postState"])
        assertTrue(enter.metadata.containsKey("reconnectAttempt"))
        assertTrue(enter.metadata.containsKey("episodeStartMs"))
        assertTrue(enter.metadata.containsKey("liveSinceMs"))
        assertTrue(enter.metadata.containsKey("graceDeadlineMs"))

        val submits = journal.filter { it.name == ConnectionJournalSchema.SUBMIT }
        assertEquals(listOf(1L, 2L), submits.map { (it.metadata["journalSeq"] as Number).toLong() })
        val background = submits.last()
        assertTrue("non-consulting events must retain the nullable field", background.metadata.containsKey("isWarm"))
        assertEquals(null, background.metadata["isWarm"])

        val encoded = journal.joinToString("\n", transform = DiagnosticEventJson::encode)
        assertFalse("raw host leaked into commit-safe journal", encoded.contains(rawHost))
        assertFalse("path-derived tmux session leaked into commit-safe journal", encoded.contains(rawSession))

        assertTrue(
            "connection_journal must be deliberately absent from automatic mirroring",
            journal.none(MirroredDiagnostics::isMirrored),
        )
        assertTrue(
            "the second archive must add zero bytes to the 64 KiB host payload",
            recorder.connectionLogJsonl().isBlank(),
        )
        assertTrue(
            "journal archive directory must be separate",
            File(context.filesDir, "diagnostics/connection-journal").isDirectory,
        )
    }

    @Test
    fun `journal base compaction drops oldest complete lines at its byte cap`() {
        val directory = tmp.newFolder("connection-journal")
        val cap = 512L
        val store = ConnectionLogPartStore(
            directory = directory,
            baseName = "connection-journal.jsonl",
            maxLinesPerPart = 1,
            maxParts = 1,
            maxBaseBytes = cap,
        )
        repeat(200) { index ->
            store.append("""{"journalSeq":$index,"padding":"${"x".repeat(40)}"}""")
        }

        val base = File(directory, "connection-journal.jsonl")
        assertTrue("compaction must create the capped base", base.isFile)
        assertTrue("base bytes ${base.length()} exceed cap $cap", base.length() <= cap)
        val all = store.readAllLines()
        assertTrue("newest checkpoint must survive", all.last().contains("\"journalSeq\":199"))
        assertFalse("oldest checkpoints must be dropped under the cap", all.any { it.contains("\"journalSeq\":0,") })
        assertTrue("part count remains independently bounded", store.partCount() <= 1)
    }

    private class WarmTransport(var warm: Boolean) : TransportPort {
        override fun isWarm(host: HostKey): Boolean = warm
        override val transportEvents: Flow<TransportUpDown> = emptyFlow()
    }
}
