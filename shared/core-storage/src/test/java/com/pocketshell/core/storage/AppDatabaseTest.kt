package com.pocketshell.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.AgentSessionEntity
import com.pocketshell.core.storage.entity.AiApiCallEntry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.PortRemappingEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SessionEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises one round-trip per DAO against an in-memory Room database.
 *
 * Robolectric provides the Android Context that Room needs to instantiate
 * its SupportSQLiteOpenHelper; the database itself lives entirely in
 * memory so the test is hermetic.
 *
 * `RobolectricTestRunner` is used (not `AndroidJUnit4` directly) because
 * `:test` runs on the host JVM; `AndroidJUnit4` would dispatch to either
 * Robolectric or instrumentation depending on classpath. Being explicit
 * avoids confusion when this same pattern gets copied elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sshKey_insert_then_read_by_id() = runTest {
        val id = db.sshKeyDao().insert(
            SshKeyEntity(name = "my-key", privateKeyPath = "/tmp/id_ed25519"),
        )
        val read = db.sshKeyDao().getById(id)
        assertNotNull(read)
        assertEquals("my-key", read!!.name)
        assertEquals("/tmp/id_ed25519", read.privateKeyPath)
    }

    @Test
    fun host_insert_with_fk_to_sshKey_then_read() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "prod",
                hostname = "example.com",
                username = "alexey",
                keyId = keyId,
            ),
        )
        val read = db.hostDao().getById(hostId)
        assertNotNull(read)
        assertEquals("prod", read!!.name)
        assertEquals(22, read.port)
        assertEquals(keyId, read.keyId)
    }

    @Test
    fun portRemapping_insert_then_read_by_host() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        db.portRemappingDao().insert(
            PortRemappingEntity(hostId = hostId, remotePort = 5432, localPort = 15432),
        )
        val remappings = db.portRemappingDao().getByHostId(hostId).first()
        assertEquals(1, remappings.size)
        assertEquals(5432, remappings[0].remotePort)
        assertEquals(15432, remappings[0].localPort)
    }

    @Test
    fun session_insert_then_read_by_host() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        db.sessionDao().insert(
            SessionEntity(hostId = hostId, name = "main", lastSeenAt = 1_000L, tags = "work"),
        )
        val sessions = db.sessionDao().getByHostId(hostId).first()
        assertEquals(1, sessions.size)
        assertEquals("main", sessions[0].name)
        assertEquals("work", sessions[0].tags)
    }

    @Test
    fun snippet_insert_then_read_by_host() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        // Issue #190: insert both an explicit-label row and a derived-
        // label row (label = null) so the schema round-trip covers both
        // paths.
        db.snippetDao().insert(
            SnippetEntity(hostId = hostId, label = "ls", body = "ls -la", kind = "command"),
        )
        db.snippetDao().insert(
            SnippetEntity(hostId = hostId, label = null, body = "echo derived", kind = "command"),
        )
        val snippets = db.snippetDao().getByHostId(hostId).first()
        assertEquals(2, snippets.size)
        // The DAO's `ORDER BY label` puts NULLs first under SQLite's
        // default sort; the explicit-label row comes second.
        val derived = snippets.first { it.label == null }
        assertEquals("echo derived", derived.body)
        val explicit = snippets.first { it.label == "ls" }
        assertEquals("ls -la", explicit.body)
        assertEquals("command", explicit.kind)
    }

    @Test
    fun projectRoot_insert_then_read_by_host() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "work", path = "~/work"),
        )

        val roots = db.projectRootDao().getByHostId(hostId).first()
        assertEquals(1, roots.size)
        assertEquals("work", roots[0].label)
        assertEquals("~/work", roots[0].path)
    }

    @Test
    fun aiApiCallLog_insert_then_streams_all() = runTest {
        val dao = db.aiApiCallLogDao()
        val firstId = dao.insert(
            AiApiCallEntry(
                timestampMillis = 1_000L,
                provider = "openai",
                feature = "whisper",
                inputUnits = 12,
                outputUnits = 84,
                unitCostUsdMillicents = 10,
                computedCostUsdMillicents = 120,
                metadataJson = null,
            ),
        )
        val secondId = dao.insert(
            AiApiCallEntry(
                timestampMillis = 5_000L,
                provider = "openai",
                feature = "whisper",
                inputUnits = 3,
                outputUnits = 20,
                unitCostUsdMillicents = 10,
                computedCostUsdMillicents = 30,
                metadataJson = """{"requestId":"abc"}""",
            ),
        )

        val all = dao.getAll().first()
        assertEquals(2, all.size)
        // Newest first.
        assertEquals(secondId, all[0].id)
        assertEquals(firstId, all[1].id)
        assertEquals(120L, all[1].computedCostUsdMillicents)
        assertEquals("""{"requestId":"abc"}""", all[0].metadataJson)

        // Range query: only the second row falls inside (timestamp >= 2_000).
        val recent = dao.getSince(2_000L).first()
        assertEquals(1, recent.size)
        assertEquals(secondId, recent[0].id)

        dao.deleteAll()
        assertEquals(0, dao.getAll().first().size)
    }

    @Test
    fun agentSession_upsert_then_read_by_paneRef() = runTest {
        val paneRef = "host1:main:0:0"
        db.agentSessionDao().upsert(
            AgentSessionEntity(
                paneRef = paneRef,
                agent = "claude",
                jsonlPath = "/home/alexey/.claude/projects/foo/abc.jsonl",
                detectedAt = 42L,
            ),
        )
        val read = db.agentSessionDao().getByPaneRef(paneRef)
        assertNotNull(read)
        assertEquals("claude", read!!.agent)
        assertEquals(42L, read.detectedAt)

        // Re-upsert with new state should replace, not duplicate.
        db.agentSessionDao().upsert(
            AgentSessionEntity(
                id = read.id,
                paneRef = paneRef,
                agent = "codex",
                jsonlPath = null,
                detectedAt = 100L,
            ),
        )
        val updated = db.agentSessionDao().getByPaneRef(paneRef)
        assertEquals("codex", updated!!.agent)
        assertNull(updated.jsonlPath)
    }
}
