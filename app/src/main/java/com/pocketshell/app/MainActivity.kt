package com.pocketshell.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pocketshell.app.proof.ProofOfLifeScreen
import com.pocketshell.app.ui.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts the Phase 0 proof-of-life: a hardcoded SSH connection to the
 * `pocketshell-test:ssh` Docker container with the remote shell rendered
 * inside a [com.pocketshell.core.terminal.ui.TerminalSurface]. Phase 1
 * (issue #11 onwards) replaces this with a proper host list + session
 * navigator.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ProofOfLifeScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
