/*
 * PocketShell stub implementation of libtermux.so.
 *
 * See CMakeLists.txt next to this file for the full rationale. In short:
 * the vendored `com.termux.terminal.JNI` class needs to link against a
 * `libtermux.so` to load. PocketShell does not spawn local PTYs (it renders
 * remote SSH streams), so the JNI calls are no-ops here. The bridge in
 * `com.pocketshell.core.terminal.bridge.SshTerminalBridge` pre-populates the
 * emulator on `TerminalSession`, so `initializeEmulator` (which would call
 * `createSubprocess` + `waitFor`) is never invoked at runtime. Only
 * `setPtyWindowSize` reliably fires (from `TerminalView.updateSize` on every
 * layout change after the emulator exists) — that one is the one we strictly
 * need to be a safe no-op.
 *
 * The native method signatures match upstream termux exactly so that
 * `RegisterNatives`-style lookups by name+signature succeed. They are derived
 * from the `native` declarations in
 * `shared/core-terminal/src/main/java/com/termux/terminal/JNI.java`.
 */

#include <jni.h>
#include <unistd.h>
#include <stdint.h>

/*
 * createSubprocess: not invoked by our bridge, but stubbed for completeness so
 * an accidental call does not crash the process. Writes pid=1 into the
 * out-array (an arbitrary positive integer so the caller's check
 * `mShellPid > 0` would pass) and returns -1 as the "fake" master fd.
 */
JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_createSubprocess(
    JNIEnv *env,
    jclass clazz,
    jstring cmd,
    jstring cwd,
    jobjectArray args,
    jobjectArray envVars,
    jintArray processId,
    jint rows,
    jint columns,
    jint cellWidth,
    jint cellHeight) {
    (void) clazz; (void) cmd; (void) cwd; (void) args; (void) envVars;
    (void) rows; (void) columns; (void) cellWidth; (void) cellHeight;

    if (processId != NULL) {
        jint fakePid = 1;
        (*env)->SetIntArrayRegion(env, processId, 0, 1, &fakePid);
    }
    return -1;
}

/*
 * setPtyWindowSize: invoked from `TerminalSession.updateSize` after the
 * emulator already exists. We have nothing to resize on the kernel side, so
 * no-op.
 */
JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_setPtyWindowSize(
    JNIEnv *env,
    jclass clazz,
    jint fd,
    jint rows,
    jint cols,
    jint cellWidth,
    jint cellHeight) {
    (void) env; (void) clazz; (void) fd;
    (void) rows; (void) cols; (void) cellWidth; (void) cellHeight;
}

/*
 * waitFor: would normally block until the local subprocess exits. Our bridge
 * never spawns one, so this should not be called. If it is, block forever so
 * the caller's thread does not spin — equivalent to an unending child.
 */
JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_waitFor(
    JNIEnv *env,
    jclass clazz,
    jint processId) {
    (void) env; (void) clazz; (void) processId;
    for (;;) {
        sleep(60);
    }
    return 0;
}

/*
 * close: no-op. We never opened a real fd via createSubprocess.
 */
JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_close(
    JNIEnv *env,
    jclass clazz,
    jint fileDescriptor) {
    (void) env; (void) clazz; (void) fileDescriptor;
}
