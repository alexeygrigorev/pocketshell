package com.pocketshell.next.tree

import com.pocketshell.core.hostapi.EngineInfo
import com.pocketshell.core.hostapi.ProfileInfo
import com.pocketshell.core.hostapi.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The create sheet's form rules, with no composition at all (task U-6).
 *
 * The load-bearing rule is that the host CLI REQUIRES a name — `sessions create
 * NAME` takes it as a positional argument and derives nothing from `--cwd` — so
 * the sheet must always send one. It prefills the folder's last segment and
 * refuses to submit while the field is blank; both halves are asserted here
 * because either one alone would let a blank name reach the host as a usage
 * error the user cannot read.
 */
class CreateSessionFormStateTest {

    @Test
    fun `the derived name is the folders last segment`() {
        assertEquals("pocketshell", defaultSessionName("/home/alexey/git/pocketshell"))
        assertEquals("pocketshell", defaultSessionName("/home/alexey/git/pocketshell/"))
        assertEquals("pocketshell", defaultSessionName("  /home/alexey/git/pocketshell  "))
        assertEquals("aplexer", defaultSessionName("~/git/aplexer"))
        assertEquals("notes", defaultSessionName("notes"))
    }

    @Test
    fun `a folder with nothing to derive from yields no name`() {
        assertEquals("", defaultSessionName(""))
        assertEquals("", defaultSessionName("   "))
        assertEquals("", defaultSessionName("/"))
        assertEquals("", defaultSessionName("~"))
        assertEquals("", defaultSessionName("."))
        assertEquals("", defaultSessionName("../"))
    }

    /**
     * Session names are normalised to stable folder-safe labels before they
     * reach the host.
     */
    @Test
    fun `the derived name is normalised for the host`() {
        assertEquals("agent.v2", defaultSessionName("/srv/agent.v2"))
        assertEquals("work:review", defaultSessionName("/srv/work:review"))
        assertEquals(".hidden", defaultSessionName("/srv/.hidden"))
    }

    @Test
    fun `the name tracks the folder until the user types a name of their own`() {
        val form = CreateSessionFormState("/home/a/git/pocketshell")
        assertEquals("pocketshell", form.name)

        form.onFolderChange("/home/a/git/aplexer")
        assertEquals("aplexer", form.name)

        form.onNameChange("review")
        form.onFolderChange("/home/a/git/something-else")

        assertEquals("a typed name must survive a later folder edit", "review", form.name)
        assertEquals("/home/a/git/something-else", form.folder)
        assertTrue(form.nameEdited)
    }

    @Test
    fun `a blank name cannot be submitted`() {
        val form = CreateSessionFormState("/")
        assertEquals("", form.name)
        assertFalse(form.canSubmit)

        form.onNameChange("   ")
        assertFalse("whitespace is not a name", form.canSubmit)

        form.onNameChange("demo")
        assertTrue(form.canSubmit)
    }

    @Test
    fun `submitted values are trimmed, and a blank folder means no cwd at all`() {
        val form = CreateSessionFormState("")
        form.onNameChange("  demo  ")
        assertEquals("demo", form.submittedName)
        assertNull("a blank folder must not become an empty --cwd", form.submittedCwd)

        form.onFolderChange("   ")
        assertNull(form.submittedCwd)

        form.onFolderChange("  /home/a/git/pocketshell ")
        assertEquals("/home/a/git/pocketshell", form.submittedCwd)
    }

    /**
     * Issue #2439 / #2522: a provider may be started only when the host says it
     * is enabled, available, and createable. A dropped-row regression (hiding a
     * working engine) or a shown-row regression (offering a disabled one) both
     * redden here.
     */
    @Test
    fun `disabled and unavailable engines are hidden, enabled plus available is shown`() {
        val rows = availableEnginesForCreate(
            listOf(
                testEngine("claude", enabled = true, available = true),
                testEngine("codex", enabled = true, available = true),
                testEngine("opencode", enabled = true, available = false, availableForCreate = false),
                testEngine("disabled", enabled = false, available = true, availableForCreate = false),
                testEngine("not-createable", enabled = true, available = true, availableForCreate = false),
                testEngine("shell", enabled = true, available = true),
            ),
        )

        assertEquals(listOf("claude", "codex"), rows.map { it.id })
        assertFalse(rows.any { it.id == "opencode" })
        assertFalse(rows.any { it.id == "disabled" })
        assertFalse(rows.any { it.id == "not-createable" })
        assertFalse(rows.any { it.id == "shell" })
    }

    @Test
    fun `a shell create with the host default omits engine and profile`() {
        val form = CreateSessionFormState("/home/a/git/pocketshell")

        assertEquals(
            CreateSessionRequest(name = "pocketshell", cwd = "/home/a/git/pocketshell"),
            form.toRequest(listOf(testEngine("claude")), emptyList()),
        )
    }

    @Test
    fun `an agent create forwards the selected engine without a backend field`() {
        val form = CreateSessionFormState("/home/a/git/pocketshell")
        form.onKindChange(CreateSessionKind.Agent)
        form.onEngineChange("codex")

        val request = form.toRequest(
            engines = listOf(testEngine("claude"), testEngine("codex"), testEngine("opencode", available = false)),
            profiles = emptyList(),
        )

        assertEquals("codex", request.engine)
        assertNull(request.profile)
        assertEquals("pocketshell", request.name)
    }

    @Test
    fun `an agent create forwards a profile only after the user picks one`() {
        val form = CreateSessionFormState("/srv")
        form.onKindChange(CreateSessionKind.Agent)
        form.onEngineChange("claude")
        form.onProfileChange("Claude (Z.AI)")

        val request = form.toRequest(
            engines = listOf(testEngine("claude")),
            profiles = listOf(
                ProfileInfo("Claude", "claude", null, isDefault = true),
                ProfileInfo("Claude (Z.AI)", "claude", "/home/a/.zlaude", isDefault = false),
            ),
        )

        assertEquals("claude", request.engine)
        assertEquals("Claude (Z.AI)", request.profile)
    }

    @Test
    fun `switching to shell drops a previously chosen engine from the request`() {
        val form = CreateSessionFormState("/srv")
        form.onKindChange(CreateSessionKind.Agent)
        form.onEngineChange("claude")
        form.onKindChange(CreateSessionKind.Shell)

        val request = form.toRequest(listOf(testEngine("claude")), emptyList())
        assertNull(request.engine)
        assertNull(request.profile)
    }

    /**
     * Schema-3 rows display as `<workspace>:<tag>`, but a create collides on
     * the TAG alone (the host CLI answers `created=false` for a repeat). Feed
     * the collision check display names and every bare tag looks free: the
     * sheet re-proposes the occupied tag and the second default session
     * silently no-ops.
     */
    @Test
    fun `a schema-3 display name does not hide an occupied tag`() {
        val row = SessionRow(
            name = "pocketshell:pocketshell",
            id = "b1ecbe62-abcd-4321-9f2e-2f37a6f0cd66",
            workspace = "/home/testuser/git/pocketshell",
            tag = "pocketshell",
            engine = "shell",
            profile = null,
            agent = null,
            agentState = null,
            agentStateSource = null,
            attached = false,
            createdEpoch = null,
            activityEpoch = null,
        )

        val tags = existingSessionTags(listOf(row))
        assertEquals("pocketshell", tags.single())
        assertEquals("pocketshell 2", collisionSafeSessionName("pocketshell", tags))
    }

    @Test
    fun `rows without a tag fall back to their raw name for collision`() {
        val row = SessionRow(
            name = "bare-name",
            id = null,
            workspace = null,
            tag = null,
            engine = null,
            profile = null,
            agent = null,
            agentState = null,
            agentStateSource = null,
            attached = false,
            createdEpoch = null,
            activityEpoch = null,
        )

        assertEquals("bare-name", existingSessionTags(listOf(row)).single())
    }
}

internal fun testEngine(
    id: String,
    label: String = id.replaceFirstChar { it.uppercase() },
    enabled: Boolean = true,
    available: Boolean = true,
    availableForCreate: Boolean = enabled && available,
): EngineInfo = EngineInfo(
    id = id,
    label = label,
    family = id,
    harness = id,
    providerMark = "",
    usageProvider = null,
    enabled = enabled,
    available = available,
    availableForCreate = availableForCreate,
    unavailableReason = if (availableForCreate) null else "unavailable",
)
