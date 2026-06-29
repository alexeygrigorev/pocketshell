# Input Methods

The full alternative-to-typing strategy. PocketShell reduces keyboard reliance through four coordinated surfaces.

## Overview

| Surface | Purpose | Trigger |
|---|---|---|
| Prompt Composer | Voice/text composing for agent prompts | Tap mic FAB on session view |
| Inline dictation | Voice straight into the terminal at cursor | Tap mic icon in the bottom controls |
| Terminal hotkeys panel | Special keys, control combos, the sticky `Ctrl` modifier + a–z letters, arrows | Tap the `⌨` launcher on the Terminal tab |
| Command chips / snippets | Whole commands or prompt templates | Always-visible chip row when keyboard is down |

For tmux operations (detach, switch sessions, etc.) PocketShell uses native UI controls rather than a chord palette — see [Quick navigation](#quick-navigation-replaces-chord-palette) below.

---

## Voice input

### Engine

Prompt Composer voice input has two selectable transcription providers:

- OpenAI Whisper via the Audio Transcriptions API. Existing `openai-transcribe` skill is the integration reference.
- Android / Google Speech via the device's system `SpeechRecognizer`.

Trade-offs accepted:
- Whisper: per-request cost (~$0.006/min), requires a saved OpenAI API key, uploads a complete recording, and usually handles technical content (code, paths, command names) better.
- Android/system recognizer: no OpenAI key, can provide partial text while speaking, but availability depends on the device image and installed speech service. Language support, offline packs, network use, and privacy handling are controlled by that service (often Google Speech Services on Play devices), not PocketShell.

Configuration: provider, API key, language, and silence threshold live in Settings. The OpenAI key is stored in Android Keystore. Future: support self-hosted `whisper.cpp` on one of the user's SSH hosts (out of v1 scope).

### Prompt Composer (primary voice surface)

~90% of voice input happens here, because agent prompts are sentences, not shell commands.

```
┌─────────────────────────────────────────┐
│ <  agent-main · main pane         ...   │
├─────────────────────────────────────────┤
│  $ tmux ls                              │
│  agent-main: 1 windows (attached)       │  terminal
│  $ _                                    │  (dimmed)
├─────────────────────────────────────────┤
│  Prompt Composer                    x   │  bottom sheet
│                                         │
│  ┌─────────────────────────────────┐    │
│  │ check the deploy log and tell   │    │
│  │ me what failed in the last run_ │    │  editable
│  └─────────────────────────────────┘    │
│                                         │
│   ┌─────┐                               │
│   │ MIC │  ▁▂▃▅▃▂▁   Listening...      │
│   └─────┘                               │
│                                         │
│  [ Snippets ]  [ Insert ]  [ Send ]     │
└─────────────────────────────────────────┘
```

Behaviours:
- Bottom sheet, modal over terminal (terminal dims behind)
- Big mic button starts recording on tap and stops on the next tap. Whisper auto-stops after the configured silence window (30s default, adjustable from 2s to 60s); Android/system recognition uses the recognizer service's own endpointing.
- Android/system recognition streams partial text into the recording panel when the service provides it. Whisper inserts the final transcript after the recording is complete.
- Text area is editable — tap any word to fix before sending
- `Insert` writes to PTY without submitting; `Send` submits with Enter; `Snippets` opens the saved-prompt library
- Sheet dismissed = transcript preserved as draft per session
- Recording state: mic fills with accent colour, inline waveform shows audio level, breadcrumb status dot pulses, haptic on start/stop

### Inline dictation (escape hatch)

For short shell commands when the prompt composer is overkill. Mic icon lives in the key bar. Tap → words stream directly into the terminal at cursor. Tap again → stop. No review step.

Inline dictation uses the same configured silence window as the prompt composer (30s default, adjustable from 2s to 60s).

Used for: `git status`, file names mid-command, dictating an `ssh` target.

### Terminal keyboard modes

The embedded `TerminalView` defaults to raw command keyboard mode. Its IME
`inputType` is:

```
TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS
```

Those password-like/no-suggestions flags are intentional. Shell input is
syntax, not prose: paths, flags, package names, branch names, hashes, and
commands can be corrupted if the keyboard silently autocorrects a token while
the text is being written to the PTY.

Settings -> Terminal exposes an explicit Smart text keyboard mode for users
who want swipe/autocorrect in the terminal. That mode requests:

```
TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL | TYPE_TEXT_FLAG_AUTO_CORRECT
```

Smart text mode is still guarded: the input connection stages committed IME
text locally and sends it to the terminal only when Enter confirms the buffer.
This avoids byte-by-byte autocorrect churn in a live shell command. Prompt
Composer remains the preferred surface for prose and longer agent prompts.

---

## Terminal hotkeys panel

The terminal control keys live in a dedicated **hotkeys panel** — its own
bottom-sheet surface opened from the Terminal tab's `⌨` launcher (NOT crammed
above the soft keyboard; #784/#789 hard-cut the old in-keyboard bar). The panel
shows every key at once in a tidy grid and stays open after a tap so you can
fire several keys in a row. It routes every tap through
`TmuxSessionViewModel.onKeyBarKey`, which maps the visible label to its control
byte (`send-keys -H` overlay) or tmux named key — no terminal resize / redraw.

Sections:

```
KEYS             Esc  Tab  ⇧Tab  Enter
CTRL COMBOS      ^A ^B ^C ^D ^E ^G ^J ^K ^L ^O ^R ^T ^U ^W ^X ^Z ^\
INTERRUPT/EOF    ^C×2  ^D×2
CTRL + LETTER    [Ctrl]                         ← sticky modifier
LETTERS          a b c d e f g … x y z
ARROWS           ←  ↑  ↓  →
```

- **CTRL COMBOS** are one-tap direct buttons — the curated common chords plus
  the keys nano (and many TUIs) need: `^G` Help, `^J` Justify, `^K` Cut, `^O`
  Write Out, `^T` Execute, `^U` cut-to-start, `^W` Where-Is, `^X` Exit, `^\`
  Replace. So you can save-and-exit `nano` (`^O`,Enter then `^X`) entirely from
  PocketShell, no modifier dance (issue #1091).
- **CTRL + LETTER** is the general escape hatch: tap the sticky **`Ctrl`**
  modifier, then any letter in the **LETTERS** grid → that letter's control byte
  (`Ctrl+A`=0x01 … `Ctrl+Z`=0x1A), so `Ctrl+<any key>` is reachable, not just
  the curated subset.

Modifier interactions (the `Ctrl` key):
- Single tap → armed for the **next** key (one-shot), then auto-releases.
- Double tap → **locked** sticky: stays on until tapped again, so you can fire
  several `Ctrl+<letter>` combos in a row.
- Active `Ctrl` lights up in the accent colour (accent-soft fill, accent-dim
  border).
- With `Ctrl` off, a LETTERS tap types that letter literally.
- The doubled `^C×2`/`^D×2` send the byte twice (the "press again to
  interrupt/exit" chord many REPLs/agents need; #787) — distinct from a single
  `^C`/`^D`.

---

## Quick navigation (replaces chord palette)

The original plan had a chord palette for tmux sequences (`Ctrl+B D` detach, `Ctrl+B S` sessions, etc.). Dropped from v1 because PocketShell's native UI already covers the common cases more smoothly than chords:

| Action | Native UI |
|---|---|
| Detach session | Tap the back arrow `‹` on the breadcrumb. Session keeps running server-side. |
| Switch session | Tap the session name in the breadcrumb → dropdown of sessions on this host |
| List sessions across hosts | Swipe down to dashboard |
| New window | `+` button in window strip (tmux control mode) |
| Next/prev window | Swipe within session |
| Kill / rename | `⋮` menu on the breadcrumb |

For things genuinely without native UI (vim `Esc :wq`, less `q`, copy mode entry) → the terminal hotkeys panel handles them (direct keys, or the sticky `Ctrl` + a letter).

A power-user chord palette may return as opt-in settings post-v1 if real demand appears. v1 stays simple.

---

## Command chips / snippets

Already covered in [vision.md](vision.md) §4. Whole commands or prompt templates. Per-host library.

Distinct from the terminal hotkeys panel: chips send literal text strings; the hotkeys panel sends key codes / control bytes (and `Ctrl+<key>` via the sticky modifier).

---

## Screen real estate

Keyboard up:

```
┌────────────────────────────┐
│   terminal output          │
├────────────────────────────┤
│                  [⌨ hotkeys]│  compact launcher above the IME
├────────────────────────────┤
│  q w e r t y u i o p       │
│   a s d f g h j k l        │  system keyboard
│    z x c v b n m  ⌫        │
└────────────────────────────┘
```

(Tapping `⌨ hotkeys` opens the terminal hotkeys panel bottom-sheet described
above.)

Keyboard down:

```
┌────────────────────────────┐
│   terminal output          │
├────────────────────────────┤
│ git status   build   logs  │  command chips
├────────────────────────────┤
│                       [MIC]│  FAB → prompt composer
└────────────────────────────┘
```

---

## Settings

Single "Input methods" settings screen with sub-pages:
- Voice: transcription provider, Whisper API key, language, auto-stop silence threshold for Whisper (30s default, 2s-60s range)
- Terminal hotkeys panel: which keys appear, ordering
- Snippets: organize, share, per-host

---

## Not in v1

- Voice commands inside dictation ("new line", "period") — raw transcript only
- Wake-word activation ("Hey shell") — too unreliable, too battery-hungry
- Chord palette for tmux/shell sequences — see [Quick navigation](#quick-navigation-replaces-chord-palette). May return post-v1 as opt-in if demand appears.
- Multilingual auto-detection — fixed locale per session, user-configurable
- Self-hosted Whisper on user's own SSH host — on brand but adds setup complexity; deferred
