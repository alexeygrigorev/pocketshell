# Launcher icon notes

## Scope

- Adaptive icon vectors: `drawable/ic_launcher_background.xml` (flat
  `#0D1117` fill) and `drawable/ic_launcher_foreground.xml` (cyan
  `#22D3EE` `>_` prompt glyph).
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

## Design intent

- Background: deep navy (`#0D1117`) per `docs/design-language.md`.
- Foreground: shell-prompt mark (`>` chevron + `_` cursor block) in
  the accent cyan (`#22D3EE`). The glyph reads instantly as
  "terminal / shell", aligns with the `--term-prompt` accent used in
  the mockups, and is geometric/bold enough to survive scaling down
  to mdpi (48px) without losing legibility.
- Artwork is constrained to the inner 66dp × 66dp adaptive-icon
  safe zone so OEM masks (circle, squircle, teardrop) never clip it.
