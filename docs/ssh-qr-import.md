# SSH QR Import

PocketShell can import an SSH host from a QR image or text payload selected with the host-list **Import** button. The first supported payload is `pocketshell.ssh-import.v1`.

## Payload Format

```json
{
  "type": "pocketshell.ssh-import.v1",
  "version": 1,
  "name": "prod",
  "host": "prod.example.com",
  "port": 22,
  "username": "ubuntu",
  "auth": {
    "type": "privateKey",
    "name": "prod-key",
    "privateKeyPem": "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----"
  }
}
```

Fields:

| Field | Required | Notes |
|---|---:|---|
| `type` | yes | Must be `pocketshell.ssh-import.v1`. |
| `version` | yes | Must be `1`. |
| `name` | no | PocketShell display name. Defaults to `host` when absent. |
| `host` | yes | SSH host name or IP address. `hostname` is accepted as an alias. |
| `port` | no | Defaults to `22`; valid range is `1..65535`. |
| `username` | yes | SSH username. |
| `auth` | yes | Authentication object. |

Supported auth objects:

```json
{
  "type": "privateKey",
  "name": "prod-key",
  "privateKeyPem": "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----"
}
```

```json
{
  "type": "keyRef",
  "name": "existing-key"
}
```

`privateKey` imports key material into PocketShell's existing key-storage path. PocketShell inspects the private key bytes locally to decide whether a passphrase prompt is required; payloads should not include a `passphraseRequired` field. `keyRef` creates the host using a key that has already been imported into PocketShell with the same name.

## Size And Security

Single QR import works best below about 2,800 UTF-8 bytes. PocketShell currently accepts payload files up to 12 KiB, but camera/QR decoding for larger private keys is not implemented yet. For keys that do not fit a reliable single QR, import the key separately and use `keyRef`, or transfer the text payload as a file and select it with **Import**.

Private keys in QR codes are visible secrets. Generate and scan them in a private space, prefer passphrase-protected keys, and delete any generated payload or QR image after import.

## Generate From SSH Config

The helper below resolves an entry from `~/.ssh/config` using `ssh -G`, then writes the JSON payload. It does not print the private key by itself; redirect the output to a file or pipe it into a QR tool.

```bash
python3 - "$1" <<'PY'
import json
import pathlib
import subprocess
import sys

alias = sys.argv[1]
resolved = subprocess.check_output(["ssh", "-G", alias], text=True)
config = {}
for line in resolved.splitlines():
    if not line or " " not in line:
        continue
    key, value = line.split(None, 1)
    config.setdefault(key.lower(), value.strip())

identity = config.get("identityfile")
if not identity:
    raise SystemExit(f"{alias}: no IdentityFile resolved")

identity_path = pathlib.Path(identity).expanduser()
private_key = identity_path.read_text()

payload = {
    "type": "pocketshell.ssh-import.v1",
    "version": 1,
    "name": alias,
    "host": config.get("hostname", alias),
    "port": int(config.get("port", "22")),
    "username": config.get("user") or subprocess.check_output(["id", "-un"], text=True).strip(),
    "auth": {
        "type": "privateKey",
        "name": identity_path.name,
        "privateKeyPem": private_key.strip(),
    },
}

print(json.dumps(payload, separators=(",", ":")))
PY
```

Example usage:

```bash
./make-pocketshell-payload prod > prod.pocketshell.json
qrencode -o prod.pocketshell.png < prod.pocketshell.json
```

For a key-reference payload:

```bash
jq -n --arg name prod --arg host prod.example.com --arg user ubuntu --arg key prod-key '{
  type: "pocketshell.ssh-import.v1",
  version: 1,
  name: $name,
  host: $host,
  port: 22,
  username: $user,
  auth: {type: "keyRef", name: $key}
}'
```
