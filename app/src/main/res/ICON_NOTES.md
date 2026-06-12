# Launcher icon notes

## Scope

- Adaptive icon vectors: `drawable/ic_launcher_background.xml` (flat
  `#0B0F14` fill) and `drawable/ic_launcher_foreground.xml` (the "C1"
  mark: a bold cyan prompt chevron `>` plus a cursor block `_` on a dark
  rounded "pocket" plate).
- Adaptive icon entries: `mipmap-anydpi-v26/ic_launcher.xml` and
  `mipmap-anydpi-v26/ic_launcher_round.xml`.
- Manifest `<application>` references `@mipmap/ic_launcher` and
  `@mipmap/ic_launcher_round`.

## Why no `mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi` PNG fallbacks?

PocketShell's `minSdk = 26` (`app/build.gradle.kts`). Adaptive icons
were introduced in API 26 (Android 8.0 Oreo), so every device that can
install this app supports `mipmap-anydpi-v26/ic_launcher.xml` natively.
Pre-26 raster fallbacks would be dead weight — shipping them would
just bloat the APK with files that no installable target can reach.

If `minSdk` is ever lowered below 26, regenerate density-bucket PNGs
(`mdpi`/`hdpi`/`xhdpi`/`xxhdpi`/`xxxhdpi`, both standard and `-round`
variants) from the foreground+background vectors — Android Studio's
"Image Asset" tool is the canonical way.

## Source of truth

The two vector drawables (`ic_launcher_background.xml` and
`ic_launcher_foreground.xml`) are the canonical artwork. Everything
else (the adaptive-icon XML, eventual raster fallbacks if needed)
derives from them. Edit those when iterating on the mark.

Issue #612's generated boards are design references only; the PNGs are
not launcher assets:

- Icon contact sheet:
  https://github.com/alexeygrigorev/pocketshell/releases/download/feedback-assets/pocketshell-icon-contact-sheet-20260607.png
- Brand board:
  https://github.com/alexeygrigorev/pocketshell/releases/download/feedback-assets/pocketshell-brand-board-20260607.png

## Design intent — mark "C1"

- Background: near-black navy (`#0B0F14`) from the selected Brand
  Board Direction 1 / "Pocket Terminal" palette.
- Foreground: the **C1** mark chosen by the maintainer (#612). The shell
  prompt itself is the logo — a bold cyan (`#22D3EE`) chevron `>` with a
  near-white (`#F8FAFC`) cursor block `_`, set on a dark rounded "pocket"
  plate (`#0F1A22`, a hair lighter than the background for depth). It
  reads as a terminal / agent CLI and stays legible down to mdpi (48px).
- The earlier terminal-frame mark (cyan frame + title bar + white arrow)
  is removed outright — a hard cut per D22, not kept as an alternative.
- Every painted element, **including the backdrop plate**, is constrained
  to the conservative inner safe box (viewport `32..76` on X, `30..80` on
  Y) that `LauncherIconVectorTest` enforces, so nothing clips under the
  circular / squircle / rounded-square OEM masks or Pixel Launcher folder
  previews. Glyphs are filled polygons (no thin strokes) so they stay
  crisp after the mask scales the artwork down.
