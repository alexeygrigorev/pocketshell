package com.pocketshell.app.assistant

import com.pocketshell.core.assistant.ToolSpec

/**
 * The tool catalog the assistant offers the model, plus the mutating/auto
 * classification that drives the confirm-or-correct gate (D25, issue #266).
 *
 * Each entry is a [ToolSpec] with a JSON-Schema parameter block (raw string,
 * per the `core-assistant` contract — the provider clients forward it to
 * Anthropic `input_schema` / OpenAI `parameters`). The names match the
 * `when` dispatch in [AssistantAgentLoop].
 *
 * Mutating tools ([MUTATING_TOOLS]) generate a candidate and route through
 * the confirm-or-correct gate before [AssistantActions] is touched. Inspect
 * and navigation tools auto-run.
 */
internal object AssistantTools {

    const val GET_CONTEXT = "get_context"
    const val LIST_HOSTS = "list_hosts"
    const val LIST_FOLDERS = "list_folders"
    const val RESOLVE_FOLDER = "resolve_folder"
    const val LIST_SESSIONS = "list_sessions"
    const val LIST_DIRECTORY = "list_directory"
    const val READ_FILE = "read_file"
    const val LIST_REPOS = "list_repos"

    const val OPEN_FOLDER = "open_folder"
    const val OPEN_SESSION = "open_session"
    const val OPEN_SCREEN = "open_screen"

    const val START_SESSION = "start_session"
    const val SEND_PROMPT_TO_SESSION = "send_prompt_to_session"
    const val CREATE_PROJECT = "create_project"
    const val RUN_COMMAND = "run_command"
    const val CREATE_FILE = "create_file"
    const val CLONE_REPO = "clone_repo"

    /** Tools that mutate remote/nav state and must pass the confirm gate. */
    val MUTATING_TOOLS: Set<String> = setOf(
        START_SESSION,
        SEND_PROMPT_TO_SESSION,
        CREATE_PROJECT,
        RUN_COMMAND,
        CREATE_FILE,
        CLONE_REPO,
    )

    fun isMutating(toolName: String): Boolean = toolName in MUTATING_TOOLS

    private const val NO_ARGS = """{"type":"object","properties":{},"additionalProperties":false}"""

    val ALL: List<ToolSpec> = listOf(
        ToolSpec(
            name = GET_CONTEXT,
            description = "Inspect the current screen, host, session, and working directory. " +
                "Call this FIRST to resolve references like \"this folder\", \"this dir\", " +
                "\"here\", or \"it\" before acting.",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = LIST_HOSTS,
            description = "List the saved SSH hosts the user can connect to.",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = LIST_FOLDERS,
            description = "List the tmux session folders (working directories) on a host.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "host":{"type":"string","description":"Saved host name."}
                },"required":["host"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = RESOLVE_FOLDER,
            description = "Resolve a fuzzy, spoken folder name to an exact working directory on a " +
                "host BEFORE starting a session. Call this when the user names a folder loosely " +
                "(e.g. \"the workshops folder\") instead of giving an absolute path. It searches " +
                "the full set of known folders on the host and returns one of: a single confident " +
                "match (use its cwd in start_session), an ambiguous result (the user is asked which " +
                "one and the chosen cwd is returned to you — then call start_session with it), or no " +
                "match (tell the user it wasn't found and list the nearest folders). Never invent a " +
                "cwd; always resolve it through this tool first.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "host":{"type":"string","description":"Saved host name."},
                  "query":{"type":"string","description":"The fuzzy folder name the user said."}
                },"required":["host","query"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = LIST_SESSIONS,
            description = "List the tmux sessions on a host.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "host":{"type":"string","description":"Saved host name."}
                },"required":["host"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = LIST_DIRECTORY,
            description = "List the contents of a directory on the active host.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Absolute or ~-relative directory path."}
                },"required":["path"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = READ_FILE,
            description = "Read the beginning of a text file on the active host.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Absolute or ~-relative file path."}
                },"required":["path"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = LIST_REPOS,
            description = "List the user's GitHub repositories (and which are already cloned " +
                "on the active host) via the server-side pocketshell repos CLI.",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = OPEN_FOLDER,
            description = "Open / navigate to a folder (working directory) on a host. " +
                "This is a navigation action and runs without confirmation.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "host":{"type":"string","description":"Saved host name."},
                  "path":{"type":"string","description":"Absolute folder path on the host."}
                },"required":["host","path"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = OPEN_SESSION,
            description = "Open / attach to an existing tmux session by name on the active host. " +
                "Navigation action; runs without confirmation.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "session_name":{"type":"string","description":"Existing tmux session name."}
                },"required":["session_name"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = OPEN_SCREEN,
            description = "Navigate to a named app screen. Allowed: hosts, settings, usage, " +
                "ai_costs, crash_reports.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "destination":{"type":"string","description":"Screen name.",
                    "enum":["hosts","settings","usage","ai_costs","crash_reports"]}
                },"required":["destination"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = START_SESSION,
            description = "Start a new tmux session on a host in a working directory, launching " +
                "an agent CLI. MUTATING: the user confirms the candidate before it runs.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "host":{"type":"string","description":"Saved host name."},
                  "cwd":{"type":"string","description":"Absolute working-directory path."},
                  "agent":{"type":"string","description":"Agent to launch.",
                    "enum":["claude","codex","opencode","shell"]}
                },"required":["host","cwd","agent"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = SEND_PROMPT_TO_SESSION,
            description = "Send a task prompt to an agent session after it has been started or opened. " +
                "Use this for action sequences like: resolve a project, start a Codex session in it, " +
                "then send the user's requested task prompt to that session. MUTATING: the user " +
                "confirms the exact target session and prompt before it runs.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "session_name":{"type":"string","description":"Target tmux session name returned by start_session or listed by list_sessions."},
                  "prompt":{"type":"string","description":"The exact task prompt to send to the agent session."}
                },"required":["session_name","prompt"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = CREATE_PROJECT,
            description = "Create an empty project folder under a configured workspace root on " +
                "a host. MUTATING: the user confirms the parent path and folder name before it runs.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "host":{"type":"string","description":"Saved host name."},
                  "parent_path":{"type":"string","description":"Absolute or ~-relative parent directory."},
                  "folder_name":{"type":"string","description":"New project folder name."}
                },"required":["host","parent_path","folder_name"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = RUN_COMMAND,
            description = "Run a single shell command in the active terminal (also handles " +
                "\"cd to ...\"). MUTATING: the user confirms the exact command before it runs. " +
                "Dangerous commands (sudo, rm -rf, shutdown, dd, mkfs, writes to raw block " +
                "devices) are blocked.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "command":{"type":"string","description":"The exact shell command to run."}
                },"required":["command"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = CREATE_FILE,
            description = "Create a file with the given contents on the active host. " +
                "MUTATING: the user confirms before it runs.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Absolute or ~-relative file path."},
                  "content":{"type":"string","description":"File contents."}
                },"required":["path","content"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = CLONE_REPO,
            description = "Clone a GitHub repository onto the active host via the server-side " +
                "pocketshell repos CLI (no phone-side GitHub credentials). MUTATING: the user " +
                "confirms before it runs. After a successful clone you may open the new folder " +
                "or start a session in it.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "full_name":{"type":"string","description":"owner/repo full name."},
                  "folder":{"type":"string","description":"Optional clone root directory."}
                },"required":["full_name"],"additionalProperties":false}
            """.trimIndent(),
        ),
    )
}
