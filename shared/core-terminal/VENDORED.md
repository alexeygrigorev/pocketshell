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
| `src/main/java/com/termux/terminal/**` | upstream `terminal-emulator/src/main/java/com/termux/terminal/**` | byte-identical |
| `src/main/java/com/termux/view/**` | upstream `terminal-view/src/main/java/com/termux/view/**` | byte-identical |
| `src/main/res/drawable/text_select_handle_*.xml` | upstream `terminal-view/src/main/res/drawable/` | byte-identical |
| `src/main/res/values/strings.xml` | upstream `terminal-view/src/main/res/values/strings.xml` | byte-identical |
| `src/main/jni/termux.c`, `src/main/jni/Android.mk` | upstream `terminal-emulator/src/main/jni/` | **not compiled** — see "JNI handling" |
| `src/test/java/com/termux/terminal/**` | upstream `terminal-emulator/src/test/java/com/termux/terminal/**` | byte-identical; all 145 tests pass |

If we ever deviate from upstream — even a one-character patch — record it in
`PATCHES.md` alongside this file.

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

## JNI handling — important for #8 and #9

Upstream `terminal-emulator` ships a small JNI library (`libtermux.so`) used
exclusively by `TerminalSession` to spawn **local** PTY subprocesses
(`JNI.createSubprocess(...)`, `JNI.setPtyWindowSize(...)`, `JNI.waitFor(...)`,
`JNI.close(...)`).

PocketShell's terminal data flow is **remote-only** (SSH-attached `tmux -CC`
panes — see `docs/architecture.md`). We do not fork local processes from the
phone, so the JNI is not on the critical path for either the Compose adapter
(#8) or the SSH/PTY wiring (#9).

This module therefore:

- Vendors the JNI sources to disk (`src/main/jni/`) only for refresh-tracking
  parity with upstream. The Gradle build explicitly **clears the `jni` and
  `jniLibs` source-set directories** so neither is compiled into the AAR and
  no native toolchain is required to build the module.
- Compiles the Java-side `com.termux.terminal.JNI` class normally. It contains
  only `native` method declarations; calling those without `libtermux.so` on
  the runtime path would `UnsatisfiedLinkError`.

**Action for #8 / #9:**

- The Compose adapter (#8) should drive `TerminalEmulator` + `TerminalBuffer`
  directly (or via a custom subclass of `TerminalSession` that bypasses the
  `JNI.createSubprocess` path). It must not call
  `TerminalSession.initializeEmulator(...)` as-is unless the JNI is built.
- The PTY plumbing (#9) feeds bytes into the emulator from sshj's `Session`
  output stream, not from a local PTY fd. The local-PTY entrypoints in
  `TerminalSession` are dead weight for our use case.
- If we ever need local PTYs (we currently don't), wire `externalNativeBuild`
  + `ndkBuild` back in this module, point at `src/main/jni/Android.mk`, and
  set `abiFilters` to whatever target ABIs we support. The upstream Android.mk
  is single-file and trivially buildable; no upstream patches needed.

### Issue #8 outcome — pass-through, no session

The Compose adapter (`com.pocketshell.core.terminal.ui.TerminalSurface`)
shipped by #8 deliberately does **not** construct or attach a
`TerminalSession`. Reasons:

- `TerminalSession` is `final`, so the suggested "custom subclass" route
  above is not actually open to us — Kotlin / Java cannot extend it.
- Constructing a real `TerminalSession` and just declining to call
  `initializeEmulator` is not enough either: `TerminalView.updateSize`
  unconditionally forwards into `session.updateSize` once it gets a
  non-zero size, which calls `initializeEmulator` and lands in JNI.

So the #8 adapter:

- Hosts a bare `TerminalView` via `AndroidView`.
- Wires a no-op `TerminalViewClient` so the view does not NPE.
- Leaves `TerminalSurfaceState.session` `null` by default.
- Exposes `attach(TerminalSession)`, `writeInput(...)`, and an `output`
  `SharedFlow` so #9 can plug in a real session source without changing
  the public API.

When #9 lands, it will either:

- Compile the JNI back into the module (`externalNativeBuild` + `ndkBuild`,
  per the bullet above) and construct a real `TerminalSession`, **or**
- Vendor a fresh fork of `TerminalSession` (non-final) into our own
  package that drives `TerminalEmulator` + `TerminalBuffer` from
  SSH-attached PTY bytes, bypassing JNI entirely. The vendored
  `TerminalSession` source stays byte-identical to upstream as
  refresh-tracking parity; our fork lives under `com.pocketshell.core.terminal.*`.

Either path keeps the #8 public API stable.

## Reflection hazards

`com.pocketshell.core.terminal.bridge.SshTerminalBridge` (in this module) is
intentionally kept outside the `com.termux.terminal` package so the vendored
sources stay byte-identical to upstream. To do that without forking Termux,
the bridge reaches into a handful of package-private members of
[`TerminalSession`](src/main/java/com/termux/terminal/TerminalSession.java)
and [`ByteQueue`](src/main/java/com/termux/terminal/ByteQueue.java) via
reflection. Each lookup is wrapped so a missing target throws
`IllegalStateException("PocketShell SshTerminalBridge: expected field <name>
on <class> (upstream Termux). Vendored source was refreshed without updating
the bridge?")` — but the failure happens at first use, not at compile time,
so refreshes must verify these targets explicitly.

### `TerminalSession` fields the bridge reflects on

All five are package-private on
[`com.termux.terminal.TerminalSession`](src/main/java/com/termux/terminal/TerminalSession.java)
and accessed via `getDeclaredField(...).setAccessible(true)`:

| Field | Type | Why the bridge needs it |
|---|---|---|
| `mEmulator` | `TerminalEmulator` | Pre-installed (write) so `TerminalSession.updateSize`'s "first call creates the emulator + spawns a local PTY via JNI" branch is never taken. |
| `mShellPid` | `int` | Set to a positive value (write) so `TerminalSession.write(byte[], int, int)`'s `if (mShellPid > 0)` guard accepts user input. |
| `mProcessToTerminalIOQueue` | `ByteQueue` | Read access — the bridge pushes SSH-stdout bytes into this queue (the upstream "PTY-to-emulator" buffer) so the emulator renders them. |
| `mTerminalToProcessIOQueue` | `ByteQueue` | Read access — the bridge drains user-typed bytes from this queue (the upstream "emulator-to-PTY" buffer) and forwards them to the SSH stdin stream. |
| `mMainThreadHandler` | `android.os.Handler` | Read access — the bridge posts the `MSG_NEW_INPUT` message here so the emulator runs on the main thread (matching upstream's threading contract). |

### `ByteQueue` methods the bridge reflects on

`com.termux.terminal.ByteQueue` is itself package-private. The bridge looks
up two methods on `Class.forName("com.termux.terminal.ByteQueue")`:

| Method | Signature | Why |
|---|---|---|
| `write` | `write(byte[] buffer, int offset, int length) -> boolean` | Push SSH-stdout bytes into `mProcessToTerminalIOQueue`. Returns `false` if the queue is closed. |
| `read` | `read(byte[] buffer, boolean block) -> int` | Block-read from `mTerminalToProcessIOQueue` for the drainer thread. Returns `-1` if closed. |

### Hardcoded handler-message constant

`TerminalSession.MSG_NEW_INPUT` is declared `private static final int = 1` in
the vendored source. The bridge hardcodes the value as
`SshTerminalBridge.MSG_NEW_INPUT = 1` and posts it via
`Handler.sendEmptyMessage(MSG_NEW_INPUT)` to nudge the emulator into draining
`mProcessToTerminalIOQueue`. The constant is duplicated in our code (rather
than reflected) on the assumption it never changes; if upstream ever renames
or renumbers it, the bridge will go silent — bytes will land in the queue but
nothing will pull them out, and the terminal will appear frozen with no
exception thrown.

### Refresh checklist for the reflection targets

After running the [refresh procedure](#refresh-procedure), before committing,
verify each item below. Any rename, removal, or signature change breaks the
bridge — most loudly with
`IllegalStateException("vendored source was refreshed")` at first use, but
silently in the `MSG_NEW_INPUT` case.

- [ ] `TerminalSession.mEmulator` still exists with type `TerminalEmulator`
- [ ] `TerminalSession.mShellPid` still exists with type `int`
- [ ] `TerminalSession.mProcessToTerminalIOQueue` still exists with type `ByteQueue`
- [ ] `TerminalSession.mTerminalToProcessIOQueue` still exists with type `ByteQueue`
- [ ] `TerminalSession.mMainThreadHandler` still exists with type `android.os.Handler`
- [ ] `ByteQueue.write(byte[], int, int): boolean` still exists with that signature
- [ ] `ByteQueue.read(byte[], boolean): int` still exists with that signature
- [ ] `TerminalSession.MSG_NEW_INPUT` still equals `1` (grep the vendored
      `TerminalSession.java` for `MSG_NEW_INPUT`); if not, update
      `SshTerminalBridge.MSG_NEW_INPUT` to match
- [ ] `./gradlew :shared:core-terminal:assemble :shared:core-terminal:testDebugUnitTest`
      passes, AND any downstream module exercising `SshTerminalBridge`
      (currently `:app` proof-of-life, eventually the production terminal
      composer) still works end-to-end against the
      `pocketshell-test:ssh` Testcontainers image

The reflection helper itself lives in
[`SshTerminalBridge.kt`](src/main/java/com/pocketshell/core/terminal/bridge/SshTerminalBridge.kt)
under `private object SessionReflection` — single source of truth, so the
refresh checklist maps 1:1 to the lookups in that object.

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
          shared/core-terminal/src/main/res/values/strings.xml \
          shared/core-terminal/src/main/jni/*
   cp -r /tmp/termux-app/terminal-emulator/src/main/java/com/termux \
         shared/core-terminal/src/main/java/com/termux
   cp -r /tmp/termux-app/terminal-view/src/main/java/com/termux/* \
         shared/core-terminal/src/main/java/com/termux/
   cp /tmp/termux-app/terminal-view/src/main/res/drawable/*.xml \
      shared/core-terminal/src/main/res/drawable/
   cp /tmp/termux-app/terminal-view/src/main/res/values/strings.xml \
      shared/core-terminal/src/main/res/values/strings.xml
   cp -r /tmp/termux-app/terminal-emulator/src/main/jni/* \
         shared/core-terminal/src/main/jni/
   cp -r /tmp/termux-app/terminal-emulator/src/test/java/com/termux \
         shared/core-terminal/src/test/java/com/termux
   ```
5. Diff against the previous pin; if the upstream `androidx.annotation`
   version changed, bump `androidx-annotation` in `gradle/libs.versions.toml`
   to match.
6. Re-read upstream `LICENSE.md` — if the `terminal-emulator` /
   `terminal-view` license stops being Apache 2.0, update `LICENSE.txt` and
   `app/src/main/res/raw/third_party_licenses.txt`.
7. Update the "Upstream pin" block above (SHA, commit summary, date).
8. If patches were carried in `PATCHES.md`, re-apply them on top.
9. `./gradlew :shared:core-terminal:assemble` + `:testDebugUnitTest`. Both
   must pass before committing the refresh.

## Open questions / risks

- **Compose BOM compatibility:** This module declares no Compose deps and
  uses no Compose APIs; there is no BOM conflict here. The Compose adapter
  (#8) will marshal between `TerminalEmulator` state and Compose draw calls
  in its own module.
- **`minSdk` mismatch:** PocketShell is at SDK 26. Upstream Termux runs as
  low as SDK 24 historically. We never lower; the upstream code's
  `Build.VERSION.SDK_INT` checks are simply always-true on our floor.
- **JNI:** documented above. No surprises at compile time; runtime callers
  must steer clear of `TerminalSession.initializeEmulator()` until #8/#9
  resolve the local-PTY question (and we may decide we never need it).
