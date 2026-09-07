# Architecture

This is the current post-rewrite architecture. `app2` is the only Android
application module, and the host session runtime is aplexer. The product has one
session contract; there is no client-side session manager selection.

## Module layout

```text
app2/                      Compose application and composition root
shared/core-transport/     sshj connection, exec, PTY, SFTP, and forwarding channels
shared/core-hostapi/       JVM client for the host `pocketshell` JSON contract
shared/core-terminal/      vendored Termux terminal emulator and terminal view
shared/core-portfwd/       port discovery and tunnel supervision
shared/core-storage/       Room entities, DAOs, and migrations
shared/core-usage/         usage and quota models
shared/core-voice/         Whisper and Android speech input plumbing
shared/core-assistant/     assistant clients and encrypted configuration
shared/ui-kit/              shared visual primitives and theme
shared/test-support/        test-only coroutine and fixture helpers
tools/pocketshell/          server-side Python helper published to PyPI
tests/docker/               disposable SSH and aplexer fixtures
```

`core-transport` is the only production module that knows sshj. `core-hostapi`
is pure JVM code, so the host contract is tested against captured JSON without
an emulator. `core-terminal` contains the vendored emulator and the
PocketShell-specific PTY bridge helpers. The app owns screen state and connects
those modules through `ConnectionsRegistry`.

## Session contract

The app calls these host commands over SSH:

```text
pocketshell sessions list --json
pocketshell sessions create ...
pocketshell sessions attach <name-or-id>
pocketshell sessions kill <name-or-id>
```

The host CLI resolves every session through its bundled aplexer binary. Create
delegates to `a start`, attach replaces the PTY process with `a attach`, and kill
uses `a kill` followed by record cleanup. If aplexer is unavailable, the command
returns a named error; it does not search for another runtime.

The JSON document is schema 3. A row contains the aplexer identity, workspace,
tag, engine/profile metadata, agent state, attachment state, timestamps, and
the `phase`/`alive` liveness pair. The document may also contain `errors[]` for
an enumeration failure. Rows are emitted only while the aplexer workload is
live, so the Android tree never presents a stale attach target. There is no
`manager`, `backend`, or runtime discriminator in the row.

The PTY path is deliberately simple: `HostCliClient.attachCommand` builds one
quoted command, `TerminalPtyBridge` copies bytes between the SSH PTY and the
vendored terminal emulator, and `TerminalHostView` renders that emulator. The
client does not parse a multiplexer control protocol, enumerate sockets, or
maintain a second session state machine.

## Connection and reconnect

`RealHostConnectionFactory` is the single SSH dial site. A
`HostConnection` owns bounded exec, PTY, SFTP, and forwarding channels. A lost
or deliberately closed connection is spent; the caller creates a new one.
`ReconnectController` owns the finite retry ladder, while `GraceCoordinator`
and `GraceService` keep an active terminal connection alive for the bounded
background grace window. The remote aplexer workload survives that transport
close and is attached again when the app returns.

The terminal surface is not cleared during reconnect. The emulator keeps its
last rendered frame until the new PTY supplies bytes. No client-side pane
snapshot, socket sweep, or hidden legacy attach path exists.

## Agent state

The host derives an optional agent kind from the live aplexer workload's
descendant process tree and recent hook/log evidence. `core-hostapi` parses the
normalized fields; the Android session tree treats them as metadata and does
not invent a second source of session identity. Conversation history remains a
host-file concern and is not required to attach a terminal.

## Storage and the migration boundary

`core-storage` uses Room database schema 21. `HostEntity` stores SSH and
PocketShell capability state but no session-runtime installation flag.
`MIGRATION_20_21` rebuilds the `hosts` table from the current entity shape and
therefore drops the obsolete `tmuxInstalled` column.

The earlier migration SQL and exported schemas intentionally still contain the
literal `tmuxInstalled`. Those migrations describe databases that users may
already have on disk; Room must be able to open versions 1 through 20 before it
can run 20→21. The column is copied only as historical compatibility data and
is not represented in the current entity, DAO, app code, or schema 21 export.
The storage tests create old table shapes and verify that the 20→21 rebuild
removes it. Product grep checks therefore exclude only the historical migration
SQL and its exported schemas, with this section as the reason.

## Host helper packaging

The PyPI package pins aplexer and resolves the bundled `a` next to the Python
interpreter. A separately installed binary on `PATH` cannot become the product's
session runtime. The Docker `agents` image follows the same rule: it derives the
release from `tools/pocketshell/pyproject.toml`, installs both `a` and its
`aplexer` worker, and runs a create → list → kill → gone self-check while the
image is built.

## Testing and operational boundary

J02, J03, J04, and J14 create or inspect real aplexer sessions in the Docker
fixture. The full Android journey lane runs against that image, while the JVM
and Python lanes test the same schema and command contract without a device.
See [testing.md](testing.md) for commands and evidence requirements.

The repository still uses tmux to isolate agent-runner and test processes. That
operational tooling is outside the product: its socket rules remain in
`AGENTS.md`, `process.md`, `scripts/`, and
[tmux-socket-recovery.md](tmux-socket-recovery.md). It must not be copied into
the host CLI, Android client, Docker product fixture, or user documentation.
