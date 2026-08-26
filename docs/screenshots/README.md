# README Screenshots

These PNGs are curated documentation assets for the root README. They were
captured from the current `main` debug build (`v0.4.44`) with:

```bash
ANDROID_SERIAL=<booted-emulator> AVD_NAME=<avd> \
  VISUAL_AUDIT_BUILD_APKS=0 RUN_ID=readme-20260826 \
  scripts/capture-walkthrough-screenshots.sh
```

The host list, session tree, terminal, settings, and composer screenshots are
app walkthrough captures against the deterministic Docker SSH fixture.

`readme-conversation-view.png` is the production conversation pane rendered
with deterministic sample agent events (so README docs do not depend on live
provider credentials or private agent logs). The live
`WalkthroughConversationScreenshotTest` capture on this run produced a blank
device frame, so the last good conversation pane screenshot is kept until that
capture is reliable.

Source run:

```text
build/walkthrough-visual-pass/readme-20260826/screenshots/walkthrough-visual-pass/
```
