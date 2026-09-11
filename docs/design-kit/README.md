# PocketShell Quiet — design kit (v1.0.0, 2026-09-06)

The maintainer's full redesign of the app, delivered as a handoff package. This
directory holds the **source of truth** parts, committed so implementers and
reviewers can work from them offline. Implementation is tracked by the umbrella
issue for the redesign.

`docs/design-system.md` still describes the **current shipped** UI and stays
authoritative until the redesign lands. When it does, that file is replaced
rather than kept alongside (D22 — one design system, not two).

## What is here

| Path | What it is |
|---|---|
| `design-system/tokens.json` | Colour, type, spacing, size, radius, motion. **The** source of truth — edit here, never in a screen. |
| `design-system/tokens.css` | Generated from `tokens.json`. Do not hand-edit. |
| `design-system/catalog.json` | All 81 frames: content, route and state descriptions. Machine-readable equivalent of the interactive prototype. |
| `design-system/icons.json` | Icon path data, shared by the SVG and Compose registries. |
| `design-system/prototype.{css,js}`, `template.html` | The browser renderer, for regenerating the prototype. |
| `icons/*.svg` | 46 icons, same paths as `icons.json`. |
| `android/PocketShellTheme.kt` | Generated `PsTokens` + `PocketShellQuietTheme`, mapped to Material color/type/shape roles. |
| `android/PocketShellIcons.kt` | Native vector registry generated from the same paths as the SVGs — avoids raster icons and glyph substitution. |
| `android/PocketShellComponents.kt` | Header, row, workspace row, button, field, sheet — plus a clearly marked **preview-only** fixture renderer. |
| `android/MockupModels.kt`, `MockupCatalog.kt` | Plain-Kotlin content fixtures for every frame. Belong in a debug/test source set. |
| `android/PocketShellPreviews.kt` | `@PreviewParameter` catalog; `PocketShellMockupDemo` is a debug fixture navigator, **not** the production NavHost. |
| `spec/` | `AndroidHandoff.md`, `DesignSystem.md`, `ScreenSpecifications.md`, `AcceptanceTests.md`, `FeedbackTraceability.md`, `screen-inventory.csv`, and the kit's own `KitReadme.md`. |

## What is deliberately not here

The heavy review artifacts are **not** committed — they are large, regenerable,
and the repo is not their home:

- `screens/` (81 PNGs, 6.9 MB), `Screen_library.pdf` (6.2 MB), `index.html`
  (1.9 MB interactive prototype), `qa/` (1.9 MB), `Design_overview.jpg`,
  `assets/approved-reference.png`.

They are attached to the redesign umbrella issue as release assets, following
the same pattern as maintainer screenshots (see the `screenshot-to-issue`
skill). `design-system/catalog.json` carries the same screen data in a form an
agent can actually read.

## Read this before implementing anything

`spec/AndroidHandoff.md` is the contract. Three points from it that are easy to
miss and expensive to get wrong:

1. **The Kotlin is a starting point, not a finished APK.** Only the plain
   models/catalog were compiled, with `kotlinc`. The Compose layer has never
   been built against this app, rendered in Android Studio, run on an emulator,
   or tested with TalkBack. Expect it not to compile as-is against our Compose
   BOM, and keep our version catalog rather than adopting upgrades from the kit.
2. **This is not automatic HTML-to-Compose conversion.** Only tokens, icon
   paths and fixture content are generated across both renderers. The Compose
   primitives are handwritten and their geometry must be synchronised by hand
   when the browser components change.
3. **A display name is not identity.** Resolve a host-specific canonical
   absolute path and keep the display path separate, or `~/git` and
   `/home/alexey/git` become duplicate roots on the same host.

`spec/AndroidHandoff.md` also carries the route-migration table from every
current destination to its proposed surface, and the native contracts for
status, Back, keyboard insets, drafts, modals, native handoffs and security.
Those are requirements, not suggestions — several encode bugs this app has
already had.

## Changing the design

1. Edit `design-system/tokens.json` (colour/type/geometry) or
   `design-system/catalog.json` (screen content, route/state descriptions).
2. Change `prototype.css` / `prototype.js` only for a component-level rule,
   never to restyle one screen.
3. The kit's own `build.py` and `tools/` regenerate the prototype, the Android
   theme/icons/fixtures, and the QA exports. Those scripts are **not** committed
   here; they live in the original kit archive.

## Provenance

Delivered by the maintainer on 2026-09-06 as `PocketShell_Design_Kit.zip`
(16.5 MB) alongside `Screen_library.pdf` and `PocketShell_Prototype.html`.
Attribution for icon and reference sources is in `THIRD_PARTY_NOTICES.md`.
