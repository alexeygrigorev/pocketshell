package com.pocketshell.app.projects

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.repos.LocalRepoInfo
import com.pocketshell.app.repos.RemoteRepoInfo
import com.pocketshell.app.repos.RepoEntry
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Unit coverage for the repos-browse merge + join — issue #230.
 *
 * The clone/open state machine opens a live SSH session inside the view
 * model, so its happy/failure paths are exercised by the connected E2E
 * suite. These tests pin the pure join logic that decides whether a repo
 * renders as a "Clone" or "Open" row, plus the cloned-first ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepoBrowserViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private fun remoteEntry(
        owner: String?,
        name: String,
        fullName: String?,
        defaultBranch: String? = "main",
        updatedAt: String? = null,
    ) = RepoEntry(
        owner = owner,
        name = name,
        fullName = fullName,
        local = null,
        remote = RemoteRepoInfo(
            defaultBranch = defaultBranch,
            htmlUrl = null,
            sshUrl = null,
            updatedAt = updatedAt,
        ),
    )

    private fun localEntry(
        owner: String?,
        name: String,
        fullName: String?,
        path: String,
        head: String? = "abc123",
    ) = RepoEntry(
        owner = owner,
        name = name,
        fullName = fullName,
        local = LocalRepoInfo(path = path, head = head),
        remote = null,
    )

    @Test
    fun joinKey_prefersFullName_thenOwnerSlash_thenName() {
        assertEquals(
            "alexeygrigorev/pocketshell",
            RepoBrowserViewModel.joinKey(remoteEntry("alexeygrigorev", "pocketshell", "alexeygrigorev/pocketshell")),
        )
        assertEquals(
            "alexeygrigorev/pocketshell",
            RepoBrowserViewModel.joinKey(remoteEntry("alexeygrigorev", "pocketshell", fullName = null)),
        )
        assertEquals(
            "pocketshell",
            RepoBrowserViewModel.joinKey(remoteEntry(owner = null, name = "pocketshell", fullName = null)),
        )
    }

    @Test
    fun mergeRepos_marksRemoteRepoClonedWhenLocalCloneMatches() {
        val remote = listOf(
            remoteEntry("a", "cloned-repo", "a/cloned-repo"),
            remoteEntry("a", "github-only", "a/github-only"),
        )
        val local = listOf(
            localEntry("a", "cloned-repo", "a/cloned-repo", path = "/home/a/git/cloned-repo"),
        )

        val rows = RepoBrowserViewModel.mergeRepos(remote, local)

        val cloned = rows.single { it.fullName == "a/cloned-repo" }
        assertTrue(cloned.cloned)
        assertEquals("/home/a/git/cloned-repo", cloned.path)

        val githubOnly = rows.single { it.fullName == "a/github-only" }
        assertFalse(githubOnly.cloned)
        assertNull(githubOnly.path)
    }

    @Test
    fun mergeRepos_surfacesClonedOnlyRepoWithNoRemoteMatch() {
        val remote = listOf(remoteEntry("a", "public", "a/public"))
        val local = listOf(
            localEntry("a", "private-mirror", "a/private-mirror", path = "/home/a/git/private-mirror"),
        )

        val rows = RepoBrowserViewModel.mergeRepos(remote, local)

        assertEquals(2, rows.size)
        val mirror = rows.single { it.fullName == "a/private-mirror" }
        assertTrue(mirror.cloned)
        assertEquals("/home/a/git/private-mirror", mirror.path)
    }

    @Test
    fun mergeRepos_clonedRowsSortFirstThenByUpdatedAtDescending() {
        val remote = listOf(
            remoteEntry("a", "fresh-uncloned", "a/fresh-uncloned", updatedAt = "2026-05-28T00:00:00Z"),
            remoteEntry("a", "old-cloned", "a/old-cloned", updatedAt = "2020-01-01T00:00:00Z"),
            remoteEntry("a", "new-cloned", "a/new-cloned", updatedAt = "2026-05-27T00:00:00Z"),
        )
        val local = listOf(
            localEntry("a", "old-cloned", "a/old-cloned", path = "/git/old-cloned"),
            localEntry("a", "new-cloned", "a/new-cloned", path = "/git/new-cloned"),
        )

        val rows = RepoBrowserViewModel.mergeRepos(remote, local)

        // Cloned-first: the two cloned repos lead, sorted by updated_at desc.
        assertEquals(
            listOf("a/new-cloned", "a/old-cloned", "a/fresh-uncloned"),
            rows.map { it.fullName },
        )
        assertTrue(rows[0].cloned)
        assertTrue(rows[1].cloned)
        assertFalse(rows[2].cloned)
    }

    @Test
    fun queryRepoBrowserRows_defaultSortPreservesMergeReposOrder() {
        val mergedRows = RepoBrowserViewModel.mergeRepos(
            remote = listOf(
                remoteEntry(
                    "a",
                    "fresh-uncloned",
                    "a/fresh-uncloned",
                    updatedAt = "2026-05-28T00:00:00Z",
                ),
                remoteEntry(
                    "a",
                    "old-cloned",
                    "a/old-cloned",
                    updatedAt = "2020-01-01T00:00:00Z",
                ),
            ),
            local = listOf(
                localEntry("a", "old-cloned", "a/old-cloned", path = "/git/old-cloned"),
            ),
        )

        assertEquals(
            listOf("a/old-cloned", "a/fresh-uncloned"),
            queryRepoBrowserRows(mergedRows, RepoBrowserQuery()).map { it.fullName },
        )
    }

    @Test
    fun mergeRepos_clonedLocalEntryWithoutLocalBlockIsSkipped() {
        // A defensive case: a local-scan row missing its `local` block
        // should not produce a cloned-only row (nothing to open).
        val local = listOf(
            RepoEntry(owner = "a", name = "broken", fullName = "a/broken", local = null, remote = null),
        )

        val rows = RepoBrowserViewModel.mergeRepos(remote = emptyList(), local = local)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun mergeRepos_emptyInputsProduceEmptyList() {
        assertTrue(RepoBrowserViewModel.mergeRepos(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun queryRepoBrowserRows_searchMatchesOwnerNameAndFullNameCaseInsensitively() {
        val rows = listOf(
            // Deliberately inconsistent synthetic rows isolate the owner and
            // name fields. A fullName-only implementation must not match them.
            row(owner = "OwnerOnly", name = "stable", fullName = "remote/repo-one"),
            row(owner = "stable-owner", name = "NameOnly", fullName = "remote/repo-two"),
            row(owner = "Acme", name = "Website", fullName = "Acme/website"),
        )

        assertEquals(
            listOf("remote/repo-one"),
            queryRepoBrowserRows(rows, RepoBrowserQuery(search = "OWNERONLY"))
                .map { it.fullName },
        )
        assertEquals(
            listOf("remote/repo-two"),
            queryRepoBrowserRows(rows, RepoBrowserQuery(search = "NAMEONLY"))
                .map { it.fullName },
        )
        // This term is present only in fullName, preserving separate coverage
        // for the fullName search field.
        assertEquals(
            listOf("Acme/website"),
            queryRepoBrowserRows(rows, RepoBrowserQuery(search = "ACME/WEB"))
                .map { it.fullName },
        )
    }

    @Test
    fun queryRepoBrowserRows_ownerOptionsAndFilterAreCaseInsensitive() {
        val rows = listOf(
            row(owner = "zeta", name = "one", fullName = "zeta/one"),
            row(owner = "Acme", name = "two", fullName = "Acme/two"),
            row(owner = "acme", name = "three", fullName = "acme/three"),
            row(owner = null, name = "local-only", fullName = "local-only"),
        )

        assertEquals(listOf("Acme", "zeta"), repoBrowserOwners(rows))
        assertEquals(
            listOf("Acme/two", "acme/three"),
            queryRepoBrowserRows(rows, RepoBrowserQuery(owner = "ACME"))
                .map { it.fullName },
        )
        assertEquals(
            rows.map { it.fullName },
            queryRepoBrowserRows(rows, RepoBrowserQuery()).map { it.fullName },
        )
    }

    @Test
    fun queryRepoBrowserRows_nameSortUsesCaseInsensitiveNameThenFullName() {
        val rows = listOf(
            row(owner = "z", name = "README", fullName = "z/readme"),
            row(owner = "a", name = "readme", fullName = "a/readme"),
            row(owner = "a", name = "alpha", fullName = "a/alpha"),
        )

        assertEquals(
            listOf("a/alpha", "a/readme", "z/readme"),
            queryRepoBrowserRows(
                rows,
                RepoBrowserQuery(sortOrder = RepoBrowserSortOrder.NAME_ASC),
            ).map { it.fullName },
        )
    }

    @Test
    fun queryRepoBrowserRows_lastChangedSortPutsNewestFirstAndMissingLast() {
        val rows = listOf(
            row(
                owner = "z",
                name = "old",
                fullName = "z/old",
                updatedAt = "2026-01-01T00:00:00Z",
            ),
            row(
                owner = "a",
                name = "new",
                fullName = "a/new",
                updatedAt = "2026-08-01T00:00:00Z",
            ),
            row(owner = "z", name = "missing", fullName = "z/missing"),
            row(
                owner = "a",
                name = "invalid",
                fullName = "a/invalid",
                updatedAt = "not-a-timestamp",
            ),
        )

        assertEquals(
            listOf("a/new", "z/old", "a/invalid", "z/missing"),
            queryRepoBrowserRows(
                rows,
                RepoBrowserQuery(sortOrder = RepoBrowserSortOrder.LAST_CHANGED),
            ).map { it.fullName },
        )
    }

    @Test
    fun queryRepoBrowserRows_searchOwnerAndSortComposeLocally() {
        val rows = listOf(
            row(
                owner = "Acme",
                name = "old",
                fullName = "Acme/old",
                updatedAt = "2026-01-01T00:00:00Z",
            ),
            row(
                owner = "Acme",
                name = "new",
                fullName = "Acme/new",
                updatedAt = "2026-08-01T00:00:00Z",
            ),
            row(
                owner = "Other",
                name = "newer",
                fullName = "Other/newer",
                updatedAt = "2026-09-01T00:00:00Z",
            ),
        )

        assertEquals(
            listOf("Acme/new", "Acme/old"),
            queryRepoBrowserRows(
                rows,
                RepoBrowserQuery(
                    search = "acme",
                    owner = "acme",
                    sortOrder = RepoBrowserSortOrder.LAST_CHANGED,
                ),
            ).map { it.fullName },
        )
    }

    @Test
    fun updateSearchQuery_onlyTransformsLoadedStateWithoutRemoteCall() = runTest(testDispatcher) {
        val fixture = FakeRepoBrowserSshFixture()
        val viewModel = loadedViewModel(fixture, this)
        val before = viewModel.state.value as RepoBrowserUiState.Ready
        val commandsBefore = fixture.session.recordedCommands.toList()
        val remoteCallsBefore = fixture.session.remoteListCount
        val localCallsBefore = fixture.session.localListCount

        // Several successive edits exercise the same loaded-state path that a
        // user follows while typing; none may start another SSH enumeration.
        viewModel.updateSearchQuery("A")
        viewModel.updateSearchQuery("AC")
        viewModel.updateSearchQuery("ACM")
        viewModel.updateSearchQuery("ACME")
        viewModel.updateOwnerFilter("Acme")
        viewModel.updateOwnerFilter(null)
        viewModel.updateSortOrder(RepoBrowserSortOrder.NAME_ASC)
        viewModel.updateSortOrder(RepoBrowserSortOrder.LAST_CHANGED)

        assertEquals(
            before.copy(
                query = before.query.copy(
                    search = "ACME",
                    owner = null,
                    sortOrder = RepoBrowserSortOrder.LAST_CHANGED,
                ),
            ),
            viewModel.state.value,
        )
        assertEquals(commandsBefore, fixture.session.recordedCommands)
        assertEquals(remoteCallsBefore, fixture.session.remoteListCount)
        assertEquals(localCallsBefore, fixture.session.localListCount)
    }

    @Test
    fun updateOwnerFilter_onlyTransformsLoadedStateWithoutRemoteCall() = runTest(testDispatcher) {
        val fixture = FakeRepoBrowserSshFixture()
        val viewModel = loadedViewModel(fixture, this)
        val before = viewModel.state.value as RepoBrowserUiState.Ready
        val callsBefore = fixture.session.recordedCommands.size

        viewModel.updateOwnerFilter("Acme")

        assertEquals(
            before.copy(query = before.query.copy(owner = "Acme")),
            viewModel.state.value,
        )
        assertEquals(callsBefore, fixture.session.recordedCommands.size)
    }

    @Test
    fun updateSortOrder_onlyTransformsLoadedStateWithoutRemoteCall() = runTest(testDispatcher) {
        val fixture = FakeRepoBrowserSshFixture()
        val viewModel = loadedViewModel(fixture, this)
        val before = viewModel.state.value as RepoBrowserUiState.Ready
        val callsBefore = fixture.session.recordedCommands.size

        viewModel.updateSortOrder(RepoBrowserSortOrder.LAST_CHANGED)

        assertEquals(
            before.copy(
                query = before.query.copy(sortOrder = RepoBrowserSortOrder.LAST_CHANGED),
            ),
            viewModel.state.value,
        )
        assertEquals(callsBefore, fixture.session.recordedCommands.size)
    }

    @Test
    fun queryState_survivesRefreshReload() = runTest(testDispatcher) {
        val fixture = FakeRepoBrowserSshFixture()
        val viewModel = loadedViewModel(fixture, this)
        val expectedQuery = RepoBrowserQuery(
            search = "acme",
            owner = "Acme",
            sortOrder = RepoBrowserSortOrder.LAST_CHANGED,
        )
        viewModel.updateSearchQuery(expectedQuery.search)
        viewModel.updateOwnerFilter(expectedQuery.owner)
        viewModel.updateSortOrder(expectedQuery.sortOrder)
        val callsBefore = fixture.session.recordedCommands.size

        viewModel.refresh()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000L) {
                while (fixture.session.localListCount < 2) delay(1L)
            }
        }
        mainDispatcherRule.runCurrent()

        assertEquals(callsBefore + 2, fixture.session.recordedCommands.size)
        assertEquals(expectedQuery, (viewModel.state.value as RepoBrowserUiState.Ready).query)
    }

    private suspend fun loadedViewModel(
        fixture: FakeRepoBrowserSshFixture,
        scope: CoroutineScope,
    ): RepoBrowserViewModel {
        val viewModel = RepoBrowserViewModel(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector { Result.success(fixture.session) },
                scope = scope,
                idleTtlMillis = 30_000L,
                connectTimeoutContext = Dispatchers.Unconfined,
            ),
        )
        viewModel.bind(testCredentials())
        val ready = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeoutOrNull(2_000L) {
                viewModel.state.filterIsInstance<RepoBrowserUiState.Ready>().first()
            }
        }
        check(ready != null) {
            "repo browser did not load: state=${viewModel.state.value}, " +
                "commands=${fixture.session.recordedCommands}, " +
                "remote=${fixture.session.remoteListCount}, local=${fixture.session.localListCount}"
        }
        return viewModel
    }

    private fun testCredentials() = RepoBrowserViewModel.SshCredentials(
        hostId = 42L,
        hostname = "docker",
        port = 2222,
        username = "testuser",
        keyPath = "/tmp/pocketshell-test-key",
        passphrase = null,
    )

    private fun row(
        owner: String?,
        name: String,
        fullName: String,
        updatedAt: String? = null,
    ) = RepoRow(
        fullName = fullName,
        name = name,
        owner = owner,
        cloned = false,
        path = null,
        defaultBranch = "main",
        updatedAt = updatedAt,
    )

    private class FakeRepoBrowserSshFixture {
        val session = FakeRepoBrowserSshSession()
    }

    private class FakeRepoBrowserSshSession : SshSession {
        val recordedCommands = mutableListOf<String>()
        @Volatile
        var remoteListCount: Int = 0
        @Volatile
        var localListCount: Int = 0

        override val isConnected: Boolean
            get() = true

        override suspend fun exec(command: String): ExecResult {
            recordedCommands += command
            return when {
                command.contains("repos list --remote --json") -> {
                    remoteListCount += 1
                    ExecResult(REMOTE_PAYLOAD, "", 0)
                }
                command.contains("repos list --local --json") -> {
                    localListCount += 1
                    ExecResult(LOCAL_PAYLOAD, "", 0)
                }
                else -> error("unexpected repo browser command: $command")
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("tail not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used")

        override fun close() = Unit

        private companion object {
            val REMOTE_PAYLOAD = """
                [
                  {
                    "owner": "Acme",
                    "name": "old",
                    "full_name": "Acme/old",
                    "local": null,
                    "remote": {
                      "default_branch": "main",
                      "html_url": null,
                      "ssh_url": null,
                      "updated_at": "2026-01-01T00:00:00Z"
                    }
                  },
                  {
                    "owner": "Other",
                    "name": "new",
                    "full_name": "Other/new",
                    "local": null,
                    "remote": {
                      "default_branch": "main",
                      "html_url": null,
                      "ssh_url": null,
                      "updated_at": "2026-08-01T00:00:00Z"
                    }
                  }
                ]
            """.trimIndent()

            val LOCAL_PAYLOAD = """
                [
                  {
                    "owner": "Acme",
                    "name": "old",
                    "full_name": "Acme/old",
                    "local": {
                      "path": "/home/test/git/old",
                      "head": "abc123"
                    },
                    "remote": null
                  }
                ]
            """.trimIndent()
        }
    }
}
