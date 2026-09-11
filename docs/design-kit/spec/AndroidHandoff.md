# Android implementation handoff

## Recommended format and implementation target
Use the **interactive HTML catalog** to review content, navigation and states; use the **native Compose theme and components** to implement. PNGs/PDF are review snapshots, not layout code. A generated image per screen would drift in spacing, fonts, icon semantics and dimensions. A PowerPoint or raster-only Figma board would still need those decisions to be recreated. This package keeps the editable visual source and screen data together.

This app already uses Jetpack Compose, Material 3, an existing shared UI kit and a NavHost. Keep those foundations. Do not introduce a WebView, another terminal emulator, a separate navigation engine, a JSON-driven production UI runtime, or a conversation layer.

## Files
`PocketShellTheme.kt`: generated `PsTokens` plus `PocketShellQuietTheme`, mapped to Material color/type/shape roles.

`PocketShellIcons.kt`: generated native vector registry from the same paths as SVG. This avoids raster icons and font glyph substitutions.

`PocketShellComponents.kt`: reusable header, row, workspace row, button, field and native sheet, followed by a clearly marked **preview-only** fixture renderer. Split the primitives into your existing shared/ui-kit; keep the renderer out of production.

`MockupModels.kt` + `MockupCatalog.kt`: plain Kotlin content fixtures for every frame, generated from catalog.json. Put these in a debug/test source set.

`PocketShellPreviews.kt`: `@PreviewParameter` catalog of all screens, plus narrow / large-font previews of the host anchor. `PocketShellMockupDemo` is an optional debug-only fixture navigator, not the production NavHost.

`assets/approved-reference.png`: optional sample file for the preview image viewer. Put under `src/debug/assets`, not your launcher resources.

## Import path
Start with a temporary package under `app2/src/debug/java/com/pocketshell/designpreview/`. Copy the Kotlin files and optional debug asset there. The package is already set to `com.pocketshell.designpreview`. Retain your current Compose BOM/version catalog rather than adopting arbitrary dependency upgrades.

The preview code expects Material 3, foundation/layout, runtime, ui, and ui-tooling-preview. Use debug-only ui-tooling for Android Studio interactive previews. It uses FlowRow, ModalBottomSheet, HorizontalDivider and lambda progress APIs; align signatures with your actual Compose version. No third-party UI runtime is required.

**Validation status:** only the plain Kotlin models/catalog were compiled here. Compose source, Gradle dependency compatibility, layoutlib previews, hardware keyboard, TalkBack, real terminal geometry and device behavior are not verified. Build and run these previews inside the repository before moving primitives into shared/ui-kit.

## Route migration
| Existing destination | Proposed surface | Notes |
|---|---|---|
| Hosts | Hosts / add host | Preserve existing saved-host source and connect gate. |
| Tree(hostId) | Host workspaces | Group roots and persistent workspace folders. |
| New | Workspace(hostId, path) | Session list and New session for a folder; never a chat. |
| Session(hostId, sessionName) | Terminal | Keep real terminal identity and emulator. |
| CreateSessionSheet | New session + More options | One program choice, sensible naming defaults. |
| Files(hostId, path) | Files | Scoped to workspace by initial path; can navigate up. |
| FileViewer(hostId, path) | Preview / source / editor / image | Keep back stack and dirty buffer handling. |
| Ports(hostId) | Services & tunnels | Host-scoped; workspace is only a contextual entry point. |
| Usage | Usage | Carry selected host explicitly; no provider secrets on phone. |
| HostForm | Connection details | Test connection before final connect. |
| SshKeys | Keys | Reuse real crypto, parsing and trust boundary. |
| WorkspaceRoots(hostId) | Project roots | Accessible from host tools, not buried in global settings. |
| Settings | Settings categories | General user controls; timings behind Advanced. |
| CrashReports | Diagnostics / report | Review before export; don't export secrets. |

Add route arguments as stable host/session/path identifiers, not credentials or live connection objects. Use the existing navigation and SavedStateHandle patterns. Keep browser frame IDs for screenshot tests, not as a second ad hoc native router.

## Folder/session model
A display name is not identity. Resolve a host-specific canonical absolute path; keep a display path separately. `~/git` and `/home/alexey/git` should not create duplicate roots on the same host. Match roots on a slash boundary, with the longest matching root winning. Do not merge different nested working folders just because their basenames or first path segment match.

The persistent list of workspace folders must not depend entirely on currently enumerated sessions: new/empty workspaces remain visible. Use the existing host helper/registry where available; agree on durable workspace registration before implementing a phone-only competing source of truth. If a local preference must be used temporarily, document its device-only scope and do not promise desktop synchronization.

Keep session identity host-defined. Use Terminal, Terminal 2 or meaningful suffixes for display, separate from the underlying aplexer identifier. Auto-name without replacing an existing session; collisions still permit starting another. Do not auto-resume an existing session when the user explicitly chose New session.

Known nested folders may be searched across configured roots. For large filesystems, use a bounded host-side folder index/query, cancellation and exclusions for generated/dependency folders. Do not recursively crawl an unbounded filesystem over individual SFTP round trips on each keystroke. Never claim a partial or failed search is exhaustive. The HTML demo only filters its supplied workspace/folder fixtures.

## Important native contracts
**Status.** SSH connection, attached terminal, running process and agent state are different facts. Server-derived metadata should drive the summary. A connected green dot does not license marking all agents as busy. When enumeration fails show unavailable/stale state and retain the last list.

**Back.** Terminal Back and Detach leave the server session running. Workspace removal affects list membership only. End session is a separate destructive confirmation naming the session, workspace and host. Folder deletion is only a file-manager action with a separate confirmation.

**Keyboard/insets.** Reuse the app's existing TerminalHostView and its fixed cell policy. Terminal content does not consume IME padding; the composer overlay does. One component owns each inset so IME padding is not counted twice. Form screens can use ordinary Scaffold/imePadding behavior; do not copy the terminal exception globally. The illustrated keyboard is not an app component to port.

**Drafts.** Persist editable text locally by workspace and an explicit intended session target. Switching session must not silently send a draft to the new target. Reconnect keeps the draft; ambiguous delivery requires review rather than automatic replay. Never erase a draft because a remote write was merely initiated.

**Modal behavior.** Replace the current sheet rather than piling sheets on one another. Use actual ModalBottomSheet/Dialog behavior for focus, accessibility, Back and outside dismissal. The static in-place preview sheets are not sufficient production modal accessibility.

**Native handoffs.** Use the Android document picker, Sharesheet, runtime microphone permissions and biometrics. No custom copies of those system screens. A changed fingerprint must block connection until independently verified, not reuse a routine first-trust prompt.

**Security.** Private-key handling remains in the existing audited path. No real secrets in preview fixtures. Use structured host helper APIs for creation; do not concatenate unsanitized names into shell commands. Show the exact destination for creation and never silently overwrite/merge or create missing parents. Tunnel default is localhost binding; broader exposure needs explicit disclosure.

## Port in this order
1. Put tokens and primitives into shared/ui-kit; render the host anchor and match it at 412dp and large font.
2. Implement workspace projection and root actions, then folder search/browse/create. Test zero/one/multiple sessions and root-level sessions.
3. Introduce workspace route and simplified launch sheet. Keep the current connection and send owners.
4. Apply the same shell to terminal controls, files, services and usage.
5. Apply the same fields/sheets/rows to setup, keys, preferences and recovery states.
6. Replace fixtures with ViewModel state and run the acceptance suite. Keep snapshots and token changes reviewed together.

## What not to port
Do not ship mock session data, demo Continue buttons, simulated transcript/file contents, the HTML renderer, the drawn software keyboard, or the debug gallery. Do not inherit the iOS-style details from earlier generated images: the design target is Android with native system bars, insets, back behavior and controls.
