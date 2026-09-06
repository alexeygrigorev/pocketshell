// GENERATED from design-system/catalog.json. Browser and native previews share this inventory.
package com.pocketshell.designpreview

object MockupScreens {
    val all: List<MockupScreen> = listOf(
        hosts(),
        hosts_empty(),
        add_host(),
        host_form(),
        connecting(),
        trust(),
        qr_scan(),
        qr_review(),
        keys(),
        key_import(),
        key_generate(),
        key_detail(),
        delete_key(),
        unlock(),
        workspaces(),
        workspace_search(),
        host_tools(),
        add_workspace(),
        folder_browser(),
        create_folder(),
        create_folder_error(),
        workspace(),
        workspace_empty(),
        workspace_actions(),
        remove_workspace(),
        roots(),
        add_root(),
        root_actions(),
        root_session(),
        reorder(),
        host_offline(),
        host_empty(),
        new_session(),
        session_options(),
        agent_unavailable(),
        terminal(),
        session_switch(),
        terminal_actions(),
        composer(),
        composer_tools(),
        dictation(),
        attachment(),
        history(),
        commands(),
        hotkeys(),
        end_session(),
        reconnecting(),
        session_ended(),
        send_uncertain(),
        files(),
        files_folder(),
        files_parent(),
        file_tools(),
        create_file_folder(),
        file_actions(),
        markdown(),
        source(),
        editor(),
        unsaved(),
        image_view(),
        rename_file(),
        delete_file(),
        file_conflict(),
        transfers(),
        services(),
        services_active(),
        tunnel_detail(),
        add_tunnel(),
        usage(),
        settings(),
        terminal_settings(),
        voice_settings(),
        language(),
        connection_settings(),
        grace(),
        advanced_settings(),
        diagnostics(),
        report(),
        clear_reports(),
        about(),
        update(),
    )
    val byId: Map<String, MockupScreen> = all.associateBy { it.id }
}

private fun hosts() = MockupScreen(
    id = "hosts", title = "Hosts", subtitle = "",
    back = "", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("hetzner", "7 workspaces", "workspaces", "", "Last opened", false),
        MockupNode.Row("Home workstation", "2 workspaces", "workspaces", "", "", false),
        MockupNode.Section("", "", ""),
        MockupNode.Row("SSH keys", "2 saved keys", "keys", "key", "", false),
        MockupNode.Row("Settings", "", "settings", "settings", "", false),
    ),
    footer = listOf(
        MockupNode.Button("Add host", "add-host", "primary"),
    ),
)

private fun hosts_empty() = MockupScreen(
    id = "hosts-empty", title = "Hosts", subtitle = "",
    back = "", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Empty("Your work, from here.", "Connect to a development machine to open its workspaces and terminals.", "server"),
    ),
    footer = listOf(
        MockupNode.Button("Add host", "add-host", "primary"),
    ),
)

private fun add_host() = MockupScreen(
    id = "add-host", title = "Add host", subtitle = "",
    back = "hosts", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "hosts", connected = false,
    nodes = listOf(
        MockupNode.Row("Scan a QR code", "Import from your computer", "qr-scan", "qr", "", false),
        MockupNode.Row("Enter connection details", "Address, user and SSH key", "host-form", "server", "", false),
    ),
    footer = listOf(
    ),
)

private fun host_form() = MockupScreen(
    id = "host-form", title = "Connection details", subtitle = "",
    back = "hosts", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Field("Name", "hetzner", "", "text"),
        MockupNode.Field("Address", "dev.example.com", "", "text"),
        MockupNode.Field("Username", "alexey", "", "text"),
        MockupNode.Row("SSH key", "Laptop key · ED25519", "keys", "key", "", false),
        MockupNode.Disclosure("Connection options", listOf(MockupNode.Field("Port", "22", "", "number"), MockupNode.Field("Usage command", "pocketshell usage --json", "", "text"))),
    ),
    footer = listOf(
        MockupNode.Button("Test connection", "connecting", "primary"),
    ),
)

private fun connecting() = MockupScreen(
    id = "connecting", title = "Connecting", subtitle = "",
    back = "host-form", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Progress("Connecting to hetzner", "Checking the server and SSH credentials."),
        MockupNode.Row("Server address", "dev.example.com", "", "", "", false),
        MockupNode.Text("You can cancel without changing your saved host.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Cancel", "back", "secondary"),
        MockupNode.Button("Continue demo", "trust", "text"),
    ),
)

private fun trust() = MockupScreen(
    id = "trust", title = "Verify server", subtitle = "",
    back = "host-form", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "host-form", connected = false,
    nodes = listOf(
        MockupNode.Text("First connection to hetzner. Check this fingerprint against the one on your server.", "secondary"),
        MockupNode.Code("ED25519\nSHA256:Qm7r…p8N2 (demo)"),
        MockupNode.Text("Only continue when it matches. A changed fingerprint is a different, blocking state.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Trust and connect", "workspaces", "primary"),
        MockupNode.Button("Cancel", "back", "text"),
    ),
)

private fun qr_scan() = MockupScreen(
    id = "qr-scan", title = "Scan host", subtitle = "",
    back = "hosts", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Text("Point your camera at the PocketShell QR code on your computer.", "secondary"),
        MockupNode.Scanner,
        MockupNode.Text("Waiting for a QR code…", "secondary"),
        MockupNode.Button("Show import review", "qr-review", "secondary"),
        MockupNode.Row("Enter details instead", "", "host-form", "", "", false),
    ),
    footer = listOf(
    ),
)

private fun qr_review() = MockupScreen(
    id = "qr-review", title = "Review import", subtitle = "",
    back = "qr-scan", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Section("Connection", "", ""),
        MockupNode.Row("hetzner", "alexey@dev.example.com", "", "", "", false),
        MockupNode.Section("Authentication", "", ""),
        MockupNode.Row("Imported key", "ED25519 · Passphrase protected", "key-detail", "key", "", false),
        MockupNode.Text("This QR includes a private key. Import it only from a source you trust.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Import and connect", "trust", "primary"),
    ),
)

private fun keys() = MockupScreen(
    id = "keys", title = "SSH keys", subtitle = "",
    back = "host-form", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Laptop key", "ED25519 · Used by hetzner", "key-detail", "key", "", false),
        MockupNode.Row("Travel key", "ED25519 · Not assigned", "key-detail", "key", "", false),
        MockupNode.Section("", "", ""),
        MockupNode.Row("Generate a key", "Create on this device", "key-generate", "", "", false),
        MockupNode.Row("Import a key", "Paste or select a file", "key-import", "", "", false),
    ),
    footer = listOf(
    ),
)

private fun key_import() = MockupScreen(
    id = "key-import", title = "Import key", subtitle = "",
    back = "keys", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Text("Prototype only — do not paste a real private key.", "secondary"),
        MockupNode.Field("Key name", "Travel key", "", "text"),
        MockupNode.Disclosure("Paste a private key", listOf(MockupNode.Field("Private key", "", "Paste PEM or OpenSSH key", "password-multiline"))),
        MockupNode.Row("Choose a key file", "Opens the Android file picker", "key-detail", "file", "", false),
        MockupNode.Text("Key parsing stays on this device. Never include key text in logs or reports.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Review key", "key-detail", "primary"),
    ),
)

private fun key_generate() = MockupScreen(
    id = "key-generate", title = "Generate key", subtitle = "",
    back = "keys", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Text("Prototype only — use a sample passphrase.", "secondary"),
        MockupNode.Field("Key name", "Phone key", "", "text"),
        MockupNode.Row("Key type", "ED25519", "", "", "", false),
        MockupNode.Field("Passphrase", "", "Recommended", "password"),
        MockupNode.Field("Confirm passphrase", "", "Repeat passphrase", "password"),
        MockupNode.Text("Add the public key to your server before connecting.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Generate key", "key-detail", "primary"),
    ),
)

private fun key_detail() = MockupScreen(
    id = "key-detail", title = "Laptop key", subtitle = "",
    back = "keys", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Type", "ED25519", "", "", "", false),
        MockupNode.Row("Protection", "Passphrase required", "", "", "", false),
        MockupNode.Row("Used by", "hetzner", "", "", "", false),
        MockupNode.Section("Public key", "", ""),
        MockupNode.Code("ssh-ed25519 AAAA…\nphone@pocketshell (demo)"),
        MockupNode.Button("Copy public key", "toast:Public key copied (demo)", "secondary"),
        MockupNode.Section("", "", ""),
        MockupNode.Row("Use this key", "", "host-form", "check", "", false),
        MockupNode.Row("Remove key from device", "", "delete-key", "trash", "", true),
    ),
    footer = listOf(
    ),
)

private fun delete_key() = MockupScreen(
    id = "delete-key", title = "Remove key?", subtitle = "",
    back = "key-detail", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "key-detail", connected = false,
    nodes = listOf(
        MockupNode.Text("Laptop key is used by hetzner. You will need another key to connect from this device.", "secondary"),
        MockupNode.Text("This does not change the server or remove its authorized key.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Remove key", "keys", "danger"),
        MockupNode.Button("Keep key", "key-detail", "text"),
    ),
)

private fun unlock() = MockupScreen(
    id = "unlock", title = "Unlock key", subtitle = "",
    back = "hosts", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Empty("Unlock Laptop key", "Use device authentication, or enter the key passphrase.", "fingerprint"),
        MockupNode.Field("Passphrase", "", "Enter passphrase", "password"),
    ),
    footer = listOf(
        MockupNode.Button("Unlock", "workspaces", "primary"),
    ),
)

private fun workspaces() = MockupScreen(
    id = "workspaces", title = "hetzner", subtitle = "Connected",
    back = "hosts", headerIcon = "more", headerRoute = "host-tools",
    layout = "page", base = "workspaces", connected = true,
    nodes = listOf(
        MockupNode.Search("Find a workspace", ""),
        MockupNode.Section("~/git", "Add", "add-workspace"),
        MockupNode.Workspace("pocketshell", listOf(AgentSummary("claude", 1), AgentSummary("shell", 1)), "workspace"),
        MockupNode.Workspace("pocketshell-desktop", listOf(AgentSummary("shell", 2)), "workspace"),
        MockupNode.Workspace("data-engineering-zoomcamp", listOf(), "workspace-empty"),
        MockupNode.Workspace("ml-experiments", listOf(AgentSummary("claude", 1), AgentSummary("codex", 1)), "workspace"),
        MockupNode.Section("~/work", "Add", "add-workspace"),
        MockupNode.Workspace("client-projects", listOf(AgentSummary("shell", 1)), "workspace"),
        MockupNode.Workspace("personal-site", listOf(AgentSummary("opencode", 1)), "workspace"),
        MockupNode.Workspace("sandbox", listOf(), "workspace-empty"),
    ),
    footer = listOf(
    ),
)

private fun workspace_search() = MockupScreen(
    id = "workspace-search", title = "hetzner", subtitle = "Connected",
    back = "workspaces", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = true,
    nodes = listOf(
        MockupNode.Search("Find a workspace", "pocket"),
        MockupNode.Section("~/git", "", ""),
        MockupNode.Workspace("pocketshell", listOf(AgentSummary("claude", 1), AgentSummary("shell", 1)), "workspace"),
        MockupNode.Workspace("pocketshell-desktop", listOf(AgentSummary("shell", 2)), "workspace"),
    ),
    footer = listOf(
    ),
)

private fun host_tools() = MockupScreen(
    id = "host-tools", title = "hetzner", subtitle = "",
    back = "workspaces", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Browse host files", "", "files", "folder", "", false),
        MockupNode.Row("Services & tunnels", "", "services", "ports", "", false),
        MockupNode.Row("Usage", "", "usage", "chart", "", false),
        MockupNode.Row("Project roots", "", "roots", "folder", "", false),
        MockupNode.Row("Refresh workspaces", "", "workspaces", "refresh", "", false),
        MockupNode.Row("Connection details", "", "host-form", "settings", "", false),
        MockupNode.Row("Disconnect", "", "hosts", "", "", false),
    ),
    footer = listOf(
    ),
)

private fun add_workspace() = MockupScreen(
    id = "add-workspace", title = "Add workspace", subtitle = "",
    back = "workspaces", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Location("~/git", "Change", "roots"),
        MockupNode.Search("Find a folder", ""),
        MockupNode.Row("Create folder", "", "create-folder", "plus", "", false),
        MockupNode.Section("Folders", "", ""),
        MockupNode.Row("aplexer", "", "workspace-empty", "", "", false),
        MockupNode.Row("course-notes", "", "workspace-empty", "", "", false),
        MockupNode.Row("experiments", "", "workspace-empty", "", "", false),
        MockupNode.Row("Browse subfolders", "", "folder-browser", "folder", "", false),
        MockupNode.Section("", "", ""),
        MockupNode.Row("Start session in ~/git", "Use the root itself", "new-session", "terminal", "", false),
    ),
    footer = listOf(
    ),
)

private fun folder_browser() = MockupScreen(
    id = "folder-browser", title = "Choose folder", subtitle = "",
    back = "add-workspace", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Location("~/git/experiments", "Up", "add-workspace"),
        MockupNode.Search("Find a folder", ""),
        MockupNode.Row("benchmarks", "", "action:drill", "", "", false),
        MockupNode.Row("notes", "", "action:drill", "", "", false),
        MockupNode.Row("Create folder here", "", "create-folder", "plus", "", false),
    ),
    footer = listOf(
        MockupNode.Button("Use this folder", "workspace-empty", "primary"),
    ),
)

private fun create_folder() = MockupScreen(
    id = "create-folder", title = "Create folder", subtitle = "",
    back = "add-workspace", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "add-workspace", connected = false,
    nodes = listOf(
        MockupNode.Location("~/git", "", ""),
        MockupNode.Field("Folder name", "new-project", "", "text"),
        MockupNode.Text("Creates ~/git/new-project on hetzner.", "secondary"),
        MockupNode.Text("The new folder becomes a workspace. A session is not started yet.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Create workspace", "action:create-workspace", "primary"),
        MockupNode.Button("Cancel", "back", "text"),
    ),
)

private fun create_folder_error() = MockupScreen(
    id = "create-folder-error", title = "Create folder", subtitle = "",
    back = "add-workspace", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "add-workspace", connected = false,
    nodes = listOf(
        MockupNode.Location("~/git", "", ""),
        MockupNode.Field("Folder name", "new-project", "", "text"),
        MockupNode.Alert("This folder already exists", "Use the existing folder as a workspace, or choose another name.", "warning", "", ""),
        MockupNode.Button("Use existing folder", "workspace-empty", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Cancel", "back", "text"),
    ),
)

private fun workspace() = MockupScreen(
    id = "workspace", title = "pocketshell", subtitle = "hetzner · ~/git",
    back = "workspaces", headerIcon = "more", headerRoute = "workspace-actions",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Terminal", "Claude Code · Running", "terminal", "hexagon", "", false),
        MockupNode.Row("Terminal 2", "Shell · Running", "terminal", "terminal", "", false),
        MockupNode.Button("New session", "new-session", "secondary"),
        MockupNode.Section("Workspace", "", ""),
        MockupNode.Row("Browse files", "", "files", "folder", "", false),
        MockupNode.Row("Services & tunnels", "On hetzner", "services", "ports", "", false),
    ),
    footer = listOf(
    ),
)

private fun workspace_empty() = MockupScreen(
    id = "workspace-empty", title = "new-project", subtitle = "hetzner · ~/git",
    back = "workspaces", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Empty("No sessions", "Start a shell or an agent in this workspace.", "terminal"),
        MockupNode.Button("New session", "new-session", "primary"),
        MockupNode.Section("Workspace", "", ""),
        MockupNode.Row("Browse files", "", "files", "folder", "", false),
    ),
    footer = listOf(
    ),
)

private fun workspace_actions() = MockupScreen(
    id = "workspace-actions", title = "pocketshell", subtitle = "",
    back = "workspace", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "workspace", connected = false,
    nodes = listOf(
        MockupNode.Row("New session", "", "new-session", "plus", "", false),
        MockupNode.Row("Browse files", "", "files", "folder", "", false),
        MockupNode.Row("Copy folder path", "", "toast:Folder path copied (demo)", "copy", "", false),
        MockupNode.Row("Reorder workspaces", "", "reorder", "sliders", "", false),
        MockupNode.Row("Remove from list", "Keeps the folder and running sessions", "remove-workspace", "close", "", false),
    ),
    footer = listOf(
    ),
)

private fun remove_workspace() = MockupScreen(
    id = "remove-workspace", title = "Remove from list?", subtitle = "",
    back = "workspace", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "workspace", connected = false,
    nodes = listOf(
        MockupNode.Text("pocketshell will be removed from this workspace list.", "secondary"),
        MockupNode.Text("The folder and its running sessions stay on hetzner. You can add it again from ~/git.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Remove from list", "workspaces", "danger"),
        MockupNode.Button("Keep workspace", "workspace", "text"),
    ),
)

private fun roots() = MockupScreen(
    id = "roots", title = "Project roots", subtitle = "hetzner",
    back = "workspaces", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("~/git", "4 workspaces", "root-actions", "", "", false),
        MockupNode.Row("~/work", "3 workspaces", "root-actions", "", "", false),
        MockupNode.Text("Roots organize folders on this host. They are not workspaces themselves.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Add root", "add-root", "primary"),
    ),
)

private fun add_root() = MockupScreen(
    id = "add-root", title = "Add project root", subtitle = "",
    back = "roots", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Field("Folder path", "~/projects", "", "text"),
        MockupNode.Row("Browse folders", "", "folder-browser", "folder", "", false),
        MockupNode.Text("PocketShell will look for workspaces in this folder. Existing folders and sessions are not moved.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Add root", "roots", "primary"),
    ),
)

private fun root_actions() = MockupScreen(
    id = "root-actions", title = "~/git", subtitle = "",
    back = "workspaces", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Add workspace", "", "add-workspace", "plus", "", false),
        MockupNode.Row("Create folder", "", "create-folder", "folder", "", false),
        MockupNode.Row("Start session here", "Working folder: ~/git", "new-session", "terminal", "", false),
        MockupNode.Row("Browse root", "", "files", "folder", "", false),
        MockupNode.Row("Remove root", "Keeps folders and sessions", "roots", "close", "", false),
    ),
    footer = listOf(
    ),
)

private fun root_session() = MockupScreen(
    id = "root-session", title = "hetzner", subtitle = "Connected",
    back = "hosts", headerIcon = "more", headerRoute = "host-tools",
    layout = "page", base = "workspaces", connected = true,
    nodes = listOf(
        MockupNode.Search("Find a workspace", ""),
        MockupNode.Section("~/git", "Add", "add-workspace"),
        MockupNode.Row("In this root", "Terminal · Shell", "terminal", "terminal", "", false),
        MockupNode.Workspace("pocketshell", listOf(AgentSummary("claude", 1)), "workspace"),
        MockupNode.Workspace("aplexer", listOf(), "workspace-empty"),
    ),
    footer = listOf(
    ),
)

private fun reorder() = MockupScreen(
    id = "reorder", title = "Reorder workspaces", subtitle = "",
    back = "workspaces", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Section("~/git", "", ""),
        MockupNode.Order(listOf("pocketshell", "pocketshell-desktop", "data-engineering-zoomcamp", "ml-experiments")),
    ),
    footer = listOf(
        MockupNode.Button("Done", "workspaces", "primary"),
    ),
)

private fun host_offline() = MockupScreen(
    id = "host-offline", title = "hetzner", subtitle = "Offline · Saved list",
    back = "hosts", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Alert("Cannot reach hetzner", "Saved workspaces are shown below. Session status is unavailable.", "warning", "Reconnect", "workspaces"),
        MockupNode.Section("~/git", "", ""),
        MockupNode.Workspace("pocketshell", listOf(), "workspace"),
        MockupNode.Workspace("pocketshell-desktop", listOf(), "workspace"),
    ),
    footer = listOf(
    ),
)

private fun host_empty() = MockupScreen(
    id = "host-empty", title = "hetzner", subtitle = "Connected",
    back = "hosts", headerIcon = "more", headerRoute = "host-tools",
    layout = "page", base = "workspaces", connected = true,
    nodes = listOf(
        MockupNode.Search("Find a workspace", ""),
        MockupNode.Section("~/git", "Add", "add-workspace"),
        MockupNode.Empty("No workspaces yet", "Add an existing folder or create a new one inside this root.", ""),
    ),
    footer = listOf(
    ),
)

private fun new_session() = MockupScreen(
    id = "new-session", title = "New session", subtitle = "",
    back = "workspace", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "workspace", connected = false,
    nodes = listOf(
        MockupNode.Text("In pocketshell", "secondary"),
        MockupNode.Choice("Shell", "A plain terminal", false, "terminal", ""),
        MockupNode.Choice("Claude Code", "Ready on hetzner", true, "hexagon", ""),
        MockupNode.Choice("Codex", "Ready on hetzner", false, "code", ""),
        MockupNode.Choice("OpenCode", "Ready on hetzner", false, "terminal", ""),
        MockupNode.Choice("Grok", "Not available", false, "zap", "agent-unavailable"),
        MockupNode.Row("More options", "Name, profile and backend", "session-options", "sliders", "", false),
    ),
    footer = listOf(
        MockupNode.Button("Start Claude Code", "action:start-session", "primary"),
    ),
)

private fun session_options() = MockupScreen(
    id = "session-options", title = "Session options", subtitle = "",
    back = "new-session", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "workspace", connected = false,
    nodes = listOf(
        MockupNode.Field("Session name", "Terminal 3", "", "text"),
        MockupNode.Row("Profile", "Host default", "toast:Profile picker uses host registry", "", "", false),
        MockupNode.Row("Backend", "Host default", "toast:Backend picker: host default, tmux, aplexer", "", "", false),
        MockupNode.Text("These options only affect the session you are about to start.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Apply options", "new-session", "primary"),
    ),
)

private fun agent_unavailable() = MockupScreen(
    id = "agent-unavailable", title = "Grok is not available", subtitle = "",
    back = "new-session", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "workspace", connected = false,
    nodes = listOf(
        MockupNode.Text("PocketShell could not find a usable Grok installation on hetzner.", "secondary"),
        MockupNode.Button("Choose another program", "new-session", "primary"),
        MockupNode.Button("Re-check", "toast:Still unavailable in this demo", "secondary"),
        MockupNode.Disclosure("Technical details", listOf(MockupNode.Code("Executable not found in the host PATH.\nHost: hetzner\nEngine: grok"))),
    ),
    footer = listOf(
    ),
)

private fun terminal() = MockupScreen(
    id = "terminal", title = "pocketshell", subtitle = "hetzner · Connected",
    back = "workspace", headerIcon = "more", headerRoute = "terminal-actions",
    layout = "terminal", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.SessionBar("Terminal", "Claude Code", 2, "session-switch"),
        MockupNode.Terminal(listOf("~/git/pocketshell", "\$ git status --short", " M app2/ui/WorkspaceScreen.kt", " M shared/ui-kit/Theme.kt", "", "\$ ./gradlew testDebugUnitTest", "> Task :app2:testDebugUnitTest", "", "BUILD SUCCESSFUL in 12s", "48 actionable tasks: 6 executed", "", "\$ _")),
        MockupNode.Launcher(false),
    ),
    footer = listOf(
    ),
)

private fun session_switch() = MockupScreen(
    id = "session-switch", title = "Sessions", subtitle = "",
    back = "terminal", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "terminal", connected = false,
    nodes = listOf(
        MockupNode.Row("Terminal", "Claude Code · Running", "terminal", "hexagon", "Current", false),
        MockupNode.Row("Terminal 2", "Shell · Running", "terminal", "terminal", "", false),
        MockupNode.Row("New session", "", "new-session", "plus", "", false),
    ),
    footer = listOf(
    ),
)

private fun terminal_actions() = MockupScreen(
    id = "terminal-actions", title = "Terminal", subtitle = "",
    back = "terminal", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "terminal", connected = false,
    nodes = listOf(
        MockupNode.Row("Sessions in workspace", "", "session-switch", "terminal", "", false),
        MockupNode.Row("Browse workspace files", "", "files", "folder", "", false),
        MockupNode.Row("Copy selection", "", "toast:Select terminal text first", "copy", "", false),
        MockupNode.Row("Detach and keep running", "", "workspace", "", "", false),
        MockupNode.Row("End session…", "", "end-session", "stop", "", true),
    ),
    footer = listOf(
    ),
)

private fun composer() = MockupScreen(
    id = "composer", title = "pocketshell", subtitle = "hetzner · Connected",
    back = "terminal", headerIcon = "", headerRoute = "",
    layout = "terminal", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.SessionBar("Terminal", "Claude Code", 2, "session-switch"),
        MockupNode.Terminal(listOf("~/git/pocketshell", "\$ git status --short", " M app2/ui/WorkspaceScreen.kt", " M shared/ui-kit/Theme.kt", "", "\$ ./gradlew testDebugUnitTest", "> Task :app2:testDebugUnitTest", "", "BUILD SUCCESSFUL in 12s")),
        MockupNode.Composer("edit", "Use the workspace name as the primary label. Keep the session indicators muted."),
        MockupNode.SystemKeyboard,
    ),
    footer = listOf(
    ),
)

private fun composer_tools() = MockupScreen(
    id = "composer-tools", title = "Add to input", subtitle = "",
    back = "composer", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "terminal", connected = false,
    nodes = listOf(
        MockupNode.Row("Attach file", "Android document picker", "attachment", "paperclip", "", false),
        MockupNode.Row("Recent prompts", "", "history", "history", "", false),
        MockupNode.Row("Slash commands", "", "commands", "terminal", "", false),
        MockupNode.Row("Terminal keys", "", "hotkeys", "keyboard", "", false),
        MockupNode.Row("Clear draft", "", "toast:Draft cleared (demo)", "close", "", false),
    ),
    footer = listOf(
    ),
)

private fun dictation() = MockupScreen(
    id = "dictation", title = "pocketshell", subtitle = "hetzner · Connected",
    back = "terminal", headerIcon = "", headerRoute = "",
    layout = "terminal", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.SessionBar("Terminal", "Claude Code", 2, "session-switch"),
        MockupNode.Terminal(listOf("~/git/pocketshell", "\$ git status --short", " M app2/ui/WorkspaceScreen.kt", " M shared/ui-kit/Theme.kt", "", "\$ ./gradlew testDebugUnitTest", "> Task :app2:testDebugUnitTest", "")),
        MockupNode.Composer("voice", "Make the workspace names larger and keep the session indicators quiet."),
    ),
    footer = listOf(
    ),
)

private fun attachment() = MockupScreen(
    id = "attachment", title = "pocketshell", subtitle = "hetzner · Connected",
    back = "composer", headerIcon = "", headerRoute = "",
    layout = "terminal", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.SessionBar("Terminal", "Claude Code", 2, "session-switch"),
        MockupNode.Terminal(listOf("~/git/pocketshell", "\$ git status --short", " M app2/ui/WorkspaceScreen.kt", " M shared/ui-kit/Theme.kt", "", "\$ ./gradlew testDebugUnitTest", "> Task :app2:testDebugUnitTest", "")),
        MockupNode.Composer("attachment", "Use this screenshot as the reference."),
    ),
    footer = listOf(
    ),
)

private fun history() = MockupScreen(
    id = "history", title = "Recent prompts", subtitle = "",
    back = "composer", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "terminal", connected = false,
    nodes = listOf(
        MockupNode.Row("Keep the session icons muted.", "Today", "action:reuse-prompt", "", "", false),
        MockupNode.Row("Run the unit tests before committing.", "Yesterday", "action:reuse-prompt", "", "", false),
        MockupNode.Row("Explain the reconnect failure.", "Yesterday", "action:reuse-prompt", "", "", false),
    ),
    footer = listOf(
    ),
)

private fun commands() = MockupScreen(
    id = "commands", title = "Slash commands", subtitle = "",
    back = "composer", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "terminal", connected = false,
    nodes = listOf(
        MockupNode.Search("Find a command", "/"),
        MockupNode.Row("/help", "Show supported commands", "action:insert-command", "", "", false),
        MockupNode.Row("/compact", "Compact agent context", "action:insert-command", "", "", false),
        MockupNode.Row("/clear", "Start a fresh agent context", "action:insert-command", "", "", false),
        MockupNode.Text("Inserted into your draft. Nothing runs until you send.", "secondary"),
    ),
    footer = listOf(
    ),
)

private fun hotkeys() = MockupScreen(
    id = "hotkeys", title = "Terminal keys", subtitle = "",
    back = "terminal", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "terminal", connected = false,
    nodes = listOf(
        MockupNode.KeyGrid(listOf("Esc", "Tab", "Ctrl+C", "Ctrl+D", "Ctrl+Z", "Alt", "Home", "End", "↑", "←", "↓", "→")),
        MockupNode.Text("Keys are sent to the current terminal immediately.", "secondary"),
    ),
    footer = listOf(
    ),
)

private fun end_session() = MockupScreen(
    id = "end-session", title = "End Terminal?", subtitle = "",
    back = "terminal", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "terminal", connected = false,
    nodes = listOf(
        MockupNode.Text("This ends Terminal in pocketshell on hetzner. Anything running in this session stops.", "secondary"),
        MockupNode.Text("The workspace and its other sessions remain. There is no undo.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("End session", "session-ended", "danger"),
        MockupNode.Button("Keep running", "terminal", "text"),
    ),
)

private fun reconnecting() = MockupScreen(
    id = "reconnecting", title = "pocketshell", subtitle = "hetzner · Reconnecting",
    back = "workspace", headerIcon = "", headerRoute = "",
    layout = "terminal", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.SessionBar("Terminal", "Claude Code", 2, "session-switch"),
        MockupNode.Alert("Connection lost", "Last output is shown. Reconnecting…", "warning", "Retry now", "terminal"),
        MockupNode.Terminal(listOf("~/git/pocketshell", "\$ git status --short", " M app2/ui/WorkspaceScreen.kt", " M shared/ui-kit/Theme.kt", "", "\$ ./gradlew testDebugUnitTest", "> Task :app2:testDebugUnitTest", "", "BUILD SUCCESSFUL in 12s", "48 actionable tasks: 6 executed", "", "\$ _")),
        MockupNode.Launcher(true),
    ),
    footer = listOf(
    ),
)

private fun session_ended() = MockupScreen(
    id = "session-ended", title = "Session ended", subtitle = "hetzner · pocketshell",
    back = "workspace", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Text("Terminal in pocketshell has ended.", "secondary"),
        MockupNode.Text("The workspace is still here. Open another session or start a new one.", "secondary"),
        MockupNode.Section("Other sessions", "", ""),
        MockupNode.Row("Terminal 2", "Shell · Running", "terminal", "terminal", "", false),
    ),
    footer = listOf(
        MockupNode.Button("New session", "new-session", "primary"),
        MockupNode.Button("Back to workspace", "workspace", "text"),
    ),
)

private fun send_uncertain() = MockupScreen(
    id = "send-uncertain", title = "Review before resending", subtitle = "",
    back = "terminal", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Alert("Delivery could not be confirmed", "The connection dropped while input was being sent. It may have reached the terminal. Your draft was kept.", "warning", "", ""),
        MockupNode.Field("Draft", "Run the unit tests before committing.", "", "multiline"),
        MockupNode.Text("Reconnect and inspect the terminal before sending again.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Reconnect and inspect", "terminal", "primary"),
    ),
)

private fun files() = MockupScreen(
    id = "files", title = "Files", subtitle = "hetzner · ~/git",
    back = "workspace", headerIcon = "more", headerRoute = "file-tools",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Location("pocketshell", "Up", "files-parent"),
        MockupNode.Search("Find a file", ""),
        MockupNode.Row("app2", "Folder", "files-folder", "folder", "", false),
        MockupNode.Row("shared", "Folder", "files-folder", "folder", "", false),
        MockupNode.Row("docs", "Folder", "files-folder", "folder", "", false),
        MockupNode.Row("README.md", "8 KB", "markdown", "file", "", false),
        MockupNode.Row("build.gradle.kts", "4 KB", "source", "file", "", false),
        MockupNode.Row("workspace.png", "128 KB", "image-view", "image", "", false),
    ),
    footer = listOf(
    ),
)

private fun files_folder() = MockupScreen(
    id = "files-folder", title = "Files", subtitle = "pocketshell · app2",
    back = "files", headerIcon = "more", headerRoute = "file-tools",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Location("app2", "Up", "files"),
        MockupNode.Search("Find a file", ""),
        MockupNode.Row("src", "Folder", "files-folder", "folder", "", false),
        MockupNode.Row("build.gradle.kts", "4 KB", "source", "file", "", false),
    ),
    footer = listOf(
    ),
)

private fun files_parent() = MockupScreen(
    id = "files-parent", title = "Files", subtitle = "hetzner",
    back = "files", headerIcon = "more", headerRoute = "file-tools",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Location("~/git", "", ""),
        MockupNode.Search("Find a file", ""),
        MockupNode.Row("pocketshell", "Folder", "files", "folder", "", false),
        MockupNode.Row("pocketshell-desktop", "Folder", "files", "folder", "", false),
        MockupNode.Row("experiments", "Folder", "files", "folder", "", false),
    ),
    footer = listOf(
    ),
)

private fun file_tools() = MockupScreen(
    id = "file-tools", title = "Files", subtitle = "",
    back = "files", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "files", connected = false,
    nodes = listOf(
        MockupNode.Row("Upload files", "Android document picker", "transfers", "upload", "", false),
        MockupNode.Row("Create folder", "", "create-file-folder", "folder", "", false),
        MockupNode.Row("New text file", "", "editor", "file", "", false),
        MockupNode.Row("Transfers", "", "transfers", "ports", "", false),
        MockupNode.Row("Sort by name", "", "toast:Sorted by name", "sliders", "", false),
        MockupNode.Row("Show hidden files", "", "toast:Hidden files shown (demo)", "eye", "", false),
    ),
    footer = listOf(
    ),
)

private fun create_file_folder() = MockupScreen(
    id = "create-file-folder", title = "Create folder", subtitle = "",
    back = "files", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "files", connected = false,
    nodes = listOf(
        MockupNode.Location("~/git/pocketshell", "", ""),
        MockupNode.Field("Folder name", "notes", "", "text"),
        MockupNode.Text("Creates ~/git/pocketshell/notes on hetzner.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Create folder", "action:create-file-folder", "primary"),
        MockupNode.Button("Cancel", "back", "text"),
    ),
)

private fun file_actions() = MockupScreen(
    id = "file-actions", title = "README.md", subtitle = "",
    back = "files", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "files", connected = false,
    nodes = listOf(
        MockupNode.Row("Preview", "", "markdown", "eye", "", false),
        MockupNode.Row("Edit", "", "editor", "edit", "", false),
        MockupNode.Row("Download", "", "transfers", "download", "", false),
        MockupNode.Row("Copy path", "", "toast:File path copied (demo)", "copy", "", false),
        MockupNode.Row("Rename", "", "rename-file", "edit", "", false),
        MockupNode.Row("Delete…", "", "delete-file", "trash", "", true),
    ),
    footer = listOf(
    ),
)

private fun markdown() = MockupScreen(
    id = "markdown", title = "README.md", subtitle = "pocketshell",
    back = "files", headerIcon = "more", headerRoute = "file-actions",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Tabs(listOf(FileMode("Preview", "markdown", true), FileMode("Source", "source", false))),
        MockupNode.Document("PocketShell", listOf("Keep working on your development machine from your phone.", "Workspaces organize folders. Each workspace can hold multiple terminals."), "Getting started", "pocketshell sessions list", "Open a workspace, then choose a terminal. Your work lives on the host."),
    ),
    footer = listOf(
    ),
)

private fun source() = MockupScreen(
    id = "source", title = "README.md", subtitle = "pocketshell",
    back = "files", headerIcon = "more", headerRoute = "file-actions",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Tabs(listOf(FileMode("Preview", "markdown", false), FileMode("Source", "source", true))),
        MockupNode.Code("# PocketShell\n\nKeep working from your phone.\n\n## Getting started\n\npocketshell sessions list\n\n- Open a workspace\n- Choose a terminal\n- Continue your work"),
    ),
    footer = listOf(
        MockupNode.Button("Edit file", "editor", "secondary"),
    ),
)

private fun editor() = MockupScreen(
    id = "editor", title = "Edit README.md", subtitle = "pocketshell",
    back = "unsaved", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Text("Unsaved changes", "secondary"),
        MockupNode.Field("Contents", "# PocketShell\n\nWorkspaces first.\nTerminals stay terminals.\n\n## Usage\nOpen your workspace and start a session.", "", "editor"),
    ),
    footer = listOf(
        MockupNode.Button("Save to host", "markdown", "primary"),
    ),
)

private fun unsaved() = MockupScreen(
    id = "unsaved", title = "Keep your changes?", subtitle = "",
    back = "editor", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "editor", connected = false,
    nodes = listOf(
        MockupNode.Text("README.md has unsaved edits. Leaving now will not update the file on hetzner.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Save to host", "markdown", "primary"),
        MockupNode.Button("Discard edits", "files", "danger"),
        MockupNode.Button("Keep editing", "editor", "text"),
    ),
)

private fun image_view() = MockupScreen(
    id = "image-view", title = "workspace.png", subtitle = "pocketshell",
    back = "files", headerIcon = "more", headerRoute = "file-actions",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.ImagePreview,
        MockupNode.Text("Pinch to zoom · Double tap to fit", "secondary"),
    ),
    footer = listOf(
    ),
)

private fun rename_file() = MockupScreen(
    id = "rename-file", title = "Rename file", subtitle = "",
    back = "files", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "files", connected = false,
    nodes = listOf(
        MockupNode.Field("File name", "README.md", "", "text"),
        MockupNode.Text("In ~/git/pocketshell", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Rename", "files", "primary"),
    ),
)

private fun delete_file() = MockupScreen(
    id = "delete-file", title = "Delete README.md?", subtitle = "",
    back = "files", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "files", connected = false,
    nodes = listOf(
        MockupNode.Text("This permanently deletes the file from ~/git/pocketshell on hetzner.", "secondary"),
        MockupNode.Text("This is not a download removal. There is no undo.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Delete file", "files", "danger"),
        MockupNode.Button("Keep file", "files", "text"),
    ),
)

private fun file_conflict() = MockupScreen(
    id = "file-conflict", title = "File changed on host", subtitle = "",
    back = "editor", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Alert("README.md has newer changes", "Your edits have not overwritten the remote file.", "warning", "", ""),
        MockupNode.Button("Save as a copy", "rename-file", "primary"),
        MockupNode.Button("Reload remote version", "markdown", "secondary"),
        MockupNode.Button("Keep editing", "editor", "text"),
    ),
    footer = listOf(
    ),
)

private fun transfers() = MockupScreen(
    id = "transfers", title = "Transfers", subtitle = "hetzner",
    back = "files", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Section("In progress", "", ""),
        MockupNode.Transfer("workspace.png", "Uploading to pocketshell", 65),
        MockupNode.Section("Completed", "", ""),
        MockupNode.Row("README.md", "Downloaded to this device", "toast:Open with Android file viewer", "file", "", false),
        MockupNode.Text("All values on this screen are sample data.", "secondary"),
    ),
    footer = listOf(
    ),
)

private fun services() = MockupScreen(
    id = "services", title = "Services & tunnels", subtitle = "hetzner",
    back = "workspaces", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Toggle("Discover services", false, "Look for listening ports on this host."),
        MockupNode.Empty("No active tunnels", "Turn on discovery or add a tunnel manually.", "ports"),
        MockupNode.Row("Show discovered services", "Preview example listeners", "services-active", "search", "", false),
    ),
    footer = listOf(
        MockupNode.Button("Add tunnel", "add-tunnel", "secondary"),
    ),
)

private fun services_active() = MockupScreen(
    id = "services-active", title = "Services & tunnels", subtitle = "hetzner",
    back = "workspaces", headerIcon = "plus", headerRoute = "add-tunnel",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Toggle("Discover services", true, ""),
        MockupNode.Section("Active tunnels", "", ""),
        MockupNode.Row("Development server", "localhost:5173", "tunnel-detail", "ports", "Open", false),
        MockupNode.Section("Available on hetzner", "", ""),
        MockupNode.Row("Port 8000", "Python · Not forwarded", "add-tunnel", "ports", "", false),
        MockupNode.Row("Port 3000", "Node · Not forwarded", "add-tunnel", "ports", "", false),
    ),
    footer = listOf(
    ),
)

private fun tunnel_detail() = MockupScreen(
    id = "tunnel-detail", title = "Development server", subtitle = "hetzner",
    back = "services-active", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Remote", "127.0.0.1:5173", "", "", "", false),
        MockupNode.Row("On this phone", "127.0.0.1:5173", "", "", "", false),
        MockupNode.Row("State", "Forwarding", "", "", "", false),
        MockupNode.Button("Open in browser", "toast:Demo only: would open http://127.0.0.1:5173", "primary"),
        MockupNode.Button("Copy local address", "toast:Local address copied (demo)", "secondary"),
        MockupNode.Row("Stop tunnel", "", "services", "stop", "", false),
    ),
    footer = listOf(
    ),
)

private fun add_tunnel() = MockupScreen(
    id = "add-tunnel", title = "Add tunnel", subtitle = "",
    back = "services-active", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Field("Name", "Development server", "", "text"),
        MockupNode.Field("Remote port", "8000", "", "number"),
        MockupNode.Field("Local port", "8000", "", "number"),
        MockupNode.Text("Local connections only: 127.0.0.1", "secondary"),
        MockupNode.Disclosure("More options", listOf(MockupNode.Field("Remote address", "127.0.0.1", "", "text"), MockupNode.Text("Changing the local bind address can expose this service to other devices.", "secondary"))),
    ),
    footer = listOf(
        MockupNode.Button("Start tunnel", "services-active", "primary"),
    ),
)

private fun usage() = MockupScreen(
    id = "usage", title = "Usage", subtitle = "hetzner",
    back = "workspaces", headerIcon = "refresh", headerRoute = "toast:Example readings refreshed",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Text("Last checked just now · Example readings", "secondary"),
        MockupNode.Quota("Claude Code", 42, "42% used · Resets in 3h 20m", "hexagon"),
        MockupNode.Quota("Codex", 18, "18% used · Resets in 4h 10m", "code"),
        MockupNode.Row("OpenCode", "No reading available", "toast:Provider did not supply usage", "terminal", "", false),
        MockupNode.Row("Grok", "No reading available", "toast:Provider did not supply usage", "zap", "", false),
        MockupNode.Disclosure("Display options", listOf(MockupNode.Text("Warn when a quota is near its limit.", "secondary"), MockupNode.Slider("Warn at", 80, 50, 95, "%"))),
    ),
    footer = listOf(
    ),
)

private fun settings() = MockupScreen(
    id = "settings", title = "Settings", subtitle = "",
    back = "hosts", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Terminal", "Text and input", "terminal-settings", "terminal", "", false),
        MockupNode.Row("Voice", "Language and dictation", "voice-settings", "mic", "", false),
        MockupNode.Row("Connections", "App switching and recovery", "connection-settings", "server", "", false),
        MockupNode.Row("Advanced", "Timing and compatibility", "advanced-settings", "sliders", "", false),
        MockupNode.Row("Diagnostics", "Local reports", "diagnostics", "info", "", false),
        MockupNode.Row("About", "Build and updates", "about", "", "", false),
    ),
    footer = listOf(
    ),
)

private fun terminal_settings() = MockupScreen(
    id = "terminal-settings", title = "Terminal", subtitle = "",
    back = "settings", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Section("Reading", "", ""),
        MockupNode.Slider("Terminal text size", 16, 12, 24, "sp"),
        MockupNode.Code("\$ git status\nOn branch main\nWorking tree clean"),
        MockupNode.Text("App text follows your Android font-size setting.", "secondary"),
        MockupNode.Section("Input", "", ""),
        MockupNode.Toggle("Show common keys", true, "Esc, Tab, Ctrl and arrows when typing."),
        MockupNode.Text("The keyboard overlays the terminal without resizing its grid.", "secondary"),
    ),
    footer = listOf(
    ),
)

private fun voice_settings() = MockupScreen(
    id = "voice-settings", title = "Voice", subtitle = "",
    back = "settings", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Language", "English", "language", "", "", false),
        MockupNode.Row("Review before sending", "Dictation always stops into an editable draft.", "", "", "", false),
        MockupNode.Row("Speech recognition", "System recognizer", "toast:Uses configured speech provider", "", "", false),
        MockupNode.Text("PocketShell asks for microphone access when you start dictating.", "secondary"),
    ),
    footer = listOf(
    ),
)

private fun language() = MockupScreen(
    id = "language", title = "Dictation language", subtitle = "",
    back = "voice-settings", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Choice("Automatic", "Use device language", false, "", ""),
        MockupNode.Choice("English", "", true, "", ""),
        MockupNode.Choice("German", "", false, "", ""),
        MockupNode.Choice("Russian", "", false, "", ""),
    ),
    footer = listOf(
        MockupNode.Button("Done", "voice-settings", "primary"),
    ),
)

private fun connection_settings() = MockupScreen(
    id = "connection-settings", title = "Connections", subtitle = "",
    back = "settings", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Keep connection after leaving", "30 seconds", "grace", "", "", false),
        MockupNode.Text("This controls the phone’s connection. Remote sessions are not deliberately ended when the app leaves the foreground.", "secondary"),
        MockupNode.Toggle("Reconnect when I return", true, ""),
        MockupNode.Row("Manage saved hosts", "", "hosts", "server", "", false),
    ),
    footer = listOf(
    ),
)

private fun grace() = MockupScreen(
    id = "grace", title = "Keep connection", subtitle = "",
    back = "connection-settings", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "connection-settings", connected = false,
    nodes = listOf(
        MockupNode.Choice("Disconnect immediately", "", false, "", ""),
        MockupNode.Choice("30 seconds", "Good for switching apps", true, "", ""),
        MockupNode.Choice("2 minutes", "More time between app switches", false, "", ""),
        MockupNode.Text("Your remote sessions are not ended by this setting.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Done", "connection-settings", "primary"),
    ),
)

private fun advanced_settings() = MockupScreen(
    id = "advanced-settings", title = "Advanced", subtitle = "",
    back = "settings", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Slider("Enter-key delay", 300, 0, 1000, "ms"),
        MockupNode.Text("Pause after pasted input before sending Enter. Change only if input is left unsubmitted.", "secondary"),
        MockupNode.Slider("Silence window", 3, 1, 10, "s"),
        MockupNode.Text("Speech-recognizer pause handling. Recording still ends when you tap Stop.", "secondary"),
        MockupNode.Button("Reset advanced defaults", "toast:Defaults restored (demo)", "secondary"),
    ),
    footer = listOf(
    ),
)

private fun diagnostics() = MockupScreen(
    id = "diagnostics", title = "Diagnostics", subtitle = "",
    back = "settings", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Latest report", "Today · Connection lost", "report", "file", "", false),
        MockupNode.Row("Earlier report", "Yesterday · Agent launch", "report", "file", "", false),
        MockupNode.Section("Sharing", "", ""),
        MockupNode.Row("Export latest report", "Review before sharing", "report", "upload", "", false),
        MockupNode.Row("Clear local reports…", "", "clear-reports", "trash", "", false),
    ),
    footer = listOf(
    ),
)

private fun report() = MockupScreen(
    id = "report", title = "Connection report", subtitle = "",
    back = "diagnostics", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Row("Time", "Today, 09:28", "", "", "", false),
        MockupNode.Row("Screen", "Terminal", "", "", "", false),
        MockupNode.Row("Summary", "SSH connection interrupted", "", "", "", false),
        MockupNode.Disclosure("Technical details", listOf(MockupNode.Code("ConnectionClosed\nSession: git-pocketshell\nHost alias: hetzner\nTransport: disconnected"))),
        MockupNode.Text("Review before sharing. Reports may contain hostnames, paths or terminal excerpts.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Share report", "toast:Would open Android Sharesheet", "primary"),
    ),
)

private fun clear_reports() = MockupScreen(
    id = "clear-reports", title = "Clear local reports?", subtitle = "",
    back = "diagnostics", headerIcon = "", headerRoute = "",
    layout = "sheet", base = "diagnostics", connected = false,
    nodes = listOf(
        MockupNode.Text("This removes saved diagnostic reports from this device. Your hosts, keys and remote sessions are unchanged.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Clear reports", "diagnostics", "danger"),
        MockupNode.Button("Keep reports", "diagnostics", "text"),
    ),
)

private fun about() = MockupScreen(
    id = "about", title = "About PocketShell", subtitle = "",
    back = "settings", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Text("Workspaces first. Terminals stay terminals.", "secondary"),
        MockupNode.Row("Installed version", "Shown from this build at runtime", "", "", "", false),
        MockupNode.Row("Check for updates", "", "update", "refresh", "", false),
        MockupNode.Row("Open-source licenses", "", "toast:Open licenses in the Android implementation", "file", "", false),
    ),
    footer = listOf(
    ),
)

private fun update() = MockupScreen(
    id = "update", title = "Update available", subtitle = "",
    back = "about", headerIcon = "", headerRoute = "",
    layout = "page", base = "workspaces", connected = false,
    nodes = listOf(
        MockupNode.Text("A newer PocketShell build is available.", "secondary"),
        MockupNode.Row("Release notes", "Review changes before updating", "toast:Would open release notes", "", "", false),
        MockupNode.Text("Keep the app and host helper on compatible versions.", "secondary"),
    ),
    footer = listOf(
        MockupNode.Button("Open release", "toast:Would open the verified GitHub release", "primary"),
    ),
)

