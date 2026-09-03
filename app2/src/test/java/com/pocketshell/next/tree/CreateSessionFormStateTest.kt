package com.pocketshell.next.tree

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
     * tmux rejects `:` and `.` inside a session name (they address a window and
     * a pane), so a folder named `agent.v2` must not prefill a name the host is
     * obliged to refuse.
     */
    @Test
    fun `the derived name never carries a character tmux rejects`() {
        assertEquals("agent-v2", defaultSessionName("/srv/agent.v2"))
        assertEquals("work-review", defaultSessionName("/srv/work:review"))
        assertEquals("hidden", defaultSessionName("/srv/.hidden"))
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
}
