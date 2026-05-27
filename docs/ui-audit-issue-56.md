# Issue #56 UI Audit Notes

## 2026-05-23 emulator pass

Reference screenshots inspected:

```text
build/walkthrough-visual-pass/issue76-followup-final-20260523-160539/screenshots/walkthrough-visual-pass/
```

Highest-impact issues observed against `docs/design-language.md` and
`docs/mockups/`:

- Host-list content rendered under the Android status bar, causing the
  `PocketShell` title and system icons to collide.
- The Android navigation bar was light while the app is dark-only, producing a
  high-contrast white strip in every screenshot.
- Bottom sheet actions near the bottom edge, especially `Continue with SSH`,
  were at risk of being obscured by the navigation bar.
- Pure-rendered prompt composer recording/transcribing screenshots lacked a
  dark host surface, so white text and disabled controls were washed out.

Focused first-pass fixes:

- `MainActivity` now requests dark system bars explicitly and applies safe
  drawing insets to the app root.
- `PromptComposerSheet.SheetContent` paints its own dark surface so composer
  content stays legible when rendered outside the real bottom-sheet host.
- `HostTmuxSessionPickerSheet` now uses the PocketShell dark sheet surface and
  navigation-bar padding, and places `Continue with SSH` before session rows so
  the raw SSH fallback is visible in the partially expanded sheet.

Larger follow-ups intentionally left out of this pass:

- Replacing text app-bar actions with an icon system.
- Redesigning the host list for richer session summary density.
- Reworking terminal glyph padding or terminal renderer metrics.

## 2026-05-23 follow-up validation

Reviewer-requested evidence was recaptured after tightening the system-bar
styling and screenshot host:

```text
build/walkthrough-visual-pass/issue56-main-final-20260523-192903/screenshots/walkthrough-visual-pass/
build/walkthrough-visual-pass/issue56-composer-final6-20260523-194201/screenshots/walkthrough-visual-pass/
```

Follow-up defects confirmed and addressed:

- `02-host-setup-session-picker.png` previously showed a light Android
  navigation-bar strip behind the 3-button controls. The final screenshot now
  shows the app, sheet, and navigation bar on the same dark system surface.
- `06-composer-recording.png` and `07-composer-transcribing.png` previously
  depended on the generic Compose test host, leaving a white page area and
  top-edge overlap. The final composer screenshots now use a dark full-screen
  host with status/navigation inset padding.

Focused follow-up changes:

- The app theme now declares dark status/navigation bars and disables platform
  navigation-bar contrast enforcement.
- `MainActivity` reinforces the same dark system-bar color at runtime before
  and after edge-to-edge setup.
- The composer screenshot test uses an explicit `ComponentActivity` host with
  dark system bars and captures recording/transcribing after a stable composed
  state update.

Validation notes:

- `./gradlew --no-daemon --no-build-cache :app:assembleDebug
  :app:assembleDebugAndroidTest` passed once in the shared worktree, then later
  shared-worktree Gradle attempts were interrupted by external daemon stops and
  concurrent generated-output churn.
- To avoid interfering with other agents' build directories, final APK
  verification used `/tmp/pocketshell-issue56-verify-20260523-193313` with
  `GRADLE_USER_HOME=/tmp/pocketshell-issue56-gradle-home`; the isolated build
  passed.
- Main screenshot instrumentation passed (`OK (1 test)`) for
  `issue56-main-final-20260523-192903`.
- Composer screenshot instrumentation passed (`OK (1 test)`) for
  `issue56-composer-final6-20260523-194201`.
