package com.pocketshell.app.terminal

import android.content.Context
import com.pocketshell.app.R

/**
 * Read the bundled raw-resource SSH key into a String. The resource is the
 * exact file shipped in `tests/docker/test_key` — an ed25519 OpenSSH-format
 * private key generated for the deterministic test container. **Do not reuse
 * this key in production**; it lives in version control and is purely a
 * smoke-test artifact.
 *
 * Used by [TerminalLabActivity] as the default key when no key is supplied via
 * the launch intent. Relocated here from the deleted Phase 0
 * `ProofOfLifeScreen` cluster (issue #735), which was the last consumer of the
 * proof package's now-removed dead composable.
 */
internal fun readKeyFromRawResource(context: Context): String {
    return context.resources.openRawResource(R.raw.proof_test_key)
        .bufferedReader()
        .use { it.readText() }
}
