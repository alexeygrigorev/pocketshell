package com.pocketshell.app.proof.signals

import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.testsupport.ImeServiceState
import com.pocketshell.testsupport.parseImeServiceState

/**
 * Issue #1879: the IO half of the **captured IME-service-state signature**.
 *
 * The decision logic is pure and lives in
 * `shared/test-support/.../ImeServiceState.kt`, pinned by
 * `ImeServiceStateSignatureTest` in the required per-PR `Unit tests` job. This
 * file only collects the three real inputs:
 *
 *  * `dumpsys input_method` — the service-side facts (`mSystemReady`,
 *    `mCurMethodId`, `mCurMethod`, `mHaveConnection`);
 *  * `Settings.Secure.DEFAULT_INPUT_METHOD` — the configured IME;
 *  * `InputMethodManager.getEnabledInputMethodList()` — whether any IME exists
 *    to be raised at all.
 *
 * **Call it only on a failure path.** The dump is a shell round-trip costing
 * tens of milliseconds and producing thousands of lines; running it per cycle
 * on the happy path would be the same class of cross-test cost this issue is
 * about.
 */
fun captureImeServiceState(): ImeServiceState = parseImeServiceState(
    dumpsysInputMethod = runCatching { executeShellCommand("dumpsys input_method") }.getOrNull(),
    defaultInputMethod = runCatching {
        Settings.Secure.getString(
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
    }.getOrNull(),
    enabledInputMethodIds = runCatching {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(InputMethodManager::class.java)
        manager?.enabledInputMethodList?.map { it.id }
    }.getOrNull(),
)

private fun executeShellCommand(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
        stream.readBytes().toString(Charsets.UTF_8)
    }
}
