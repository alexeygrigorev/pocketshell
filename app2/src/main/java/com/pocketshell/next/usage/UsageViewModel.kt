package com.pocketshell.next.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.usage.UsageProviderRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [UsageRoute] (rewrite task P-5, journey J12).
 *
 * Deliberately small: [UsageFetcher] does the one thing that used to need 564
 * (`UsageScheduler`) + 611 (the old `UsageViewModel`'s lease fan-out) lines —
 * dial nothing, ask every already-connected host once, in parallel — and this
 * class just turns the result into [UsageScreenState] and remembers whether a
 * refresh is in flight.
 *
 * Every visit gets a fresh instance (`hiltViewModel()` is nav-entry scoped),
 * and [UsageRoute] calls [refresh] from `ON_START`, so there is nothing to
 * warm on construction — the class does nothing until asked.
 */
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val fetcher: UsageFetcher,
) : ViewModel() {

    private val _state = MutableStateFlow(UsageScreenState())
    val state: StateFlow<UsageScreenState> = _state.asStateFlow()

    /** Guards against a pull-to-refresh tap re-entering a fetch already in flight. */
    private var inFlight: Job? = null

    fun refresh() {
        if (inFlight?.isActive == true) return
        _state.value = _state.value.copy(isRefreshing = true)
        inFlight = viewModelScope.launch {
            val result = fetcher.fetchAll()
            _state.value = usageScreenState(
                snapshots = result.snapshots.values,
                connectedHostCount = result.connectedHostCount,
                isRefreshing = false,
                loaded = true,
                resetBanner = usageResetBannerState(result.resetEvents),
            )
        }
    }
}

/**
 * Backs the terminal top bar's usage glance pill (rewrite task P-5).
 *
 * A separate, smaller ViewModel rather than sharing [UsageViewModel]: the
 * pill lives on the SESSION screen, not the usage panel, and the two visits
 * are independent — opening a session must not depend on the usage panel
 * ever having been opened first. Like [UsageViewModel] it does its own
 * foreground-only [UsageFetcher] round on `ON_START`; there is no shared
 * cache between the two, matching the plan's "no stale-while-revalidate"
 * call for this whole feature.
 */
@HiltViewModel
class UsageGlanceViewModel @Inject constructor(
    private val fetcher: UsageFetcher,
) : ViewModel() {

    private val _state = MutableStateFlow<UsageGlancePillState?>(null)
    val state: StateFlow<UsageGlancePillState?> = _state.asStateFlow()

    private var inFlight: Job? = null

    fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            val result = fetcher.fetchAll()
            _state.value = usageGlancePillState(
                snapshots = result.snapshots,
                warnPercent = UsageProviderRecord.DEFAULT_WARN_PERCENT,
            )
        }
    }
}
