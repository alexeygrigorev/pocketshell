# README Screenshots

These PNGs are curated documentation assets for the root README.

**Provenance (historical).** They were captured on 2026-08-26 from the
pre-rewrite `main` debug build (`v0.4.44`, applicationId `com.pocketshell.app`)
by the then-current `scripts/capture-walkthrough-screenshots.sh`, which drove
`WalkthroughVisualScreenshotTest` / `WalkthroughConversationScreenshotTest` /
`PromptComposerVisualScreenshotTest` against the deterministic Docker SSH
fixture, into
`build/walkthrough-visual-pass/readme-20260826/screenshots/walkthrough-visual-pass/`.
`readme-conversation-view.png` was rendered from deterministic sample agent
events rather than the live capture (which produced a blank device frame), so
README docs never depended on provider credentials or private agent logs.

**That command no longer reproduces them (issue #2481).** All three screenshot
classes were deleted with the `app` module in the rewrite's hard cut, and the
conversation view is a cut feature entirely
(`docs/rewrite-implementation-plan.md`, "Scope amendment").
`scripts/capture-walkthrough-screenshots.sh` still exists but now runs app2's
instrumented journeys and collects THEIR screenshots, under
`build/walkthrough-visual-pass/<run-id>/screenshots/files/<journey>/`:

```bash
ANDROID_SERIAL=<booted-emulator> AVD_NAME=<avd> RUN_ID=readme-<date> \
  scripts/capture-walkthrough-screenshots.sh
```

These committed PNGs are kept as-is until someone refreshes the README against
an app2 build; they document the product the README describes, not the current
applicationId.
