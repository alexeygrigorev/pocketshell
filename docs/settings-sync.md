# Settings sync — optional Google sign-in

One optional feature: sign in with a Google account and the hosts you pick
sync to it, encrypted so the server cannot read them. Everything here is
opt-in — with no account signed in, PocketShell behaves exactly as it did
before this existed.

This is the Android half of the feature the desktop client already ships
(`~/git/pocketshell-electron/docs/SYNC.md`). The backend is unchanged and
shared: API Gateway + Lambda + DynamoDB, deployed from
`aws-infra/sandbox/pocketshell-sync`, whose wire contract lives in that repo's
`docs/CLIENT-INTEGRATION.md`. Same API URL, same `main` slot, same
`{"hosts":[…]}` payload, same envelope — so one account works from the phone
and the laptop at once.

Code: `app2/src/main/java/com/pocketshell/next/sync/`. Entry point: Settings →
**Account & sync**.

## Status: blocked on one Google Cloud Console action

Everything below is implemented and tested EXCEPT the two live calls to
Google, which need an OAuth client that does not exist yet.

`SyncConfig.GOOGLE_ANDROID_CLIENT_ID` is a placeholder. Until it is replaced,
`SyncConfig.isGoogleClientConfigured` is false, the settings screen says so,
and the sign-in button is disabled rather than opening a browser at a 400.

To unblock, register an OAuth client of type **Android** (not "Desktop", not
"Web") in the SAME Google Cloud project as the desktop client's
`GOOGLE_CLIENT_ID` — reusing the project is what keeps the server-side email
allowlist and the deployed API working unchanged. It needs:

| Field | Value |
| --- | --- |
| Package name | `com.pocketshell.app` |
| Debug signing SHA-1 | `A0:4C:74:33:93:AD:23:1C:54:9E:CB:81:E7:43:FA:D7:D9:63:C4:17` |

(The debug certificate is the committed `debug.keystore`, shared by every
build on the dev box. A release-signing SHA-1 is a separate, later step.)

Then, in the same change:

1. Put the issued client ID in `SyncConfig.GOOGLE_ANDROID_CLIENT_ID`.
2. Update the `android:scheme` of the sync redirect `<intent-filter>` in
   `app2/src/main/AndroidManifest.xml` to the reversed form
   (`com.googleusercontent.apps.<the-id-without-the-suffix>`).

Both, or neither. `SyncConfigTest` asks the real `PackageManager` whether the
manifest resolves the redirect URI this build asks Google for, and fails if
the two drift — a mismatch is silent at build time and shows up only as a
sign-in that never comes back.

## Why the desktop flow could not be ported as-is

The desktop client runs a one-shot loopback HTTP listener on `127.0.0.1` and
uses a "Desktop app" OAuth credential whose client secret it reads from a
dotfile. Neither works on Android:

- Google rejects `http://127.0.0.1` redirects for Android clients, and a
  mobile app cannot hold a loopback listener open across a browser handoff.
- A client secret embedded in an APK is not a secret — it ships to every
  device and falls out of a decompile.

So the Android side is a **public client with PKCE and no secret at all**:

| | desktop | Android |
| --- | --- | --- |
| client type | "Desktop app" | "Android" |
| client secret | required at the token endpoint | none |
| redirect | `http://127.0.0.1:<port>` listener | reversed-client-ID scheme, caught by `SyncOAuthRedirectActivity` |
| browser | `shell.openExternal` | Custom Tab (`androidx.browser`), never a WebView |
| tokens at rest | Electron `safeStorage` | `EncryptedSharedPreferences` over an Android Keystore master key |

The `state` nonce matters more here than it does on the desktop: on Android
the redirect arrives as an `Intent`, which any installed app can send, so the
nonce is what makes an injected authorization code unusable.

## Encryption is unchanged, deliberately

`SyncCrypto` is a byte-for-byte port: PBKDF2-SHA256, 600 000 iterations,
256-bit key over a fresh 16-byte salt; AES-256-GCM under a fresh 12-byte IV;
the 16-byte tag appended to the ciphertext; the envelope
`{v, kdf, iter, salt, iv, ct}` uploaded as the `data` string. The salt travels
in the header, which is what makes the same passphrase work on every device.

Two things the port had to add, both covered by `SyncCryptoTest`:

- **PBKDF2 is hand-rolled over explicit UTF-8 bytes.** The JCE's `PBEKeySpec`
  takes a `char[]` and leaves the char→byte conversion to the provider, and
  providers disagree. A non-ASCII passphrase would then derive a different key
  on Android than on the laptop, for exactly the users who could never guess
  why. Pinned to published known-answer vectors.
- **A real desktop-produced envelope is a committed fixture.** Decrypting it
  is the only assertion that proves the wire format still matches; a
  round-trip test would pass just as happily against a drifted format.

The passphrase is typed in Settings, lives in the screen's composition memory
for that session, and is written nowhere — not preferences, not the Keystore,
not the server. Losing it loses the stored blob, and the screen says so.

## What syncs: the selection

Sync is selective, same model as the desktop client. The Account & sync
section lists saved hosts with a checkbox each; ONLY ticked hosts are
uploaded. An unticked host never leaves the device, encrypted or otherwise —
that is the privacy property, and it is why the payload is *assembled* rather
than merged: pushing replaces the account's content with the ticked set.

The ticks persist per device (`SyncSelectionStore`). That is load-bearing, not
housekeeping: a selection that forgot itself across a relaunch would turn the
next "Sync now" into a silent wipe of the account.

The account is part of the selection rather than a rival to it. Aliases pulled
from the account tick themselves on — but only ones the local host list lacks,
so an alias this device can see is one the user has decided about and their
untick stands. Together those give the flows that matter: a fresh device pulls
and auto-ticks everything, so its next push re-uploads the account instead of
wiping it; and removing a host from the account is untick + Sync now, nowhere
else.

Entries carry connection metadata only — name, hostname, port, user. Private
keys are files in app-private storage and never leave it. Fields the desktop
client models and this one does not (`proxyJump`, forwards, `identityFile`)
ride through untouched, so a phone push cannot quietly strip a laptop's entry.

A push reads the version the pull returned as its conflict base; a 409
(another device wrote first) re-pulls, re-absorbs its aliases, re-assembles
and retries up to three times. The 8 KB ceiling the Lambda enforces is checked
before upload, in bytes.

## Not built yet

Writing account-only hosts back into this device's `hosts` table. The desktop
client can append to `~/.ssh/config` because an entry there is just text; a
Room `HostEntity` needs a `keyId` pointing at a private key this device may
not have, so "restore this host to my phone" needs a key-selection step that
is its own piece of UX. Today the account's extra aliases are shown in the
picker and preserved across syncs, which is what keeps them safe until then.
