# Roadmap

The original phased plan is complete where it concerned the old terminal
architecture. The current roadmap describes work that can be built on the
app2 rewrite and the aplexer-only session contract.

## Current foundation

- `app2` is the only Android application module.
- `core-transport` owns sshj connections and PTYs.
- `core-hostapi` speaks schema-3 `pocketshell sessions` JSON.
- The host CLI creates, lists, attaches, and kills aplexer sessions only.
- Room schema 21 removes the obsolete host session-runtime capability column.
- Docker and emulator journeys use real pinned aplexer binaries.

## Near term

- Ship the lean-core release target after the current emulator journeys have
  been reviewed on the maintainer's device.
- Finish the remaining app2 session-menu chrome: reconnect, files, forwarding,
  and quick session switching.
- Improve aplexer-backed agent identity and state presentation once the host
  contract is stable enough to expose it in the tree.
- Keep the host CLI and APK versions in lockstep and maintain the real fixture
  self-check as a release gate.

## Later

- Port-forwarding polish and host setup recovery.
- QR host sharing and biometric key handling improvements.
- Home-screen session/tunnel status surfaces.
- Mosh, only after a real UDP transport and a defined server installation path
  exist.

## Out of scope

- Windows or desktop targets.
- Cloud-stored terminal history.
- Multi-user host configuration sync.
- A second session runtime or compatibility path for retired host tooling.

See [architecture.md](architecture.md) for the shipped module map and
[decisions.md](decisions.md) for locked choices. Historical rewrite plans are
kept in git history; they are not implementation instructions for current work.
