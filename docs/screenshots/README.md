# README Screenshots

These PNGs are curated documentation assets for the root README. They were
captured from a current debug build with:

```bash
RUN_ID=issue551-readme-screenshots-v2 scripts/phone-walkthrough.sh visual-audit
```

The host list, terminal session, settings, and composer screenshots are app
walkthrough captures against the deterministic Docker SSH fixture. The
conversation screenshot is the production conversation pane rendered with
deterministic sample agent events so README docs do not depend on live provider
credentials or private agent logs.

The source run wrote full reviewer artifacts under:

```text
build/phone-walkthrough/issue551-readme-screenshots-v2/screenshots/visual-audit/
```
