package com.pocketshell.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import com.pocketshell.app.crash.CrashReportsScreen
import com.pocketshell.app.hosts.AddEditHostScreen
import com.pocketshell.app.hosts.HostListScreen
import com.pocketshell.app.hosts.SshKeysScreen
import com.pocketshell.app.jobs.RecurringJobsScreen
import com.pocketshell.app.jobs.RecurringJobsViewModel
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.portfwd.PortForwardPanelScreen
import com.pocketshell.app.session.SessionScreen
import com.pocketshell.app.session.SessionViewModel
import com.pocketshell.app.systemsurfaces.ForwardingChooserScreen
import com.pocketshell.app.systemsurfaces.ForwardingTileService
import com.pocketshell.app.tmux.TmuxSessionScreen
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Phase 1 entry point.
 *
 * Hosts a hand-rolled state-based navigator (see
 * [com.pocketshell.app.nav.AppDestination] for rationale on not pulling
 * in `androidx.navigation:navigation-compose` — the brief for #18
 * forbids new catalog entries and we have a small finite destination
 * set). The current destination lives in a `mutableStateOf`, with a
 * small back-stack as a list for the back gesture.
 *
 * Destinations:
 *
 * - [AppDestination.HostList] (landing) — list of saved SSH hosts.
 * - [AppDestination.AddHost] / [AppDestination.EditHost] — host form.
 * - [AppDestination.SshKeys] — SSH key list / add / delete.
 * - [AppDestination.Session] — live SSH session for a selected host.
 *
 * The Phase 0 `ProofOfLifeScreen` is kept on disk (the
 * `ProofPipelineTest` still imports its helper functions) but is no
 * longer the launcher entry. The host picker landed here in #18 owns
 * that role now.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    // SessionViewModel stays activity-scoped: we only have one live
    // session at a time today; multi-pane lifecycle arrives with #22.
    private val sessionViewModel: SessionViewModel by viewModels()
    private var requestedDestination by mutableStateOf<AppDestination>(AppDestination.HostList)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedDestination = initialDestinationFromIntent(intent)
        enableEdgeToEdge()
        setContent {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigator(
                        sessionViewModel = sessionViewModel,
                        requestedDestination = requestedDestination,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination = initialDestinationFromIntent(intent)
    }
}

/**
 * Sealed-class destination state machine. The back-stack is a `List<AppDestination>`
 * we push / pop; rendering branches on the head.
 *
 * Saveable: we don't persist the back stack across process death today.
 * Phase 1 acceptance only requires that hosts + keys persist across app
 * restarts; the back stack is volatile by design (cold-launch returns
 * the user to `HostList`).
 */
@Composable
private fun AppNavigator(
    sessionViewModel: SessionViewModel,
    requestedDestination: AppDestination,
) {
    // Volatile in-memory state. Cold-launch (process death) always lands
    // on `HostList`; full saved-state restoration arrives when
    // navigation-compose lands and brings its own saver machinery.
    var current: AppDestination by remember {
        mutableStateOf(requestedDestination)
    }

    val backStack = remember { mutableListOf<AppDestination>() }

    LaunchedEffect(requestedDestination) {
        if (requestedDestination == AppDestination.PortForwardChooser && current != requestedDestination) {
            backStack += current
            current = requestedDestination
        }
    }

    fun navigate(dest: AppDestination) {
        backStack += current
        current = dest
    }

    fun replace(dest: AppDestination) {
        current = dest
    }

    fun back() {
        current = backStack.removeLastOrNull() ?: AppDestination.HostList
    }

    when (val dest = current) {
        AppDestination.HostList -> HostListScreen(
            onAddHost = { navigate(AppDestination.AddHost) },
            onEditHost = { id -> navigate(AppDestination.EditHost(id)) },
            onManageKeys = { navigate(AppDestination.SshKeys) },
            onOpenCrashReports = { navigate(AppDestination.CrashReports) },
            onOpenSession = { host, keyPath, passphrase ->
                navigate(
                    AppDestination.Session(
                        hostId = host.id,
                        hostName = host.name,
                        hostname = host.hostname,
                        port = host.port,
                        username = host.username,
                        keyPath = keyPath,
                        passphrase = passphrase,
                    ),
                )
            },
            onOpenTmuxHostSession = { host, keyPath, passphrase, sessionName ->
                navigate(
                    AppDestination.TmuxSession(
                        hostId = host.id,
                        hostName = host.name,
                        hostname = host.hostname,
                        port = host.port,
                        username = host.username,
                        keyPath = keyPath,
                        passphrase = passphrase,
                        sessionName = sessionName,
                    ),
                )
            },
            onOpenPortForwardPanel = { host, keyPath, passphrase ->
                navigate(AppDestination.PortForwardPanel(hostId = host.id, keyPath = keyPath, passphrase = passphrase))
            },
            onOpenTmuxSession = { entry, sessionName ->
                navigate(
                    AppDestination.TmuxSession(
                        hostId = entry.hostId,
                        hostName = entry.hostName,
                        hostname = entry.hostname,
                        port = entry.port,
                        username = entry.username,
                        keyPath = entry.keyPath,
                        passphrase = null,
                        sessionName = sessionName,
                    ),
                )
            },
        )

        AppDestination.AddHost -> AddEditHostScreen(
            hostId = null,
            onDone = ::back,
            onManageKeys = { navigate(AppDestination.SshKeys) },
        )

        is AppDestination.EditHost -> AddEditHostScreen(
            hostId = dest.hostId,
            onDone = ::back,
            onManageKeys = { navigate(AppDestination.SshKeys) },
        )

        AppDestination.SshKeys -> SshKeysScreen(onBack = ::back)

        AppDestination.CrashReports -> CrashReportsScreen(onBack = ::back)

        AppDestination.PortForwardChooser -> ForwardingChooserScreen(
            onBack = ::back,
            onOpenPortForwardPanel = { host, keyPath, passphrase ->
                navigate(
                    AppDestination.PortForwardPanel(
                        hostId = host.id,
                        keyPath = keyPath,
                        passphrase = passphrase,
                    ),
                )
            },
        )

        is AppDestination.Session -> SessionScreen(
            viewModel = sessionViewModel,
            host = dest.hostname,
            port = dest.port,
            user = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            // Issue #17: the session screen surfaces the snippet picker
            // off the chip row + the composer's Snippets button. Both
            // need the persisted host id to scope the library.
            hostId = dest.hostId,
            onBack = ::back,
            onOpenJobs = {
                navigate(
                    AppDestination.RecurringJobs(
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = DefaultTmuxSessionName,
                    ),
                )
            },
        )

        is AppDestination.PortForwardPanel -> PortForwardPanelScreen(
            hostId = dest.hostId,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            onBack = ::back,
        )

        is AppDestination.RecurringJobs -> {
            val jobsViewModel = hiltViewModel<RecurringJobsViewModel>()
            androidx.compose.runtime.LaunchedEffect(dest) {
                jobsViewModel.load(
                    hostName = dest.hostName,
                    hostname = dest.hostname,
                    port = dest.port,
                    username = dest.username,
                    keyPath = dest.keyPath,
                    passphrase = dest.passphrase,
                    sessionName = dest.sessionName,
                )
            }
            val state by jobsViewModel.state.collectAsState()
            RecurringJobsScreen(
                state = state,
                onBack = ::back,
                onRefresh = jobsViewModel::refresh,
                onAdd = jobsViewModel::add,
                onEdit = jobsViewModel::edit,
                onRemove = jobsViewModel::remove,
            )
        }

        // Issue #45: tmux control-mode session. The view model is
        // obtained via `hiltViewModel()` — distinct from the
        // activity-scoped `sessionViewModel` above so each navigation to
        // a tmux destination spins up its own client (and tears it down
        // when the user navigates away). Once the host bootstrap from
        // #49 detects tmux on a host, the picker can route here in place
        // of [AppDestination.Session]; today the picker still routes to
        // plain SSH and this branch is reached only by future deep-link
        // / explicit-route wiring.
        is AppDestination.TmuxSession -> TmuxSessionScreen(
            viewModel = hiltViewModel<TmuxSessionViewModel>(),
            hostId = dest.hostId,
            hostName = dest.hostName,
            host = dest.hostname,
            port = dest.port,
            user = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            sessionName = dest.sessionName,
            onBack = ::back,
            onOpenTmuxSession = { sessionName ->
                navigate(
                    dest.copy(sessionName = sessionName),
                )
            },
            onReplaceTmuxSession = { sessionName ->
                replace(dest.copy(sessionName = sessionName))
            },
            onOpenJobs = {
                navigate(
                    AppDestination.RecurringJobs(
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = dest.sessionName,
                    ),
                )
            },
        )
    }
}

internal fun initialDestinationFromIntent(intent: Intent?): AppDestination =
    if (intent?.getBooleanExtra(ForwardingTileService.EXTRA_OPEN_PORT_FORWARDING, false) == true) {
        AppDestination.PortForwardChooser
    } else {
        AppDestination.HostList
    }

private const val DefaultTmuxSessionName = "pocketshell"
