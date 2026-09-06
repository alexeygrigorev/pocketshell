# Sources and scope
Reviewed 6 September 2026. Source code was read; neither the original Android app nor the desktop app was built/run as part of this handoff.

## User-provided evidence
The uploaded Android recording and the user's explicit feedback in this conversation. The visual anchor is the last approved flat host/workspaces mock (`assets/approved-reference.png`). Earlier audit recommendations for a Conversation view, colored session badges and dense setup screens are superseded by this kit.

## PocketShell Android
- [Current route inventory](https://github.com/alexeygrigorev/pocketshell/blob/main/app2/src/main/java/com/pocketshell/next/nav/Destinations.kt): actual Hosts/Tree/Session/Files/FileViewer/Ports/Usage/Settings/setup/diagnostics route coverage.
- [Session screen](https://github.com/alexeygrigorev/pocketshell/blob/main/app2/src/main/java/com/pocketshell/next/terminal/SessionScreen.kt): existing terminal owner and keyboard/grid policy.
- [Shared design-system foundation](https://github.com/alexeygrigorev/pocketshell/blob/main/docs/design-system.md): existing shared UI kit. This handoff proposes a larger-type, flatter revision, not a claim that those new tokens already ship.

## PocketShell Desktop
- [README](https://github.com/alexeygrigorev/pocketshell-desktop/blob/main/README.md): folder workspaces contain sessions and file views; terminal-first.
- [sessionGrouping.ts](https://github.com/alexeygrigorev/pocketshell-desktop/blob/main/src/renderer/sessionGrouping.ts): distinct root/folder/session levels and stable order.
- [sessionRoots.ts](https://github.com/alexeygrigorev/pocketshell-desktop/blob/main/src/renderer/sessionRoots.ts): canonical host paths, longest root match, root-level sessions and Other.
- [workspaceTabs.ts](https://github.com/alexeygrigorev/pocketshell-desktop/blob/main/src/shared/workspaceTabs.ts): friendly Terminal/Terminal 2/suffix labels distinct from wire identities.
- [agentBadge.ts](https://github.com/alexeygrigorev/pocketshell-desktop/blob/main/src/shared/agentBadge.ts): ordinary monochrome glyph mapping, not vendor logos.
- [AppIcon.vue](https://github.com/alexeygrigorev/pocketshell-desktop/blob/main/src/renderer/components/AppIcon.vue): single 24×24 path registry, currentColor and consistent stroke construction.

## Official Android guidance
- [Compose design systems](https://developer.android.com/develop/ui/compose/designsystems)
- [Custom Compose design systems](https://developer.android.com/develop/ui/compose/designsystems/custom)
- [Accessibility defaults and touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- [Window insets and consumption](https://developer.android.com/develop/ui/compose/system/insets-ui)

These support the native theme/component, semantics, minimum target and inset strategy. They do not validate an unbuilt preview or prescribe PocketShell's product decisions.
