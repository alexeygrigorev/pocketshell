package com.pocketshell.app.repos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReposJsonParserTest {
    private val parser = ReposJsonParser()

    @Test
    fun parseList_parsesRemoteAndLocalEntries() {
        val repos = parser.parseList(
            """
            [
              {
                "owner": "alexeygrigorev",
                "name": "pocketshell",
                "full_name": "alexeygrigorev/pocketshell",
                "local": {"path": "/home/alexey/git/pocketshell", "head": "main"},
                "remote": {
                  "default_branch": "main",
                  "html_url": "https://github.com/alexeygrigorev/pocketshell",
                  "ssh_url": "git@github.com:alexeygrigorev/pocketshell.git",
                  "updated_at": "2026-05-27T00:00:00Z"
                }
              }
            ]
            """.trimIndent(),
        )

        assertEquals(
            RepoEntry(
                owner = "alexeygrigorev",
                name = "pocketshell",
                fullName = "alexeygrigorev/pocketshell",
                local = LocalRepoInfo(
                    path = "/home/alexey/git/pocketshell",
                    head = "main",
                ),
                remote = RemoteRepoInfo(
                    defaultBranch = "main",
                    htmlUrl = "https://github.com/alexeygrigorev/pocketshell",
                    sshUrl = "git@github.com:alexeygrigorev/pocketshell.git",
                    updatedAt = "2026-05-27T00:00:00Z",
                ),
            ),
            repos.single(),
        )
    }

    @Test
    fun parseList_allowsNullMetadata() {
        val repos = parser.parseList(
            """
            [
              {
                "owner": null,
                "name": "internal",
                "full_name": null,
                "local": {"path": "/work/internal", "head": null},
                "remote": null
              }
            ]
            """.trimIndent(),
        )

        val repo = repos.single()
        assertNull(repo.owner)
        assertEquals("internal", repo.name)
        assertNull(repo.fullName)
        assertEquals(LocalRepoInfo(path = "/work/internal", head = null), repo.local)
        assertNull(repo.remote)
    }

    @Test
    fun parseList_rejectsMalformedJson() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseList("not-json")
        }
    }

    @Test
    fun parseList_rejectsMissingName() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseList("""[{"owner":"alexeygrigorev"}]""")
        }
    }
}
