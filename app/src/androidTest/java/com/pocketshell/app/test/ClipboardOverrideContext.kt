package com.pocketshell.app.test

import android.content.Context
import android.content.ContextWrapper
import android.content.RecordingClipboardManager

/**
 * [ContextWrapper] that routes `getSystemService(CLIPBOARD_SERVICE)` to the
 * supplied [recording] subclass. Provide it via
 * `CompositionLocalProvider(LocalContext provides ClipboardOverrideContext(...))`
 * around the composable under test so the production copy code's
 * `LocalContext.current.getSystemService(CLIPBOARD_SERVICE)` resolves to the
 * recording instance instead of the real system clipboard.
 *
 * This is the app-module twin of the `ClipboardOverrideContext` proven in
 * `shared/core-terminal/.../TerminalSurfaceComposeIntegrationTest`. It makes
 * clipboard assertions deterministic on the AOSP API 35 swiftshader AVD,
 * whose default un-focused activity window makes a real
 * `clipboard.primaryClip` read return `null` (the foreground-focus policy on
 * API 29+).
 *
 * [getApplicationContext] also returns a wrapper that routes
 * CLIPBOARD_SERVICE to the same recording instance, so copy paths that look
 * the service up on `context.applicationContext` are covered too. Every other
 * system service passes through to [base].
 */
class ClipboardOverrideContext(
    base: Context,
    private val recording: RecordingClipboardManager,
) : ContextWrapper(base) {
    override fun getSystemService(name: String): Any? {
        if (name == Context.CLIPBOARD_SERVICE) return recording
        return super.getSystemService(name)
    }

    override fun getApplicationContext(): Context {
        val baseApp = super.getApplicationContext()
        return object : ContextWrapper(baseApp) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.CLIPBOARD_SERVICE) return recording
                return super.getSystemService(name)
            }
        }
    }
}
