# Dogfood Visual Pass

Issue #76 tracks the screenshot-backed visual confidence pass for the next
phone APK release.

## Capture Command

Start or reuse the local `test` AVD, then run:

```bash
scripts/capture-dogfood-screenshots.sh
```

The script uses explicit SDK paths unless overridden:

```bash
ANDROID_SDK=/home/alexey/Android/Sdk
ADB=/home/alexey/Android/Sdk/platform-tools/adb
EMULATOR=/home/alexey/Android/Sdk/emulator/emulator
AVD_NAME=test
```

Artifacts are written under:

```text
build/dogfood-visual-pass/<run-id>/screenshots/dogfood-visual-pass/
```

The script intentionally runs the connected pass through explicit APK build,
`adb install`, and `adb shell am instrument` steps instead of Gradle's
`connectedDebugAndroidTest` runner. This keeps package installation outside the
instrumentation window and avoids UTP reinstalling the app while screenshots
are being captured. The live terminal and pure composer screenshot classes run
as separate instrumentation phases, with an app/test force-stop settle before
each phase, so a process restart in one phase cannot drop screenshots from the
other.

On-device screenshots are staged under
`/sdcard/Android/media/com.pocketshell.app/additional_test_output/dogfood-visual-pass`
and pulled directly into the run artifact directory.

Each run also writes command logs, Docker SSH fixture logs, Docker container
state, `adb devices`, `adb getprop`, and `adb logcat` under the run directory.
On failure, inspect the run directory named in the script's `FAIL:` output.

## Screenshot Set

The connected screenshot pass captures:

- `01-host-list.png` - saved host list with system bars.
- `02-host-setup-session-picker.png` - host tap setup/session choice state,
  including the raw SSH skip path.
- `03-terminal-session-input-controls.png` - live terminal with bottom chips
  and key controls after a Docker-backed command.
- `04-snippets.png` - snippet picker over the live session.
- `05-composer-draft.png` - prompt composer opened from the session with a
  typed draft before send.
- `06-composer-recording.png` - composer recording state with waveform.
- `07-composer-transcribing.png` - composer processing state.

The screenshots include the emulator display capture, so system bars and bottom
controls are visible in the artifact.

The main dogfood screenshot test also asserts system-bar guards after the
evidence screenshots are written:

- `01-host-list.png`: the `PocketShell`, `Crashes`, `Import`, and `Keys` app-bar
  labels must start below the status-bar inset. Failure messages include the
  text bounds, status-bar bottom, and screenshot artifact path.
- `02-host-setup-session-picker.png`: the `Tmux sessions`, `+ New session`, and
  `Continue with SSH` sheet controls must end above the navigation-bar inset.
  Failure messages include the text bounds, navigation-bar top, and screenshot
  artifact path.

## Audit Checklist

Compare the screenshot set against [docs/design-language.md](design-language.md)
and the static targets in [docs/mockups](mockups/):

- Dark navy/charcoal surface is preserved; no pure black screen chrome.
- Cards and sheets use restrained borders/elevation and consistent corner
  radius.
- Accent color is reserved for active/primary states.
- Terminal text, prompt composer, snippets, chip row, key bar, and bottom
  controls do not overlap incoherently with system bars.
- Host setup/session choice exposes an understandable raw SSH path.
- Composer recording/transcribing states are visually distinct.

Only small high-impact blockers should be fixed in this issue. Larger redesign
work should get a separate issue with screenshots attached.

## 2026-05-23 Audit Notes

Latest passing artifact set:

```text
build/dogfood-visual-pass/issue76-reviewer-fix-20260523-194202/screenshots/dogfood-visual-pass/
```

Observed coverage:

- Host list, tmux/session choice, live terminal controls, snippets, composer
  draft, composer recording, and composer transcribing screenshots were
  captured from the emulator at 1080x2400.
- The terminal screenshot is captured after a Docker-backed marker command is
  visible and after the prompt composer/keyboard have been dismissed, so the
  artifact covers marker command output, prompt readability, and bottom controls
  before the snippet picker opens.
- Composer draft is captured in the live session with the keyboard visible;
  recording and transcribing states use the composer pure renderer because
  those states are timing-sensitive in the live audio flow.
- The Docker SSH target is recreated for each capture, then proved ready from
  the host via `ssh` and from Android via SSHJ before the tmux fixture is
  prepared.
- The capture script now requires each instrumentation phase to report `OK (...)`
  without `FAILURES!!!`, then asserts all expected PNG files were pulled.
- The current host-list artifact shows the app-bar labels below the status bar,
  and the test asserts all app-bar labels listed above.
- The current tmux/session-picker artifact shows the sheet title and raw SSH
  controls above the navigation bar, and the test asserts the listed controls.

Visual blockers found:

- Terminal text begins at the left display edge; the prompt is readable but
  visually clipped against the screen boundary.

The earlier reviewer-observed status/title and raw-SSH/nav collisions are not
present in the latest artifact set above. The assertions now guard multiple
visible labels/controls in those regions, but they are still scoped to named
Compose semantics nodes, not a full pixel-level proof for every painted element.

## 2026-05-23 Reviewer Follow-Up

Fresh compile validation after the assertion changes:

```text
./gradlew --no-daemon --no-build-cache :app:compileDebugAndroidTestKotlin
```

Result: `BUILD SUCCESSFUL in 21s`. This specifically verifies the screenshot
test source after broadening the status/navigation bar assertions.

Fresh wrapper capture:

```text
RUN_ID=issue76-reviewer-fix-20260523-194202 scripts/capture-dogfood-screenshots.sh
```

Result: `PASS: dogfood visual screenshots captured`.

```text
Main visual instrumentation: OK (1 test)
Composer visual instrumentation: OK (1 test)
```

Artifacts and logs are under:

```text
build/dogfood-visual-pass/issue76-reviewer-fix-20260523-194202/
```

The prior wrapper crash was investigated in
`build/dogfood-visual-pass/issue76-reviewer-followup2-20260523-184308/adb-logcat.txt`.
Logcat showed PackageManager replacing `com.pocketshell.app` while
`DogfoodVisualScreenshotTest` was running:

```text
Killing ... com.pocketshell.app ... stop com.pocketshell.app due to installPackageLI
Crash of app com.pocketshell.app running instrumentation
```

The wrapper now preserves per-attempt instrumentation logs and retries this
specific `Process crashed` case up to `INSTRUMENTATION_ATTEMPTS` times, default
`3`. The latest passing run did not need a retry; both phases passed on attempt
1.
