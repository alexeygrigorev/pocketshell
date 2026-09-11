# Remaining full UI extraction — issue #2636

This is the maintainer's still-open request, not a claim that this browser-only slice fulfills it. Read current `AGENTS.md`, `process.md` and relevant architecture/testing docs before implementation. Work in a separate worktree; never switch or reset the daily-use root checkout. No release or main merge without the repository's required review and actual evidence.

## Required result

```text
app2 (production Android composition root)
  ├─ real ViewModels, SSH, storage, voice, permissions and platform adapters
  └─ shared:ui-screens (the one production presentation implementation)
       └─ shared:ui-kit

ui-mock (independent development app, distinct applicationId)
  ├─ deterministic fixtures + local UI-state reducer/navigation
  └─ same shared:ui-screens
```

The mock's **runtime dependency graph** must not contain app2, transport, storage, voice or live-service implementations. Do not satisfy this by importing all app sources into a second APK, binding fake services to an otherwise unchanged production graph, copying screens into React, or generating alternate Kotlin layouts.

The browser renderer supplied here is useful before/during extraction. It does not by itself establish the module boundary.

## Verified source starting points (main inspected 2026-09-10)

- `app2/src/main/java/com/pocketshell/next/terminal/SessionScreen.kt`: `SessionRoute` owns real bindings; `SessionScreen` already accepts state/callbacks. The screen still references `SessionUiState.Live(TerminalSession)`, platform/window handling and composer dependencies, so it is not a platform-neutral DTO boundary yet.
- `.../hosts/HostListScreen.kt`: `HostListRoute` and stateless `HostListScreen` are in one file. UI types `HostListUiState` and `HostRow` are currently associated with the ViewModel layer; move presentation types without importing Room entities into the shared screen module.
- `.../composer/ComposerUiState.kt`: mixes UI state with `SessionSink`. Keep transport-facing `SessionSink` with production logic; move pure states to the presentation boundary. Check `RecordingState`, `StagedAttachment` and formatting dependencies rather than assuming this single file is self-contained.
- `.../composer/ComposerBar.kt`: uses the app's `MarkdownParser`/`MarkdownView`, recording surfaces, slash-command UI and attachment tiles. Extract their presentation dependencies together, not a cloned simpler composer.
- `.../settings/SettingsScreen.kt`: settings landing UI is straightforward, but the file also contains release-check adapters/types. Keep service/result adaptation outside the UI module. Several helpers are `internal`; moving modules changes visibility for current consumers/tests.
- `app2/src/test/java/com/pocketshell/next/render/`: real screen fixture inventory, including composer, host forms, settings, session tree, services, shares, ports and session chrome. It is not guaranteed to cover every destination/state.
- `shared/ui-kit/src/test/.../render/DesignRenders.kt`: some examples mirror app screens. Do not mistake these for the actual app screen implementation.
- `.github/workflows/build.yml`: GitHub releases publish **debug APKs**. `src/debug` alone does not keep a mock Activity out of the published APK. Prefer a separate opt-in application module with its own applicationId and release checks that reject it.

## Implementation order and acceptance evidence

1. Inventory **current production navigation destinations**, plus dialogs/sheets and screen states, from current app navigation and MainActivity. Commit a coverage table linking each destination to its shared composable and mock scenarios. Mark every missing row explicitly.
2. Extract pure screen models and stateless screen components. Preserve fully-qualified names where appropriate to minimize churn, but explicitly fix `internal` visibility, package-resource references, tests and all production call sites. Do not leave duplicate definitions or add compatibility copies.
3. Use view-model-to-UI mapping at production routes. Introduce an injected composable slot/platform adapter for terminal content, camera preview, file/permission pickers and any Android service boundary. Visual placeholder mode must be labeled; real platform behavior remains separately tested.
4. Create the independent `ui-mock` app with local state-only handlers. Typing edits mock draft state, toggles/navigation/sheets really work locally, and buttons switch deterministic loading/error/success states rather than quietly launching SSH, a microphone or browser/network intents. Provide reset and state selection.
5. Reuse the same fixtures for mock navigation and Roborazzi renders. Feed the browser renderer from the extracted module's test tasks rather than `app2` once the boundary is real. Replace the hardcoded catalog roots/module allowlist together; keep unsupported-case and freshness tests.
6. For optional interactive browser access, run the small mock APK in a **dedicated** Android emulator, stream/control it through a loopback-only service and SSH. Do not touch a pre-existing emulator or real app. This is a separate mode from static JVM rendering. A CLI-only native Compose change still needs incremental compilation/deployment unless an actually supported hot-reload path is added and verified.
7. Wire tests into the repository's running gates. Prove the production app compiles against the shared presentation module, mock runtime dependencies exclude live backend implementations, shipped debug APKs exclude the mock entry point, and each production destination has mock coverage.
8. Run focused tests and authoritative emulator checks, including keyboard-up composer/window geometry, terminal resizing, focus/selection and real-file-picker/permission integration. Do not claim "full UI separated" before these checks and the destination inventory are complete.

## Performance evidence

Measure cold setup separately from repeated single-composable edits with a warm daemon. Record actual save-to-fresh-image duration and selected-scene preservation. Do not present the Python fake-build unit-test duration as Android rendering speed, or a static image viewer as interactive hot reload.
