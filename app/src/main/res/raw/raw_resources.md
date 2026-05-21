# `app/src/main/res/raw/` — bundled raw resources

This file documents every entry in `app/src/main/res/raw/` and the reason
it ships inside the production APK. Anyone auditing the resources
directory should be able to answer "why is this shipping with the app?"
without spelunking through code.

**Filename note:** Android's resource compiler (AAPT2) requires
file-based resource names in `res/raw/` to be lowercase, so this file
lives as `raw_resources.md` (originally proposed as `RAW_RESOURCES.md`
in the design brief). The `.md` extension means R.raw.raw_resources is
generated, but nothing in the app references it — the file is for
humans reading the source tree, not for runtime use.

## `proof_test_key`

**What:** An ed25519 OpenSSH-format private key. Byte-identical to
`tests/docker/test_key`, the key the `pocketshell-test:ssh` Docker container
trusts in its `authorized_keys`.

**Why it ships in the APK:** Phase 0's `ProofOfLifeScreen`
(`app/src/main/java/com/pocketshell/app/proof/ProofOfLifeScreen.kt`) is a
hardcoded smoke-test screen that connects to a single Docker host on
`10.0.2.2:2222` to prove the `:shared:core-ssh` + `:shared:core-terminal`
modules wire together end-to-end. It has no host management UI, no key
import flow, and no host-key verification — all of which are deliberately
out of scope for Phase 0. So that the screen can authenticate at all, the
test key is bundled as a raw resource and read at launch via
`readKeyFromRawResource`.

**Status: proof-of-life only.** This key is committed to a public repo and
trusts a container with a published Dockerfile. It is **not** a secret and
has **no** real security value. Treat it the same way you would treat the
default Android debug-keystore password ("android") — useful for local
plumbing, never for anything reachable from the open internet.

**Path to obsolescence:** Issue #18 ("Host management screens", now merged)
introduced `HostListScreen` + `SshKeysScreen` + `AddEditHostScreen`, which
let the user store their own hosts and keys in the Room database
(`HostDao` / `SshKeyDao` from `:shared:core-storage`). Once `MainActivity`
lands on `HostListScreen` by default and the user-supplied key flow is the
only path into a session, `proof_test_key` (and the `ProofOfLifeScreen`
itself) can be deleted in a follow-up cleanup issue. The key is kept
temporarily until the SSH key picker in `HostsScreen` can read
user-supplied keys end-to-end and the proof screen is no longer reachable
from any user-facing nav target.

When that cleanup happens:

1. Delete `app/src/main/res/raw/proof_test_key`.
2. Delete `app/src/main/java/com/pocketshell/app/proof/ProofOfLifeScreen.kt`
   and its associated test
   `app/src/test/java/com/pocketshell/app/proof/ProofPipelineTest.kt`
   (or port the SSH + emulator-content assertions to a longer-lived
   integration suite first).
3. Drop this section from `raw_resources.md`.

## `third_party_licenses.txt`

**What:** The canonical attribution text for every third-party source the
APK includes (vendored Termux terminal-emulator + terminal-view, sshj,
BouncyCastle, etc.). Plain text, no formatting.

**Why it ships in the APK:** The licenses of the included components
(Apache 2.0, BSD, ...) require attribution in distributed binaries. Shipping
the file as a raw resource means an in-app "Licenses" / "About" screen
(planned, not yet implemented) can render it directly at runtime without a
network round-trip.

**Status: permanent.** This file grows as new third-party code is added; it
does not get removed.
