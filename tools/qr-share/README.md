# qr-share

Desktop emitter for the PocketShell QR-import flow (issue #129).

Reads a host from `~/.ssh/config` (via `ssh -G`) or explicit flags, builds the
`pocketshell.ssh-import.v1` payload, wraps it in the `pocketshell.qr.v1`
chunked envelope (see [`docs/ssh-qr-import.md`](../../docs/ssh-qr-import.md)),
and emits one or more QR codes. Large keys are split across QRs automatically;
the phone-side scanner accumulates and assembles them.

## Install

```bash
python3 -m pip install --user "qrcode[pil]"
```

The script is a single file and has no other runtime dependencies.

## Usage

From an ssh-config alias (resolves host, port, user, identity file via `ssh -G`):

```bash
python3 tools/qr-share/main.py prod
```

From explicit flags (skip ssh-config):

```bash
python3 tools/qr-share/main.py \
  --host prod.example.com \
  --user ubuntu \
  --port 22 \
  --key ~/.ssh/id_ed25519 \
  --name prod
```

When stdout is a terminal the QR(s) are rendered inline as Unicode blocks;
between parts the script pauses on "Press Enter for next QR" so the user
can scan each in turn. With `--png` (or when stdout is not a TTY) a
numbered sequence is written to disk:

```bash
python3 tools/qr-share/main.py prod --png --out-dir /tmp/qr
ls /tmp/qr
# qr-share-01.png
# qr-share-02.png
```

## Verifying

The emitter and the on-device assembler share a deterministic codec.
For a smoke test:

```bash
python3 tools/qr-share/main.py --host h --user u --key /dev/null --name h \
  --print-only --id deadbeef
```

The output lines are the envelope strings the phone scanner expects.

## Deep links

For payloads small enough to fit in a URL (typically just key-reference
style imports, no embedded private key), wrap the JSON in a
`pocketshell://import?payload=<urlencoded>` link. Tapping the link from
any app routes the payload directly into PocketShell's import path —
see the deep-link section of the docs for details.
