# Screen specifications

Each frame is a page, sheet, or meaningful state. Content is illustrative. The same frame IDs link HTML, PNGs and Kotlin fixtures.

## 01 · Hosts & access

### 01 · Hosts (`hosts`)
**Surface:** page. **Existing mapping:** Hosts.
**Job:** Choose a machine.
The occasional machine switcher, not a new dashboard.

- Opening the app resumes the last host workspace list when configured.
- Do not claim a saved host is online before a connection check.
- Rows stay flat; last-opened is metadata, not a status probe.

PNG: `screens/hosts.png`

### 02 · Hosts (`hosts-empty`)
**Surface:** page. **Existing mapping:** Hosts: empty.
**Job:** Connect the first host.
First-run state. One next action.

- No upfront permission requests.
- No duplicate Add action in both header and empty state.

PNG: `screens/hosts-empty.png`

### 03 · Add host (`add-host`)
**Surface:** sheet. **Existing mapping:** HostForm.
**Job:** Choose a setup method.
One path into the same setup flow.

- The path ends in a connection review.
- Sheet contains no separate primary button.

PNG: `screens/add-host.png`

### 04 · Connection details (`host-form`)
**Surface:** page. **Existing mapping:** HostForm.
**Job:** Enter and test connection details.
Existing and new host forms share one layout.

- Edited values persist after an unsuccessful test.
- Default port stays inside Connection options.
- Private key contents never appear on this screen.

PNG: `screens/host-form.png`

### 05 · Connecting (`connecting`)
**Surface:** page. **Existing mapping:** ConnectGate.
**Job:** Understand that a connection attempt is in progress.
Observable progress with cancellation.

- Continue demo is a prototype-only control, not part of the Android production screen.
- Production advances on a verified transport result, not a timer.

PNG: `screens/connecting.png`

### 06 · Verify server (`trust`)
**Surface:** sheet. **Existing mapping:** ConnectGate: TrustPromptSheet.
**Job:** Confirm the server identity.
Explicit trust, not a disguised success step.

- The mock fingerprint is illustrative and deliberately not a valid trusted identity.
- Production must show the complete fingerprint with copy and screen-reader support.
- A changed key must not reuse this first-trust copy.

PNG: `screens/trust.png`

### 07 · SSH keys (`keys`)
**Surface:** page. **Existing mapping:** SshKeys.
**Job:** Select or manage authentication.
Friendly identities, not raw IDs.

- When opened from a host form, choosing a key returns it to that form.
- When opened from Hosts, Back returns to Hosts; keep return context.

PNG: `screens/keys.png`

### 08 · Import key (`key-import`)
**Surface:** page. **Existing mapping:** SshKeys: import.
**Job:** Import existing authentication.
A focused import form with a system-file alternative.

- Preview fixture uses no real key.
- Enable Review only after successful local parsing in Android.
- Use platform clipboard and file picker; mask by default and never log input.

PNG: `screens/key-import.png`

### 09 · Generate key (`key-generate`)
**Surface:** page. **Existing mapping:** SshKeys: generate.
**Job:** Create a device key.
One modern default and an explicit protection choice.

- Generation and validation are fixtures here, not cryptographic operations.
- Never export a private key as part of the public-key install flow.

PNG: `screens/key-generate.png`

### 10 · Laptop key (`key-detail`)
**Surface:** page. **Existing mapping:** SshKeys: details.
**Job:** Use or inspect a key.
Dependencies are visible before deletion.

- The sample public key is not a real key.
- Production shows complete fingerprint and public key on demand.

PNG: `screens/key-detail.png`

### 11 · Remove key? (`delete-key`)
**Surface:** sheet. **Existing mapping:** SshKeys: delete.
**Job:** Remove local authentication safely.
Deletion scope is explicit.

- Never present removal as deleting a key from the remote host.

PNG: `screens/delete-key.png`

### 12 · Unlock key (`unlock`)
**Surface:** sheet. **Existing mapping:** Key unlock / Android BiometricPrompt.
**Job:** Authenticate without reconfiguring the host.
A native biometric handoff with a passphrase fallback.

- Biometric prompt is OS-owned and is not recreated by this UI.
- Passphrase persistence must follow the existing key-storage policy.

PNG: `screens/unlock.png`

## 02 · Workspaces & roots

### 13 · hetzner (`workspaces`)
**Surface:** page. **Existing mapping:** Tree → new workspace-first projection.
**Job:** Find the workspace, then its terminal.
The approved visual anchor: a quiet, folder-first host screen.

- Root → workspace → sessions. A workspace name is never replaced by its single session.
- No duplicated paths, folder icons, colored agent logos, cards, or activity timestamps.
- Muted marks + names are non-interactive metadata. The entire workspace row opens the workspace.
- Stable manual/creation order; updates do not reshuffle rows.

PNG: `screens/workspaces.png`

### 14 · hetzner (`workspace-search`)
**Surface:** page. **Existing mapping:** New: workspace search.
**Job:** Find a workspace by name.
Search returns workspaces, not mixed object types.

- Match workspace names; session names may contribute a match but result type stays workspace.
- Root headings disambiguate otherwise identical names.
- Search keyboard is OS-owned; typed browser input filters the live host screen too.

PNG: `screens/workspace-search.png`

### 15 · hetzner (`host-tools`)
**Surface:** sheet. **Existing mapping:** Tree header actions.
**Job:** Reach occasional host utilities.
Host tools stop competing with the workspace list.

- No Workspaces/Host tab strip.
- Files, tunnels and usage keep host scope, even when launched from a workspace.

PNG: `screens/host-tools.png`

### 16 · Add workspace (`add-workspace`)
**Surface:** page. **Existing mapping:** New: root-scoped folder picker.
**Job:** Choose a workspace within the selected root.
Existing folders and new folders share the same entry point.

- Root Add is contextual; it does not ask for the host again.
- Existing workspaces are marked Already added in the Android implementation.
- Choosing a folder opens its workspace; it does not automatically start a session.
- Root session is explicit and does not require a dummy child folder.

PNG: `screens/add-workspace.png`

### 17 · Choose folder (`folder-browser`)
**Surface:** page. **Existing mapping:** New: nested folder picker.
**Job:** Use a nested folder, not just a root’s first child.
Browsing and selecting a directory are separate gestures.

- Tapping a folder in browse mode enters it; Use this folder selects it.
- Keep a full canonical path as identity. Never group solely by basename.

PNG: `screens/folder-browser.png`

### 18 · Create folder (`create-folder`)
**Surface:** sheet. **Existing mapping:** New: folder creation.
**Job:** Start from a folder that does not exist yet.
Create exactly what is named; no hidden process launch.

- Validate empty name, separators, existing path, permissions and connection errors inline.
- Never silently overwrite, merge or create missing parent directories.
- A creation failure keeps the name and parent location.

PNG: `screens/create-folder.png`

### 19 · Create folder (`create-folder-error`)
**Surface:** sheet. **Existing mapping:** New: create validation.
**Job:** Recover without losing the folder choice.
Existence is not a fatal dead end.

- Existing file at that path is a different error and cannot be used as a workspace.

PNG: `screens/create-folder-error.png`

### 20 · pocketshell (`workspace`)
**Surface:** page. **Existing mapping:** New: workspace/{hostId}/{path}.
**Job:** Open a terminal or start another session here.
Sessions belong to this folder; terminal remains the work surface.

- Display labels follow desktop: Terminal, Terminal 2, or a meaningful suffix.
- Multiple agents of the same kind get separate session names here.
- No conversation view, transcript UI, activity dashboard or agent chat.

PNG: `screens/workspace.png`

### 21 · new-project (`workspace-empty`)
**Surface:** page. **Existing mapping:** New: empty workspace.
**Job:** Start the first terminal in a workspace.
A folder persists independently of its sessions.

- Ending the final session does not remove the workspace.
- The workspace identity is host + canonical remote path, not its display name.

PNG: `screens/workspace-empty.png`

### 22 · pocketshell (`workspace-actions`)
**Surface:** sheet. **Existing mapping:** New: workspace actions.
**Job:** Manage workspace presentation.
Folder operations have explicit scope.

- No hidden directory delete behind Remove from list.
- File deletion belongs in the file browser with a separate confirmation.

PNG: `screens/workspace-actions.png`

### 23 · Remove from list? (`remove-workspace`)
**Surface:** sheet. **Existing mapping:** New: remove workspace.
**Job:** Unpin without destroying work.
Remove is not delete and is not stop.

- Storage implementation must preserve discoverability of unlisted running sessions under Other.

PNG: `screens/remove-workspace.png`

### 24 · Project roots (`roots`)
**Surface:** page. **Existing mapping:** WorkspaceRoots.
**Job:** Choose where workspaces live.
Roots remain distinct from projects.

- Normalize home-relative paths per host; deduplicate aliases after resolution.
- Longest matching root wins. Unmatched workspaces remain under Other.

PNG: `screens/roots.png`

### 25 · Add project root (`add-root`)
**Surface:** page. **Existing mapping:** WorkspaceRoots: add.
**Job:** Register a remote root.
A location, not another project abstraction.

- Validate existence and permission before saving.
- A nonexistent root offers explicit creation rather than silently inventing directories.

PNG: `screens/add-root.png`

### 26 · ~/git (`root-actions`)
**Surface:** sheet. **Existing mapping:** New: root actions.
**Job:** Create a session at ~/git itself.
An explicit route to working directly at the root.

- On Start session here, the actual working directory is the root.
- Root-local sessions appear in a quiet “In this root” group, never as a fake child workspace.

PNG: `screens/root-actions.png`

### 27 · hetzner (`root-session`)
**Surface:** page. **Existing mapping:** New: root-local session.
**Job:** Find the terminal started directly in ~/git.
Sessions can exist at a root without inventing a project folder.

- This is an edge-state example, not an extra section on every root.
- Only show In this root when a root-local session exists.

PNG: `screens/root-session.png`

### 28 · Reorder workspaces (`reorder`)
**Surface:** page. **Existing mapping:** New: manual order.
**Job:** Arrange projects once.
Stable placement is a preference, not an activity feed.

- Move controls have 48dp hit targets; native drag handles are an optional enhancement.
- Never silently re-sort this list after a refresh.

PNG: `screens/reorder.png`

### 29 · hetzner (`host-offline`)
**Surface:** page. **Existing mapping:** Tree: offline.
**Job:** Keep context during network failure.
Unavailable is not the same as empty.

- Session summaries read Status unavailable, not No sessions.
- Avoid promising that remote processes are still running without checking.

PNG: `screens/host-offline.png`

### 30 · hetzner (`host-empty`)
**Surface:** page. **Existing mapping:** Tree: empty root.
**Job:** Add a first workspace in a registered root.
An empty root still exists and can create work.

- Do not remove a root because its session list is empty.

PNG: `screens/host-empty.png`

## 03 · Start & use terminals

### 31 · New session (`new-session`)
**Surface:** sheet. **Existing mapping:** CreateSessionSheet.
**Job:** Start another terminal in the current folder.
Choose a program. No separate Shell-versus-Agent wizard.

- Program selection and launch are separate; the last valid selection may be remembered.
- Name is derived and collision-safe. Existing sessions never block starting another.
- Agent output remains in the real terminal.

PNG: `screens/new-session.png`

### 32 · Session options (`session-options`)
**Surface:** sheet. **Existing mapping:** CreateSessionSheet: advanced.
**Job:** Override launch defaults deliberately.
Advanced choices exist, but not in the everyday path.

- Display names are not raw command strings.
- Do not expose unsupported engines or backends as working options.

PNG: `screens/session-options.png`

### 33 · Grok is not available (`agent-unavailable`)
**Surface:** sheet. **Existing mapping:** CreateSessionSheet: failure.
**Job:** Recover from a missing agent.
A launch failure explains the next step.

- Only show an installation command when verified for that host and provider.
- Preflight is authoritative host capability data, not a client guess.

PNG: `screens/agent-unavailable.png`

### 34 · pocketshell (`terminal`)
**Surface:** terminal. **Existing mapping:** Session.
**Job:** Read output and send input.
A terminal, not a transcript. Input opens over the fixed grid.

- Browser terminal is static fixture output, not an emulator.
- In Android reuse the existing TerminalHostView; preserve its grid when IME appears.
- Only the input overlay consumes keyboard insets; do not apply imePadding to the terminal column.

PNG: `screens/terminal.png`

### 35 · Sessions (`session-switch`)
**Surface:** sheet. **Existing mapping:** New: workspace session switcher.
**Job:** Move between terminals in a workspace.
Readable switching instead of squeezing tabs onto a phone.

- Session labels, not vendor identity alone, disambiguate same-agent sessions.
- A switch preserves each session’s selection and the workspace draft target.

PNG: `screens/session-switch.png`

### 36 · Terminal (`terminal-actions`)
**Surface:** sheet. **Existing mapping:** Session: overflow.
**Job:** Leave safely or explicitly end a process.
Navigation and destructive lifecycle actions are not peers.

- Back and Detach do not send a kill command.
- End session requires confirmation and names the remote target.

PNG: `screens/terminal-actions.png`

### 37 · pocketshell (`composer`)
**Surface:** terminal. **Existing mapping:** PromptComposerSheet.
**Job:** Write input without losing the output.
One draft surface; visible terminal context stays above it.

- The keyboard in the mock is a platform stand-in, not an app component to implement.
- Send writes text then Enter; Paste only writes text. Keep the distinction explicit.
- Every draft is bound to an explicit target session before sending.

PNG: `screens/composer.png`

### 38 · Add to input (`composer-tools`)
**Surface:** sheet. **Existing mapping:** ComposerBar: tools.
**Job:** Add context to a draft.
One tools menu, no stack of competing pills.

- A submenu replaces this sheet; it does not stack another modal on top.
- Returning preserves text and selection.

PNG: `screens/composer-tools.png`

### 39 · pocketshell (`dictation`)
**Surface:** terminal. **Existing mapping:** ComposerBar: recording.
**Job:** Dictate, review, then send.
Recording and sending are separate actions.

- Request microphone permission on first use, not on launch.
- Stop produces an editable draft. Never automatically execute a transcript.
- Animation reflects capture state; fake audio levels are not production telemetry.

PNG: `screens/dictation.png`

### 40 · pocketshell (`attachment`)
**Surface:** terminal. **Existing mapping:** ComposerBar: attachments.
**Job:** Send a file reference with input.
Attachment status sits next to the draft, not in another destination.

- workspace.png is a fixture; no upload occurs here.
- Production displays remote destination and upload/failure state before sending.
- The Android picker is a native external surface; do not redesign it.

PNG: `screens/attachment.png`

### 41 · Recent prompts (`history`)
**Surface:** sheet. **Existing mapping:** MessageHistorySheet.
**Job:** Reuse input.
History inserts into the draft; it never executes.

- Selecting an entry returns to the composer for review.
- History is not a conversation view. It contains only reusable sent input.

PNG: `screens/history.png`

### 42 · Slash commands (`commands`)
**Surface:** sheet. **Existing mapping:** SlashCommandDropdown.
**Job:** Find supported input commands.
Contextual command insertion, not a second agent UI.

- Example commands are fixtures, not a cross-agent API guarantee.
- Android must use capabilities for the actual selected session; unknown agent means no invented commands.

PNG: `screens/commands.png`

### 43 · Terminal keys (`hotkeys`)
**Surface:** sheet. **Existing mapping:** TerminalHotkeysSheet.
**Job:** Send special terminal keys.
Large keyboard targets, quiet styling.

- Actual byte bindings come from the existing terminal key controller.
- Browser buttons are demonstrations and never send remote commands.

PNG: `screens/hotkeys.png`

### 44 · End Terminal? (`end-session`)
**Surface:** sheet. **Existing mapping:** Stop-session ConfirmDialog.
**Job:** Stop remote work deliberately.
Danger is scoped to one named session.

- Do not label this Close or Back.
- Force kill is not an ordinary navigation gesture.

PNG: `screens/end-session.png`

### 45 · pocketshell (`reconnecting`)
**Surface:** terminal. **Existing mapping:** Session: Reconnecting.
**Job:** Recover the existing session, not start a duplicate.
Network failure preserves output and drafts.

- Disable remote sends, but allow local draft editing.
- Do not claim the process has exited because SSH is unavailable.
- Preserve fixed terminal geometry through the reconnect overlay.

PNG: `screens/reconnecting.png`

### 46 · Session ended (`session-ended`)
**Surface:** page. **Existing mapping:** Session: ended.
**Job:** Continue after a session exits.
An ended process cannot be fixed by a generic retry.

- No active composer when its target no longer exists.
- Only show an exit code when supplied by the host; absence is not zero.

PNG: `screens/session-ended.png`

### 47 · Review before resending (`send-uncertain`)
**Surface:** page. **Existing mapping:** Composer: delivery state.
**Job:** Avoid duplicate command execution.
No false delivered claim and no dangerous automatic resend.

- PTY writes are not equivalent to agent acknowledgement.
- No automatic queued resend after reconnect.
- Keep the draft and distinguish not sent from delivery unknown.

PNG: `screens/send-uncertain.png`

## 04 · Files & transfers

### 48 · Files (`files`)
**Surface:** page. **Existing mapping:** Files.
**Job:** Find and inspect a remote file.
The same flat row language, starting at the current workspace.

- Location is shown once, not repeated on every file row.
- Long press or the viewer overflow opens file actions.
- For host-scope entry the start folder is host home, not an unrelated project.

PNG: `screens/files.png`

### 49 · Files (`files-folder`)
**Surface:** page. **Existing mapping:** Files: directory state.
**Job:** Browse into a workspace folder.
A deeper directory keeps the file-browser navigation model.

- Back and Up return to the parent directory without switching to a folder-selection flow.
- Remember each directory’s scroll position.

PNG: `screens/files-folder.png`

### 50 · Files (`files-parent`)
**Surface:** page. **Existing mapping:** Files: directory state.
**Job:** Move up without entering workspace selection.
The parent location remains in the file browser.

- Up changes only the remote file-browser path. It does not open Add workspace.
- The full current path is useful here because the user is navigating the filesystem.

PNG: `screens/files-parent.png`

### 51 · Files (`file-tools`)
**Surface:** sheet. **Existing mapping:** Files: overflow.
**Job:** Manage file operations.
File actions do not become six header buttons.

- Keep native picker appearance owned by Android.
- Creating a folder here creates in the current file-browser location; it need not pin a workspace.

PNG: `screens/file-tools.png`

### 52 · Create folder (`create-file-folder`)
**Surface:** sheet. **Existing mapping:** Files: create folder.
**Job:** Create a remote directory while browsing files.
Folder creation is not workspace registration.

- Create a directory only; do not add a workspace or launch a terminal.
- Name and parent remain intact on a permission or connection failure.

PNG: `screens/create-file-folder.png`

### 53 · README.md (`file-actions`)
**Surface:** sheet. **Existing mapping:** Files / FileViewer: actions.
**Job:** Act on a selected file.
Save is not an ambiguous substitute for Download.

- Edit saves back to the remote host; Download copies to the phone.
- Hide unsupported actions for binary files.

PNG: `screens/file-actions.png`

### 54 · README.md (`markdown`)
**Surface:** page. **Existing mapping:** FileViewer: markdown.
**Job:** Read formatted text.
Document content is readable without adding card layers.

- Document typography is content, distinct from app chrome.
- Tables scroll within the document; they must not widen the entire screen.

PNG: `screens/markdown.png`

### 55 · README.md (`source`)
**Surface:** page. **Existing mapping:** FileViewer: source.
**Job:** Inspect raw file content.
Source is a file mode, never a session mode.

- No Terminal/Conversation tabs anywhere. These tabs are strictly file Preview/Source.

PNG: `screens/source.png`

### 56 · Edit README.md (`editor`)
**Surface:** page. **Existing mapping:** FileViewer: edit.
**Job:** Update a file on hetzner.
Editing is an explicit mode with a clear remote save.

- Back with a dirty buffer asks before discarding.
- Production saves compare remote modification metadata and surface conflicts.

PNG: `screens/editor.png`

### 57 · Keep your changes? (`unsaved`)
**Surface:** sheet. **Existing mapping:** FileViewer: dirty buffer.
**Job:** Leave an editor safely.
Local edits are not silently thrown away.

- Saving failures leave the buffer open and unchanged.

PNG: `screens/unsaved.png`

### 58 · workspace.png (`image-view`)
**Surface:** page. **Existing mapping:** FileViewer: image.
**Job:** Inspect a remote image.
The image owns the viewport.

- No disabled Edit button.
- Browser demo supports zoom with a tap; Android uses native gesture handling.

PNG: `screens/image-view.png`

### 59 · Rename file (`rename-file`)
**Surface:** sheet. **Existing mapping:** Files: rename.
**Job:** Change a remote filename.
A small form uses the same field and action rules.

- Existing path conflicts and lost connections keep this field intact.

PNG: `screens/rename-file.png`

### 60 · Delete README.md? (`delete-file`)
**Surface:** sheet. **Existing mapping:** Files: delete.
**Job:** Delete a remote file deliberately.
The host and directory remove scope ambiguity.

- Only show this for a selected file, not an entire workspace by accident.

PNG: `screens/delete-file.png`

### 61 · File changed on host (`file-conflict`)
**Surface:** page. **Existing mapping:** New: file conflict.
**Job:** Avoid overwriting concurrent changes.
Preserve both versions by default.

- A force overwrite, if offered in production, needs a separate explicit confirmation.

PNG: `screens/file-conflict.png`

### 62 · Transfers (`transfers`)
**Surface:** page. **Existing mapping:** Files: transfer states.
**Job:** Understand upload/download state.
Progress means measured bytes, not an animated guess.

- Unknown duration uses an indeterminate indicator.
- Failed transfers retain Retry and show source/destination.

PNG: `screens/transfers.png`

## 05 · Services & usage

### 63 · Services & tunnels (`services`)
**Surface:** page. **Existing mapping:** Ports: discovery off.
**Job:** Choose how to expose a remote service locally.
Discovery and a running tunnel are separate states.

- Show discovered services is a prototype-only example control.
- Forwarding remains host-scoped and may outlive a terminal.

PNG: `screens/services.png`

### 64 · Services & tunnels (`services-active`)
**Surface:** page. **Existing mapping:** Ports: discovery on.
**Job:** Forward a service and open its local address.
Available versus active is visible without a port table.

- Open is only offered for a verified HTTP(S) service.
- No localhost address is opened by this prototype.

PNG: `screens/services-active.png`

### 65 · Development server (`tunnel-detail`)
**Surface:** page. **Existing mapping:** Ports: details.
**Job:** Use or stop one active tunnel.
Addresses and exposure are inspectable before use.

- A real tunnel’s lifecycle is controlled by the foreground service.
- Stopping a tunnel does not end its associated shell or agent.

PNG: `screens/tunnel-detail.png`

### 66 · Add tunnel (`add-tunnel`)
**Surface:** page. **Existing mapping:** Ports: add.
**Job:** Create a local port forward.
Safe defaults without hiding the mapping.

- Validate ranges and local port collisions.
- Non-loopback binds require explicit exposure confirmation.

PNG: `screens/add-tunnel.png`

### 67 · Usage (`usage`)
**Surface:** page. **Existing mapping:** Usage.
**Job:** Check remaining provider capacity.
Quota gets a destination, not duplicate header chrome.

- Sample percentages and reset times are illustrative, not live account data.
- Unknown reading is never rendered as zero usage.
- Provider credentials remain on the host; no credential entry on this screen.

PNG: `screens/usage.png`

## 06 · Settings & support

### 68 · Settings (`settings`)
**Surface:** page. **Existing mapping:** Settings.
**Job:** Find a preference.
A short index, not six expanded configuration panels.

- Root management stays with the host; it is not renamed Pinned projects.
- No theme selector or density carousel until there is a real product need.

PNG: `screens/settings.png`

### 69 · Terminal (`terminal-settings`)
**Surface:** page. **Existing mapping:** Settings: terminal.
**Job:** Make terminal output comfortable to read.
A live reading sample instead of a raw pixel setting.

- The emulator may require px: convert user-facing sp to pixels through current density.
- Changing app typography must not silently alter remote PTY columns.

PNG: `screens/terminal-settings.png`

### 70 · Voice (`voice-settings`)
**Surface:** page. **Existing mapping:** Settings: voice.
**Job:** Configure dictation.
Everyday voice choices; technical silence windows stay elsewhere.

- Review before sending is a safety policy in this design, not an invitation to auto-run shell commands.
- Reuse existing voice providers; no new provider credentials model is implied.

PNG: `screens/voice-settings.png`

### 71 · Dictation language (`language`)
**Surface:** page. **Existing mapping:** Settings: voice language.
**Job:** Choose a language.
A focused, accessible list instead of inline radio clutter.

- Language choices must come from the supported recognizer configuration.

PNG: `screens/language.png`

### 72 · Connections (`connection-settings`)
**Surface:** page. **Existing mapping:** Settings: background grace.
**Job:** Tune mobile app switching behavior.
Transport lifetime is not process lifetime.

- Do not promise indefinite background connectivity on Android.
- Active tunnels use their existing foreground-service policy.

PNG: `screens/connection-settings.png`

### 73 · Keep connection (`grace`)
**Surface:** sheet. **Existing mapping:** Settings: grace picker.
**Job:** Choose a connection grace period.
A understandable duration picker.

- Available durations are a design fixture; reconcile with existing supported values during implementation.

PNG: `screens/grace.png`

### 74 · Advanced (`advanced-settings`)
**Surface:** page. **Existing mapping:** Settings: advanced.
**Job:** Adjust compatibility settings deliberately.
Failure-recovery knobs leave the main path.

- Host-specific versus global storage must match existing ownership.
- Do not ship a control whose value is not read by the runtime.

PNG: `screens/advanced-settings.png`

### 75 · Diagnostics (`diagnostics`)
**Surface:** page. **Existing mapping:** CrashReports.
**Job:** Find useful evidence after a failure.
Understand and review before exporting.

- Never silently send reports.
- Redact sensitive fields by default and let the user inspect export contents.

PNG: `screens/diagnostics.png`

### 76 · Connection report (`report`)
**Surface:** page. **Existing mapping:** CrashReports: detail.
**Job:** Inspect and share a support report.
Plain-language summary with optional trace.

- Report text is a synthetic fixture, not an extracted crash.
- Android Sharesheet remains system-owned.

PNG: `screens/report.png`

### 77 · Clear local reports? (`clear-reports`)
**Surface:** sheet. **Existing mapping:** CrashReports: clear.
**Job:** Remove local diagnostics.
A consistent destructive-action pattern.

- The action does not delete server-side logs.

PNG: `screens/clear-reports.png`

### 78 · About PocketShell (`about`)
**Surface:** page. **Existing mapping:** Settings: About.
**Job:** Check the installed build.
Build identity without outdated fixture version claims.

- Use PackageManager for version. Never hard-code a release version from this mock.
- The design-system version is not the app version.

PNG: `screens/about.png`

### 79 · Update available (`update`)
**Surface:** page. **Existing mapping:** Settings: update.
**Job:** Review a new release.
The update path is separate from ordinary workspace use.

- Only show this state after the release checker returns verified release metadata.
- No network lookup runs from this offline prototype.

PNG: `screens/update.png`
