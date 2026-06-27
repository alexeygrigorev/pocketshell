package com.pocketshell.app.sessions

import com.pocketshell.app.hosts.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class StartDirectoryAutocompleteTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun requestParsesHomePrefix() {
        val request = StartDirectoryAutocompleteRequest.from("~/code/p")!!

        assertEquals("~/code", request.parentDirectory)
        assertEquals("p", request.childPrefix)
        assertEquals("~/code/", request.suggestionPrefix)
    }

    @Test
    fun requestParsesAbsolutePrefixAndTrailingSlash() {
        val absolute = StartDirectoryAutocompleteRequest.from("/var/l")!!
        assertEquals("/var", absolute.parentDirectory)
        assertEquals("l", absolute.childPrefix)
        assertEquals("/var/", absolute.suggestionPrefix)

        val trailing = StartDirectoryAutocompleteRequest.from("/var/log/")!!
        assertEquals("/var/log", trailing.parentDirectory)
        assertEquals("", trailing.childPrefix)
        assertEquals("/var/log/", trailing.suggestionPrefix)
    }

    @Test
    fun requestParsesBareAndRootPrefixes() {
        val bare = StartDirectoryAutocompleteRequest.from("proj")!!
        assertEquals(".", bare.parentDirectory)
        assertEquals("proj", bare.childPrefix)
        assertEquals("", bare.suggestionPrefix)

        val root = StartDirectoryAutocompleteRequest.from("/")!!
        assertEquals("/", root.parentDirectory)
        assertEquals("", root.childPrefix)
        assertEquals("/", root.suggestionPrefix)
    }

    @Test
    fun requestRejectsBlankPrefix() {
        assertNull(StartDirectoryAutocompleteRequest.from(""))
        assertNull(StartDirectoryAutocompleteRequest.from("   "))
    }

    @Test
    fun commandQuotesParentAndPrefixAndExpandsHome() {
        val request = StartDirectoryAutocompleteRequest.from("~/it isn't/prefix; rm -rf \$HOME", limit = 7)!!

        val command = startDirectoryAutocompleteCommand(request)

        assertTrue(command.contains("pocketshell_ac_parent='~/it isn'\\''t'"))
        assertTrue(command.contains("pocketshell_ac_prefix='prefix; rm -rf \$HOME'"))
        assertTrue(command.contains("'~/'*) pocketshell_ac_parent=\$HOME/\${pocketshell_ac_parent#~/} ;;"))
        assertTrue(command.contains("find -L \"\$pocketshell_ac_parent\" -mindepth 1 -maxdepth 1 -type d -print"))
        assertTrue(command.contains("\"\$pocketshell_ac_prefix\"*) ;;"))
        assertTrue(command.contains("[ \"\$pocketshell_ac_count\" -ge 7 ] && break"))
        assertFalse(command.contains("/\"\$pocketshell_ac_prefix\"*"))
    }

    @Test
    fun commandTreatsPrefixAsLiteralWhenFilteringFindOutput() {
        val temp = Files.createTempDirectory("pocketshell-ac").toFile()
        try {
            java.io.File(temp, "lit[abc-one").mkdir()
            java.io.File(temp, "lita-one").mkdir()
            java.io.File(temp, "lit[abc-file").writeText("not a directory")

            val request = StartDirectoryAutocompleteRequest.from("lit[abc", limit = 5)!!
            val process = ProcessBuilder("/bin/sh", "-c", startDirectoryAutocompleteCommand(request))
                .directory(temp)
                .redirectErrorStream(true)
                .start()
            val output = process
                .inputStream
                .bufferedReader()
                .readText()
            val exitCode = process.waitFor()

            assertEquals(0, exitCode)
            assertEquals("lit[abc-one/\n", output)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun outputParserAddsTypedSuggestionPrefixAndKeepsDirectoriesOnly() {
        val request = StartDirectoryAutocompleteRequest.from("/srv/app")!!

        val suggestions = parseStartDirectoryAutocompleteOutput(
            request = request,
            stdout = "api/\nfile.txt\nnested/child/\nweb/\r\n",
        )

        assertEquals(listOf("/srv/api/", "/srv/web/"), suggestions)
    }

    @Test
    fun controllerDebouncesInputBeforeFetching() = runTest {
        val calls = mutableListOf<String>()
        val controller = StartDirectoryAutocompleteController(
            scope = this,
            debounceMs = 200L,
            suggest = { query ->
                calls += query
                listOf("$query/")
            },
        )

        controller.onInputChanged("/srv/a")
        advanceTimeBy(199L)
        runCurrent()
        assertTrue(calls.isEmpty())

        advanceTimeBy(1L)
        advanceUntilIdle()

        assertEquals(listOf("/srv/a"), calls)
        assertEquals(listOf("/srv/a/"), controller.state.value.suggestions)
    }

    @Test
    fun controllerCancelsStaleRequestAndKeepsLatestResults() = runTest {
        val calls = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val controller = StartDirectoryAutocompleteController(
            scope = this,
            debounceMs = 0L,
            suggest = { query ->
                calls += query
                try {
                    delay(500L)
                    listOf("$query/")
                } finally {
                    if (query == "/srv/a") cancelled += query
                }
            },
        )

        controller.onInputChanged("/srv/a")
        runCurrent()
        controller.onInputChanged("/srv/b")
        advanceTimeBy(500L)
        advanceUntilIdle()

        assertEquals(listOf("/srv/a", "/srv/b"), calls)
        assertEquals(listOf("/srv/a"), cancelled)
        assertEquals(listOf("/srv/b/"), controller.state.value.suggestions)
    }

    @Test
    fun controllerAcceptsHighlightedSuggestionAndClearsMenu() = runTest {
        val controller = StartDirectoryAutocompleteController(
            scope = this,
            debounceMs = 0L,
            suggest = { listOf("/srv/app/", "/srv/api/") },
        )

        controller.onInputChanged("/srv/a")
        advanceUntilIdle()

        assertEquals("/srv/app/", controller.acceptHighlighted())
        assertTrue(controller.state.value.suggestions.isEmpty())
        assertFalse(controller.state.value.loading)
    }

    @Test
    fun controllerTimesOutSlowSuggestionAndIgnoresItsResult() = runTest {
        val controller = StartDirectoryAutocompleteController(
            scope = this,
            debounceMs = 0L,
            requestTimeoutMs = 100L,
            suggest = {
                delay(1_000L)
                listOf("/srv/app/")
            },
        )

        controller.onInputChanged("/srv/a")
        runCurrent()
        assertTrue(controller.state.value.loading)

        advanceTimeBy(100L)
        runCurrent()

        assertEquals(emptyList<String>(), controller.state.value.suggestions)
        assertFalse(controller.state.value.loading)

        advanceTimeBy(900L)
        runCurrent()

        assertEquals(emptyList<String>(), controller.state.value.suggestions)
        assertFalse(controller.state.value.loading)
    }

    @Test
    fun controllerDisposeClearsLoading() = runTest {
        val controller = StartDirectoryAutocompleteController(
            scope = this,
            debounceMs = 0L,
            suggest = {
                delay(1_000L)
                listOf("/srv/app/")
            },
        )

        controller.onInputChanged("/srv/a")
        runCurrent()
        assertTrue(controller.state.value.loading)

        controller.dispose()
        runCurrent()

        assertEquals(emptyList<String>(), controller.state.value.suggestions)
        assertFalse(controller.state.value.loading)
    }
}
