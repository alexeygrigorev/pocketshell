# Launcher icon notes

## Scope

- Adaptive icon vectors: `drawable/ic_launcher_background.xml` (flat
  `#0B0F14` fill) and `drawable/ic_launcher_foreground.xml` (D/1
  pocket-terminal mark with a cyan frame and prompt/cursor glyph).
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

## Design intent

- Background: near-black navy (`#0B0F14`) from the selected Brand
  Board Direction 1 / "Pocket Terminal" palette.
- Foreground: direction D plus Direction 1, redrawn as a compact
  pocket-terminal mark. The cyan (`#22D3EE`) frame/title bar/cursor
  carries the brand color; the white prompt chevron preserves contrast
  inside the small Android icon mask.
- Artwork is constrained to the inner 66dp × 66dp adaptive-icon
  safe zone so OEM masks (circle, squircle, teardrop) never clip it.
