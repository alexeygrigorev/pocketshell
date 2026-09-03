package com.pocketshell.core.hostapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field-level parser coverage for the two picker listings.
 *
 * `engines-list-real.json` and `profiles-list-real.json` are REAL captures
 * from the dev box (`pocketshell engines list --json` / `pocketshell profiles
 * list --json`, CLI 0.4.47 — the same `engines.py`/`profiles.py` bytes as this
 * branch's source). The real engines capture happens to include an
 * unavailable engine (`opencode`, harness not installed), so the
 * not-createable path is covered by real data rather than an invented row.
 * Every other fixture here is hand-built for a state the live box was not in.
 */
class EnginesProfilesJsonTest {

    // --- engines: real capture -------------------------------------------

    private fun engines(): List<EngineInfo> =
        EnginesJson.parseEnginesList(fixture("engines-list-real.json")).getOrThrow()

    @Test
    fun `real engines capture parses every row in order`() {
        assertEquals(
            listOf("claude", "codex", "opencode", "grok", "zcodex"),
            engines().map { it.id },
        )
    }

    @Test
    fun `real engines capture maps every field of an available engine`() {
        val claude = engines().first { it.id == "claude" }

        assertEquals(
            EngineInfo(
                id = "claude",
                label = "Claude",
                family = "claude",
                harness = "claude",
                providerMark = "Anthropic",
                usageProvider = "claude",
                enabled = true,
                available = true,
                availableForCreate = true,
                unavailableReason = null,
            ),
            claude,
        )
    }

    @Test
    fun `real engines capture keeps an unavailable engine with the host's reason`() {
        // Hiding the row would leave the user wondering why an engine they
        // installed is missing; the reason is what makes it actionable.
        val opencode = engines().first { it.id == "opencode" }

        assertFalse(opencode.availableForCreate)
        assertFalse(opencode.available)
        assertTrue(opencode.enabled)
        assertEquals(
            "`opencode` is not installed on this host (not on PATH).",
            opencode.unavailableReason,
        )
    }

    @Test
    fun `an engine family can differ from its id`() {
        val zcodex = engines().first { it.id == "zcodex" }

        assertEquals("codex", zcodex.family)
        assertEquals("zcodex", zcodex.harness)
        assertEquals("zai", zcodex.usageProvider)
    }

    @Test
    fun `the launch block the host emits is deliberately dropped`() {
        // Not an omission: since schema 2 the HOST starts the agent, so a
        // phone-side copy of the launch argv would be a second, drifting
        // source of truth. The real capture carries a full `launch` object
        // for every row and parsing it must stay a no-op.
        assertTrue(fixture("engines-list-real.json").contains("\"launch\""))
        assertEquals(5, engines().size)
    }

    // --- engines: edge cases ---------------------------------------------

    @Test
    fun `an engines listing with no engines is a success, not a failure`() {
        assertEquals(
            emptyList<EngineInfo>(),
            EnginesJson.parseEnginesList(fixture("engines-list-empty.json")).getOrThrow(),
        )
    }

    @Test
    fun `a missing engines key fails instead of looking like an empty registry`() {
        val error = EnginesJson.parseEnginesList(fixture("engines-list-missing-key.json"))
            .hostCliError()

        assertTrue(error is HostCliError.Malformed)
        assertEquals(
            "Could not read the host's response: missing the `engines` field",
            error.userMessage,
        )
    }

    @Test
    fun `a row missing available_for_create fails the whole listing`() {
        // Defaulting it either way is a lie: `true` offers an engine that
        // cannot start, `false` hides a working one.
        val error = EnginesJson.parseEnginesList(fixture("engines-list-malformed-row.json"))
            .hostCliError()

        assertTrue(error is HostCliError.Malformed)
        assertTrue(error.userMessage.contains("did not match the expected shape"))
    }

    @Test
    fun `a minimal row survives with defaults and unknown keys are ignored`() {
        val engines =
            EnginesJson.parseEnginesList(fixture("engines-list-minimal-row.json")).getOrThrow()

        val only = engines.single()
        assertEquals("futurecode", only.id)
        assertEquals("FutureCode", only.label)
        assertFalse(only.availableForCreate)
        assertEquals("", only.family)
        assertEquals("", only.harness)
        assertEquals("", only.providerMark)
        assertNull(only.usageProvider)
        assertTrue(only.enabled)
        assertTrue(only.available)
    }

    @Test
    fun `engines parsing fails cleanly on garbage`() {
        assertTrue(
            EnginesJson.parseEnginesList("not json at all").hostCliError()
                is HostCliError.Malformed,
        )
        assertTrue(
            EnginesJson.parseEnginesList("[]").hostCliError() is HostCliError.Malformed,
        )
        assertTrue(
            EnginesJson.parseEnginesList("").hostCliError() is HostCliError.Malformed,
        )
    }

    // --- profiles: real capture ------------------------------------------

    private fun profiles(): List<ProfileInfo> =
        ProfilesJson.parseProfilesList(fixture("profiles-list-real.json")).getOrThrow()

    @Test
    fun `real profiles capture parses every row in order`() {
        assertEquals(
            listOf("Claude", "Claude (Z.AI)", "Codex", "Godex", "Zcodex", "Zodex"),
            profiles().map { it.name },
        )
    }

    @Test
    fun `a built-in default profile has no config dir`() {
        val claude = profiles().first()

        assertEquals(
            ProfileInfo(name = "Claude", engine = "claude", configDir = null, isDefault = true),
            claude,
        )
    }

    @Test
    fun `an alternative profile carries its config dir and is not default`() {
        val zlaude = profiles().first { it.name == "Claude (Z.AI)" }

        assertEquals("claude", zlaude.engine)
        assertEquals("/home/alexey/.zlaude", zlaude.configDir)
        assertFalse(zlaude.isDefault)
    }

    @Test
    fun `each engine has exactly one default profile in the real capture`() {
        val defaultsByEngine = profiles().filter { it.isDefault }.groupBy { it.engine }

        assertEquals(setOf("claude", "codex"), defaultsByEngine.keys)
        assertTrue(defaultsByEngine.values.all { it.size == 1 })
    }

    // --- profiles: edge cases --------------------------------------------

    @Test
    fun `a profiles listing with no profiles is a success, not a failure`() {
        assertEquals(
            emptyList<ProfileInfo>(),
            ProfilesJson.parseProfilesList(fixture("profiles-list-empty.json")).getOrThrow(),
        )
    }

    @Test
    fun `a missing profiles key fails instead of looking like no profiles`() {
        val error = ProfilesJson.parseProfilesList(fixture("profiles-list-missing-key.json"))
            .hostCliError()

        assertEquals(
            "Could not read the host's response: missing the `profiles` field",
            error.userMessage,
        )
    }

    @Test
    fun `a profile row with no engine fails the whole listing`() {
        // `--profile NAME` is meaningless without knowing which engine it
        // belongs to, so the row cannot be shown and cannot be skipped.
        val error = ProfilesJson.parseProfilesList(fixture("profiles-list-malformed-row.json"))
            .hostCliError()

        assertTrue(error is HostCliError.Malformed)
    }

    @Test
    fun `a profile row omitting config_dir and default takes the safe defaults`() {
        val profiles = ProfilesJson.parseProfilesList(
            """{"profiles":[{"name":"Sparse","engine":"codex"}]}""",
        ).getOrThrow()

        val only = profiles.single()
        assertNull(only.configDir)
        assertFalse(only.isDefault)
    }

    @Test
    fun `profiles parsing fails cleanly on garbage`() {
        assertTrue(
            ProfilesJson.parseProfilesList("<html>500</html>").hostCliError()
                is HostCliError.Malformed,
        )
        assertTrue(
            ProfilesJson.parseProfilesList("null").hostCliError() is HostCliError.Malformed,
        )
    }
}
