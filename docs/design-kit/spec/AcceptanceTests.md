# Android acceptance criteria
These are target tests for integration, not claims that device testing has happened.

## Visual consistency
Every route uses the same theme. No raw per-screen colors/type/radii unless terminal content requires it. Compare the host anchor plus one form, sheet, file screen, terminal and settings screen at 360dp and 412dp. Check native 1.0 / 1.3 / 2.0 font scales and landscape. Workspace labels wrap without shrinking; no footer overlaps content; all actions stay reachable by scrolling.

## Main journeys
1. Select an existing workspace → choose a named session → attach real terminal. Back leaves the remote process running.
2. Root Add → select existing folder → empty workspace → start Shell. No path re-entry.
3. Root Add → create new folder → workspace appears without starting a process → explicit New session.
4. Start session directly in root. It appears under In this root; no duplicate or synthetic child folder.
5. Browse multiple subfolders and choose the current folder. Parent traversal remains in the same task.
6. Two simultaneous Claude sessions remain distinct by name. New session does not resume/replace either.
7. Search same-named workspaces in different roots; display enough parent context to choose correctly. Do not globally add paths to every row.
8. Reconnect and refresh retain stable workspace/session ordering, selection and scroll position.

## Correct states
Use fixtures for empty folder, no sessions, unreachable host, partial enumeration, inaccessible directory, executable missing, remote process exit and ambiguous send. Offline is not empty. Running is not attached/busy. A workspace remains after its last session ends.

## Input / lifecycle
Bring up the Android IME during terminal use and verify the terminal column/row count does not change. Composer remains reachable above the keyboard. Check hardware keyboard, predictive text commit, multiline input, Paste without Enter, Send with Enter, voice permission denied, recording cancellation and reconnect. Drafts survive process recreation as intended by the app's persistence contract. Never auto-replay an uncertain terminal write.

## File and network safety
Reject empty/illegal folder names inline. Existing folder detection offers reuse rather than overwrite. Permission errors preserve input. Files Up stays in Files; workspace browsing stays in its picker. Save is remote; Download is local. Dirty buffers are protected on Back. Remote modification conflicts do not silently overwrite newer content. Tunnels default to loopback and have explicit endpoint labels.

## Accessibility
Use TalkBack to verify root headings, merged workspace row semantics and distinct sessions. Do not announce decorative chevrons twice. Tiny session marks are not focusable controls. Check each interactive control's actual hit rectangle is at least 48dp. Use real selectable/toggleable semantics and one owner for RadioButton/Switch actions. Verify sheet focus trapping and Back/outside dismissal with native containers. Do not depend on color alone.

## Setup and security
Unknown fingerprint and changed fingerprint are distinct states. Biometric cancellation falls back safely without storing passphrases in preview state or logs. Only use native permission/document/share surfaces. Inspect support exports for sensitive terminal content and secrets.

## Backend/API tasks not supplied by the UI kit
Durable empty-workspace membership and desktop sharing, bounded recursive directory discovery, folder existence/preflight validation, program availability detection, model/status accuracy, actual terminal send acknowledgement, reconnect state, secure key operations, real file conflicts/transfers and tunnel management still belong to the existing app/helper layers.
