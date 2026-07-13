package com.pocketshell.app.tmux.connection

import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkChangeKind
import com.pocketshell.app.connectivity.hasSameNetworkIdentityAs

internal enum class NetworkChangeArm {
    Ignore,
    SuppressNetworkNotValidated,
    SuppressNetworkCoalesced,
    SuppressNetworkTransportProvenAlive,
    SuppressNetworkLostTransportProvenAlive,
    ScheduleNetworkReconnect,
    HoldNetworkLost,
    ScheduleNetworkReconnectOnRestore,
}

internal fun shouldReportValidatedHandoffToController(
    change: TerminalNetworkChange,
    transportKeepAliveProvenAlive: () -> Boolean,
): Boolean {
    val realValidatedHandoff = change.kind == TerminalNetworkChangeKind.ValidatedIdentityChange &&
        (change.previousValidated?.let { previous ->
            !previous.hasSameNetworkIdentityAs(change.current)
        } ?: false)
    return realValidatedHandoff && !transportKeepAliveProvenAlive()
}

internal fun selectNetworkChangeArm(
    change: TerminalNetworkChange,
    appActive: Boolean,
    hasTarget: Boolean,
    hasClientOrSession: Boolean,
    autoReconnectActive: Boolean,
    inlineConnected: Boolean,
    lifecycleCoalesces: () -> Boolean,
    transportKeepAliveProvenAlive: () -> Boolean,
): NetworkChangeArm {
    if (!appActive) return NetworkChangeArm.Ignore

    when (change.kind) {
        TerminalNetworkChangeKind.NetworkLost -> {
            if (!hasTarget) return NetworkChangeArm.Ignore
            if (!hasClientOrSession) return NetworkChangeArm.Ignore
            if (autoReconnectActive) return NetworkChangeArm.Ignore
            // Issue #1522 (H1): mirror the ValidatedIdentityChange arm below — a bare
            // loss of NET_CAPABILITY_VALIDATED on a socket the transport keepalive
            // still vouches for is NOT a real transport death (cellular clears the
            // validated bit for sub-second windows constantly while the TCP / -CC
            // channel stays alive). Suppress the calm band and keep the session Live,
            // exactly as the identity-change handoff does. When the keepalive CANNOT
            // vouch we fall through to HoldNetworkLost, whose VM body debounces the
            // band so a transient blip still does not flap.
            if (transportKeepAliveProvenAlive()) {
                return NetworkChangeArm.SuppressNetworkLostTransportProvenAlive
            }
            return NetworkChangeArm.HoldNetworkLost
        }
        TerminalNetworkChangeKind.NetworkRestored -> {
            if (!hasTarget) return NetworkChangeArm.Ignore
            if (!hasClientOrSession) return NetworkChangeArm.Ignore
            if (autoReconnectActive) return NetworkChangeArm.Ignore
            return NetworkChangeArm.ScheduleNetworkReconnectOnRestore
        }
        TerminalNetworkChangeKind.ValidatedIdentityChange -> Unit
    }

    if (autoReconnectActive) return NetworkChangeArm.Ignore
    if (!inlineConnected) return NetworkChangeArm.Ignore
    if (!hasTarget) return NetworkChangeArm.Ignore
    if (!hasClientOrSession) return NetworkChangeArm.Ignore

    val previousValidated = change.previousValidated
    val realValidatedIdentityChange = previousValidated != null &&
        !previousValidated.hasSameNetworkIdentityAs(change.current)
    if (!realValidatedIdentityChange) return NetworkChangeArm.SuppressNetworkNotValidated
    if (lifecycleCoalesces()) return NetworkChangeArm.SuppressNetworkCoalesced
    if (transportKeepAliveProvenAlive()) {
        return NetworkChangeArm.SuppressNetworkTransportProvenAlive
    }
    return NetworkChangeArm.ScheduleNetworkReconnect
}

internal class NetworkChangeEffects(
    private val appActive: () -> Boolean,
    private val hasTarget: () -> Boolean,
    private val hasClientOrSession: () -> Boolean,
    private val autoReconnectActive: () -> Boolean,
    private val inlineConnected: () -> Boolean,
    private val lifecycleCoalesces: () -> Boolean,
    private val transportKeepAliveProvenAlive: () -> Boolean,
    private val suppressNetworkNotValidated: (TerminalNetworkChange) -> Unit,
    private val suppressNetworkCoalesced: (TerminalNetworkChange) -> Unit,
    private val suppressNetworkTransportProvenAlive: (TerminalNetworkChange) -> Unit,
    private val suppressNetworkLostTransportProvenAlive: (TerminalNetworkChange) -> Unit,
    private val scheduleNetworkReconnect: (TerminalNetworkChange) -> Unit,
    private val holdNetworkLost: (TerminalNetworkChange) -> Unit,
    private val scheduleNetworkReconnectOnRestore: (TerminalNetworkChange) -> Unit,
) {
    fun dispatch(change: TerminalNetworkChange): NetworkChangeArm {
        val arm = selectNetworkChangeArm(
            change = change,
            appActive = appActive(),
            hasTarget = hasTarget(),
            hasClientOrSession = hasClientOrSession(),
            autoReconnectActive = autoReconnectActive(),
            inlineConnected = inlineConnected(),
            lifecycleCoalesces = lifecycleCoalesces,
            transportKeepAliveProvenAlive = transportKeepAliveProvenAlive,
        )
        when (arm) {
            NetworkChangeArm.SuppressNetworkNotValidated ->
                suppressNetworkNotValidated(change)
            NetworkChangeArm.SuppressNetworkCoalesced ->
                suppressNetworkCoalesced(change)
            NetworkChangeArm.SuppressNetworkTransportProvenAlive ->
                suppressNetworkTransportProvenAlive(change)
            NetworkChangeArm.SuppressNetworkLostTransportProvenAlive ->
                suppressNetworkLostTransportProvenAlive(change)
            NetworkChangeArm.ScheduleNetworkReconnect ->
                scheduleNetworkReconnect(change)
            NetworkChangeArm.HoldNetworkLost ->
                holdNetworkLost(change)
            NetworkChangeArm.ScheduleNetworkReconnectOnRestore ->
                scheduleNetworkReconnectOnRestore(change)
            NetworkChangeArm.Ignore -> Unit
        }
        return arm
    }
}
