package com.pocketshell.app.tmux

/** One canonical queue identity shared by badge, composer, promotion and drain. */
internal data class TmuxOutboundQueueBinding(
    val targetKey: String,
    val fallbackKey: String,
    val durableKey: String?,
    val tmuxSessionId: String?,
    val sessionCreated: Long?,
    val generationPaneIds: Set<String>,
)

internal fun outboundGenerationSettled(sessionLive: Boolean, wireWritable: Boolean): Boolean =
    sessionLive && wireWritable

internal fun tmuxOutboundQueueBinding(
    hostId: Long,
    sessionName: String,
    panes: List<TmuxPaneState>,
    navigationTmuxSessionId: String?,
    navigationSessionCreated: Long?,
    generationSettled: Boolean,
): TmuxOutboundQueueBinding {
    val paneGeneration = panes.firstOrNull {
        it.sessionId.isNotBlank() &&
            it.sessionCreated != null &&
            (navigationTmuxSessionId == null || it.sessionId == navigationTmuxSessionId) &&
            (navigationSessionCreated == null || it.sessionCreated == navigationSessionCreated)
    }
    val sessionId = paneGeneration?.sessionId ?: navigationTmuxSessionId
    val created = paneGeneration?.sessionCreated ?: navigationSessionCreated
    // Navigation identity is enough to stamp the write-ahead row with exact
    // generation evidence, but it is not enough to OWN the queue. Until a live
    // pane row confirms that generation, badge/composer/drain stay on host/name;
    // the screen effect promotes them atomically once this settles (#1944).
    val durable = paneGeneration?.takeIf { generationSettled }?.let {
        durableTmuxSessionKey(hostId, it.sessionId, it.sessionCreated)
    }
    val fallback = "$hostId/$sessionName"
    val expectedId = sessionId?.trim()?.takeIf(String::isNotEmpty)
    val paneIds = if (expectedId == null || created == null) {
        emptySet()
    } else {
        panes.filter { it.sessionId == expectedId && it.sessionCreated == created }
            .mapTo(linkedSetOf(), TmuxPaneState::paneId)
    }
    return TmuxOutboundQueueBinding(
        targetKey = durable ?: fallback,
        fallbackKey = fallback,
        durableKey = durable,
        tmuxSessionId = sessionId,
        sessionCreated = created,
        generationPaneIds = paneIds,
    )
}

internal fun knownSessionNavigationTarget(
    sessionName: String,
    activeTarget: TmuxSessionViewModel.ConnectionTarget?,
    connectingTarget: TmuxSessionViewModel.ConnectionTarget?,
    runtimeCache: TmuxSessionRuntimeCache,
): TmuxSessionNavigationTarget {
    val direct = (activeTarget ?: connectingTarget)?.takeIf { it.sessionName == sessionName }
    val durableKey = direct?.durableSessionKey() ?: activeTarget?.let { active ->
        runtimeCache.cachedRuntimesForHost(active.hostId)
            .firstOrNull { it.key.sessionName == sessionName }?.key?.durableSessionKey
    }
    val hostId = direct?.hostId ?: activeTarget?.hostId
    val identity = hostId?.let { parseDurableTmuxSessionIdentity(it, durableKey) }
    return TmuxSessionNavigationTarget(
        sessionName,
        identity?.sessionId,
        identity?.createdEpochSeconds,
    )
}

internal fun knownPaneSessionNavigationTarget(
    hostId: Long,
    sessionName: String,
    paneId: String,
    runtimeCache: TmuxSessionRuntimeCache,
): TmuxSessionNavigationTarget {
    val identity = runtimeCache.cachedRuntimesForHost(hostId)
        .firstOrNull { it.key.sessionName == sessionName && it.panes.any { pane -> pane.paneId == paneId } }
        ?.key?.durableSessionKey
        ?.let { parseDurableTmuxSessionIdentity(hostId, it) }
    return TmuxSessionNavigationTarget(
        sessionName,
        identity?.sessionId,
        identity?.createdEpochSeconds,
    )
}
