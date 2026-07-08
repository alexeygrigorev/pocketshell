package com.pocketshell.app.composer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Issue #1272 — a REAL `@AndroidEntryPoint` host that mounts the PRODUCTION
 * [PromptComposerSheet] with ZERO test seams, so the connected regression test
 * exercises the exact composer wiring that ships.
 *
 * ## Why this activity has to exist (the #1341 reviewer's blocking gap)
 *
 * The composer resolves the activity-scoped [com.pocketshell.app.session.InlineDictationViewModel]
 * that owns the durable undelivered-transcript queue via a guard in
 * `rememberComposerUndeliveredBinding`:
 *
 * ```kotlin
 * val vm = override
 *     ?: if (storeOwner is GeneratedComponentManagerHolder) hiltViewModel(storeOwner)
 *     else null
 * ```
 *
 * The other `PromptComposerUndeliveredRetryTest` cases mount the sheet under a
 * plain (non-Hilt) `ComponentActivity`, so the `storeOwner` is NOT a
 * `GeneratedComponentManagerHolder` and the guard takes the inert `null` branch
 * (test 3 forces the surface via the `inlineDictationViewModel` OVERRIDE seam).
 * That proves "SheetContent renders a banner when handed a list", but NEVER
 * executes the REAL `storeOwner is GeneratedComponentManagerHolder ->
 * hiltViewModel(storeOwner)` branch — the exact production link the #1341
 * reviewer blocked closure on.
 *
 * This activity IS `@AndroidEntryPoint`, so its `ViewModelStoreOwner` (the
 * activity, exposed as `LocalViewModelStoreOwner.current` inside the composer)
 * IS a `GeneratedComponentManagerHolder` and the guard takes the REAL Hilt
 * branch. It mounts [PromptComposerSheet] with NO override — every ViewModel
 * (`PromptComposerViewModel` AND the guarded `InlineDictationViewModel`) is
 * resolved from the live app Hilt graph exactly as production does. The test
 * then reaches the SAME activity-scoped `InlineDictationViewModel` instance via
 * `ViewModelProvider(activity)` and drives the durable queue, proving the guard
 * resolves the production VM.
 *
 * Debug-source-set only (Hilt processes `src/main` + the debug build type, which
 * is what `androidTest` runs against). It never ships in a release build.
 */
@AndroidEntryPoint
class ComposerHiltHostActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // A minimal terminal backdrop so the composer sheet renders
                    // over content, matching the real session screen framing.
                    Text(
                        text = "alex@pocketshell:~$ claude\n> ready",
                        color = PocketShellColors.Text,
                    )
                    // PRODUCTION wiring, no seams: `viewModel` and the guarded
                    // `inlineDictationViewModel` (left null) both resolve from
                    // the live Hilt graph via hiltViewModel(). This is the exact
                    // path TmuxSessionScreen mounts.
                    PromptComposerSheet(
                        onDismiss = {},
                        onSend = { _ -> true },
                    )
                }
            }
        }
    }
}
