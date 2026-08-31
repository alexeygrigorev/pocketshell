package com.pocketshell.app.projects

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.dao.HostDao
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TreeClientCacheIdentityTest {
    private lateinit var context: Context

    @Before
    fun resetCache() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "tree-cache").deleteRecursively()
    }

    @Test
    fun stableHostIdsSeparateSameNameAndSanitisationCollisionOwners() {
        val cache = TreeClientCache(context)
        cache.write(41L, 1L, cached("dev-slash"))
        cache.write(42L, 1L, cached("dev-colon"))

        assertEquals("dev-slash", cache.read(41L).nodes.single().session)
        assertEquals("dev-colon", cache.read(42L).nodes.single().session)
        val files = File(context.filesDir, "tree-cache").listFiles().orEmpty().map { it.name }
        assertTrue("host-41.json" in files)
        assertTrue("host-42.json" in files)
    }

    @Test
    fun remoteIdentityIsOpaqueUniqueAndSurvivesDisplayNameRename() {
        val first = HostEntity(name = "same", hostname = "a", username = "u", keyId = 1L)
        val second = HostEntity(name = "same", hostname = "b", username = "u", keyId = 1L)

        assertNotEquals(first.treeIdentity, second.treeIdentity)
        assertEquals(first.treeIdentity, first.copy(name = "renamed").treeIdentity)
    }

    @Test
    fun duplicateLegacyRemoteNameHasOneDeterministicStableClaimant() {
        val first = HostEntity(id = 41L, name = "same", hostname = "a", username = "u", keyId = 1L)
        val second = HostEntity(id = 42L, name = "same", hostname = "b", username = "u", keyId = 1L)

        assertEquals(41L, legacyRemoteTreeOwnerId(listOf(second, first), "same"))
        assertEquals(null, legacyRemoteTreeOwnerId(listOf(first, second), "missing"))
    }

    @Test
    fun processLocalTreeDurationsUseMonotonicClockDefaults() {
        val model = productionSource("HostTreeModel.kt")
        val coordinator = productionSource("TreeSyncCoordinator.kt")

        assertTrue(model.contains("SystemClock.elapsedRealtime()"))
        assertTrue(coordinator.contains("SystemClock::elapsedRealtime"))
        assertFalse(model.contains("System.currentTimeMillis()"))
        assertFalse(coordinator.contains("System.currentTimeMillis"))
    }

    @Test
    fun delayedOlderRevisionCannotRegressMemoryOrDisk() {
        val cache = TreeClientCache(context)
        cache.write(7L, 11L, cached("new"))
        cache.write(7L, 11L, cached("equal-revision-old-owner"))
        cache.write(7L, 10L, cached("old"))

        assertEquals("new", cache.peek(7L)!!.nodes.single().session)
        val cold = TreeClientCache(context)
        assertEquals("new", cold.read(7L).nodes.single().session)
    }

    @Test
    fun authoritativeEmptySnapshotReplacesLastSessionOnDisk() {
        val cache = TreeClientCache(context)
        cache.write(9L, 1L, cached("last"))
        cache.write(9L, 2L, TreeClientCache.CachedTree(emptyList()))

        val file = File(context.filesDir, "tree-cache/host-9.json")
        assertTrue(file.exists())
        assertEquals(emptyList<TreeRemoteSource.TreeNode>(), TreeClientCache(context).read(9L).nodes)
    }

    @Test
    fun crashCutBeforeAtomicReplaceLeavesPreviousCompleteSnapshot() {
        val cache = TreeClientCache(context)
        cache.write(12L, 1L, cached("old"))
        cache.beforeAtomicReplaceForTest = { error("simulated process cut") }

        cache.write(12L, 2L, cached("partial-new"))

        assertEquals("old", TreeClientCache(context).read(12L).nodes.single().session)
        assertTrue(File(context.filesDir, "tree-cache").listFiles().orEmpty().none {
            it.name.endsWith(".tmp")
        })
    }

    @Test
    fun legacyNameCollisionMigratesOnceToDeterministicStableOwnerAndSurvivesRename() = runTest {
        val dir = File(context.filesDir, "tree-cache").apply { mkdirs() }
        val legacy = File(dir, "dev_a.json").apply {
            writeText(
                """{"nodes":[{"session":"legacy","order":0,"folder_path":"/legacy","collapsed":false}]}""",
            )
        }
        val first = HostEntity(
            id = 41L,
            name = "dev/a",
            hostname = "first",
            username = "u",
            keyId = 1L,
        )
        val second = HostEntity(
            id = 42L,
            name = "dev?a",
            hostname = "second",
            username = "u",
            keyId = 1L,
        )
        val cache = TreeClientCache(context, hostDaoFor(first, second))

        cache.warmAll()
        assertTrue("pre-warm must not erase unmapped legacy state", legacy.exists())
        cache.migrateLegacy(second.id, second.name)
        assertTrue("collision loser must leave the source for its owner", legacy.exists())
        assertTrue(cache.read(second.id).nodes.isEmpty())

        cache.migrateLegacy(first.id, first.name)

        assertFalse(legacy.exists())
        assertEquals("legacy", cache.read(first.id).nodes.single().session)
        assertTrue(File(dir, "host-${first.id}.json").exists())
        assertFalse(File(dir, "host-${second.id}.json").exists())

        // Display-name changes no longer participate after the one deliberate
        // migration; the stable owner keeps the tree without a fallback path.
        cache.migrateLegacy(first.id, "renamed")
        assertEquals("legacy", cache.read(first.id).nodes.single().session)
    }

    private fun cached(session: String) = TreeClientCache.CachedTree(
        nodes = listOf(TreeRemoteSource.TreeNode(session, 0, "/$session", false)),
    )

    @Suppress("UNCHECKED_CAST")
    private fun hostDaoFor(vararg hosts: HostEntity): HostDao = Proxy.newProxyInstance(
        HostDao::class.java.classLoader,
        arrayOf(HostDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAll" -> flowOf(hosts.toList())
            "getById" -> hosts.firstOrNull { it.id == args?.firstOrNull() }
            else -> error("${method.name} must not be called")
        }
    } as HostDao

    private fun productionSource(name: String): String = listOf(
        File("app/src/main/java/com/pocketshell/app/projects/$name"),
        File("src/main/java/com/pocketshell/app/projects/$name"),
    ).first(File::isFile).readText()
}
