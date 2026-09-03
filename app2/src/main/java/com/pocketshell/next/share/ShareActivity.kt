package com.pocketshell.next.share

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The system share target (rewrite task P-9).
 *
 * ## Why a second Activity in a single-Activity app
 *
 * app2 is deliberately one Activity (`MainActivity`) plus one Compose nav graph.
 * This is the one exception, and it is forced: an `ACTION_SEND` intent filter is
 * a manifest-level declaration on a component, so the share sheet needs a
 * component to launch. Routing it through `MainActivity` instead would mean the
 * launcher Activity — which owns the whole navigation back stack and, with
 * `singleTask`, an already-running task the user is mid-session in — has to grow
 * an "am I a share right now?" mode. A share is a one-shot transaction that
 * finishes itself; giving it its own task-excluded Activity keeps it from
 * touching the session the user was in.
 *
 * ## Untrusted input
 *
 * The component is EXPORTED, so `intent` is attacker-controlled. Decoding is
 * fully defensive (see [decodeShareIntent]) and an intent carrying nothing
 * routable finishes with a toast rather than an empty screen or a crash.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val staged = decodeShareIntent(intent)
        if (staged.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Staged BEFORE the first composition so the "one host, upload without
        // asking" shortcut can fire on the very first host emission instead of
        // flashing a picker the user never needed to see.
        viewModel.stage(staged)

        setContent {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ShareRoute(
                        onFinished = { finish() },
                        // Same edge-to-edge inset treatment as MainActivity:
                        // targetSdk 35 draws under the bars, and the header must
                        // not render under the clock.
                        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

}
