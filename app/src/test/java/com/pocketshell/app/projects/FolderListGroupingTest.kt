package com.pocketshell.app.projects

import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the pure folder-grouping logic exposed on
 * [FolderListViewModel] — issue #171.
 *
 * The grouping function is the load-bearing piece of the folder
 * list — it takes the gateway's per-session `pane_current_path`
 * mapping and the user's per-host [ProjectRootEntity] overlay and
 * emits the sorted folder list the screen renders. Driving it
 * directly (without spinning up SSH / DAO) keeps the tests fast and
 * deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListGroupingTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun canonicalisePathStripsTrailingSlash() {
        assertEquals("/home/alexey/git/pocketshell", FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/"))
        assertEquals("/home/alexey/git/pocketshell", FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"))
    }

    @Test
    fun canonicalisePathReturnsUntrackedForBlank() {
        assertEquals(FolderListViewModel.UNTRACKED_PATH, FolderListViewModel.canonicalisePath(""))
        assertEquals(FolderListViewModel.UNTRACKED_PATH, FolderListViewModel.canonicalisePath("   "))
    }

    @Test
    fun defaultLabelTakesTrailingPathSegment() {
        assertEquals("pocketshell", FolderListViewModel.defaultLabelForPath("/home/alexey/git/pocketshell"))
        // Trailing slashes don't change the trailing segment.
        assertEquals("pocketshell", FolderListViewModel.defaultLabelForPath("/home/alexey/git/pocketshell/"))
        assertEquals("Untracked", FolderListViewModel.defaultLabelForPath(FolderListViewModel.UNTRACKED_PATH))
        // A single-segment home path keeps the bare segment.
        assertEquals("alexey", FolderListViewModel.defaultLabelForPath("/home/alexey"))
    }

    @Test
    fun defaultLabelNeverReturnsBlankOrLoneSlash() {
        // Filesystem root → a labelled "/ (root)", never a lone "/" (#438).
        assertEquals(FolderListViewModel.ROOT_LABEL, FolderListViewModel.defaultLabelForPath("/"))
        assertEquals(FolderListViewModel.ROOT_LABEL, FolderListViewModel.defaultLabelForPath("//"))
        assertEquals(FolderListViewModel.ROOT_LABEL, FolderListViewModel.defaultLabelForPath("///"))
        // Literal home markers → "~ (home)".
        assertEquals(FolderListViewModel.HOME_LABEL, FolderListViewModel.defaultLabelForPath("~"))
        assertEquals(FolderListViewModel.HOME_LABEL, FolderListViewModel.defaultLabelForPath("\$HOME"))
        // Blank / whitespace → "Untracked", never an empty string.
        assertEquals(FolderListViewModel.UNTRACKED_LABEL, FolderListViewModel.defaultLabelForPath(""))
        assertEquals(FolderListViewModel.UNTRACKED_LABEL, FolderListViewModel.defaultLabelForPath("   "))
        // A nested path keeps its meaningful tail.
        assertEquals("pocketshell", FolderListViewModel.defaultLabelForPath("/home/alexey/git/pocketshell"))
        // Whatever the input, the label is never blank or a degenerate token.
        val inputs = listOf("/", "//", "~", "\$HOME", "", "   ", "/home/alexey", "/a/b/c")
        for (input in inputs) {
            val label = FolderListViewModel.defaultLabelForPath(input)
            assertTrue("label for '$input' must not be blank", label.isNotBlank())
            assertTrue("label for '$input' must not be a lone slash", label != "/")
            assertTrue("label for '$input' must not be a lone dot", label != ".")
            assertTrue("label for '$input' must not be a lone colon", label != ":")
        }
    }

    @Test
    fun groupSessionsIntoFoldersGroupsByCanonicalisedPath() {
        val sessions = listOf(
            entry("claude-main", 1_000L),
            entry("build-shell", 800L),
            entry("training", 1_500L),
        )
        val cwds = mapOf(
            "claude-main" to "/home/alexey/git/pocketshell",
            "build-shell" to "/home/alexey/git/pocketshell/", // trailing slash → collapses
            "training" to "/home/alexey/git/llm-zoomcamp",
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds.mapValues { (_, v) -> FolderListViewModel.canonicalisePath(v) },
            watchedFolders = emptyList(),
        )
        assertEquals(2, rows.size)
        // Sorted by most-recent activity desc: llm-zoomcamp (1_500) > pocketshell (1_000)
        assertEquals("llm-zoomcamp", rows[0].label)
        assertEquals(1, rows[0].sessions.size)
        assertEquals("pocketshell", rows[1].label)
        assertEquals(2, rows[1].sessions.size)
        // Sessions within a folder sort by activity desc as well.
        assertEquals("claude-main", rows[1].sessions[0].sessionName)
        assertEquals("build-shell", rows[1].sessions[1].sessionName)
    }

    @Test
    fun groupSessionsIntoFoldersRoutesMissingCwdToUntracked() {
        val sessions = listOf(
            entry("claude-main", 1_000L),
            entry("orphan", 500L),
        )
        val cwds = mapOf("claude-main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"))
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = emptyList(),
        )
        // Active group first, untracked last.
        assertEquals(2, rows.size)
        assertEquals("pocketshell", rows[0].label)
        assertEquals(FolderListViewModel.UNTRACKED_PATH, rows[1].path)
        assertEquals(FolderListViewModel.UNTRACKED_LABEL, rows[1].label)
        assertEquals(1, rows[1].sessions.size)
        assertEquals("orphan", rows[1].sessions[0].sessionName)
    }

    @Test
    fun watchedFolderWithoutMatchingSessionStillAppears() {
        val sessions = listOf(entry("claude-main", 1_000L))
        val cwds = mapOf("claude-main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"))
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "pocketshell", path = "/home/alexey/git/pocketshell"),
            ProjectRootEntity(id = 2L, hostId = 7L, label = "empty-pinned-folder", path = "/home/alexey/git/empty-pinned-folder"),
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
        )
        assertEquals(2, rows.size)
        // Active group first: pocketshell with claude-main.
        assertEquals("pocketshell", rows[0].label)
        assertTrue(rows[0].isWatched)
        assertEquals(1, rows[0].sessions.size)
        // Watched-but-empty group last (before untracked).
        assertEquals("empty-pinned-folder", rows[1].label)
        assertTrue(rows[1].isEmpty)
        assertTrue(rows[1].isWatched)
    }

    @Test
    fun watchedFolderLabelStripOrderPrefix() {
        val sessions = listOf(entry("claude-main", 1_000L))
        val cwds = mapOf("claude-main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"))
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[01] pocketshell", path = "/home/alexey/git/pocketshell"),
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
        )
        assertEquals(1, rows.size)
        // The "[01] " order prefix is stripped per WatchedFoldersViewModel.stripOrderPrefix.
        assertEquals("pocketshell", rows[0].label)
        assertTrue(rows[0].isWatched)
    }

    @Test
    fun groupingPreservesSessionAgentKind() {
        val sessions = listOf(
            FolderSessionEntry("claude-main", 1_000L, attached = true, agentKind = SessionAgentKind.Claude),
            FolderSessionEntry("plain-shell", 800L, attached = false, agentKind = SessionAgentKind.Shell),
        )
        val cwds = mapOf(
            "claude-main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "plain-shell" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = emptyList(),
        )
        assertEquals(1, rows.size)
        assertEquals(SessionAgentKind.Claude, rows[0].sessions[0].agentKind)
        assertEquals(SessionAgentKind.Shell, rows[0].sessions[1].agentKind)
        assertTrue(rows[0].sessions[0].attached)
        assertFalse(rows[0].sessions[1].attached)
    }

    @Test
    fun threeSessionsAcrossTwoFoldersGroupAsTwoRows() {
        // Mirrors the connected E2E setup: three sessions, two folders.
        val sessions = listOf(
            entry("claude-pocketshell", 3_000L, kind = SessionAgentKind.Claude),
            entry("build-pocketshell", 2_500L, kind = SessionAgentKind.Shell),
            entry("codex-llm-zoomcamp", 1_000L, kind = SessionAgentKind.Codex),
        )
        val cwds = mapOf(
            "claude-pocketshell" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "build-pocketshell" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "codex-llm-zoomcamp" to FolderListViewModel.canonicalisePath("/home/alexey/git/llm-zoomcamp"),
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = emptyList(),
        )
        assertEquals(2, rows.size)
        // pocketshell wins on max activity (3_000 > 1_000)
        assertEquals("pocketshell", rows[0].label)
        assertEquals(2, rows[0].sessions.size)
        assertEquals("llm-zoomcamp", rows[1].label)
        assertEquals(1, rows[1].sessions.size)
        // Agent-kind tints are routed all the way through.
        assertEquals(SessionAgentKind.Claude, rows[0].sessions[0].agentKind)
        assertEquals(SessionAgentKind.Shell, rows[0].sessions[1].agentKind)
        assertEquals(SessionAgentKind.Codex, rows[1].sessions[0].agentKind)
    }

    @Test
    fun buildFolderTreeGroupsSessionsUnderWatchedRootProjectFolder() {
        val sessions = listOf(
            entry("codex-api", 3_000L, kind = SessionAgentKind.Codex),
            entry("shell-api", 2_000L),
            entry("claude-docs", 1_000L, kind = SessionAgentKind.Claude),
        )
        val cwds = mapOf(
            "codex-api" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/app"),
            "shell-api" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "claude-docs" to FolderListViewModel.canonicalisePath("/home/alexey/git/docs/site"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[00] git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = mapOf(
                "/home/alexey/git" to listOf(
                    "/home/alexey/git/pocketshell",
                    "/home/alexey/git/docs",
                    "/home/alexey/git/empty-project",
                ),
            ),
        )

        assertEquals(1, roots.size)
        assertEquals("/home/alexey/git", roots[0].path)
        assertEquals("git", roots[0].label)
        assertTrue(roots[0].isWatched)
        assertEquals(
            listOf("pocketshell", "docs"),
            roots[0].folders.map { it.label },
        )
        assertEquals(listOf("codex-api", "shell-api"), roots[0].folders[0].sessions.map { it.sessionName })
        assertEquals(listOf("claude-docs"), roots[0].folders[1].sessions.map { it.sessionName })
        assertEquals(
            listOf("empty-project"),
            roots[0].addSheetProjects.map { it.label },
        )
        assertEquals(
            listOf(RootProjectSource.Scanned),
            roots[0].addSheetProjects.map { it.source },
        )
    }

    @Test
    fun cableWorldSessionsStayRawUnderPathDerivedProjectInTreeAndFlatGrouping() {
        val cableWorldPath = FolderListViewModel.canonicalisePath("/home/alexey/git/cable-world")
        val sessions = listOf(
            entry("git-cable-world", 1_000L, kind = SessionAgentKind.Shell),
            entry("git-cable-world-map", 1_500L, kind = SessionAgentKind.Claude),
        )
        val cwds = sessions.associate { it.sessionName to cableWorldPath }
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[00] git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = emptyMap(),
        )
        val flat = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = emptyList(),
        )

        assertEquals("cable-world", roots.single().folders.single().label)
        assertEquals(cableWorldPath, roots.single().folders.single().path)
        assertEquals(
            listOf("git-cable-world-map", "git-cable-world"),
            roots.single().folders.single().sessions.map { it.sessionName },
        )
        assertEquals("cable-world", flat.single().label)
        assertEquals(
            listOf("git-cable-world-map", "git-cable-world"),
            flat.single().sessions.map { it.sessionName },
        )
    }

    @Test
    fun groupingPreservesCompactWindowMetadataForMultiWindowSessions() {
        val session = FolderSessionEntry(
            sessionName = "git-cable-world-map",
            lastActivity = 1_500L,
            attached = false,
            agentKind = SessionAgentKind.Claude,
            windows = listOf(
                FolderSessionWindowEntry(
                    index = 0,
                    name = "node",
                    active = false,
                    command = "node",
                    agentKind = SessionAgentKind.Shell,
                ),
                FolderSessionWindowEntry(
                    index = 1,
                    name = "claude",
                    active = true,
                    command = "claude",
                    agentKind = SessionAgentKind.Claude,
                ),
            ),
        )

        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = listOf(session),
            sessionFolderPaths = mapOf(
                "git-cable-world-map" to FolderListViewModel.canonicalisePath("/home/alexey/git/cable-world"),
            ),
            watchedFolders = emptyList(),
        )

        assertEquals(
            listOf("node", "claude"),
            rows.single().sessions.single().windows.map { it.name },
        )
        assertEquals(
            listOf(SessionAgentKind.Shell, SessionAgentKind.Claude),
            rows.single().sessions.single().windows.map { it.agentKind },
        )
    }

    @Test
    fun buildFolderTreeMatchesTildeWatchedRootToAbsoluteProjectsAndSessions() {
        val sessions = listOf(
            entry("codex-pocketshell", 3_000L, kind = SessionAgentKind.Codex),
            entry("shell-docs", 2_000L),
        )
        val cwds = mapOf(
            "codex-pocketshell" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/app"),
            "shell-docs" to FolderListViewModel.canonicalisePath("/home/alexey/git/docs"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[00] git", path = "~/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = mapOf(
                "~/git" to listOf(
                    "/home/alexey/git/pocketshell",
                    "/home/alexey/git/docs",
                    "/home/alexey/git/empty-project",
                ),
            ),
            resolvedWatchedRootPaths = mapOf("~/git" to "/home/alexey/git"),
        )

        assertEquals(1, roots.size)
        assertEquals("~/git", roots[0].path)
        assertEquals("git", roots[0].label)
        assertEquals(
            listOf("pocketshell", "docs"),
            roots[0].folders.map { it.label },
        )
        assertEquals(listOf("codex-pocketshell"), roots[0].folders[0].sessions.map { it.sessionName })
        assertEquals(listOf("shell-docs"), roots[0].folders[1].sessions.map { it.sessionName })
        assertEquals(
            listOf("empty-project"),
            roots[0].addSheetProjects.map { it.label },
        )
    }

    @Test
    fun rootAddSheetCandidatesPutUsedBeforeInactiveBeforeScannedFolders() {
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = emptyList(),
            sessionFolderPaths = emptyMap(),
            watchedFolders = watched,
            historyProjectFoldersByRoot = mapOf(
                "/home/alexey/git" to listOf(
                    "/home/alexey/git/zoomcamp",
                    "/home/alexey/git/pocketshell",
                ),
            ),
            scannedProjectFoldersByRoot = mapOf(
                "/home/alexey/git" to listOf(
                    "/home/alexey/git/alpha",
                    "/home/alexey/git/pocketshell",
                    "/home/alexey/git/zoomcamp",
                    "/home/alexey/git/beta",
                ),
            ),
        )

        assertTrue("inactive scanned folders should not flood the main tree", roots.single().folders.isEmpty())
        assertEquals(
            listOf("zoomcamp", "pocketshell", "alpha", "beta"),
            roots.single().addSheetProjects.map { it.label },
        )
        assertEquals(
            listOf(
                RootProjectSource.History,
                RootProjectSource.History,
                RootProjectSource.Scanned,
                RootProjectSource.Scanned,
            ),
            roots.single().addSheetProjects.map { it.source },
        )
    }

    @Test
    fun rootAddSheetCandidatesExcludeActiveProjects() {
        val sessions = listOf(entry("main", 3_000L), entry("worker", 2_000L))
        val cwds = mapOf(
            "main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/app"),
            "worker" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            historyProjectFoldersByRoot = mapOf("/home/alexey/git" to listOf("/home/alexey/git/old")),
            scannedProjectFoldersByRoot = mapOf("/home/alexey/git" to listOf("/home/alexey/git/alpha")),
        )

        assertEquals(listOf("pocketshell"), roots.single().folders.map { it.label })
        assertEquals(
            listOf("old", "alpha"),
            roots.single().addSheetProjects.map { it.label },
        )
        assertFalse(roots.single().addSheetProjects.any { it.path == "/home/alexey/git/pocketshell" })
    }

    @Test
    fun rootAddSheetCandidateFilteringMatchesLabelOrPathCaseInsensitively() {
        val candidates = listOf(
            RootProjectCandidate("/home/alexey/git/pocketshell", "pocketshell", RootProjectSource.History),
            RootProjectCandidate("/home/alexey/git/llm-zoomcamp", "llm-zoomcamp", RootProjectSource.History),
            RootProjectCandidate("/srv/tools/beta", "beta", RootProjectSource.Scanned),
        )

        assertEquals(
            listOf("llm-zoomcamp"),
            FolderListViewModel.filterRootProjectCandidates(candidates, "ZOOM").map { it.label },
        )
        assertEquals(
            listOf("beta"),
            FolderListViewModel.filterRootProjectCandidates(candidates, "/srv").map { it.label },
        )
        assertEquals(candidates, FolderListViewModel.filterRootProjectCandidates(candidates, " "))
    }

    @Test
    fun buildFolderTreeKeepsSessionsOutsideWatchedRootsReachable() {
        val sessions = listOf(
            entry("inside", 2_000L),
            entry("outside", 3_000L),
        )
        val cwds = mapOf(
            "inside" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "outside" to FolderListViewModel.canonicalisePath("/tmp/scratch"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = emptyMap(),
        )

        assertEquals(listOf("git", FolderListViewModel.OTHER_ROOT_LABEL), roots.map { it.label })
        assertEquals("/home/alexey/git", roots[0].displayPath)
        assertEquals(FolderListViewModel.OTHER_ROOT_PATH, roots[1].path)
        assertNull(roots[1].displayPath)
        assertEquals("pocketshell", roots[0].folders.single().label)
        assertEquals("scratch", roots[1].folders.single().label)
        assertFalse(roots[1].isWatched)
    }

    @Test
    fun buildFolderTreePreservesConfiguredRootOrder() {
        val sessions = listOf(
            entry("tmp-session", 3_000L),
            entry("git-session", 2_000L),
        )
        val cwds = mapOf(
            "tmp-session" to FolderListViewModel.canonicalisePath("/home/alexey/tmp/scratch"),
            "git-session" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 2L, hostId = 7L, label = "[00] tmp", path = "/home/alexey/tmp"),
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[01] git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = emptyMap(),
        )

        assertEquals(listOf("tmp", "git"), roots.map { it.label })
        assertEquals("scratch", roots[0].folders.single().label)
        assertEquals("pocketshell", roots[1].folders.single().label)
    }

    @Test
    fun configuredRootsStayAheadOfOutsideSessionsEvenWhenOutsideIsNewer() {
        val sessions = listOf(
            entry("outside-newest", 9_000L),
            entry("git-session", 2_000L),
            entry("tmp-session", 1_000L),
        )
        val cwds = mapOf(
            "outside-newest" to FolderListViewModel.canonicalisePath("/opt/work/demo"),
            "git-session" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "tmp-session" to FolderListViewModel.canonicalisePath("/home/alexey/tmp/scratch"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[00] git", path = "/home/alexey/git"),
            ProjectRootEntity(id = 2L, hostId = 7L, label = "[01] tmp", path = "/home/alexey/tmp"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = emptyMap(),
        )

        assertEquals(listOf("git", "tmp", FolderListViewModel.OTHER_ROOT_LABEL), roots.map { it.label })
        assertEquals("demo", roots.last().folders.single().label)
    }

    @Test
    fun createdEmptyProjectsStayPickerOnlyUntilSessionStarts() {
        val sessions = listOf(entry("main", 2_000L))
        val cwds = mapOf(
            "main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/app"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = emptyMap(),
            extraFolders = mapOf("/home/alexey/git/new-empty" to "new-empty"),
        )

        assertEquals(listOf("pocketshell"), roots.single().folders.map { it.label })
        assertEquals(listOf("new-empty"), roots.single().addSheetProjects.map { it.label })
        assertEquals(RootProjectSource.Scanned, roots.single().addSheetProjects.single().source)
    }

    @Test
    fun manyScannedFoldersStayOutOfMainTreeAndSortAlphabeticallyInPicker() {
        val sessions = listOf(entry("main", 2_000L))
        val cwds = mapOf(
            "main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/app"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )
        val scanned = (30 downTo 1).map { index ->
            "/home/alexey/git/project-${index.toString().padStart(2, '0')}"
        } + "/home/alexey/git/pocketshell"

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = mapOf("/home/alexey/git" to scanned),
        )

        assertEquals(listOf("pocketshell"), roots.single().folders.map { it.label })
        assertEquals(30, roots.single().addSheetProjects.size)
        assertEquals(
            (1..30).map { index -> "project-${index.toString().padStart(2, '0')}" },
            roots.single().addSheetProjects.map { it.label },
        )
    }

    @Test
    fun flatModeGroupingDoesNotIntroduceOutsideRootCallout() {
        val sessions = listOf(
            entry("inside", 2_000L),
            entry("outside", 3_000L),
        )
        val cwds = mapOf(
            "inside" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "outside" to FolderListViewModel.canonicalisePath("/tmp/scratch"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )

        val flatRows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
        )

        assertEquals(listOf("scratch", "pocketshell", "git"), flatRows.map { it.label })
        assertFalse(flatRows.any { it.label == FolderListViewModel.OTHER_ROOT_LABEL })
        assertFalse(flatRows.any { it.path == FolderListViewModel.OTHER_ROOT_PATH })
    }

    @Test
    fun treeRenderingStaysStableWhenRefreshAddsScanAndHistoryData() {
        val sessions = listOf(
            entry("main", 3_000L),
            entry("worker", 2_000L),
        )
        val cwds = mapOf(
            "main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/app"),
            "worker" to FolderListViewModel.canonicalisePath("/home/alexey/tmp/scratch"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[00] git", path = "/home/alexey/git"),
            ProjectRootEntity(id = 2L, hostId = 7L, label = "[01] tmp", path = "/home/alexey/tmp"),
        )

        val first = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = emptyMap(),
        )
        val refreshed = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = mapOf(
                "/home/alexey/git" to listOf("/home/alexey/git/inactive"),
                "/home/alexey/tmp" to listOf("/home/alexey/tmp/old"),
            ),
            historyProjectFoldersByRoot = mapOf(
                "/home/alexey/git" to listOf("/home/alexey/git/used-before"),
            ),
        )

        assertEquals(
            first.map { root -> root.path to root.folders.map { it.path } },
            refreshed.map { root -> root.path to root.folders.map { it.path } },
        )
        assertEquals(
            listOf("used-before", "inactive"),
            refreshed.first { it.path == "/home/alexey/git" }.addSheetProjects.map { it.label },
        )
    }

    @Test
    fun sessionsSortAgentsFirstWithinProjectThenByRecency() {
        val sessions = listOf(
            entry("new-shell", 4_000L, kind = SessionAgentKind.Shell),
            entry("older-codex", 1_000L, kind = SessionAgentKind.Codex),
            entry("newer-claude", 3_000L, kind = SessionAgentKind.Claude),
            entry("old-shell", 500L, kind = SessionAgentKind.Shell),
        )
        val cwds = sessions.associate { session ->
            session.sessionName to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell")
        }
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = emptyMap(),
        )

        assertEquals(
            listOf("newer-claude", "older-codex", "new-shell", "old-shell"),
            roots.single().folders.single().sessions.map { it.sessionName },
        )
    }

    @Test
    fun projectExpansionTogglesFromCollapsedDefault() {
        val projectPath = "/home/alexey/git/pocketshell"
        val collapsed = emptySet<String>()

        val expanded = FolderListViewModel.toggleProjectExpansion(collapsed, projectPath)
        val collapsedAgain = FolderListViewModel.toggleProjectExpansion(expanded, "$projectPath/")

        assertEquals(setOf(projectPath), expanded)
        assertEquals(emptySet<String>(), collapsedAgain)
    }

    @Test
    fun projectCountTextPluralisesSessionsAndAgents() {
        assertEquals(
            "1 session",
            projectCountText(folderWithSessions(entry("shell", 1_000L))),
        )
        assertEquals(
            "1 agent",
            projectCountText(folderWithSessions(entry("claude", 1_000L, kind = SessionAgentKind.Claude))),
        )
        assertEquals(
            "2 sessions · 1 agent",
            projectCountText(
                folderWithSessions(
                    entry("claude", 2_000L, kind = SessionAgentKind.Claude),
                    entry("shell", 1_000L),
                ),
            ),
        )
        assertEquals(
            "3 sessions · 2 agents",
            projectCountText(
                folderWithSessions(
                    entry("claude", 3_000L, kind = SessionAgentKind.Claude),
                    entry("codex", 2_000L, kind = SessionAgentKind.Codex),
                    entry("shell", 1_000L),
                ),
            ),
        )
        assertEquals(
            "2 agents",
            projectCountText(
                folderWithSessions(
                    entry("claude", 2_000L, kind = SessionAgentKind.Claude),
                    entry("codex", 1_000L, kind = SessionAgentKind.Codex),
                ),
            ),
        )
    }

    @Test
    fun rootCountSubtitleRendersOrgsAndSessions() {
        // #478: group header subtitle reads `N orgs · M sessions`, mirroring
        // the maintainer's target mockup ("10 orgs · 14 sessions").
        val root = FolderTreeRoot(
            path = "~/git",
            label = "git",
            folders = listOf(
                folderAt(
                    "/home/u/git/cable-world",
                    "cable-world",
                    entry("a", 1L, kind = SessionAgentKind.Codex),
                    entry("b", 2L, kind = SessionAgentKind.Claude),
                ),
                folderAt(
                    "/home/u/git/pocketshell",
                    "pocketshell",
                    entry("c", 3L, kind = SessionAgentKind.Claude),
                ),
            ),
            isWatched = true,
            addSheetProjects = listOf(
                RootProjectCandidate("/home/u/git/scanned", "scanned", RootProjectSource.Scanned),
            ),
        )
        // 2 active projects + 1 inactive/scanned = 3 orgs; 3 live sessions.
        assertEquals("3 orgs · 3 sessions", rootCountSubtitle(root))
    }

    @Test
    fun rootCountSubtitleSingularAndSessionless() {
        val oneOrgNoSessions = FolderTreeRoot(
            path = "~/git",
            label = "git",
            folders = emptyList(),
            isWatched = true,
            addSheetProjects = listOf(
                RootProjectCandidate("/home/u/git/scanned", "scanned", RootProjectSource.Scanned),
            ),
        )
        assertEquals("1 org", rootCountSubtitle(oneOrgNoSessions))

        val oneOrgOneSession = FolderTreeRoot(
            path = "~/git",
            label = "git",
            folders = listOf(
                folderAt("/home/u/git/solo", "solo", entry("s", 1L)),
            ),
            isWatched = true,
        )
        assertEquals("1 org · 1 session", rootCountSubtitle(oneOrgOneSession))
    }

    @Test
    fun rebindingToSecondHostStartsSecondHostProbe() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstHostProbeStarted = CompletableDeferred<Unit>()
        val secondHostProbeStarted = CompletableDeferred<Unit>()
        val gateway = RebindRecordingFolderListGateway(
            firstHostProbeStarted = firstHostProbeStarted,
            secondHostProbeStarted = secondHostProbeStarted,
        )
        val vm = FolderListViewModel(
            gateway = gateway,
            hostDao = MapHostDao(FIRST_HOST, SECOND_HOST),
            projectRootDao = MapProjectRootDao(
                FIRST_HOST.id to listOf(
                    ProjectRootEntity(
                        id = 1L,
                        hostId = FIRST_HOST.id,
                        label = "first-only",
                        path = "/home/alexey/first",
                    ),
                ),
                SECOND_HOST.id to emptyList(),
            ),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector {
                    Result.failure(IllegalStateException("prewarm disabled for rebind test"))
                },
                scope = this,
                idleTtlMillis = 0L,
            ),
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
        ).also {
            it.ioDispatcher = dispatcher
            // Issue #430: the gateway poll loop is now gated on the
            // whole-process foreground signal. Robolectric's
            // ProcessLifecycleOwner is not STARTED under runTest, so open
            // the gate explicitly to exercise the probe.
            it.setProcessStartedForTest(true)
        }

        try {
            vm.bind(
                hostId = FIRST_HOST.id,
                hostName = FIRST_HOST.name,
                hostname = FIRST_HOST.hostname,
                port = FIRST_HOST.port,
                username = FIRST_HOST.username,
                keyPath = KEY_PATH,
                passphrase = null,
            )
            runCurrent()
            assertTrue("first host probe should start", firstHostProbeStarted.isCompleted)

            vm.bind(
                hostId = SECOND_HOST.id,
                hostName = SECOND_HOST.name,
                hostname = SECOND_HOST.hostname,
                port = SECOND_HOST.port,
                username = SECOND_HOST.username,
                keyPath = KEY_PATH,
                passphrase = null,
            )
            runCurrent()
            assertTrue("first host probe should be cancelled on rebind", gateway.firstHostProbeCancelled.isCompleted)
            assertTrue("second host probe should start", secondHostProbeStarted.isCompleted)

            assertEquals(listOf(FIRST_HOST.id, SECOND_HOST.id), gateway.probedHostIds)
            assertEquals(
                listOf(FIRST_HOST.id to listOf("/home/alexey/first"), SECOND_HOST.id to emptyList<String>()),
                gateway.watchedRootsByProbe,
            )
            assertEquals(
                listOf("second-host-session"),
                (vm.state.value as FolderListUiState.Ready).flatSessions.map { it.sessionName },
            )
        } finally {
            vm.stopPolling()
        }
    }

    private fun folderWithSessions(vararg sessions: FolderSessionEntry): FolderRow =
        FolderRow(
            path = "/home/alexey/git/pocketshell",
            label = "pocketshell",
            sessions = sessions.toList(),
            isWatched = false,
        )

    private fun folderAt(path: String, label: String, vararg sessions: FolderSessionEntry): FolderRow =
        FolderRow(
            path = path,
            label = label,
            sessions = sessions.toList(),
            isWatched = false,
        )

    private fun entry(
        name: String,
        activity: Long,
        attached: Boolean = false,
        kind: SessionAgentKind = SessionAgentKind.Shell,
    ): FolderSessionEntry =
        FolderSessionEntry(
            sessionName = name,
            lastActivity = activity,
            attached = attached,
            agentKind = kind,
        )

    private class RebindRecordingFolderListGateway(
        private val firstHostProbeStarted: CompletableDeferred<Unit>,
        private val secondHostProbeStarted: CompletableDeferred<Unit>,
    ) : FolderListGateway {
        val probedHostIds: MutableList<Long> = mutableListOf()
        val watchedRootsByProbe: MutableList<Pair<Long, List<String>>> = mutableListOf()
        val firstHostProbeCancelled: CompletableDeferred<Unit> = CompletableDeferred()

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            probedHostIds += host.id
            watchedRootsByProbe += host.id to watchedRoots.map { it.path }
            return if (host.id == FIRST_HOST.id) {
                firstHostProbeStarted.complete(Unit)
                try {
                    CompletableDeferred<Nothing>().await()
                } catch (_: CancellationException) {
                    firstHostProbeCancelled.complete(Unit)
                    staleFirstHostResult()
                }
            } else {
                secondHostProbeStarted.complete(Unit)
                FolderListResult.Sessions(
                    rows = listOf(
                        FolderSessionRow(
                            sessionName = "second-host-session",
                            lastActivity = 2L,
                            attached = false,
                            cwd = "/home/alexey/second",
                            agentKind = SessionAgentKind.Shell,
                        ),
                    ),
                )
            }
        }

        private fun staleFirstHostResult(): FolderListResult.Sessions =
            FolderListResult.Sessions(
                rows = listOf(
                    FolderSessionRow(
                        sessionName = "stale-first-host-session",
                        lastActivity = 1L,
                        attached = false,
                        cwd = "/home/alexey/first",
                        agentKind = SessionAgentKind.Shell,
                    ),
                ),
            )

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
        ): Result<String> = error("not used")

        override suspend fun createEmptyProject(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            parentPath: String,
            folderName: String,
        ): Result<String> = error("not used")

        override suspend fun importFile(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            folderPath: String,
            payload: FolderImportPayload,
        ): Result<String> = error("not used")
    }

    private class MapHostDao(vararg hosts: HostEntity) : HostDao {
        private val hostsById = hosts.associateBy { it.id }

        override fun getAll(): Flow<List<HostEntity>> = flowOf(hostsById.values.toList())
        override suspend fun getById(id: Long): HostEntity? = hostsById[id]
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(hostsById.values.toList())
        override suspend fun insert(host: HostEntity): Long = error("not used")
        override suspend fun update(host: HostEntity) = error("not used")
        override suspend fun delete(host: HostEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }

    private class EmptyProjectRootDao : ProjectRootDao {
        override fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>> = flowOf(emptyList())
        override suspend fun insert(root: ProjectRootEntity): Long = error("not used")
        override suspend fun update(root: ProjectRootEntity) = error("not used")
        override suspend fun delete(root: ProjectRootEntity) = error("not used")
    }

    private class MapProjectRootDao(
        vararg entries: Pair<Long, List<ProjectRootEntity>>,
    ) : ProjectRootDao {
        private val rootsByHost = entries.toMap()

        override fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>> =
            MutableStateFlow(rootsByHost[hostId].orEmpty())

        override suspend fun insert(root: ProjectRootEntity): Long = error("not used")
        override suspend fun update(root: ProjectRootEntity) = error("not used")
        override suspend fun delete(root: ProjectRootEntity) = error("not used")
    }

    private companion object {
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val FIRST_HOST: HostEntity = HostEntity(
            id = 101L,
            name = "first",
            hostname = "10.0.0.101",
            username = "tester",
            keyId = 1L,
        )
        val SECOND_HOST: HostEntity = HostEntity(
            id = 202L,
            name = "second",
            hostname = "10.0.0.202",
            username = "tester",
            keyId = 1L,
        )
    }
}
