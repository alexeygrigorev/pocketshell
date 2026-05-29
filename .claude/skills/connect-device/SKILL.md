---
name: connect-device
description: Connect to the maintainer's physical Android phone (Pixel 7a) over adb on this Windows machine, and troubleshoot when it isn't detected. Use when asked to connect to "the device" / "my phone", capture its screen, read its logs, or reproduce a bug on real hardware.
---

# Connect to the device (Pixel 7a over adb)

This machine is Windows. `adb` is **not on PATH** and the SDK env vars often
aren't exported in a fresh shell, so always call adb by its full path.

- **adb**: `C:/Users/alexey/Android/Sdk/platform-tools/adb.exe`
- **Known phone**: Pixel 7a — serial `44011JEHN09327`, model `lynx`, screen `1080x2400`, density `420`.
- The phone shares the adb server with a running **emulator** (`emulator-5554`),
  so most commands must target the phone with `-s 44011JEHN09327`.

## 1. Check whether the phone is connected

```bash
ADB="C:/Users/alexey/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices -l
```

Expected when the phone is attached and authorized:

```
44011JEHN09327   device   product:lynx model:Pixel_7a device:lynx ...
emulator-5554    device   ...
```

- `device` = ready. `unauthorized` = accept the RSA prompt on the phone.
- If only the emulator shows, the phone isn't reaching adb — go to **Troubleshooting**.

## 2. Run commands against the phone

Two devices are usually attached, so pin the serial:

```bash
ADB="C:/Users/alexey/Android/Sdk/platform-tools/adb.exe"
S="44011JEHN09327"
"$ADB" -s "$S" shell getprop ro.product.model   # -> Pixel 7a
```

### Git Bash mangles device paths

Git Bash (MSYS) rewrites any argument that looks like a Unix path, so
`/sdcard/ui.xml` becomes `C:/Program Files/Git/sdcard/ui.xml` and breaks.
**Set `MSYS_NO_PATHCONV=1`** for any command that passes an on-device path:

```bash
export MSYS_NO_PATHCONV=1
"$ADB" -s "$S" shell uiautomator dump /sdcard/ui.xml
"$ADB" -s "$S" pull /sdcard/ui.xml ./_ui.xml
```

### Screenshots: use Bash, never PowerShell `>`

PowerShell's `>` redirection writes UTF-16 with a BOM and corrupts binary
output (a PNG saved that way starts `ff fe ...` and won't open). Capture
through the **Bash** tool, whose `>` is byte-clean:

```bash
"$ADB" -s "$S" exec-out screencap -p > ./_screen.png   # Bash tool only
```

## 3. Useful post-connect recipes

```bash
# Installed PocketShell version
"$ADB" -s "$S" shell dumpsys package com.pocketshell.app | grep -E "versionName|versionCode"

# What's in the foreground
"$ADB" -s "$S" shell dumpsys activity activities | grep topResumedActivity

# Tap by coordinates (find bounds in the uiautomator dump: bounds="[x1,y1][x2,y2]", tap the center)
"$ADB" -s "$S" shell input tap <x> <y>

# Prove whether a screen scrolls: swipe, then compare screenshot byte sizes
"$ADB" -s "$S" exec-out screencap -p > ./_before.png
"$ADB" -s "$S" shell input swipe 540 1700 540 500 300
"$ADB" -s "$S" exec-out screencap -p > ./_after.png   # identical size => nothing scrolled
```

Clean up `_*.png` / `_*.xml` scratch files when done (they sit in the repo root,
which already holds the maintainer's own crash screenshots — only delete the
underscore-prefixed temporaries you created).

## Troubleshooting — phone not in `adb devices`

Work down this list; the most common cause is first.

1. **Restart the adb server** (picks up a freshly plugged device):

   ```bash
   "$ADB" kill-server; "$ADB" start-server; "$ADB" devices -l
   ```

2. **Check whether Windows even sees the phone.** If adb shows nothing, find
   out if it's an adb problem or a USB problem. Google's USB vendor ID is
   `VID_18D1`. Run via the **PowerShell** tool:

   ```powershell
   Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -match "VID_18D1" } |
     Select-Object Status, FriendlyName, InstanceId | Format-Table -AutoSize
   ```

   - **No `VID_18D1` row** → Windows isn't enumerating the phone at all. This is
     a **physical/USB** problem, not adb. It is *not* an authorization issue (an
     unauthorized phone still enumerates as a Google ADB device). Causes, in
     order: **charge-only cable** (swap to a known data cable), connection
     **through a hub/dock** (plug straight into the laptop), USB mode set to
     **"No data transfer / charging only"** (pull down the notification shade →
     "Charging this device via USB" → choose **File Transfer / Android Auto**).
   - **A `VID_18D1` row exists** but adb still doesn't list it → it's an adb /
     authorization layer issue: confirm **USB debugging** is ON (Developer
     options being *enabled* is separate from the **USB debugging** toggle),
     then accept the **"Allow USB debugging from this computer?"** prompt on the
     phone (check "Always allow"). Re-run `kill-server`/`start-server`.

3. **Re-scan after each change** with `"$ADB" devices -l` until the phone shows
   `device`.

> Real example from this repo: the phone appeared absent because Windows
> enumerated zero `VID_18D1` devices — the only USB composite device was an
> Elgato (`VID_0FD9`). A data-cable swap + direct port fixed it; the phone then
> showed up as `44011JEHN09327 device` after a re-scan.
