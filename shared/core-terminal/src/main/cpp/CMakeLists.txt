# PocketShell stub for libtermux.so
#
# Issue #9 (Phase 0 proof-of-life) needs the vendored `com.termux.terminal.JNI`
# class to load without `UnsatisfiedLinkError`. The vendored Java class declares
# four native methods (createSubprocess, setPtyWindowSize, waitFor, close) all
# concerned with spawning and managing a *local* PTY subprocess on the device.
#
# PocketShell only renders REMOTE shells (bytes arrive over an SSH stream from
# `core-ssh`), so the local-PTY semantics are not needed. We ship a stub
# `libtermux.so` whose four entry points are deliberate no-ops:
#
# - createSubprocess returns -1 (an invalid fd) and writes a fake pid into the
#   processId out-array. We never actually call this from our bridge — the
#   PocketShellBridge pre-populates `TerminalSession.mEmulator` before any size
#   change so `TerminalSession.initializeEmulator` is skipped — but stubbing it
#   keeps `JNI.createSubprocess` safe to link in case future code paths reach it.
# - setPtyWindowSize is the actual function that gets called by
#   `TerminalView.updateSize` on every layout change after the emulator exists.
#   We accept the call and do nothing.
# - waitFor sleeps forever — it would normally block the
#   `TermSessionWaiter` thread until the local subprocess exits, but we never
#   start that thread because `initializeEmulator` is never invoked.
# - close is a no-op (no real fd to close).
#
# When PocketShell ever needs a local PTY (it currently does not — see
# VENDORED.md), the upstream `src/main/jni/termux.c` would compile in place of
# this stub. The vendored source is preserved under `src/main/jni/` as
# refresh-tracking parity; this stub at `src/main/cpp/` is the build-time
# alternative chosen for Phase 0.
cmake_minimum_required(VERSION 3.18.1)

project(pocketshell_termux_stub C)

# Library name MUST be `termux` so that `System.loadLibrary("termux")` in
# `com.termux.terminal.JNI`'s static initializer finds it.
add_library(termux SHARED pocketshell_termux_stub.c)

# Link against the Android log library so future debugging logs can land in
# logcat. Not strictly required by the current no-op stub, but cheap to
# pre-wire so we do not bump the CMake file again when we add diagnostics.
find_library(log-lib log)
target_link_libraries(termux ${log-lib})
