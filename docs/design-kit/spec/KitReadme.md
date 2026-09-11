# PocketShell Quiet — Android design handoff
Version 1.0.0 · 6 September 2026

## Start here
Open **index.html** in a desktop browser. It is one standalone, offline file: no server, package install, login, fonts, external scripts or network connection is required. For mobile browsers that cannot open local HTML, use the PDF or PNG exports.

The prototype starts on the approved host/workspaces screen. Use the left rail to choose a screen, **All screens** to compare the entire family, and **Design system** to inspect the shared rules. The side panel documents the job, interaction contract and Android mapping. Width controls show 360/412/600 reference pixels; type controls simulate 100/130/200% scaling. These are browser layout checks, not exact Android font-scaling emulation.

## Deliverables
- `index.html`: linked prototype, 81 screen/state fixtures, gallery and design-system page.
- `Screen_library.pdf`: portable review book; two annotated screens per spread.
- `Design_overview.jpg`: six key surfaces in one glance.
- `screens/`: 81 PNGs at 824 × 1830 px (412 × 915 reference, 2×).
- `design-system/`: source tokens, icon paths, shared components/renderer, and screen catalog.
- `android/`: native Jetpack Compose theme, icons, primitives and all 81 preview fixtures.
- `docs/AndroidHandoff.md`: implementation sequence, scope boundaries and platform contracts.
- `docs/DesignSystem.md`: visual system and semantic rules.
- `docs/ScreenSpecifications.md`: screen-by-screen behavior and current-route mapping.
- `docs/AcceptanceTests.md`: target Android acceptance criteria.
- `docs/FeedbackTraceability.md`: user feedback mapped to design decisions.
- `docs/screen-inventory.csv`: machine-readable inventory.
- `qa/`: browser test report, contact sheets and large-text samples.

## Three flows to try
**Resume:** hetzner → pocketshell → Terminal.

**Start new work:** root Add → Create folder → enter a name → Create workspace → New session → choose a program → Start.

**Work in a root:** tap ~/git → Start session here → Shell → Start Shell. A root does not require an invented child folder.

## What is real and what is simulated
The layouts, controls, screen navigation, search, selected demo state, editable drafts, folder/session creation fixtures and the visual system are implemented in HTML. No SSH, terminal emulation, microphone, camera, biometric authentication, provider data, credential handling, remote file operation or tunnel runs. Rows labelled as native handoffs intentionally show an explanation instead of performing an operation. Do not enter real secrets.

The 79 frames are **screens, sheets and states**, not 81 new app destinations. They cover the original recording's app-owned surfaces, current Android navigation routes, and the proposed workspace-first flows. The recording's Android-owned file picker and notification panel are not redesigned as custom app screens.

The Kotlin is an **integration starting point and native preview catalog**, not a finished APK. Its pure models and fixture catalog were compiled with kotlinc. The Compose layer has not been built against your app, rendered in Android Studio, run on an emulator or tested with TalkBack. See the remaining gates in `docs/AcceptanceTests.md`.

## Make a consistent change
1. Edit `design-system/tokens.json` for color, type and geometry.
2. Edit `design-system/catalog.json` for screen content and route/state descriptions.
3. Change shared `prototype.css` / `prototype.js` only for a component-level behavior or layout rule, not to restyle one screen.
4. Run `python build.py` to rebuild standalone HTML and CSS tokens.
5. Run `python tools/generate_android.py` to regenerate native theme, icon registry, models and fixtures.
6. Run `python tools/export_qa.py` to regenerate PNGs and browser QA (requires Playwright and Chromium; see script header).

Handwritten Compose primitives stay in `android/PocketShellComponents.kt`; synchronize their component-level geometry when changing the browser components. Only tokens, icon paths and fixture content are generated across both renderers. **This is not automatic HTML-to-Compose conversion.**

No font binaries are supplied. Browser and native use system fonts. See `THIRD_PARTY_NOTICES.md` for source attribution.
