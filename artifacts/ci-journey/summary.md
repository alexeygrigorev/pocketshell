# Per-push CI journey suite — summary

| Selection | Args | Exit | Elapsed | Result |
| --- | --- | --- | --- | --- |
| 116 load-bearing journey classes (shard 0/1; per-class retry-once) | `pocketshellCi=true` | 1 | 2s | **STEP_TIMEOUT** |

Classes exercised:
- `com.pocketshell.app.proof.TmuxSessionScreenArtVerifyE2eTest`
- `com.pocketshell.app.proof.DeepLinkSessionSwitchE2eTest`
- `com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest`
- `com.pocketshell.app.proof.BackThenOpenSecondSessionReusesWarmLeaseE2eTest`
- `com.pocketshell.app.proof.ColdRestoreGoneSessionNoResurrectE2eTest`
- `com.pocketshell.app.proof.LifecycleReattachGoneSessionNoResurrectE2eTest`
- `com.pocketshell.app.proof.ServerDeathReconnectNoResurrectE2eTest`
- `com.pocketshell.app.proof.AttachmentDropReconnectRecoversE2eTest`
- `com.pocketshell.app.proof.ReconnectRepaintE2eTest`
- `com.pocketshell.app.proof.BackgroundGraceReconnectE2eTest`
- `com.pocketshell.app.proof.BoundedGraceSessionHoldJourneyE2eTest`
- `com.pocketshell.app.share.ShareTargetE2eTest`
- `com.pocketshell.app.share.SharePassphraseDialogE2eTest`
- `com.pocketshell.app.projects.ProfileDiscoveryPickerDockerTest`
- `com.pocketshell.app.tmux.TmuxInSessionNewSessionCollisionDockerTest`
- `com.pocketshell.app.composer.PromptComposerImeSquishProofTest`
- `com.pocketshell.app.composer.PromptComposerImeTightScreenSquishProofTest`
- `com.pocketshell.app.tmux.Issue887TerminalFixedUnderImeProofTest`
- `com.pocketshell.app.composer.PromptComposerRecordingTimerAndTailTest`
- `com.pocketshell.app.composer.PromptComposerLiveTranscriptTwoLineTest`
- `com.pocketshell.app.composer.PromptComposerDegradedSendE2eTest`
- `com.pocketshell.app.composer.PromptComposerSendDismissE2eTest`
- `com.pocketshell.app.composer.PromptComposerOutboundQueueTest`
- `com.pocketshell.app.composer.PromptComposerUndeliveredRetryTest`
- `com.pocketshell.app.composer.PromptComposerUndeliveredRetryHiltWiringTest`
- `com.pocketshell.app.composer.ComposerResendAllImeProofTest`
- `com.pocketshell.app.composer.PromptComposerDiscardE2eTest`
- `com.pocketshell.app.composer.PromptComposerRecordingRowFitProofTest`
- `com.pocketshell.app.composer.PromptComposerRecordingNoLockJourneyTest`
- `com.pocketshell.app.voice.ComposerLauncherHoldSwipeUpJourneyTest`
- `com.pocketshell.app.composer.ComposerDraftDurabilityE2eTest`
- `com.pocketshell.app.proof.SwitchStaleCaptureSessionBodyJourneyE2eTest`
- `com.pocketshell.app.proof.WithinGraceSocketDropForegroundJourneyE2eTest`
- `com.pocketshell.app.proof.ReconnectPartialBlankReseedJourneyE2eTest`
- `com.pocketshell.app.proof.RedrawFullViewportReseedJourneyE2eTest`
- `com.pocketshell.app.proof.Issue1206PrewarmEmptyCaptureSeedRetryJourneyE2eTest`
- `com.pocketshell.app.proof.NotificationTapLivePinnedForegroundReseedJourneyE2eTest`
- `com.pocketshell.app.proof.RedrawNonDestructiveNearBlankCaptureE2eTest`
- `com.pocketshell.app.proof.ReconnectKebabInPlaceJourneyE2eTest`
- `com.pocketshell.app.proof.StaleRenderHealOnLiveTransportJourneyE2eTest`
- `com.pocketshell.app.proof.AgentAltScreenPartialBlackHealJourneyE2eTest`
- `com.pocketshell.app.proof.PaneOutputOverflowRecoveryJourneyE2eTest`
- `com.pocketshell.app.proof.VoiceSendActivePaneStaysVisibleE2eTest`
- `com.pocketshell.app.proof.MostlyEmptyModelHealsAtRevealJourneyE2eTest`
- `com.pocketshell.app.proof.IdleClaudeFragmentsOverBlackRecoveryJourneyE2eTest`
- `com.pocketshell.app.proof.SendWithAttachmentStaysVisibleE2eTest`
- `com.pocketshell.app.proof.PreExistingMultiWindowSeedE2eTest`
- `com.pocketshell.app.projects.FolderListWindowCloseAfterStopPollingDockerTest`
- `com.pocketshell.app.projects.FolderListKillWindowDockerTest`
- `com.pocketshell.app.proof.LongRunningSessionStabilityTest#steadyForegroundHoldDoesNotFlapTransportEveryTenSeconds`
- `com.pocketshell.app.proof.AttachmentNoReconnectE2eTest`
- `com.pocketshell.app.proof.SendNoReconnectE2eTest`
- `com.pocketshell.app.proof.StableWifiNoSpuriousReconnectE2eTest`
- `com.pocketshell.app.proof.BareNetworkLossRestoreReconnectE2eTest`
- `com.pocketshell.app.proof.MobileSpuriousReconnectE2eTest`
- `com.pocketshell.app.proof.Issue1078DeadSocketHandoffRedialJourneyE2eTest`
- `com.pocketshell.app.proof.RealisticWifiStabilityNoSpuriousReconnectE2eTest`
- `com.pocketshell.app.tmux.Issue796ComposerOpenTerminalScopeProofTest`
- `com.pocketshell.app.tmux.Issue1085RecompositionScopeProofTest`
- `com.pocketshell.app.proof.ComposerAlwaysPresentSwitchJourneyE2eTest`
- `com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#conversationTapIsHonouredBeforeDetectionLands`
- `com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#agentOpensOnDefaultViewAndIsNotYankedMidSessionShellGetsNoConversationRow`
- `com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#reconciledPresumedAgentWithDroppedRowReseedsConversationPlaceholderOnDevice`
- `com.pocketshell.app.tmux.ConversationToggleVisibleForLiveAgentInShellRecordedSessionDockerTest`
- `com.pocketshell.app.tmux.ConversationStaysReachableAfterDetectionDropsDockerTest`
- `com.pocketshell.app.tmux.ConversationTuiCommandJourneyDockerTest`
- `com.pocketshell.app.tmux.TmuxComposerLauncherNarrowFontClipProofTest`
- `com.pocketshell.app.tmux.TmuxChromeConversationTogglePresentTest`
- `com.pocketshell.app.cards.SessionCardFeedRegistryTest`
- `com.pocketshell.app.tmux.TmuxConnectingStatesScreenshotTest`
- `com.pocketshell.app.tmux.SessionSurfaceReconnectWrapperTest`
- `com.pocketshell.app.hosts.HostResumeLastSessionE2eTest`
- `com.pocketshell.app.tmux.TmuxConversationBottomComposerScreenshotTest`
- `com.pocketshell.app.conversation.ConversationImageContentRenderTest`
- `com.pocketshell.app.conversation.ConversationTuiCommandNoticeRenderTest`
- `com.pocketshell.app.portfwd.PortForwardDuplicateKeyCrashTest`
- `com.pocketshell.app.portfwd.PortForwardDuplicateKeyRenderTest`
- `com.pocketshell.app.portfwd.ForwardingNetworkRideThroughE2eTest`
- `com.pocketshell.app.portfwd.ForwardingNotificationE2eTest#sessionPinnedForForward_hasExactlyOneForwardNotification_andStopTearsDownTunnels`
- `com.pocketshell.app.proof.SilentDropSyntheticSeamJourneyE2eTest`
- `com.pocketshell.app.proof.CleanOutageReattachResilienceE2eTest`
- `com.pocketshell.app.proof.BackgroundResumeSocketDeathE2eTest`
- `com.pocketshell.app.proof.Issue895SwitchWhileBlackBandJourneyE2eTest`
- `com.pocketshell.app.projects.ManualKindWriterDockerTest`
- `com.pocketshell.app.projects.AgentRecordedKindReadBackDockerTest`
- `com.pocketshell.app.projects.ProfileChipRelaunchDockerTest`
- `com.pocketshell.app.projects.AgentLaunchVersionMismatchHintE2eTest`
- `com.pocketshell.app.projects.FolderListOldCliHydrateDockerTest`
- `com.pocketshell.app.projects.FolderListBootstrapSkipTreeLoadsDockerTest`
- `com.pocketshell.app.projects.FolderListClientCacheInstantRenderDockerTest`
- `com.pocketshell.app.projects.FolderListScaleAnrStrictModeDockerTest`
- `com.pocketshell.app.projects.FolderListDurableTreeDaemonDockerTest`
- `com.pocketshell.app.proof.AgentSubmitAckJourneyE2eTest`
- `com.pocketshell.app.projects.FolderListScreenE2eTest#profiledSessionsShowProfileChipDefaultSessionsDoNot`
- `com.pocketshell.app.projects.SessionKindPickerUiTest`
- `com.pocketshell.app.projects.SessionTypePickerNameFieldUiTest`
- `com.pocketshell.app.snippets.SnippetTemplateDialogButtonsTest`
- `com.pocketshell.app.crash.ConfirmDeleteAllDialogButtonsTest`
- `com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#goToDialogGoButtonDispatchesPath`
- `com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#goToDialogCancelDismissesWithoutNavigating`
- `com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#failedStateShowsMessageAndRetry`
- `com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#successTransferShowsDismissibleBanner`
- `com.pocketshell.app.fileviewer.FileViewerScaffoldTest#cannotPreviewStateShowsMessageAndRetry`
- `com.pocketshell.app.fileviewer.FileViewerScaffoldTest#cannotPreviewWithLocateCandidatesOffersOpenRows`
- `com.pocketshell.app.fileviewer.FileViewerScaffoldTest#markdownRenderedPipeTableShowsCellsNotRawDelimiter`
- `com.pocketshell.app.bootstrap.HostReadyPrimaryActionTest`
- `com.pocketshell.app.bootstrap.HostNotificationsReadinessTest`
- `com.pocketshell.app.proof.StrictModeMainThreadIoDetectorE2eTest`
- `com.pocketshell.app.cards.SessionChecklistPushJourneyDockerTest`
- `com.pocketshell.app.projects.CliVersionMismatchBannerUpdateButtonTest`
- `com.pocketshell.app.proof.LaunchNoMainThreadRoomReadE2eTest`
- `com.pocketshell.app.diagnostics.ConnectionLogHostMirrorReconnectDockerTest`
- `com.pocketshell.app.env.EnvScreenE2eTest`
- `com.pocketshell.app.tmux.TmuxResizeSessionE2eTest#cachedSizeReplayRestoresFullWindowAndAgentPaneIsNotCut`
- `com.pocketshell.app.usage.UsageGlancePillE2eTest`
- `com.pocketshell.app.usage.Usage1318StrictSchemaRenderE2eTest`

Core-terminal #803 append-burst proof (`shared:core-terminal`): **SKIPPED**
- `com.pocketshell.core.terminal.ui.CodexAppendBurstMainThreadProofTest`

Core-terminal #796 output-burst-IME ANR proof (`shared:core-terminal`): **SKIPPED**
- `com.pocketshell.core.terminal.ui.CodexOutputBurstImeMainThreadProofTest`

Core-terminal #866 multi-chunk seed attach ANR proof (`shared:core-terminal`): **SKIPPED**
- `com.pocketshell.core.terminal.ui.CodexMultiChunkSeedAttachMainThreadProofTest`

Core-terminal #871 agent-pane link-affordance off-main proof (`shared:core-terminal`): **SKIPPED**
- `com.pocketshell.core.terminal.ui.AgentPaneLinkAffordanceOffMainProofTest`

Core-terminal #879 beyond-grace reattach-repaint proof (`shared:core-terminal`): **SKIPPED**
- `com.termux.view.TerminalViewReattachLateSubscribeRepaintInstrumentedTest`

Core-terminal v0.4.17 overlay-unbounded-measure crash proof (`shared:core-terminal`): **SKIPPED**
- `com.pocketshell.core.terminal.selection.TerminalOverlayUnboundedMeasureCrashTest`

Core-terminal #1203 surface-only-black recovery proof (`shared:core-terminal`): **SKIPPED**
- `com.termux.view.TerminalViewForceSurfaceRepaintInstrumentedTest`

Core-terminal #1233 shell-pane single-snapshot affordance-scan proof (`shared:core-terminal`): **SKIPPED**
- `com.pocketshell.core.terminal.ui.ShellPaneAffordanceSingleSnapshotProofTest`

Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):
Budget: 1s; elapsed: 2s. The in-emulator tmux
`list-sessions` enumeration (picker/tree) stalled and consumed the budget before all
load-bearing classes could run. This is the #470 enumeration stall, NOT a never-booted
emulator (#771) and NOT a genuine test regression. Classes cut short / not run:
- `com.pocketshell.app.proof.TmuxSessionScreenArtVerifyE2eTest`
- `com.pocketshell.app.proof.DeepLinkSessionSwitchE2eTest`
- `com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest`
- `com.pocketshell.app.proof.BackThenOpenSecondSessionReusesWarmLeaseE2eTest`
- `com.pocketshell.app.proof.ColdRestoreGoneSessionNoResurrectE2eTest`
- `com.pocketshell.app.proof.LifecycleReattachGoneSessionNoResurrectE2eTest`
- `com.pocketshell.app.proof.ServerDeathReconnectNoResurrectE2eTest`
- `com.pocketshell.app.proof.AttachmentDropReconnectRecoversE2eTest`
- `com.pocketshell.app.proof.ReconnectRepaintE2eTest`
- `com.pocketshell.app.proof.BackgroundGraceReconnectE2eTest`
- `com.pocketshell.app.proof.BoundedGraceSessionHoldJourneyE2eTest`
- `com.pocketshell.app.share.ShareTargetE2eTest`
- `com.pocketshell.app.share.SharePassphraseDialogE2eTest`
- `com.pocketshell.app.projects.ProfileDiscoveryPickerDockerTest`
- `com.pocketshell.app.tmux.TmuxInSessionNewSessionCollisionDockerTest`
- `com.pocketshell.app.composer.PromptComposerImeSquishProofTest`
- `com.pocketshell.app.composer.PromptComposerImeTightScreenSquishProofTest`
- `com.pocketshell.app.tmux.Issue887TerminalFixedUnderImeProofTest`
- `com.pocketshell.app.composer.PromptComposerRecordingTimerAndTailTest`
- `com.pocketshell.app.composer.PromptComposerLiveTranscriptTwoLineTest`
- `com.pocketshell.app.composer.PromptComposerDegradedSendE2eTest`
- `com.pocketshell.app.composer.PromptComposerSendDismissE2eTest`
- `com.pocketshell.app.composer.PromptComposerOutboundQueueTest`
- `com.pocketshell.app.composer.PromptComposerUndeliveredRetryTest`
- `com.pocketshell.app.composer.PromptComposerUndeliveredRetryHiltWiringTest`
- `com.pocketshell.app.composer.ComposerResendAllImeProofTest`
- `com.pocketshell.app.composer.PromptComposerDiscardE2eTest`
- `com.pocketshell.app.composer.PromptComposerRecordingRowFitProofTest`
- `com.pocketshell.app.composer.PromptComposerRecordingNoLockJourneyTest`
- `com.pocketshell.app.voice.ComposerLauncherHoldSwipeUpJourneyTest`
- `com.pocketshell.app.composer.ComposerDraftDurabilityE2eTest`
- `com.pocketshell.app.proof.SwitchStaleCaptureSessionBodyJourneyE2eTest`
- `com.pocketshell.app.proof.WithinGraceSocketDropForegroundJourneyE2eTest`
- `com.pocketshell.app.proof.ReconnectPartialBlankReseedJourneyE2eTest`
- `com.pocketshell.app.proof.RedrawFullViewportReseedJourneyE2eTest`
- `com.pocketshell.app.proof.Issue1206PrewarmEmptyCaptureSeedRetryJourneyE2eTest`
- `com.pocketshell.app.proof.NotificationTapLivePinnedForegroundReseedJourneyE2eTest`
- `com.pocketshell.app.proof.RedrawNonDestructiveNearBlankCaptureE2eTest`
- `com.pocketshell.app.proof.ReconnectKebabInPlaceJourneyE2eTest`
- `com.pocketshell.app.proof.StaleRenderHealOnLiveTransportJourneyE2eTest`
- `com.pocketshell.app.proof.AgentAltScreenPartialBlackHealJourneyE2eTest`
- `com.pocketshell.app.proof.PaneOutputOverflowRecoveryJourneyE2eTest`
- `com.pocketshell.app.proof.VoiceSendActivePaneStaysVisibleE2eTest`
- `com.pocketshell.app.proof.MostlyEmptyModelHealsAtRevealJourneyE2eTest`
- `com.pocketshell.app.proof.IdleClaudeFragmentsOverBlackRecoveryJourneyE2eTest`
- `com.pocketshell.app.proof.SendWithAttachmentStaysVisibleE2eTest`
- `com.pocketshell.app.proof.PreExistingMultiWindowSeedE2eTest`
- `com.pocketshell.app.projects.FolderListWindowCloseAfterStopPollingDockerTest`
- `com.pocketshell.app.projects.FolderListKillWindowDockerTest`
- `com.pocketshell.app.proof.LongRunningSessionStabilityTest#steadyForegroundHoldDoesNotFlapTransportEveryTenSeconds`
- `com.pocketshell.app.proof.AttachmentNoReconnectE2eTest`
- `com.pocketshell.app.proof.SendNoReconnectE2eTest`
- `com.pocketshell.app.proof.StableWifiNoSpuriousReconnectE2eTest`
- `com.pocketshell.app.proof.BareNetworkLossRestoreReconnectE2eTest`
- `com.pocketshell.app.proof.MobileSpuriousReconnectE2eTest`
- `com.pocketshell.app.proof.Issue1078DeadSocketHandoffRedialJourneyE2eTest`
- `com.pocketshell.app.proof.RealisticWifiStabilityNoSpuriousReconnectE2eTest`
- `com.pocketshell.app.tmux.Issue796ComposerOpenTerminalScopeProofTest`
- `com.pocketshell.app.tmux.Issue1085RecompositionScopeProofTest`
- `com.pocketshell.app.proof.ComposerAlwaysPresentSwitchJourneyE2eTest`
- `com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#conversationTapIsHonouredBeforeDetectionLands`
- `com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#agentOpensOnDefaultViewAndIsNotYankedMidSessionShellGetsNoConversationRow`
- `com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#reconciledPresumedAgentWithDroppedRowReseedsConversationPlaceholderOnDevice`
- `com.pocketshell.app.tmux.ConversationToggleVisibleForLiveAgentInShellRecordedSessionDockerTest`
- `com.pocketshell.app.tmux.ConversationStaysReachableAfterDetectionDropsDockerTest`
- `com.pocketshell.app.tmux.ConversationTuiCommandJourneyDockerTest`
- `com.pocketshell.app.tmux.TmuxComposerLauncherNarrowFontClipProofTest`
- `com.pocketshell.app.tmux.TmuxChromeConversationTogglePresentTest`
- `com.pocketshell.app.cards.SessionCardFeedRegistryTest`
- `com.pocketshell.app.tmux.TmuxConnectingStatesScreenshotTest`
- `com.pocketshell.app.tmux.SessionSurfaceReconnectWrapperTest`
- `com.pocketshell.app.hosts.HostResumeLastSessionE2eTest`
- `com.pocketshell.app.tmux.TmuxConversationBottomComposerScreenshotTest`
- `com.pocketshell.app.conversation.ConversationImageContentRenderTest`
- `com.pocketshell.app.conversation.ConversationTuiCommandNoticeRenderTest`
- `com.pocketshell.app.portfwd.PortForwardDuplicateKeyCrashTest`
- `com.pocketshell.app.portfwd.PortForwardDuplicateKeyRenderTest`
- `com.pocketshell.app.portfwd.ForwardingNetworkRideThroughE2eTest`
- `com.pocketshell.app.portfwd.ForwardingNotificationE2eTest#sessionPinnedForForward_hasExactlyOneForwardNotification_andStopTearsDownTunnels`
- `com.pocketshell.app.proof.SilentDropSyntheticSeamJourneyE2eTest`
- `com.pocketshell.app.proof.CleanOutageReattachResilienceE2eTest`
- `com.pocketshell.app.proof.BackgroundResumeSocketDeathE2eTest`
- `com.pocketshell.app.proof.Issue895SwitchWhileBlackBandJourneyE2eTest`
- `com.pocketshell.app.projects.ManualKindWriterDockerTest`
- `com.pocketshell.app.projects.AgentRecordedKindReadBackDockerTest`
- `com.pocketshell.app.projects.ProfileChipRelaunchDockerTest`
- `com.pocketshell.app.projects.AgentLaunchVersionMismatchHintE2eTest`
- `com.pocketshell.app.projects.FolderListOldCliHydrateDockerTest`
- `com.pocketshell.app.projects.FolderListBootstrapSkipTreeLoadsDockerTest`
- `com.pocketshell.app.projects.FolderListClientCacheInstantRenderDockerTest`
- `com.pocketshell.app.projects.FolderListScaleAnrStrictModeDockerTest`
- `com.pocketshell.app.projects.FolderListDurableTreeDaemonDockerTest`
- `com.pocketshell.app.proof.AgentSubmitAckJourneyE2eTest`
- `com.pocketshell.app.projects.FolderListScreenE2eTest#profiledSessionsShowProfileChipDefaultSessionsDoNot`
- `com.pocketshell.app.projects.SessionKindPickerUiTest`
- `com.pocketshell.app.projects.SessionTypePickerNameFieldUiTest`
- `com.pocketshell.app.snippets.SnippetTemplateDialogButtonsTest`
- `com.pocketshell.app.crash.ConfirmDeleteAllDialogButtonsTest`
- `com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#goToDialogGoButtonDispatchesPath`
- `com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#goToDialogCancelDismissesWithoutNavigating`
- `com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#failedStateShowsMessageAndRetry`
- `com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#successTransferShowsDismissibleBanner`
- `com.pocketshell.app.fileviewer.FileViewerScaffoldTest#cannotPreviewStateShowsMessageAndRetry`
- `com.pocketshell.app.fileviewer.FileViewerScaffoldTest#cannotPreviewWithLocateCandidatesOffersOpenRows`
- `com.pocketshell.app.fileviewer.FileViewerScaffoldTest#markdownRenderedPipeTableShowsCellsNotRawDelimiter`
- `com.pocketshell.app.bootstrap.HostReadyPrimaryActionTest`
- `com.pocketshell.app.bootstrap.HostNotificationsReadinessTest`
- `com.pocketshell.app.proof.StrictModeMainThreadIoDetectorE2eTest`
- `com.pocketshell.app.cards.SessionChecklistPushJourneyDockerTest`
- `com.pocketshell.app.projects.CliVersionMismatchBannerUpdateButtonTest`
- `com.pocketshell.app.proof.LaunchNoMainThreadRoomReadE2eTest`
- `com.pocketshell.app.diagnostics.ConnectionLogHostMirrorReconnectDockerTest`
- `com.pocketshell.app.env.EnvScreenE2eTest`
- `com.pocketshell.app.tmux.TmuxResizeSessionE2eTest#cachedSizeReplayRestoresFullWindowAndAgentPaneIsNotCut`
- `com.pocketshell.app.usage.UsageGlancePillE2eTest`
- `com.pocketshell.app.usage.Usage1318StrictSchemaRenderE2eTest`
