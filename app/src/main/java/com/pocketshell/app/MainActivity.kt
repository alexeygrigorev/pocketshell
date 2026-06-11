package com.pocketshell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.pocketshell.app.conversation.LocalConversationFontSizeSp
import com.pocketshell.app.costs.CostsScreen
import com.pocketshell.app.crash.CrashReportContext
import com.pocketshell.app.crash.CrashReporter
import com.pocketshell.app.crash.CrashReportsScreen
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.AddEditHostScreen
import com.pocketshell.app.hosts.HostListScreen
import com.pocketshell.app.hosts.HostListViewModel
import com.pocketshell.app.hosts.QrScannerScreen
import com.pocketshell.app.env.EnvCopySourceFolder
import com.pocketshell.app.env.EnvScreen
import com.pocketshell.app.fileexplorer.FileExplorerScreen
import com.pocketshell.app.git.GitHistoryScreen
import com.pocketshell.app.fileviewer.FileViewerScreen
import com.pocketshell.app.jobs.RecurringJobsScreen
import com.pocketshell.app.jobs.RecurringJobsViewModel
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.portfwd.PortForwardPanelScreen
import com.pocketshell.app.projects.FolderListScreen
import com.pocketshell.app.projects.RepoBrowserScreen
import com.pocketshell.app.projects.WatchedFoldersScreen
import com.pocketshell.app.projects.WatchedFoldersViewModel
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.session.LastSessionStore
import com.pocketshell.app.sessions.StartDirectoryAutocompleteRemoteSource
import com.pocketshell.app.sessions.StartDirectoryAutocompleteTarget
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.settings.HostDetailViewMode
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.settings.SettingsScreen
import com.pocketshell.app.settings.SettingsViewModel
import com.pocketshell.app.systemsurfaces.ForwardingChooserScreen
import com.pocketshell.app.systemsurfaces.ForwardingTileService
import com.pocketshell.app.release.UpdateCheckScheduler
import com.pocketshell.app.tmux.TmuxConnectTrigger
import com.pocketshell.app.tmux.TmuxRestoreIntentSnapshot
import com.pocketshell.app.tmux.TmuxSessionScreen
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.app.usage.UsageScreen
import com.pocketshell.app.usage.UsageViewModel
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject

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
 * - [AppDestination.TmuxSession] — live tmux session for a selected host.
 *
 * The Phase 0 `ProofOfLifeScreen` is kept on disk (the
 * `ProofPipelineTest` still imports its helper functions) but is no
 * longer the launcher entry. The host picker landed here in #18 owns
 * that role now.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    // The tmux session VM stays activity-scoped: we only have one live
    // session at a time today; multi-pane lifecycle arrives with #22.
    private val tmuxSessionViewModel: TmuxSessionViewModel by viewModels()
    private var requestedDestination by mutableStateOf<AppDestination>(AppDestination.HostList)

    /**
     * Issue #446 (epic #432 slice D): Android 13+ gates notifications
     * behind the runtime POST_NOTIFICATIONS grant. Without it the
     * port-forwarding foreground-service notification — the user's
     * always-on "ports forwarded, tap to stop" control — is silently
     * hidden. We request it once at launch via this contract; the result
     * is best-effort (the foreground service still keeps tunnels alive if
     * denied, but the Stop/deep-link affordance would be unavailable, so
     * asking up front maximizes the chance the control is visible the
     * first time the user forwards a port).
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    /**
     * Issue #129: payload pulled out of a `pocketshell://import?...`
     * deep link. Consumed exactly once by the [AppNavigator] root
     * composition so a re-launch from `onNewIntent` with a different
     * payload is routed without the previous payload being re-imported.
     */
    private var pendingImportPayload by mutableStateOf<String?>(null)

    // Issue #112: theme preference observed at the composable root so a
    // tap in Settings re-themes the entire activity with no restart. The
    // repository is `@Singleton` so the Settings view model and this
    // activity share the same instance.
    @Inject
    lateinit var settingsRepository: SettingsRepository

    // UsageScheduler remains activity-visible for instrumentation that
    // asserts lifecycle/background behavior. Usage alerts are delivered
    // by the scheduler/notifier path; terminal session screens must not
    // receive scheduler snapshots for persistent quota chrome.
    @Inject
    lateinit var usageScheduler: UsageScheduler

    /**
     * Issue #698: the foreground update-check scheduler. We notify it when
     * the user opens a host (folder list / session) so the maintainer, who
     * rarely opens the home screen, still gets a (throttled) update check
     * the first time they reach a host. The same singleton's foreground-
     * resume trigger is wired in [App.onCreate]; this is the open-host
     * trigger.
     */
    @Inject
    lateinit var updateCheckScheduler: UpdateCheckScheduler

    /**
     * Issue #177: persists the last in-session view so a return-to-
     * foreground (after an app-switch, or a process death while
     * backgrounded) restores the previous tmux session optimistically
     * instead of dumping the user on the host list.
     */
    @Inject
    lateinit var lastSessionStore: LastSessionStore

    @Inject
    lateinit var startDirectoryAutocomplete: StartDirectoryAutocompleteRemoteSource

    @Inject
    lateinit var hostDao: HostDao

    /**
     * Issue #487: the singleton coordinator that knows when ≥1 host is
     * actively port forwarding. The maintainer reported never seeing the
     * D21 ongoing notification — the root cause is that on Android 13+ the
     * POST_NOTIFICATIONS grant was only requested once at first launch, so a
     * user who dismissed it (or who first forwards a port much later) never
     * gets the notification. We observe this controller and re-request the
     * permission exactly when forwarding goes active (0 → ≥1), the moment the
     * notification actually has something to show.
     */
    @Inject
    lateinit var forwardingController: com.pocketshell.app.portfwd.ForwardingController

    @Inject
    lateinit var sshKeyDao: SshKeyDao

    /**
     * Issue #177: the navigator's current top destination, reported up by
     * [AppNavigator] so `onStop` can persist it. The session screen also
     * reports its current composer draft here so the restored view comes
     * back with the user's half-typed message intact.
     */
    private var currentTopDestination: AppDestination = AppDestination.HostList
    private var currentComposerDraft: String = ""
    private var restoredTmuxDestination: AppDestination.TmuxSession? = null

    /**
     * Issue #177: the composer draft restored alongside a recent
     * persisted session, handed to the session screen so the user's
     * half-typed message comes back. Consumed once by the navigator;
     * cleared after the restored destination has been seeded.
     */
    private var restoredComposerDraft by mutableStateOf("")

    /**
     * Issue #560: remote attachment path(s) handed in by the share-into-
     * session flow ([com.pocketshell.app.share.ShareActivity]). When the
     * launch intent opens a tmux session and carries staged attachment
     * paths, the navigator seeds them into the session composer as #544
     * chips and opens the composer focused. Consumed once; cleared after the
     * session destination has been seeded so a later in-session navigation
     * does not re-seed a stale chip.
     */
    private var pendingComposerAttachments by mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTiming.mark("main-on-create-start", "savedInstanceState" to (savedInstanceState != null))
        super.onCreate(savedInstanceState)
        // Issue #177: prefer the explicit intent route (deep links, the
        // QS-tile forwarding chooser). Otherwise the launch lands on the
        // host list — UNLESS this `onCreate` is the system re-creating the
        // activity after it killed our process while backgrounded. In that
        // one case we restore the last in-session view so the user picks up
        // exactly where they left off (#177 fast resume), and the async SSH
        // reconnect fires from `TmuxSessionScreen`'s existing
        // connect-on-compose effect (the optimistic UI is usable
        // immediately while it handshakes; input affordances stay gated
        // until live per #249).
        //
        // Why gate on `savedInstanceState != null`:
        //
        // The DOMINANT app-switch case — Home then return while the process
        // is still alive — never re-enters `onCreate`. The activity instance
        // and the navigator's in-memory back stack survive the
        // background/foreground cycle, and the existing #235 `ON_START`
        // auto-reattach already returns the user straight to their live
        // session. So that case needs nothing from this restore route.
        //
        // `onCreate` only re-runs when the activity is genuinely (re)created.
        // Two flavours:
        //
        //  - A deliberate cold launch / explicit close + relaunch: the
        //    system passes `savedInstanceState == null`. The user chose to
        //    start fresh, so we MUST land them on the host list (this is the
        //    documented "close + relaunch lands on host list" contract that
        //    `ColdInstallE2eTest`, `RealAgentReleaseGateTest`, and
        //    `EmulatorWorkflowE2eTest` assert against).
        //
        //  - Process death while backgrounded, then the user returns: the
        //    system re-creates the activity with a NON-null
        //    `savedInstanceState` (the saved-state bundle). This is the only
        //    flavour where the restore route should fire — it is exactly the
        //    "I came back and my process had been reaped" experience #177
        //    targets.
        //
        // The recency cap inside [LastSessionStore.read] is the second guard:
        // even on a genuine process-death resume, a snapshot older than 24h
        // is not restored.
        val intentDestination = initialDestinationFromIntent(intent)
        val resumingFromProcessDeath = savedInstanceState != null
        val importPayload = importPayloadFromIntent(intent)
        // Issue #560: a share-into-session launch carries the staged remote
        // attachment path(s); seed them into the session composer as #544
        // chips once the navigator reaches the tmux destination.
        pendingComposerAttachments = composerAttachmentsFromIntent(intent)
        StartupTiming.mark(
            "main-initial-route-input",
            "savedInstanceState" to resumingFromProcessDeath,
            "intentDestination" to intentDestination.timingName(),
            "hasImportPayload" to (importPayload != null),
        )
        // Only read the persisted snapshot on the process-death resume path;
        // a fresh launch must not even touch the store so the cold-launch
        // route is always the host list (see [resolveInitialDestination]).
        val restored =
            if (intentDestination == AppDestination.HostList && resumingFromProcessDeath) {
                lastSessionStore.read()
            } else {
                null
            }
        val defaultHostDestination =
            if (
                intentDestination == AppDestination.HostList &&
                !resumingFromProcessDeath &&
                importPayload == null
            ) {
                runBlocking {
                    resolveDefaultHostLaunchDestination(
                        defaultHostId = settingsRepository.settings.value.defaultHostId,
                        hostDao = hostDao,
                        sshKeyDao = sshKeyDao,
                    )
                }
            } else {
                null
            }
        requestedDestination = resolveInitialDestination(
            intentDestination = intentDestination,
            resumingFromProcessDeath = resumingFromProcessDeath,
            restoredDestination = restored?.let { with(lastSessionStore) { it.toDestination() } },
            defaultHostDestination = defaultHostDestination,
        )
        StartupTiming.mark(
            "main-requested-destination",
            "destination" to requestedDestination.timingName(),
            "restoredSnapshot" to (restored != null),
            "processDeathResume" to resumingFromProcessDeath,
            "defaultHostLaunch" to (defaultHostDestination != null),
        )
        restoredTmuxDestination = requestedDestination as? AppDestination.TmuxSession
        restoredComposerDraft = if (requestedDestination is AppDestination.TmuxSession) {
            restored?.composerDraft.orEmpty()
        } else {
            ""
        }
        pendingImportPayload = importPayload
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(DarkSystemBarColor))
        window.decorView.setBackgroundColor(DarkSystemBarColor)
        @Suppress("DEPRECATION")
        window.statusBarColor = DarkSystemBarColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = DarkSystemBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(DarkSystemBarColor),
            navigationBarStyle = SystemBarStyle.dark(DarkSystemBarColor),
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = DarkSystemBarColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = DarkSystemBarColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        StartupTiming.mark("main-set-content-called")
        setContent {
            val settings by settingsRepository.settings.collectAsState()
            // Issue #496: publish the user's conversation font size to the
            // whole composition so the agent-conversation turns
            // (ConversationMessageTurn) scale their body text. Provided once
            // at the root so both the plain-SSH and tmux session screens
            // observe the same value.
            CompositionLocalProvider(
                LocalConversationFontSizeSp provides settings.conversationFontSizeSp,
            ) {
            PocketShellTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        // Issue #457 (Part 1): pad for the system bars +
                        // display cutout but deliberately EXCLUDE the IME
                        // inset here. Consuming the IME at the root would
                        // shrink the whole window when the soft keyboard
                        // shows, which on the terminal screens shrinks the
                        // embedded TerminalView's pixel height -> the vendored
                        // `TerminalView.updateSize()` recomputes fewer rows ->
                        // a tmux pane resize + full reflow/redraw (the jank the
                        // maintainer hit). Keyboard avoidance is now owned per
                        // screen: text-entry screens opt back into
                        // `.imePadding()` (see [AppNavigator]); the terminal
                        // screens PAN their viewport up instead of resizing.
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.exclude(WindowInsets.ime),
                        ),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigator(
                        tmuxSessionViewModel = tmuxSessionViewModel,
                        startDirectoryAutocomplete = startDirectoryAutocomplete,
                        hostDetailViewMode = settings.hostDetailViewMode,
                        requestedDestination = requestedDestination,
                        pendingImportPayload = pendingImportPayload,
                        onImportPayloadConsumed = { pendingImportPayload = null },
                        // Issue #177: the navigator reports its current top
                        // destination + composer draft so `onStop` can
                        // persist the in-session view for fast resume; the
                        // restored draft flows the other way so the session
                        // comes back with the user's half-typed message.
                        onCurrentDestinationChanged = { dest ->
                            currentTopDestination = dest
                            // Issue #698: opening a host (folder list / session)
                            // is one of the maintainer's main entry points and
                            // they skip the home screen, so fire the throttled
                            // update check here too.
                            if (dest is AppDestination.FolderList || dest is AppDestination.TmuxSession) {
                                updateCheckScheduler.onHostOpened()
                            }
                        },
                        onComposerDraftChanged = { draft -> currentComposerDraft = draft },
                        initialComposerDraft = restoredComposerDraft,
                        onInitialComposerDraftConsumed = { restoredComposerDraft = "" },
                        initialComposerAttachments = pendingComposerAttachments,
                        onInitialComposerAttachmentsConsumed = {
                            pendingComposerAttachments = emptyList()
                        },
                        restoredTmuxDestination = restoredTmuxDestination,
                        // Issue #666: clear the persisted last-session snapshot
                        // when a restored session is found gone, so the next
                        // foreground does not retry-and-resurrect it.
                        onClearLastSession = { lastSessionStore.clear() },
                    )
                }
            }
            }
        }
        maybeRequestNotificationPermission()
        observeForwardingForNotificationPermission()
        StartupTiming.mark("main-on-create-end")
    }

    /**
     * Issue #487: re-request POST_NOTIFICATIONS the moment port forwarding
     * actually goes active (0 → ≥1 active hosts). The first-launch request in
     * [maybeRequestNotificationPermission] is best-effort and can be dismissed
     * before the user ever forwards a port — which is exactly the maintainer's
     * "I didn't see it" case. By re-prompting on the activation edge we give
     * the runtime grant a second chance precisely when the ongoing
     * notification has something to show. No-op below API 33 and when already
     * granted; harmless if the user permanently denied (the launcher just
     * returns immediately without a dialog).
     */
    private fun observeForwardingForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wasActive = false
                forwardingController.flowOfActiveHostCount().collect { count ->
                    val active = count > 0
                    if (active && !wasActive) {
                        maybeRequestNotificationPermission()
                    }
                    wasActive = active
                }
            }
        }
    }

    /**
     * Issue #446: request the Android 13+ POST_NOTIFICATIONS runtime
     * permission so the port-forwarding ongoing notification (and its
     * Stop / panel-deep-link actions) can be shown. No-op below API 33
     * (the permission is install-time there) and when already granted.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }.onFailure { Log.w("MainActivity", "POST_NOTIFICATIONS request failed", it) }
    }

    /**
     * Issue #177: persist the last in-session view on the way out (D21 —
     * persistence happens at `onStop` time, nothing runs while
     * backgrounded). When the user is sitting on a tmux session we record
     * the destination + draft so the next foreground restores it; when
     * they are anywhere else we clear the snapshot so a stale session is
     * never silently restored after the user navigated away on purpose.
     */
    override fun onStop() {
        val session = resolveLastSessionForStop(
            currentDestination = currentTopDestination,
            tmuxIntent = tmuxSessionViewModel.latestRestoreIntentSnapshot(),
            composerDraft = currentComposerDraft,
            savedAtMillis = System.currentTimeMillis(),
        )
        if (session != null) {
            lastSessionStore.save(session)
        } else {
            lastSessionStore.clear()
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination = initialDestinationFromIntent(intent)
        StartupTiming.mark(
            "main-new-intent",
            "requestedDestination" to requestedDestination.timingName(),
            "hasImportPayload" to (importPayloadFromIntent(intent) != null),
        )
        importPayloadFromIntent(intent)?.let { pendingImportPayload = it }
        // Issue #560: re-delivered share-into-session intents (the app was
        // already running) seed the staged attachment chip(s) the same way
        // the cold-launch path does in onCreate.
        composerAttachmentsFromIntent(intent).takeIf { it.isNotEmpty() }?.let {
            pendingComposerAttachments = it
        }
    }

    companion object {
        /**
         * Issue #560: share-into-session launch extras. The share flow
         * ([com.pocketshell.app.share.ShareActivity]) stages the shared file
         * into a chosen active session, then launches MainActivity with these
         * extras so it opens that tmux session with the staged path(s)
         * pre-loaded as composer attachment chips and the composer focused.
         */
        const val EXTRA_OPEN_SESSION_HOST_ID: String = "pocketshell.extra.OPEN_SESSION_HOST_ID"
        const val EXTRA_OPEN_SESSION_HOST_NAME: String = "pocketshell.extra.OPEN_SESSION_HOST_NAME"
        const val EXTRA_OPEN_SESSION_HOSTNAME: String = "pocketshell.extra.OPEN_SESSION_HOSTNAME"
        const val EXTRA_OPEN_SESSION_PORT: String = "pocketshell.extra.OPEN_SESSION_PORT"
        const val EXTRA_OPEN_SESSION_USERNAME: String = "pocketshell.extra.OPEN_SESSION_USERNAME"
        const val EXTRA_OPEN_SESSION_KEY_PATH: String = "pocketshell.extra.OPEN_SESSION_KEY_PATH"
        const val EXTRA_OPEN_SESSION_NAME: String = "pocketshell.extra.OPEN_SESSION_NAME"
        const val EXTRA_OPEN_SESSION_ATTACHMENTS: String =
            "pocketshell.extra.OPEN_SESSION_ATTACHMENTS"
        const val EXTRA_OPEN_USAGE: String = "pocketshell.extra.OPEN_USAGE"
    }
}

private val DarkSystemBarColor: Int = android.graphics.Color.rgb(13, 17, 23)

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
    tmuxSessionViewModel: TmuxSessionViewModel,
    startDirectoryAutocomplete: StartDirectoryAutocompleteRemoteSource,
    hostDetailViewMode: HostDetailViewMode,
    requestedDestination: AppDestination,
    pendingImportPayload: String? = null,
    onImportPayloadConsumed: () -> Unit = {},
    // Issue #177: report the current top destination + the active session
    // composer draft up to the activity so `onStop` can persist the
    // in-session view for fast resume.
    onCurrentDestinationChanged: (AppDestination) -> Unit = {},
    onComposerDraftChanged: (String) -> Unit = {},
    // Issue #177: the composer draft restored from the persisted session,
    // seeded into the session screen on the restore path. Consumed once;
    // [onInitialComposerDraftConsumed] clears it so a later in-session
    // navigation does not re-seed a stale draft.
    initialComposerDraft: String = "",
    onInitialComposerDraftConsumed: () -> Unit = {},
    // Issue #560: staged remote attachment path(s) from a share-into-session
    // launch. Seeded into the session composer as #544 chips with the
    // composer opened + focused. Consumed once; cleared via
    // [onInitialComposerAttachmentsConsumed].
    initialComposerAttachments: List<String> = emptyList(),
    onInitialComposerAttachmentsConsumed: () -> Unit = {},
    restoredTmuxDestination: AppDestination.TmuxSession? = null,
    // Issue #666: clear the persisted last-session snapshot when the restored
    // session is found gone on the server, so the next foreground does not
    // retry-and-resurrect it. Wired by the activity to [LastSessionStore.clear].
    onClearLastSession: () -> Unit = {},
) {
    // Issue #129: the activity scrapes the import payload out of a
    // `pocketshell://import?...` deep link before composition starts
    // and stores it here. We hand it to the host-list view model the
    // moment that VM materialises (host list is the landing
    // destination, so this is always one composition away), then call
    // `onImportPayloadConsumed` so the activity clears the state and
    // a future re-launch with a fresh payload is processed correctly.
    if (pendingImportPayload != null) {
        val hostListViewModelForImport: HostListViewModel = hiltViewModel()
        LaunchedEffect(pendingImportPayload) {
            hostListViewModelForImport.importSharedHostPayload(pendingImportPayload)
            onImportPayloadConsumed()
        }
    }

    // Volatile in-memory back-stack state. The initial destination comes
    // from [requestedDestination], which the activity seeds with either an
    // intent route or — for a plain launch that would otherwise land on
    // the host list — the persisted last tmux session (#177 fast resume).
    // The back stack itself is still volatile by design (a back gesture
    // from a restored session returns the user to the host list).
    var current: AppDestination by remember {
        mutableStateOf(requestedDestination)
    }
    LaunchedEffect(Unit) {
        StartupTiming.markOnce(
            "app-navigator-first-composed",
            "current" to current.timingName(),
            "requestedDestination" to requestedDestination.timingName(),
        )
    }

    // Issue #177: report the current top destination up to the activity
    // so `onStop` can persist the in-session view. Fires on every
    // navigation, including the initial restored destination.
    LaunchedEffect(current) {
        StartupTiming.mark("app-navigator-current", "destination" to current.timingName())
        CrashReporter.updateContext(current.crashReportContext())
        DiagnosticEvents.record(
            "navigation",
            "route_changed",
            "route" to current.diagnosticRouteName(),
        )
        onCurrentDestinationChanged(current)
    }

    val backStack = remember { mutableListOf<AppDestination>() }

    fun setCurrentDestination(dest: AppDestination) {
        current = dest
        onCurrentDestinationChanged(dest)
    }

    LaunchedEffect(requestedDestination) {
        StartupTiming.mark(
            "app-navigator-requested",
            "requestedDestination" to requestedDestination.timingName(),
            "current" to current.timingName(),
        )
        if (requestedDestination == AppDestination.PortForwardChooser && current != requestedDestination) {
            backStack += current
            setCurrentDestination(requestedDestination)
        }
    }

    fun navigate(dest: AppDestination) {
        backStack += current
        setCurrentDestination(dest)
    }

    fun replace(dest: AppDestination) {
        setCurrentDestination(dest)
    }

    fun popToHostList() {
        backStack.clear()
        setCurrentDestination(AppDestination.HostList)
    }

    fun back() {
        setCurrentDestination(backStack.removeLastOrNull() ?: AppDestination.HostList)
    }

    // Issue #457 (Part 1): the root Surface no longer consumes the IME inset
    // (see MainActivity.onCreate), so each destination owns its own keyboard
    // behaviour. Text-entry screens (host form, settings, jobs, env, folder
    // list, etc.) opt back into `.imePadding()` here so their fields still
    // float above the soft keyboard exactly as before. The terminal screen
    // ([AppDestination.TmuxSession]) is excluded: it keeps full height under
    // the keyboard and PANs the terminal viewport up instead of resizing the
    // pane, which avoids the tmux reflow + full redraw jank.
    val activeDestination = current
    val keyboardAvoidanceModifier =
        if (activeDestination is AppDestination.TmuxSession) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().imePadding()
        }
    // Issue #520: the hand-rolled navigator owns the system/gesture Back
    // button for every non-root destination. Without this, screens that do
    // not register their own `BackHandler` (host-detail FolderList, the
    // tmux session, RepoBrowser, FileViewer, EnvFiles, Usage, AiCosts,
    // CrashReports, WatchedFolders, RecurringJobs, QR scanner) fall through
    // to the Activity default, which finishes the app — so system Back exits
    // to the launcher instead of returning to the previous screen, the
    // pre-release blocker the #513 audit reproduced. Routing through the same
    // `back()` the in-app `‹` chevron uses keeps the two identical.
    //
    // This is registered BEFORE the destination `when` block, so a screen
    // that DOES register its own `BackHandler` (TmuxSession overlay routing,
    // the AddEditHost unsaved-changes confirm, Settings, PortForward panel,
    // ForwardingChooser, Snippets) composes later and therefore wins on
    // Compose's LIFO back dispatcher — those screens keep their richer
    // back behaviour untouched.
    //
    // The root HostList is deliberately excluded: leaving default Back there
    // means Back from the landing screen still exits the app, which is the
    // expected behaviour (and what ColdInstall/EmulatorWorkflow assert).
    BackHandler(enabled = shouldTrapSystemBack(activeDestination)) {
        back()
    }
    androidx.compose.foundation.layout.Box(modifier = keyboardAvoidanceModifier) {
    when (val dest = activeDestination) {
        AppDestination.HostList -> HostListScreen(
            onAddHost = { navigate(AppDestination.AddHost) },
            onEditHost = { id -> navigate(AppDestination.EditHost(id)) },
            onOpenCrashReports = { navigate(AppDestination.CrashReports) },
            onOpenSettings = { navigate(AppDestination.Settings) },
            // Issue #116 (usage-panel Fix B): wire the cross-host usage
            // strip's tap target to the same Usage destination the
            // bootstrap-success CTA opens. The bootstrap path keeps the
            // existing wiring (the strip uses the same callback so the
            // two routes always agree).
            onOpenUsage = { navigate(AppDestination.Usage) },
            // Issue #171: host-tap now routes to the new folder list
            // (the default destination after a host tap). The folder
            // list owns its own probe + grouping; it surfaces the
            // existing flat session list via the "Show all sessions on
            // this host" link. The previous `onOpenSession` /
            // `onOpenTmuxHostSession` callbacks (picker-sheet routes)
            // were removed from `HostListScreen` per D22 hard-cut.
            onOpenFolderList = { host, keyPath, passphrase ->
                navigate(
                    AppDestination.FolderList(
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
            onOpenPortForwardPanel = { host, keyPath, passphrase ->
                navigate(AppDestination.PortForwardPanel(hostId = host.id, keyPath = keyPath, passphrase = passphrase))
            },
            // Issue #446: the global "ports forwarding" app-bar indicator
            // routes to the same host-picker the QS tile + notification
            // body-tap use, so the user lands on the port-forward entry.
            onOpenPortForwarding = { navigate(AppDestination.PortForwardChooser) },
            // Issue #206: kebab → "Watched folders". Pass the SSH
            // connection parameters so the discover-from-remote
            // probe can authenticate. The destination carries them
            // as optional fields because the Settings host-picker
            // path arrives without them.
            onOpenWatchedFolders = { host, keyPath, passphrase ->
                navigate(
                    AppDestination.WatchedFolders(
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
        )

        AppDestination.AddHost -> AddEditHostScreen(
            hostId = null,
            onDone = ::back,
            onScanQr = { navigate(AppDestination.Scan) },
        )

        is AppDestination.EditHost -> AddEditHostScreen(
            hostId = dest.hostId,
            onDone = ::back,
        )

        // Issue #129 + #290: live camera QR scanner. It is launched
        // from Add host and dispatches the decoded envelope payload
        // through the existing host-list import path. On import we pop
        // to the host list so the success banner or "already added"
        // conflict prompt is visible immediately.
        AppDestination.Scan -> {
            val hostListViewModel: HostListViewModel = hiltViewModel()
            QrScannerScreen(
                onDecoded = { payload ->
                    hostListViewModel.importSharedHostPayload(payload)
                    popToHostList()
                },
                onPickFile = { uri ->
                    hostListViewModel.importSharedHostUri(uri)
                    popToHostList()
                },
                onClose = ::back,
            )
        }

        AppDestination.CrashReports -> CrashReportsScreen(onBack = ::back)

        AppDestination.Settings -> {
            val hostListViewModel: HostListViewModel = hiltViewModel()
            SettingsScreen(
                onBack = ::back,
                onOpenCrashReports = { navigate(AppDestination.CrashReports) },
                onOpenUsage = { navigate(AppDestination.Usage) },
                onOpenAiCosts = { navigate(AppDestination.AiCosts) },
                onScanHostImport = { navigate(AppDestination.Scan) },
                onChooseHostImportFile = { uri ->
                    hostListViewModel.importSharedHostUri(uri)
                    popToHostList()
                },
                // Issue #206: Settings → Watched folders host picker routes
                // here without SSH credentials. The destination's SSH
                // fields stay null so the discover-from-remote button is
                // hidden — the user can still add / edit / delete / reorder
                // folders manually.
                onOpenWatchedFoldersForHost = { hostId, hostName ->
                    navigate(
                        AppDestination.WatchedFolders(
                            hostId = hostId,
                            hostName = hostName,
                        ),
                    )
                },
            )
        }

        // Issue #181: AI Costs screen — client-side OpenAI spend
        // tracker. Sister of the Usage screen but sourced from the
        // local Room log rather than server-side `pocketshell usage` output.
        AppDestination.AiCosts -> CostsScreen(onBack = ::back)

        // Issue #114 Fix A: Usage / quota panel. The view model loads
        // every bootstrapped host on construction and pull-to-refresh
        // (the Usage overflow refresh action) re-runs
        // `fetchUsage` for each host. A fresh ViewModel is materialised
        // every visit so missing-tool / failed-fetch state can't leak
        // across navigations.
        AppDestination.Usage -> {
            val usageViewModel = hiltViewModel<UsageViewModel>()
            val usageState by usageViewModel.state.collectAsState()
            UsageScreen(
                state = usageState,
                onBack = ::back,
                onRefresh = usageViewModel::refresh,
                onOpenSettings = { navigate(AppDestination.Settings) },
                modifier = Modifier.fillMaxSize(),
            )
        }

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

        is AppDestination.PortForwardPanel -> PortForwardPanelScreen(
            hostId = dest.hostId,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            prefillRemotePort = dest.prefillRemotePort,
            openBrowserWhenForwardedRemotePort = dest.openBrowserWhenForwardedRemotePort,
            openBrowserWhenForwardedLocalhostUrl = dest.openBrowserWhenForwardedLocalhostUrl,
            onBack = ::back,
        )

        // Issue #206: per-host watched-folders config screen. SSH
        // connection parameters are optional on the destination — only
        // the host-list kebab path supplies them (so the discover
        // probe can authenticate); the Settings host-picker path
        // arrives with them null and the screen hides the discover
        // button accordingly.
        is AppDestination.WatchedFolders -> {
            val settingsViewModel = hiltViewModel<SettingsViewModel>()
            val settings by settingsViewModel.state.collectAsState()
            val creds = if (
                dest.hostname != null &&
                dest.port != null &&
                dest.username != null &&
                dest.keyPath != null
            ) {
                WatchedFoldersViewModel.SshCredentials(
                    hostname = dest.hostname,
                    port = dest.port,
                    username = dest.username,
                    keyPath = dest.keyPath,
                    passphrase = dest.passphrase,
                )
            } else {
                null
            }
            WatchedFoldersScreen(
                hostId = dest.hostId,
                hostName = dest.hostName,
                sshCredentials = creds,
                onBack = ::back,
                hostDetailViewMode = settings.hostDetailViewMode,
                onHostDetailViewModeSelected = settingsViewModel::setHostDetailViewMode,
            )
        }

        // Issue #171: per-host folder list — the default destination
        // after a host tap. Routes onward to `TmuxSession` (via tap);
        // its "Show all sessions on this host" link expands an inline
        // flat session list in place.
        is AppDestination.FolderList -> FolderListScreen(
            hostId = dest.hostId,
            hostName = dest.hostName,
            hostname = dest.hostname,
            port = dest.port,
            username = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            onBack = ::back,
            onOpenSession = { sessionName, startDirectory ->
                navigate(
                    AppDestination.TmuxSession(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = sessionName,
                        startDirectory = startDirectory,
                    ),
                )
            },
            onOpenSessionWindow = { sessionName, startDirectory, windowIndex ->
                navigate(
                    AppDestination.TmuxSession(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = sessionName,
                        startDirectory = startDirectory,
                        initialWindowIndex = windowIndex,
                    ),
                )
            },
            onSessionCreated = { sessionName, cwd ->
                // Issue #171 round 2: the SessionTypePickerSheet has
                // already created the tmux session on the remote (and
                // optionally `send-keys`'d the agent CLI), so the only
                // remaining step is to attach. We route to TmuxSession
                // with `-A` semantics — if the create path collided on
                // an existing session name, the attach falls through.
                navigate(
                    AppDestination.TmuxSession(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = sessionName,
                        startDirectory = cwd,
                    ),
                )
            },
            // Issue #230: route to the GitHub repos browser, reusing the
            // SSH credentials this folder screen already holds.
            onBrowseRepos = { cloneRoot ->
                navigate(
                    AppDestination.RepoBrowser(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        cloneRoot = cloneRoot ?: "~/git",
                    ),
                )
            },
            onOpenPortForwarding = {
                navigate(
                    AppDestination.PortForwardPanel(
                        hostId = dest.hostId,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                    ),
                )
            },
            onOpenSettings = { navigate(AppDestination.Settings) },
            onOpenWorkspaceSettings = {
                navigate(
                    AppDestination.WatchedFolders(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                    ),
                )
            },
            onOpenUsage = { navigate(AppDestination.Usage) },
            // Issue #264: route to the per-folder env-file manager. The
            // discovered folder set is forwarded so the env screen's
            // copy picker stays inside the known folders (D24).
            onEditEnv = { path, label, allFolders ->
                navigate(
                    AppDestination.EnvFiles(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        directory = path,
                        folderLabel = label,
                        copySources = allFolders,
                    ),
                )
            },
            // Issue #646: route to the read-only Git commit-history view,
            // reusing the SSH credentials this folder screen already holds.
            onGitHistory = { path, label ->
                navigate(
                    AppDestination.GitHistory(
                        hostId = dest.hostId,
                        hostName = label.ifBlank { dest.hostName },
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        dir = path,
                    ),
                )
            },
            // Issue #643: open the SFTP file explorer rooted at the requested
            // directory (`~` from the host overflow, a folder path from a
            // long-press), reusing this screen's SSH credentials.
            onBrowseFiles = { startDir ->
                navigate(
                    AppDestination.FileExplorer(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        startDir = startDir,
                    ),
                )
            },
            onAssistantNavigate = { destination ->
                navigate(destination)
            },
            assistantDictationViewModel = hiltViewModel<InlineDictationViewModel>(),
            suggestStartDirectories = { prefix ->
                startDirectoryAutocomplete.suggestions(
                    target = StartDirectoryAutocompleteTarget(
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                    ),
                    typedPrefix = prefix,
                )
            },
            hostDetailViewMode = hostDetailViewMode,
        )

        // Issue #264: per-folder `.env` / `.envrc` key manager backed by
        // the host's `pocketshell env ...` CLI over SSH.
        is AppDestination.EnvFiles -> EnvScreen(
            hostId = dest.hostId,
            hostName = dest.hostName,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            directory = dest.directory,
            folderLabel = dest.folderLabel,
            copySources = dest.copySources.map { (path, label) ->
                EnvCopySourceFolder(path = path, label = label)
            },
            onBack = ::back,
        )

        // Issue #230: GitHub repos browser. Lists the user's GitHub
        // repos joined with the host's cloned repos; tapping a repo
        // clones it (if needed) and opens a tmux session in the clone
        // folder. Reached from the FolderList "Repos" action.
        is AppDestination.RepoBrowser -> RepoBrowserScreen(
            hostId = dest.hostId,
            hostName = dest.hostName,
            hostname = dest.hostname,
            port = dest.port,
            username = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            cloneRoot = dest.cloneRoot,
            onBack = ::back,
            // Issue #516: tapping a repo now opens the SAME Shell/Agent
            // SessionTypePickerSheet the folder flow uses, pre-filled with
            // the resolved clone path. The picker create path
            // (FolderListViewModel.createSession) has already built the
            // tmux session — and, for an Agent pick, send-keys'd the agent
            // CLI into the new pane — so the only remaining step here is to
            // attach. The old direct onOpenRepo → TmuxSession bypass is
            // removed (D22 hard-cut, no fallback open path).
            onSessionCreated = { sessionName, cwd ->
                navigate(
                    AppDestination.TmuxSession(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        sessionName = sessionName,
                        startDirectory = cwd,
                    ),
                )
            },
            suggestStartDirectories = { prefix ->
                startDirectoryAutocomplete.suggestions(
                    target = StartDirectoryAutocompleteTarget(
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                    ),
                    typedPrefix = prefix,
                )
            },
        )

        is AppDestination.FileViewer -> FileViewerScreen(
            hostName = dest.hostName,
            hostname = dest.hostname,
            port = dest.port,
            username = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            remotePath = dest.remotePath,
            cwd = dest.cwd,
            onBack = ::back,
        )

        // Issue #528: browsable remote file explorer. A tapped file pushes the
        // FileViewer destination with the already-absolute path (cwd = null,
        // it's resolved). Back returns to the explorer at the same directory.
        is AppDestination.FileExplorer -> FileExplorerScreen(
            hostId = dest.hostId,
            hostName = dest.hostName,
            hostname = dest.hostname,
            port = dest.port,
            username = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            startDir = dest.startDir,
            onBack = ::back,
            onOpenFile = { absolutePath ->
                navigate(
                    AppDestination.FileViewer(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        remotePath = absolutePath,
                        cwd = null,
                    ),
                )
            },
        )

        is AppDestination.GitHistory -> GitHistoryScreen(
            hostName = dest.hostName,
            hostname = dest.hostname,
            port = dest.port,
            username = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            dir = dest.dir,
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

        // Issue #45: tmux control-mode session — the live terminal route.
        // The folder-list / session picker, share-into-session intents, and
        // last-session restore all land here. The view model is activity-
        // scoped (see `tmuxSessionViewModel` above) so the live client
        // survives navigation within the session.
        is AppDestination.TmuxSession -> TmuxSessionScreen(
            viewModel = tmuxSessionViewModel,
            hostId = dest.hostId,
            hostName = dest.hostName,
            host = dest.hostname,
            port = dest.port,
            user = dest.username,
            keyPath = dest.keyPath,
            passphrase = dest.passphrase,
            sessionName = dest.sessionName,
            startDirectory = dest.startDirectory,
            initialWindowIndex = dest.initialWindowIndex,
            onBack = ::back,
            // Issue #666: the restored/last session no longer exists on the
            // server (killed elsewhere while backgrounded). Clear the persisted
            // snapshot so the next foreground does not retry-and-resurrect it,
            // then drop to the previous screen (the host/session list).
            onSessionEnded = {
                onClearLastSession()
                back()
            },
            onOpenTmuxSession = { sessionName, startDirectory ->
                navigate(
                    dest.copy(
                        sessionName = sessionName,
                        startDirectory = startDirectory,
                        initialWindowIndex = null,
                    ),
                )
            },
            onReplaceTmuxSession = { sessionName ->
                replace(dest.copy(sessionName = sessionName, startDirectory = null, initialWindowIndex = null))
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
            onOpenUsage = { navigate(AppDestination.Usage) },
            onOpenSettings = { navigate(AppDestination.Settings) },
            // Issue #445 (epic #432 slice A): kebab -> per-host port-forward
            // panel. Same hand-rolled back-stack as onOpenJobs above, so
            // onBack = ::back restores this exact tmux session/window.
            onOpenPortForwarding = {
                navigate(
                    AppDestination.PortForwardPanel(
                        hostId = dest.hostId,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                    ),
                )
            },
            // Issue #448 (epic #432 slice C): the detection overlay's
            // Forward action opens the same panel pre-filled with the
            // detected port (#447 prefillRemotePort). Back returns to this
            // exact session via the hand-rolled back-stack.
            onOpenPortForwardingWithPort = { remotePort, autoOpenLocalhostUrl ->
                navigate(
                    AppDestination.PortForwardPanel(
                        hostId = dest.hostId,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        prefillRemotePort = remotePort,
                        openBrowserWhenForwardedLocalhostUrl = autoOpenLocalhostUrl,
                    ),
                )
            },
            onAssistantNavigate = ::navigate,
            // Issue #497: kebab -> in-app file viewer. The active pane's cwd
            // is passed so a relative path the agent referenced resolves
            // correctly. Back returns to this exact tmux session/window.
            onOpenFile = { path, cwd ->
                navigate(
                    AppDestination.FileViewer(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        remotePath = path,
                        cwd = cwd,
                    ),
                )
            },
            // Issue #528: kebab -> browsable file explorer. Seeds at the active
            // pane's cwd (or `~` when unknown). Back returns to this session.
            onBrowseFiles = { startDir ->
                navigate(
                    AppDestination.FileExplorer(
                        hostId = dest.hostId,
                        hostName = dest.hostName,
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                        startDir = startDir,
                    ),
                )
            },
            // Issue #177: seed the restored composer draft into the agent
            // composer so a fast-resumed session comes back with the
            // user's half-typed message, and report draft edits up so the
            // next `onStop` persists them.
            initialComposerDraft = initialComposerDraft,
            onInitialComposerDraftConsumed = onInitialComposerDraftConsumed,
            // Issue #560: seed the share-staged attachment(s) into the
            // session composer as #544 chips and open the composer focused.
            initialComposerAttachments = initialComposerAttachments,
            onInitialComposerAttachmentsConsumed = onInitialComposerAttachmentsConsumed,
            onComposerDraftChanged = onComposerDraftChanged,
            suggestStartDirectories = { prefix ->
                startDirectoryAutocomplete.suggestions(
                    target = StartDirectoryAutocompleteTarget(
                        hostname = dest.hostname,
                        port = dest.port,
                        username = dest.username,
                        keyPath = dest.keyPath,
                        passphrase = dest.passphrase,
                    ),
                    typedPrefix = prefix,
                )
            },
            connectTrigger = if (dest == restoredTmuxDestination) {
                TmuxConnectTrigger.ColdRestore
            } else {
                TmuxConnectTrigger.UserTap
            },
        )
    }
    }
}

/**
 * Issue #520: whether the navigator-level [BackHandler] should intercept the
 * system/gesture Back button for [destination].
 *
 * Returns `true` for every non-root destination so system Back routes through
 * the hand-rolled `back()` (returning to the previous screen, identical to the
 * in-app `‹` chevron). Returns `false` for the root [AppDestination.HostList]
 * so Back on the landing screen keeps the Activity default and exits the app —
 * the intended behaviour the cold-launch / workflow e2e tests assert against.
 */
internal fun shouldTrapSystemBack(destination: AppDestination): Boolean =
    destination != AppDestination.HostList

internal fun resolveLastSessionForStop(
    currentDestination: AppDestination,
    tmuxIntent: TmuxRestoreIntentSnapshot?,
    composerDraft: String,
    savedAtMillis: Long,
): LastSessionStore.LastSession? {
    val routeDestination = currentDestination as? AppDestination.TmuxSession ?: return null
    val source = tmuxIntent
    if (source != null && !source.sameRestoreIdentity(routeDestination)) {
        Log.i(
            LAST_SESSION_ACTIVITY_LOG_TAG,
            "last-session-save-override trigger=onStop reason=intended-target " +
                "routeHostId=${routeDestination.hostId} routeSession=${routeDestination.sessionName} " +
                "routeStartDirectory=${routeDestination.startDirectory} " +
                "intendedHostId=${source.hostId} intendedSession=${source.sessionName} " +
                "intendedStartDirectory=${source.startDirectory} " +
                "intendedTrigger=${source.trigger.logValue} intendedGeneration=${source.generation}",
        )
    }
    return if (source != null) {
        LastSessionStore.LastSession(
            hostId = source.hostId,
            hostName = source.hostName,
            hostname = source.hostname,
            port = source.port,
            username = source.username,
            keyPath = source.keyPath,
            sessionName = source.sessionName,
            startDirectory = source.startDirectory,
            composerDraft = composerDraft,
            savedAtMillis = savedAtMillis,
        )
    } else {
        LastSessionStore.LastSession(
            hostId = routeDestination.hostId,
            hostName = routeDestination.hostName,
            hostname = routeDestination.hostname,
            port = routeDestination.port,
            username = routeDestination.username,
            keyPath = routeDestination.keyPath,
            sessionName = routeDestination.sessionName,
            startDirectory = routeDestination.startDirectory,
            composerDraft = composerDraft,
            savedAtMillis = savedAtMillis,
        )
    }
}

private fun TmuxRestoreIntentSnapshot.sameRestoreIdentity(
    destination: AppDestination.TmuxSession,
): Boolean =
    hostId == destination.hostId &&
        hostName == destination.hostName &&
        hostname == destination.hostname &&
        port == destination.port &&
        username == destination.username &&
        keyPath == destination.keyPath &&
        sessionName == destination.sessionName &&
        startDirectory == destination.startDirectory

internal fun initialDestinationFromIntent(intent: Intent?): AppDestination {
    if (intent?.getBooleanExtra(ForwardingTileService.EXTRA_OPEN_PORT_FORWARDING, false) == true) {
        return AppDestination.PortForwardChooser
    }
    if (intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_USAGE, false) == true) {
        return AppDestination.Usage
    }
    shareSessionDestinationFromIntent(intent)?.let { return it }
    return AppDestination.HostList
}

/**
 * Issue #560: build a [AppDestination.TmuxSession] from a share-into-session
 * launch intent. Returns null when the intent is not a share-session launch
 * (the host id / session name / key path extras are required). The share
 * flow ([com.pocketshell.app.share.ShareActivity]) populates these extras
 * after staging the shared file into the chosen session.
 */
internal fun shareSessionDestinationFromIntent(intent: Intent?): AppDestination.TmuxSession? {
    if (intent == null) return null
    val hostId = intent.getLongExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_ID, 0L)
    val sessionName = intent.getStringExtra(MainActivity.EXTRA_OPEN_SESSION_NAME)
    val keyPath = intent.getStringExtra(MainActivity.EXTRA_OPEN_SESSION_KEY_PATH)
    val hostname = intent.getStringExtra(MainActivity.EXTRA_OPEN_SESSION_HOSTNAME)
    val username = intent.getStringExtra(MainActivity.EXTRA_OPEN_SESSION_USERNAME)
    if (hostId <= 0L || sessionName.isNullOrBlank() ||
        keyPath.isNullOrBlank() || hostname.isNullOrBlank() || username.isNullOrBlank()
    ) {
        return null
    }
    return AppDestination.TmuxSession(
        hostId = hostId,
        hostName = intent.getStringExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_NAME).orEmpty(),
        hostname = hostname,
        port = intent.getIntExtra(MainActivity.EXTRA_OPEN_SESSION_PORT, 22),
        username = username,
        keyPath = keyPath,
        passphrase = null,
        sessionName = sessionName,
    )
}

/**
 * Issue #560: pull the staged remote attachment path(s) out of a share-into-
 * session launch intent. Empty when the intent carries none.
 */
internal fun composerAttachmentsFromIntent(intent: Intent?): List<String> =
    intent?.getStringArrayExtra(MainActivity.EXTRA_OPEN_SESSION_ATTACHMENTS)
        ?.filter { it.isNotBlank() }
        ?: emptyList()

/**
 * Issue #177: decide the activity's initial destination, distinguishing a
 * genuine app-switch / process-death resume from a deliberate cold launch.
 *
 * Pulled out as a pure function so the route-restore decision — the part the
 * reviewer flagged as hijacking every plain relaunch — is unit-testable
 * without an Activity. The rules, in priority order:
 *
 *  1. An explicit intent route (deep link, QS-tile forwarding chooser) always
 *     wins; we never override it with a restored session.
 *  2. Otherwise the base destination is the host list. We replace it with the
 *     restored tmux session ONLY when [resumingFromProcessDeath] is true AND a
 *     fresh [restoredDestination] was supplied. `resumingFromProcessDeath`
 *     maps to `savedInstanceState != null` in `onCreate`: the system passes a
 *     non-null bundle only when it re-creates the activity after reaping our
 *     backgrounded process. A user-driven cold launch / explicit close +
 *     relaunch passes a null bundle, so this returns the host list — the
 *     documented contract `ColdInstallE2eTest`, `RealAgentReleaseGateTest`,
 *     and `EmulatorWorkflowE2eTest` assert against.
 *
 * The dominant app-switch case (Home then return while the process is alive)
 * never re-enters `onCreate`, so it never reaches this function; the existing
 * #235 `ON_START` reattach handles it from the surviving navigator state.
 */
internal fun resolveInitialDestination(
    intentDestination: AppDestination,
    resumingFromProcessDeath: Boolean,
    restoredDestination: AppDestination?,
    defaultHostDestination: AppDestination? = null,
): AppDestination {
    if (intentDestination != AppDestination.HostList) return intentDestination
    if (!resumingFromProcessDeath) return defaultHostDestination ?: AppDestination.HostList
    return restoredDestination ?: AppDestination.HostList
}

internal suspend fun resolveDefaultHostLaunchDestination(
    defaultHostId: Long?,
    hostDao: HostDao,
    sshKeyDao: SshKeyDao,
    keyFileExists: (String) -> Boolean = { path -> File(path).exists() },
): AppDestination.FolderList? {
    val hostId = defaultHostId?.takeIf { it > 0L } ?: return null
    val host = hostDao.getById(hostId) ?: return null
    val key = sshKeyDao.getById(host.keyId) ?: return null
    if (key.hasPassphrase) return null
    val keyPath = key.privateKeyPath.trim()
    if (keyPath.isEmpty() || !keyFileExists(keyPath)) return null
    return AppDestination.FolderList(
        hostId = host.id,
        hostName = host.name,
        hostname = host.hostname,
        port = host.port,
        username = host.username,
        keyPath = keyPath,
        passphrase = null,
    )
}

/**
 * Issue #129: pull a `pocketshell://import?payload=...` (or
 * `pocketshell://import?uri=...`) intent into a UTF-8 payload string
 * the host-list import path can consume.
 *
 * Returns `null` if the intent is unrelated to import or is missing
 * both query parameters. The MainActivity wires the result through
 * `HostListViewModel.importSharedHostPayload` on `onCreate` /
 * `onNewIntent`.
 *
 * Reusing the existing import path means both deep-links and QR
 * scans share the same envelope-aware logic — multi-part envelopes
 * delivered via deep link still trigger the same "scan from the QR
 * scanner" hint that the camera path uses, which is the right
 * behaviour because a deep link can't transport more than one part
 * at a time anyway.
 */
internal fun importPayloadFromIntent(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val data = intent.data ?: return null
    if (data.scheme != "pocketshell") return null
    if (data.host != "import") return null
    val payload = data.getQueryParameter("payload")
    if (!payload.isNullOrBlank()) return payload
    return null
}

internal fun AppDestination.timingName(): String = when (this) {
    AppDestination.HostList -> "HostList"
    AppDestination.AddHost -> "AddHost"
    is AppDestination.EditHost -> "EditHost"
    AppDestination.Scan -> "Scan"
    AppDestination.CrashReports -> "CrashReports"
    AppDestination.Settings -> "Settings"
    AppDestination.Usage -> "Usage"
    AppDestination.AiCosts -> "AiCosts"
    AppDestination.PortForwardChooser -> "PortForwardChooser"
    is AppDestination.PortForwardPanel -> "PortForwardPanel(hostId=$hostId)"
    is AppDestination.WatchedFolders -> "WatchedFolders(hostId=$hostId)"
    is AppDestination.TmuxSession -> "TmuxSession(hostId=$hostId,session=$sessionName)"
    is AppDestination.FolderList -> "FolderList(hostId=$hostId)"
    is AppDestination.RepoBrowser -> "RepoBrowser(hostId=$hostId)"
    is AppDestination.EnvFiles -> "EnvFiles(hostId=$hostId)"
    is AppDestination.FileViewer -> "FileViewer(hostId=$hostId)"
    is AppDestination.FileExplorer -> "FileExplorer(hostId=$hostId)"
    is AppDestination.GitHistory -> "GitHistory(hostId=$hostId)"
    is AppDestination.RecurringJobs -> "RecurringJobs(session=$sessionName)"
}

internal fun AppDestination.diagnosticRouteName(): String = when (this) {
    AppDestination.HostList -> "HostList"
    AppDestination.AddHost -> "AddHost"
    is AppDestination.EditHost -> "EditHost"
    AppDestination.Scan -> "Scan"
    AppDestination.CrashReports -> "CrashReports"
    AppDestination.Settings -> "Settings"
    AppDestination.Usage -> "Usage"
    AppDestination.AiCosts -> "AiCosts"
    AppDestination.PortForwardChooser -> "PortForwardChooser"
    is AppDestination.PortForwardPanel -> "PortForwardPanel"
    is AppDestination.WatchedFolders -> "WatchedFolders"
    is AppDestination.TmuxSession -> "TmuxSession"
    is AppDestination.FolderList -> "FolderList"
    is AppDestination.RepoBrowser -> "RepoBrowser"
    is AppDestination.EnvFiles -> "EnvFiles"
    is AppDestination.FileViewer -> "FileViewer"
    is AppDestination.FileExplorer -> "FileExplorer"
    is AppDestination.GitHistory -> "GitHistory"
    is AppDestination.RecurringJobs -> "RecurringJobs"
}

internal fun AppDestination.crashReportContext(): CrashReportContext = when (this) {
    AppDestination.HostList -> CrashReportContext(screen = "Hosts")
    AppDestination.AddHost -> CrashReportContext(screen = "Add host")
    is AppDestination.EditHost -> CrashReportContext(
        screen = "Edit host",
        action = "hostId=$hostId",
    )
    AppDestination.Scan -> CrashReportContext(screen = "QR scanner")
    AppDestination.CrashReports -> CrashReportContext(screen = "Crash reports")
    AppDestination.Settings -> CrashReportContext(screen = "Settings")
    AppDestination.Usage -> CrashReportContext(screen = "Usage")
    AppDestination.AiCosts -> CrashReportContext(screen = "AI costs")
    AppDestination.PortForwardChooser -> CrashReportContext(screen = "Port forwarding chooser")
    is AppDestination.PortForwardPanel -> CrashReportContext(
        screen = "Port forwarding",
        action = listOfNotNull(
            "hostId=$hostId",
            prefillRemotePort?.let { "port=$it" },
        ).joinToString(" ").ifBlank { null },
    )
    is AppDestination.WatchedFolders -> CrashReportContext(
        screen = "Watched folders",
        hostName = hostName,
        hostname = hostname,
        username = username,
    )
    is AppDestination.TmuxSession -> CrashReportContext(
        screen = "Tmux session",
        hostName = hostName,
        hostname = hostname,
        username = username,
        sessionName = sessionName,
        startDirectory = startDirectory,
    )
    is AppDestination.FolderList -> CrashReportContext(
        screen = "Folders",
        hostName = hostName,
        hostname = hostname,
        username = username,
    )
    is AppDestination.RepoBrowser -> CrashReportContext(
        screen = "Repos",
        hostName = hostName,
        hostname = hostname,
        username = username,
        startDirectory = cloneRoot,
    )
    is AppDestination.EnvFiles -> CrashReportContext(
        screen = "Env files",
        hostName = hostName,
        hostname = hostname,
        username = username,
        startDirectory = directory,
        action = folderLabel,
    )
    is AppDestination.FileViewer -> CrashReportContext(
        screen = "File viewer",
        hostName = hostName,
        hostname = hostname,
        username = username,
        startDirectory = cwd,
        action = remotePath,
    )
    is AppDestination.FileExplorer -> CrashReportContext(
        screen = "File explorer",
        hostName = hostName,
        hostname = hostname,
        username = username,
        startDirectory = startDir,
    )
    is AppDestination.GitHistory -> CrashReportContext(
        screen = "Git history",
        hostName = hostName,
        hostname = hostname,
        username = username,
        startDirectory = dir,
    )
    is AppDestination.RecurringJobs -> CrashReportContext(
        screen = "Recurring jobs",
        hostName = hostName,
        hostname = hostname,
        username = username,
        sessionName = sessionName,
    )
}

private const val DefaultTmuxSessionName = "pocketshell"
private const val LAST_SESSION_ACTIVITY_LOG_TAG = "PsLastSession"
