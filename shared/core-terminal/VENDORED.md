# Vendored: Termux terminal-emulator + terminal-view

This module is a source-level copy of two libraries from
[`termux/termux-app`](https://github.com/termux/termux-app). PocketShell ships
them in-process rather than depending on a published artifact (no first-party
Maven artifact is published; JitPack hosts the libraries but vendoring removes
the network-publish dependency from our build and pins the exact tree we test).

Per [D4](../../docs/decisions.md): writing a VT emulator from scratch is a
6-month detour. Termux's emulator is battle-tested xterm-256color and is the
foundation for PocketShell's per-pane rendering (`docs/architecture.md` —
"Three load-bearing decisions" #3).

## Upstream pin

- **Repository:** https://github.com/termux/termux-app
- **Commit SHA:** `30ebb2dee381d292ade0f2868cfde0f9f20b89fe`
- **Commit summary:** "Fixed: Fix inverted typo in termcap values for
  `KEYCODE_PAGE_UP` and `KEYCODE_PAGE_DOWN`"
- **Vendored on:** 2026-05-21
- **Vendored subprojects:**
  - [`terminal-emulator/`](https://github.com/termux/termux-app/tree/30ebb2dee381d292ade0f2868cfde0f9f20b89fe/terminal-emulator)
  - [`terminal-view/`](https://github.com/termux/termux-app/tree/30ebb2dee381d292ade0f2868cfde0f9f20b89fe/terminal-view)

## License

> ⚠️ The issue body for #7 claimed LGPL-3.0. The upstream `LICENSE.md` is
> explicit that `terminal-view` and `terminal-emulator` are an **exception** to
> termux-app's GPLv3 license — they descend from
> [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)
> and are released under **Apache License 2.0**. See
> https://github.com/termux/termux-app/blob/master/LICENSE.md (commit pin
> above). Apache 2.0 is what is reproduced in `LICENSE.txt` next to this file
> and what is acknowledged in `app/src/main/res/raw/third_party_licenses.txt`.

The vendored source files themselves carry no per-file copyright headers
upstream. Re-licensing is not attempted; downstream callers of this module
inherit the upstream Apache 2.0 obligations (notice, attribution, mark
modifications).

## What is and isn't vendored

| Path | Source | Notes |
|---|---|---|
| `src/main/java/com/termux/terminal/**` | upstream `terminal-emulator/src/main/java/com/termux/terminal/**` | **patched** — `TerminalEmulator`, `TerminalBuffer`, `TerminalRow`, `TextStyle`; every deviation is listed in `PATCHES.md`. **`TerminalSession.java` is NOT vendored at all** — see "PocketShell's own `TerminalSession`" below. `ByteQueue`/`JNI` are deleted. Rest (including `TerminalSessionClient`) byte-identical. |
| `src/main/java/com/termux/view/**` | upstream `terminal-view/src/main/java/com/termux/view/**` | **patched** — `TerminalRenderer`, `TerminalView`, `TerminalViewClient`; every deviation is listed in `PATCHES.md`. Rest (including the `textselection` package) byte-identical. |
| `src/main/res/drawable/text_select_handle_*.xml` | upstream `terminal-view/src/main/res/drawable/` | byte-identical |
| `src/main/res/values/strings.xml` | upstream `terminal-view/src/main/res/values/strings.xml` | byte-identical |
| `src/test/java/com/termux/terminal/**` | upstream `terminal-emulator/src/test/java/com/termux/terminal/**` | **patched** — adds cases (never modifies upstream ones) to `TerminalTest`, `OperatingSystemControlTest`, `RectangularAreasTest`, `TextStyleTest`; listed in `PATCHES.md`. Rest byte-identical. |
| `src/test/java/com/termux/view/**` | **PocketShell-authored, not vendored** | Upstream `terminal-view` ships no unit tests, so this package is ours: `TerminalScrollGestureTest.kt` (#2555) lives here because it needs package-private access to `TerminalView.doScroll`. Delete nothing here on a refresh. |
| `src/test/resources/pocketshell/**` | **PocketShell-authored, not vendored** | Real PTY attach captures used as fixtures (#2555); see the README next to them. |

If we ever deviate from upstream — even a one-character patch — record it in
`PATCHES.md` alongside this file. `PATCHES.md`'s file list is authoritative and
was produced by diffing the whole vendored tree against the pin; regenerate it
the same way (step 5 of the refresh procedure) rather than editing it from
memory.

## Namespace handling

PocketShell's other shared modules use `com.pocketshell.core.<area>` for their
Android namespace (and thus their generated `R` class). This module deviates:

- `android.namespace` is set to **`com.termux.view`**, not
  `com.pocketshell.core.terminal`.
- Reason: `terminal-view` source files import `com.termux.view.R` directly
  (e.g. `R.drawable.text_select_handle_left_material`). Picking the upstream
  namespace makes the vendored source byte-identical to upstream and removes
  any patching from the refresh procedure.
- The merged module still emits a single `R` class — the
  `terminal-emulator` package (`com.termux.terminal`) doesn't reference an
  `R` class, so there's no clash.

If we later wire downstream `:shared:core-*` modules that themselves want
`com.termux.view.R` symbols, they consume them transitively through this AAR.

## Dependencies pulled in

| Library | Version | Why |
|---|---|---|
| `androidx.annotation:annotation` | 1.9.0 | Vendored sources use `@NonNull` / `@Nullable` on public surfaces. Matches the version pinned at the upstream commit. Declared `api` so consumers see the annotation types. |
| `junit:junit` | 4.13.2 | Required by the vendored unit tests under `src/test/`. Declared `testImplementation`. Version already lives in the project version catalog (`libs.versions.toml`). |

No `androidx.appcompat` is required. The upstream code is plain Android
framework `View` / `EditText` plumbing; it does not extend any `AppCompat*`
classes. This avoids a transitive AppCompat dependency in PocketShell, which
is purely a Compose app.

## PocketShell's own `TerminalSession` — NOT vendored, never refreshed

`src/main/java/com/termux/terminal/TerminalSession.java` keeps upstream's
package and class name so `TerminalView`, `TextSelectionCursorController` and
`TerminalEmulator` compile unpatched against it, but the body is **PocketShell's
own** (issue #2566). It is not a patched copy of upstream's file and it must
never be re-copied from a refresh.

Upstream's class exists to spawn a **local** pty subprocess through JNI and
shuttle bytes across two byte ring buffers on three threads. PocketShell's
terminal data flow is remote-only — bytes arrive on an SSH PTY channel opened
by app2's `TerminalPtyBridge` and keystrokes leave the same way — so every part
of that machinery had to be worked around rather than used: app2 pre-installed
the emulator and faked a shell pid by reflection, duplicated a private
handler-message constant, polled the input buffer every 8 ms, and this module
compiled a stub `libtermux.so` purely so `updateSize` would not land in a native
call nobody wanted. Replacing the class deleted all of it.

The replacement is a remote-only session:

- constructed at a known geometry and building its own `TerminalEmulator`
  (there is no "no emulator yet" window, because there is no pty to wait for);
- `append(byte[], int, int)` parses remote bytes into the grid and fires
  `onTextChanged`, and **throws `IllegalStateException` off the main thread**;
- `setInputSink(InputSink)` is where typed bytes and the emulator's own query
  replies go. With no sink installed they are held in a bounded 4 KB buffer
  (`PENDING_INPUT_CAPACITY_BYTES`) and flushed, in order, to the next sink
  installed — that is what carries a keystroke typed at the "Reconnecting"
  banner into the reattached channel (journey J06), the one behaviour upstream's
  terminal-to-process ring buffer was load-bearing for. Past the bound bytes are
  dropped rather than blocking the writer;
- `updateSize`, `write`, `writeCodePoint`, `getEmulator`, `getTitle`,
  `updateTerminalSessionClient` and the `TerminalOutput` callbacks are the rest
  of the surface — nothing else exists.

Consequences for this module:

- `ByteQueue.java`, `JNI.java`, `src/main/jni/` (upstream's C sources) and
  `src/main/cpp/` (the PocketShell stub + its `CMakeLists.txt`) are **deleted**.
- `build.gradle.kts` has **no `externalNativeBuild`, no `abiFilters` and no
  `jniLibs` block**: the module builds with no NDK and no CMake installed.
- There are **no reflection hazards left**. A Termux refresh cannot silently
  break app2's terminal by moving a package-private field, because app2 no
  longer reads one.

### Refresh rule

Step 4 of the [refresh procedure](#refresh-procedure) below copies the upstream
`com/termux` tree wholesale. After doing so, **delete the upstream
`TerminalSession.java`, `ByteQueue.java` and `JNI.java` it brings back and
restore ours from git**, and do not re-create `src/main/jni/`. If upstream's
`TerminalOutput` or `TerminalSessionClient` contract changes, ours is the file
that has to be updated by hand — `TerminalSessionTest`
(`src/test/java/com/pocketshell/core/terminal/session/`) is what tells you
whether it still holds.

## Refresh procedure

When a future Termux release fixes a bug or adds a CSI sequence we care about:

1. `git clone --depth 30 https://github.com/termux/termux-app /tmp/termux-app`
2. `cd /tmp/termux-app && git log --oneline -- terminal-emulator terminal-view`
3. Pick a target commit. Record its full SHA.
4. From the PocketShell repo root, **replace** (don't merge) the vendored
   trees:
   ```bash
   rm -rf shared/core-terminal/src/main/java/com/termux \
          shared/core-terminal/src/test/java/com/termux \
          shared/core-terminal/src/main/res/drawable/text_select_handle_*.xml \
          shared/core-terminal/src/main/res/values/strings.xml
   cp -r /tmp/termux-app/terminal-emulator/src/main/java/com/termux \
         shared/core-terminal/src/main/java/com/termux
   cp -r /tmp/termux-app/terminal-view/src/main/java/com/termux/* \
         shared/core-terminal/src/main/java/com/termux/
   cp /tmp/termux-app/terminal-view/src/main/res/drawable/*.xml \
      shared/core-terminal/src/main/res/drawable/
   cp /tmp/termux-app/terminal-view/src/main/res/values/strings.xml \
      shared/core-terminal/src/main/res/values/strings.xml
   cp -r /tmp/termux-app/terminal-emulator/src/test/java/com/termux \
         shared/core-terminal/src/test/java/com/termux
   ```
5. Undo what the copy brought back for the three files this module does not
   take from upstream — restore ours with `git checkout --` on
   `TerminalSession.java`, and delete `ByteQueue.java` and `JNI.java` again
   (see "PocketShell's own `TerminalSession`" above). Then diff the whole
   vendored tree against the new pin (`diff -rq` of
   `src/main/java/com/termux`, `src/test/java/com/termux` and `src/main/res`
   against the upstream checkout) and **regenerate `PATCHES.md`
   from that diff**, not from memory — its file list is the record a future
   refresh re-applies from, and it has been wrong before. If the upstream
   `androidx.annotation` version changed, bump `androidx-annotation` in
   `gradle/libs.versions.toml` to match.
6. Re-read upstream `LICENSE.md` — if the `terminal-emulator` /
   `terminal-view` license stops being Apache 2.0, update `LICENSE.txt` and
   `app/src/main/res/raw/third_party_licenses.txt`.
7. Update the "Upstream pin" block above (SHA, commit summary, date).
8. If patches were carried in `PATCHES.md`, re-apply them on top.
9. `./gradlew :shared:core-terminal:assemble` + `:testDebugUnitTest`. Both
   must pass before committing the refresh.
