package com.pocketshell.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pocketshell.app.session.SessionScreen
import com.pocketshell.app.session.SessionViewModel
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Phase 1 entry point.
 *
 * Hosts a single destination — the [SessionScreen] — wired to a Hilt-scoped
 * [SessionViewModel]. The brief for #13 specifies a single-destination
 * `NavHost`, but `androidx.navigation:navigation-compose` is not on the
 * version catalog and the brief forbids new `libs.versions.toml` entries.
 * Since there is precisely one destination today, a direct composable
 * invocation is functionally identical to a `NavHost { composable("session") {...} }`
 * with no `navigate(...)` callers. #18's host picker is the first issue
 * that will actually need navigation — that issue can land the navigation
 * dependency along with the second destination.
 *
 * The Phase 0 `ProofOfLifeScreen` is kept (it still owns the SSH→terminal
 * helper functions and the byte-pipeline integration test in
 * `app/src/test/`), but is no longer the launcher entry.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val sessionViewModel: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SessionScreen(
                        viewModel = sessionViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
