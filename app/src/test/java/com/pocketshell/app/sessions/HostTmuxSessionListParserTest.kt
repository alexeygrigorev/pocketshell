package com.pocketshell.app.sessions

import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTmuxSessionListParserTest {
    private val parser = HostTmuxSessionListParser()

    @Test
    fun parsePocketshellSessionsListSkipsHeaderAndHints() {
        val output = """
            IDX  SESSION               CREATED
            1    codex                 2026-05-23 10:00:00
            2    claude-main           2026-05-23 09:30:00

            Join a session: pocketshell sessions <id> or pocketshell sessions <session>
            Create a new one: pocketshell sessions :<session>
        """.trimIndent()

        val rows = parser.parsePocketshellSessionsList(output)

        assertEquals(listOf("codex", "claude-main"), rows.map { it.name })
        assertFalse(rows.first().attached)
        assertEquals(rows.first().createdAt, rows.first().lastActivity)
    }

    @Test
    fun parsePocketshellSessionsListKeepsNamesWiderThanPrintedColumn() {
        val row = parser.parsePocketshellSessionsListRow(
            "12   very-long-agent-session-name  2026-05-23 08:00:00",
        )

        assertEquals("very-long-agent-session-name", row?.name)
    }

    @Test
    fun parseTmuxListSessionsPreservesCustomRawIdAndResolvedFamily() {
        val rows = parser.parseTmuxListSessions(
            "${'$'}7::custom-session::100::200::1::custom-codex::/srv/custom\n" +
                "foreign::150::150::0::::/srv/foreign\n",
            familyForRawId = { rawId ->
                if (rawId == "custom-codex") SessionAgentKind.Codex else null
            },
        )

        assertEquals(listOf("custom-session", "foreign"), rows.map { it.name })
        assertEquals("custom-codex", rows[0].recordedKindId)
        assertEquals(SessionAgentKind.Codex, rows[0].recordedKind)
        assertEquals(SessionAgentKind.Codex, rows[0].agentKind)
        assertEquals("${'$'}7", rows[0].tmuxSessionId)
        assertEquals("/srv/custom", rows[0].path)
        assertNull(rows[1].recordedKindId)
        assertNull(rows[1].recordedKind)
        assertEquals(SessionAgentKind.Shell, rows[1].agentKind)
    }

    @Test
    fun parseTmuxListSessionsRejectsMalformedRows() {
        assertTrue(parser.parseTmuxListSessions("not-a-tmux-row").isEmpty())
        assertTrue(parser.parseTmuxListSessions("{}\n").isEmpty())
    }

    /**
     * Issue #200 regression: when a session name overflows the underlying
     * `tmuxctl list` printf padding for the SESSION column (proxied
     * byte-for-byte by `pocketshell sessions list`), the row separator
     * collapses to a single space before the trailing CREATED timestamp.
     * The previous regex required `\s{2,}` between the name and the
     * timestamp, so these rows were silently dropped — the maintainer
     * with 11 sessions on the host saw only the short-named subset.
     * This test pins the parser against the verbatim Hetzner output so
     * we don't regress on the same printf-padding edge.
     */
    @Test
    fun parsePocketshellSessionsListReturnsAllRowsWhenNamesOverflowSessionColumn() {
        val output = """
            IDX  SESSION               CREATED
            1    git-pocketshell-c     2026-05-24 11:32:14
            2    git-llm-zoomcamp      2026-05-27 06:26:00
            3    git-datamailer        2026-05-24 13:54:50
            4    git-ai-shipping-labs  2026-05-13 20:27:06
            5    git-ai-shipping-labs-workshops-raw-guard 2026-05-20 17:41:29
            6    git-au-tomator-lambda 2026-05-26 19:34:08
            7    git-dtc-operations-yaml 2026-05-21 05:18:21
            8    git-ai-shipping-labs-workshops-raw 2026-05-20 15:23:07
            9    git-ai-shipping-labs-web 2026-05-13 17:33:22
            10   git-telegram-writing-assistant 2026-05-12 12:10:03
            11   v2-md                 2026-04-13 14:06:12

            Join a session: pocketshell sessions <id> or pocketshell sessions <session>
            Create a new one: pocketshell sessions :<session>
            Use current folder: pocketshell sessions - or pocketshell sessions -name
            Help: pocketshell sessions --help
        """.trimIndent()

        val rows = parser.parsePocketshellSessionsList(output)

        assertEquals(11, rows.size)
        assertEquals(
            listOf(
                "git-pocketshell-c",
                "git-llm-zoomcamp",
                "git-datamailer",
                "git-ai-shipping-labs",
                "git-ai-shipping-labs-workshops-raw-guard",
                "git-au-tomator-lambda",
                "git-dtc-operations-yaml",
                "git-ai-shipping-labs-workshops-raw",
                "git-ai-shipping-labs-web",
                "git-telegram-writing-assistant",
                "v2-md",
            ),
            rows.map { it.name },
        )
    }

    /**
     * Issue #200 belt-and-braces: even when every row uses the
     * single-space separator (an extreme of the same printf overflow
     * pattern), the parser must still return every row. This is a
     * minimal pinning case for a 10-session host so the bug never
     * comes back as "9 of 10" because of a different column-width quirk.
     */
    @Test
    fun parsePocketshellSessionsListReturnsAllTenRowsWhenEverySeparatorIsSingleSpace() {
        val sessions = (1..10).map { "session-with-a-fairly-long-name-$it" }
        val output = buildString {
            appendLine("IDX  SESSION               CREATED")
            sessions.forEachIndexed { index, name ->
                appendLine("${index + 1}    $name 2026-05-27 ${"%02d".format(index + 1)}:00:00")
            }
            appendLine()
            appendLine("Join a session: pocketshell sessions <id> or pocketshell sessions <session>")
        }

        val rows = parser.parsePocketshellSessionsList(output)

        assertEquals(10, rows.size)
        assertEquals(sessions, rows.map { it.name })
    }

    @Test
    fun parseTmuxListSessionsReadsDeterministicFields() {
        val rows = parser.parseTmuxListSessions(
            "codex::1779520800::1779521400::1\nidle::1779510000::1779510500::0\n",
        )

        assertEquals(listOf("codex", "idle"), rows.map { it.name })
        assertEquals(1779520800L, rows[0].createdAt)
        assertEquals(1779521400L, rows[0].lastActivity)
        assertTrue(rows[0].attached)
        assertFalse(rows[1].attached)
    }

    @Test
    fun parseTmuxListSessionsCarriesFullGenerationIdentityFromPickerShapes() {
        val rows = parser.parseTmuxListSessions(
            "\$7::renamed::1779520800::1779521400::1::/srv/project\n" +
                "\$8::other::1779510000::1779510500::0\n",
        )

        assertEquals(listOf("renamed", "other"), rows.map { it.name })
        assertEquals(listOf("\$7", "\$8"), rows.map { it.tmuxSessionId })
        assertEquals(listOf(1779520800L, 1779510000L), rows.map { it.createdAt })
        assertEquals("/srv/project", rows[0].path)
        assertNull(rows[1].path)
    }

    @Test
    fun parseTmuxListSessionsCarriesRawKindAndDeclaredFamily() {
        val rows = parser.parseTmuxListSessions(
            "\$7::custom::1779520800::1779521400::1::custom-codex::/srv/project\n" +
                "\$8::unknown::1779510000::1779510500::0::custom-other::/srv/other\n",
            familyForRawId = { rawId ->
                if (rawId == "custom-codex") SessionAgentKind.Codex else null
            },
        )

        assertEquals(listOf("custom-codex", "custom-other"), rows.map { it.recordedKindId })
        assertEquals(SessionAgentKind.Codex, rows[0].recordedKind)
        assertEquals(SessionAgentKind.Codex, rows[0].agentKind)
        assertEquals(SessionAgentKind.Unknown, rows[1].recordedKind)
        assertEquals(SessionAgentKind.Unknown, rows[1].agentKind)
        assertEquals(listOf("/srv/project", "/srv/other"), rows.map { it.path })
    }

    @Test
    fun parseTmuxListSessionsNormalizesBlankRawKindAcrossPickerShapes() {
        val rows = parser.parseTmuxListSessions(
            "\$7::identity-blank::1779520800::1779521400::1::   ::/srv/identity\n" +
                "kind-path-blank::1779510000::1779510500::0::::/srv/kind-path\n",
        )

        assertEquals(listOf("identity-blank", "kind-path-blank"), rows.map { it.name })
        assertEquals(listOf("/srv/identity", "/srv/kind-path"), rows.map { it.path })
        assertNull(rows[0].recordedKindId)
        assertNull(rows[0].recordedKind)
        assertEquals(SessionAgentKind.Shell, rows[0].agentKind)
        assertNull(rows[1].recordedKindId)
        assertNull(rows[1].recordedKind)
        assertEquals(SessionAgentKind.Shell, rows[1].agentKind)
    }

    @Test
    fun parseTmuxListSessionsReadsSessionPathFromFiveFieldShape() {
        // Issue #463: the warm live-client query appends `#{session_path}`.
        val rows = parser.parseTmuxListSessions(
            "codex::1779520800::1779521400::1::/home/alexey/git/pocketshell\n" +
                "idle::1779510000::1779510500::0::/home/alexey/git/other\n",
        )

        assertEquals(listOf("codex", "idle"), rows.map { it.name })
        assertEquals(
            listOf("/home/alexey/git/pocketshell", "/home/alexey/git/other"),
            rows.map { it.path },
        )
        assertEquals(1779520800L, rows[0].createdAt)
        assertTrue(rows[0].attached)
        assertFalse(rows[1].attached)
    }

    @Test
    fun parseTmuxListSessionsReadsAgentStateFromSixFieldDashboardShape() {
        // Issue #1237: the cross-host dashboard query appends
        // `#{@ps_agent_state}` and `#{@ps_agent_state_updated_at}` as a 5th + 6th
        // column. Field order: name::created::activity::attached::state::updated.
        val rows = parser.parseTmuxListSessions(
            "codex::1779520800::1779521400::1::idle::1779521300\n" +
                "web::1779510000::1779510500::0::waiting_for_input::1779510450\n",
        )

        assertEquals(listOf("codex", "web"), rows.map { it.name })
        assertEquals(1779521400L, rows[0].lastActivity)
        assertTrue(rows[0].attached)
        assertEquals("idle", rows[0].agentStateRaw)
        assertEquals(1779521300L, rows[0].agentStateUpdatedAt)
        assertEquals("waiting_for_input", rows[1].agentStateRaw)
        assertEquals(1779510450L, rows[1].agentStateUpdatedAt)
        // The state columns carry no path.
        assertNull(rows[0].path)
    }

    @Test
    fun parseTmuxListSessionsReadsIsoStateUpdatedAtTheHostActuallyWrites() {
        // Issue #1570: the host hook writes @ps_agent_state_updated_at as an
        // ISO-8601 string, not an epoch int; the 6-field dashboard parse must
        // accept it (both the shape guard and the stored value).
        // 2023-11-14T22:13:20+00:00 == 1_700_000_000.
        val rows = parser.parseTmuxListSessions(
            "codex::1779520800::1779521400::1::idle::2023-11-14T22:13:20+00:00\n",
        )

        assertEquals(listOf("codex"), rows.map { it.name })
        assertEquals("idle", rows[0].agentStateRaw)
        assertEquals(1_700_000_000L, rows[0].agentStateUpdatedAt)
        assertNull(rows[0].path)
    }

    @Test
    fun parseTmuxListSessionsSixFieldBlankStateIsNull() {
        // A never-hooked session leaves both state columns empty; tmux expands
        // them to blank fields, so the row parses with no recorded state.
        val rows = parser.parseTmuxListSessions(
            "codex::1779520800::1779521400::1::::\n",
        )

        assertEquals(listOf("codex"), rows.map { it.name })
        assertEquals(1779521400L, rows[0].lastActivity)
        assertNull(rows[0].agentStateRaw)
        assertNull(rows[0].agentStateUpdatedAt)
    }

    @Test
    fun parseTmuxListSessionsFiveFieldPathStillWinsOverSixFieldStateShape() {
        // The 5-field path shape (issue #463) has only 4 separators, so the
        // 6-field state attempt cannot match it — the path is preserved and no
        // spurious agent-state is invented from the path.
        val rows = parser.parseTmuxListSessions(
            "codex::1779520800::1779521400::1::/home/alexey/git/pocketshell\n",
        )

        assertEquals("/home/alexey/git/pocketshell", rows[0].path)
        assertNull(rows[0].agentStateRaw)
        assertNull(rows[0].agentStateUpdatedAt)
    }

    @Test
    fun parseTmuxListSessionsFiveFieldKeepsNameWithDoubleColons() {
        // Issue #463: a name containing `::` must not be eaten by the new
        // 5-field path; created/activity/attached are numeric so the name
        // absorbs the leading double-colon segment.
        val rows = parser.parseTmuxListSessions(
            "codex::feature::1779520800::1779521400::1::/srv/app\n",
        )

        assertEquals(listOf("codex::feature"), rows.map { it.name })
        assertEquals(listOf("/srv/app"), rows.map { it.path })
    }

    @Test
    fun parseTmuxListSessionsFourFieldShapeHasNullPath() {
        // The host-wide tmux/pocketshell fallbacks still emit 4 fields; path
        // must be null there so the project switcher treats it as unknown.
        val rows = parser.parseTmuxListSessions("codex::1779520800::1779521400::1\n")

        assertEquals(listOf("codex"), rows.map { it.name })
        assertNull(rows[0].path)
    }

    @Test
    fun parseTmuxListSessionsAcceptsTabDelimitedRows() {
        val rows = parser.parseTmuxListSessions("codex\t1779520800\t1779521400\t1\n")

        assertEquals(listOf("codex"), rows.map { it.name })
        assertEquals(1779520800L, rows[0].createdAt)
        assertEquals(1779521400L, rows[0].lastActivity)
        assertTrue(rows[0].attached)
    }

    @Test
    fun parseTmuxListSessionsAcceptsLiteralBackslashTDelimitedRows() {
        val rows = parser.parseTmuxListSessions("""codex\t1779520800\t1779521400\t1""" + "\n")

        assertEquals(listOf("codex"), rows.map { it.name })
        assertEquals(1779520800L, rows[0].createdAt)
        assertEquals(1779521400L, rows[0].lastActivity)
        assertTrue(rows[0].attached)
    }

    @Test
    fun parseTmuxListSessionsPrefersDoubleColonDelimiterWhenNameContainsEscapedTabs() {
        val rows = parser.parseTmuxListSessions("""a\tb\tc\td::1780253919::1780253920::0""" + "\n")

        assertEquals(listOf("""a\tb\tc\td"""), rows.map { it.name })
        assertEquals(1780253919L, rows[0].createdAt)
        assertEquals(1780253920L, rows[0].lastActivity)
        assertFalse(rows[0].attached)
    }

    @Test
    fun parseTmuxListSessionsKeepsEscapedAndUnusualNames() {
        val rows = parser.parseTmuxListSessions(
            """
            codex::feature::1779520800::1779521400::1
            weird\040name [${'$'}HOME] 'quoted'::1779520000::1779520500::0
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "codex::feature",
                """weird\040name [${'$'}HOME] 'quoted'""",
            ),
            rows.map { it.name },
        )
        assertTrue(rows[0].attached)
        assertFalse(rows[1].attached)
    }

    @Test
    fun parseTmuxListSessionsSkipsMalformedRowsIntentionally() {
        val rows = parser.parseTmuxListSessions(
            """
            valid::1779520800::1779521400::0
            missing-created::1779521400::0
            ::1779520800::1779521400::1
            missing-attached::1779520800::1779521400
            """.trimIndent(),
        )

        assertEquals(listOf("valid"), rows.map { it.name })
    }

    @Test
    fun parseTmuxListSessionsAcceptsFallbackOutputWithoutTimestamps() {
        val rows = parser.parseTmuxListSessions(
            """
            codex: 1 windows (created Sun May 31 10:00:00 2026) [80x24] (attached)
            idle work: 2 windows (created Sun May 31 09:00:00 2026) [120x40]
            """.trimIndent(),
        )

        assertEquals(listOf("codex", "idle work"), rows.map { it.name })
        assertNull(rows[0].createdAt)
        assertNull(rows[0].lastActivity)
        assertTrue(rows[0].attached)
        assertFalse(rows[1].attached)
    }

    @Test
    fun malformedRowsReturnNull() {
        assertNull(parser.parsePocketshellSessionsListRow("IDX  SESSION               CREATED"))
        assertNull(parser.parsePocketshellSessionsListRow("not a row"))
        assertNull(parser.parseTmuxListSessionsRow(""))
        assertNull(parser.parseTmuxListSessionsRow("name\tcreated"))
        assertNull(parser.parseTmuxListSessionsRow("\t1\t2\t0"))
    }

    @Test
    fun parsePocketshellSessionsListNameSetEqualsTmuxctlEnumeratorIncludingLongNames() {
        val table = """
            IDX  SESSION               CREATED
            1    git-pocketshell-release 2026-08-27 09:22:55
            2    git-aplexer-2         2026-08-26 19:05:34
            3    git-dataops-2         2026-08-26 16:14:33
            4    git-pocketshell-2     2026-08-26 15:51:07
            5    git-dtc-website-4     2026-08-26 15:23:22
            6    git-dtc-website-3     2026-08-26 15:09:26
            7    git-zoom-calls        2026-08-26 13:48:17
            8    git-aplexer           2026-08-26 13:09:26
            9    git-game-tester       2026-08-26 07:12:25
            10   git-ai-shipping-labs  2026-08-25 17:28:03
            11   git-dtc-website       2026-08-24 12:26:03
            12   git-pocketshell       2026-08-24 12:26:02

            Join a session: tmuxctl <id> or tmuxctl <session>
        """.trimIndent()
        val names = parser.parsePocketshellSessionsList(table).map { it.name }.toSet()
        assertEquals(12, names.size)
        assertTrue(names.contains("git-pocketshell-release"))
        val defaultSocketOnly = setOf("git-dtc-website", "git-game-tester", "git-pocketshell")
        assertTrue(names.containsAll(defaultSocketOnly))
        assertTrue(names.size > defaultSocketOnly.size)
    }

    @Test
    fun parsePocketshellSessionsJsonUnionsTmuxAndAplexerManagers() {
        val json = """
            {
              "managers": ["tmux", "aplexer"],
              "sessions": [
                {"name": "git-pocketshell", "manager": "tmux"},
                {"name": "git-aplexer-2", "manager": "tmux"},
                {
                  "name": "toyaikit:codex",
                  "manager": "aplexer",
                  "id": "b3feff71-4a78-4055-a2d3-6c99187ecffb",
                  "workspace": "/home/alexey/git/toyaikit",
                  "engine": "codex",
                  "attach": "a attach b3feff71-4a78-4055-a2d3-6c99187ecffb"
                }
              ]
            }
        """.trimIndent()
        val rows = parser.parsePocketshellSessionsJson(json)
        assertEquals(listOf("git-pocketshell", "git-aplexer-2", "toyaikit:codex"), rows?.map { it.name })
        assertEquals(listOf("tmux", "tmux", "aplexer"), rows?.map { it.manager })
        assertEquals("b3feff71-4a78-4055-a2d3-6c99187ecffb", rows?.get(2)?.aplexerId)
        assertEquals("/home/alexey/git/toyaikit", rows?.get(2)?.path)
    }

    @Test
    fun unionLiveSessionRowsUsesTmuxctlAuthorityNotDefaultSocketSubset() {
        val overlay = parser.parseTmuxListSessions(
            "git-dtc-website::1::1::1::0:::/home/alexey/git/dtc-website\n" +
                "git-game-tester::1::1::1::0:::/home/alexey/git/game-tester\n" +
                "git-pocketshell::1::1::1::0:::/home/alexey/git/pocketshell\n",
        )
        val authority = parser.parsePocketshellSessionsJson(
            """
            {"managers":["tmux"],"sessions":[
              {"name":"git-pocketshell-release","manager":"tmux"},
              {"name":"git-aplexer-2","manager":"tmux"},
              {"name":"git-dtc-website","manager":"tmux"},
              {"name":"git-game-tester","manager":"tmux"},
              {"name":"git-pocketshell","manager":"tmux"}
            ]}
            """.trimIndent(),
        )!!
        val unioned = parser.unionLiveSessionRows(authority, overlay)
        assertEquals(
            listOf(
                "git-pocketshell-release",
                "git-aplexer-2",
                "git-dtc-website",
                "git-game-tester",
                "git-pocketshell",
            ),
            unioned.map { it.name },
        )
        assertEquals("/home/alexey/git/pocketshell", unioned.last().path)
        assertTrue(unioned.size > overlay.size)
    }

    @Test
    fun parsePocketshellSessionsJsonRejectsHumanTable() {
        assertNull(parser.parsePocketshellSessionsJson("IDX  SESSION               CREATED\n"))
    }
}
