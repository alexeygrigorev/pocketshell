# Input Methods

The full alternative-to-typing strategy. PocketShell reduces keyboard reliance through five coordinated surfaces, each tuned for a different rhythm of input.

## Overview

| Surface | Purpose | Trigger |
|---|---|---|
| **Prompt Composer** | Voice/text composing for agent prompts | Tap mic FAB on session view |
| **Inline dictation** | Voice straight into the terminal at cursor | Tap mic icon in the key bar |
| **Key bar** (tier 1) | Single special keys + sticky modifiers | Always visible above the system keyboard |
| **Chord palette** (tier 2) | Named multi-key sequences | Long-press ⚡ in the key bar |
| **Command chips / snippets** (tier 3) | Whole commands or prompt templates | Always-visible chip row when keyboard is down |

Each tier handles a different rhythm: tier 1 is for keys you press *while typing* (Esc to leave vim, Ctrl+C to break a process). Tier 2 is for sequences you do *between actions* (detach, new window). Tier 3 is for whole commands or prompts.

---

## Voice input

### Engine

**Whisper via OpenAI Audio Transcriptions API.** Existing `openai-transcribe` skill is the integration reference.

Trade-offs accepted:
- Per-request cost (~$0.006/min)
- Network round-trip (~200–500ms)
- Better quality than Android `SpeechRecognizer` for technical content (code, paths, command names)

Configuration: API key stored in Android Keystore. Future: support self-hosted `whisper.cpp` on one of the user's SSH hosts (out of v1 scope).

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
│  [ Snippets ]  [ Send ]  [ Send + ↵ ]   │
└─────────────────────────────────────────┘
```

Behaviours:
- Bottom sheet, modal over terminal (terminal dims behind)
- Big mic button: **tap to start, tap again to stop**; auto-stop after 5s silence
- Live partial transcription streams into the text area as you speak
- Text area is editable — tap any word to fix before sending
- `Send` writes to PTY without newline; `Send + ↵` sends with Enter; `Snippets` opens the saved-prompt library
- Sheet dismissed = transcript preserved as draft per session
- Recording state: mic fills with accent colour, inline waveform shows audio level, breadcrumb status dot pulses, haptic on start/stop

### Inline dictation (escape hatch)

For short shell commands when the prompt composer is overkill. Mic icon lives in the key bar. Tap → words stream directly into the terminal at cursor. Tap again → stop. No review step.

Used for: `git status`, file names mid-command, dictating an `ssh` target.

---

## Key bar (tier 1)

**Always visible** above the system keyboard, only while the keyboard is up.

```
├─────────────────────────────────────────┤
│ [Esc] [Tab] [Ctrl] [Alt] [<][^][v][>] ⚡│
├─────────────────────────────────────────┤
│  q w e r t y u i o p                    │
│   a s d f g h j k l                     │  system keyboard
│    z x c v b n m  ⌫                     │
└─────────────────────────────────────────┘
```

Interactions:
- **Tap** a modifier (Ctrl/Alt) → next key sent with modifier; modifier auto-releases
- **Double-tap** a modifier → sticky (stays on until tapped again or auto-releases after timeout)
- **Tap** Esc/Tab/arrows → sends key immediately
- **Long-press** ⚡ → opens chord palette

Active modifiers light up in the accent colour. Bar height ~40dp.

---

## Chord palette (tier 2)

Grid of named multi-key sequences. Opens as a bottom sheet on long-press of ⚡.

```
┌─────────────────────────────────────────┐
│  Chords                          x      │
├─────────────────────────────────────────┤
│  tmux                                   │
│  ┌────────┐ ┌────────┐ ┌────────┐       │
│  │ Detach │ │New win │ │Sessions│       │
│  └────────┘ └────────┘ └────────┘       │
│  ┌────────┐ ┌────────┐ ┌────────┐       │
│  │Copy mod│ │  Zoom  │ │Split | │       │
│  └────────┘ └────────┘ └────────┘       │
│                                         │
│  shell                                  │
│  ┌────────┐ ┌────────┐                  │
│  │Ctrl+C  │ │Ctrl+R  │                  │
│  └────────┘ └────────┘                  │
│                                         │
│  [ + Add chord ]                        │
└─────────────────────────────────────────┘
```

Each chord tile records as a sequence (e.g. `Ctrl+B` then `d`) with configurable inter-key timing. Long-press a tile to edit, drag to reorder, swipe to delete.

Default chord set:

| Group | Chord | Sends |
|---|---|---|
| tmux | Detach | `Ctrl+B` `d` |
| tmux | New window | `Ctrl+B` `c` |
| tmux | Sessions | `Ctrl+B` `s` |
| tmux | Copy mode | `Ctrl+B` `[` |
| tmux | Zoom | `Ctrl+B` `z` |
| tmux | Split vertical | `Ctrl+B` `%` |
| tmux | Split horizontal | `Ctrl+B` `"` |
| shell | Ctrl+C | `Ctrl+C` |
| shell | Ctrl+R | `Ctrl+R` |

Per-host overrides (e.g. custom tmux prefix if user remapped to `Ctrl+A`).

### Synergy with tmux control mode

Many tmux chords have *better* native PocketShell UI:

| Chord | Native equivalent |
|---|---|
| Detach | `x` on breadcrumb |
| New window | `+` on window strip |
| Next/prev window | Swipe up |
| Sessions | Swipe down to dashboard |
| Jump to window N | Tap window in strip |

Chord palette stays for muscle memory + chords without UI equivalents (copy mode, zoom, custom user chords).

---

## Command chips / snippets (tier 3)

Already covered in [vision.md](vision.md) §4. Whole commands or prompt templates. Per-host library.

Distinct from chords: chips send literal text; chords send key sequences with modifier timing.

---

## Screen real estate

**Keyboard up:**

```
┌────────────────────────────┐
│   terminal output          │
├────────────────────────────┤
│ [Esc][Tab][Ctrl]...    [⚡]│  key bar (~40dp)
├────────────────────────────┤
│  q w e r t y u i o p       │
│   a s d f g h j k l        │  system keyboard
│    z x c v b n m  ⌫        │
└────────────────────────────┘
```

**Keyboard down:**

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
- **Voice**: Whisper API key, language, auto-stop silence threshold
- **Key bar**: which keys appear, ordering
- **Chord palette**: edit/add chords, per-host
- **Snippets**: organize, share, per-host

---

## Not in v1

- Voice commands inside dictation ("new line", "period") — raw transcript only
- Wake-word activation ("Hey shell") — too unreliable, too battery-hungry
- Predictive context-aware chord suggestions (vim running → surface `:wq`) — nice, high effort, defer
- Multilingual auto-detection — fixed locale per session, user-configurable
- Self-hosted Whisper on user's own SSH host — on brand but adds setup complexity; deferred
