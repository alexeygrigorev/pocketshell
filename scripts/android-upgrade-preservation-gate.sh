#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-lock.sh"
pocketshell_acquire_avd_lock "$ROOT_DIR" "${1:-}"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
PYTHON3="${PYTHON3:-python3}"
OLD_REF="${OLD_REF:-v0.3.2}"
PACKAGE_NAME="${PACKAGE_NAME:-com.pocketshell.app}"
ACTIVITY_NAME="${ACTIVITY_NAME:-com.pocketshell.app/.MainActivity}"
NEW_APK_PATH="${NEW_APK_PATH:-$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
OLD_APK_PATH="${OLD_APK_PATH:-}"
BUILD_NEW_APK="${BUILD_NEW_APK:-1}"
BUILD_OLD_APK="${BUILD_OLD_APK:-1}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/android-upgrade-preservation-gate}"
if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
GRADLE_FLAGS="${GRADLE_FLAGS:---no-daemon --no-build-cache --no-parallel --max-workers=2}"

usage() {
  cat <<'USAGE'
Usage: scripts/android-upgrade-preservation-gate.sh

Installs an old PocketShell APK, seeds a shipped schema-8 database, upgrades
with `adb install -r` to the current APK without `pm clear` or uninstall,
launches the app, and verifies representative Room rows were preserved.

Environment overrides:
  OLD_APK_PATH=/path/to/old.apk       use an existing old APK
  OLD_REF=v0.3.2                     ref used when building the old APK
  NEW_APK_PATH=app/build/...apk       new APK to install with -r
  BUILD_OLD_APK=1                    build old APK if OLD_APK_PATH is empty
  BUILD_NEW_APK=1                    build current debug APK first
  ADB=/path/to/adb
  PYTHON3=python3
  LOG_ROOT=build/android-upgrade-preservation-gate
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

mkdir -p "$RUN_DIR"

wait_package_manager_idle() {
  "$ADB" shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
  "$ADB" shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
}

install_apk() {
  local apk_path="$1"
  "$ADB" install -r -d -t "$apk_path"
  wait_package_manager_idle
}

if [[ "$BUILD_NEW_APK" == "1" ]]; then
  ./gradlew $GRADLE_FLAGS :app:assembleDebug
fi

if [[ -z "$OLD_APK_PATH" ]]; then
  if [[ "$BUILD_OLD_APK" != "1" ]]; then
    printf 'OLD_APK_PATH is required when BUILD_OLD_APK=0\n' >&2
    exit 1
  fi
  old_worktree="$RUN_DIR/old-$OLD_REF"
  git worktree add --detach "$old_worktree" "$OLD_REF"
  (
    cd "$old_worktree"
    ./gradlew $GRADLE_FLAGS :app:assembleDebug
  )
  built_old_apk="$RUN_DIR/old-app-debug-$OLD_REF.apk"
  cp "$old_worktree/app/build/outputs/apk/debug/app-debug.apk" "$built_old_apk"
  git worktree remove --force "$old_worktree"
  OLD_APK_PATH="$built_old_apk"
fi

[[ -f "$OLD_APK_PATH" ]] || { printf 'Old APK not found: %s\n' "$OLD_APK_PATH" >&2; exit 1; }
[[ -f "$NEW_APK_PATH" ]] || { printf 'New APK not found: %s\n' "$NEW_APK_PATH" >&2; exit 1; }

seed_db_host="$RUN_DIR/pocketshell-schema8-seed.db"
seed_db_device="/data/local/tmp/pocketshell-schema8-seed.db"
verified_db_host="$RUN_DIR/pocketshell-upgraded.db"
logcat_file="$RUN_DIR/upgrade-launch-logcat.log"

"$PYTHON3" - "$seed_db_host" <<'PY'
import sqlite3
import sys
from pathlib import Path

db_path = Path(sys.argv[1])
db_path.unlink(missing_ok=True)
conn = sqlite3.connect(db_path)
try:
    conn.executescript(
        """
        PRAGMA foreign_keys=OFF;
        CREATE TABLE ssh_keys (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            privateKeyPath TEXT NOT NULL,
            hasPassphrase INTEGER NOT NULL,
            createdAt INTEGER NOT NULL
        );
        CREATE TABLE hosts (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            hostname TEXT NOT NULL,
            port INTEGER NOT NULL,
            username TEXT NOT NULL,
            keyId INTEGER NOT NULL,
            maxAutoPort INTEGER NOT NULL,
            skipPortsBelow INTEGER NOT NULL,
            scanIntervalSec INTEGER NOT NULL,
            enabled INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            lastConnectedAt INTEGER,
            tmuxInstalled INTEGER,
            lastBootstrapAt INTEGER,
            pocketshellInstalled INTEGER,
            pocketshellLastDetectedAt INTEGER,
            usageCommandOverride TEXT,
            pathOverride TEXT,
            FOREIGN KEY(keyId) REFERENCES ssh_keys(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_hosts_keyId ON hosts(keyId);
        CREATE TABLE port_remappings (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            remotePort INTEGER NOT NULL,
            localPort INTEGER NOT NULL,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_port_remappings_hostId ON port_remappings(hostId);
        CREATE UNIQUE INDEX index_port_remappings_hostId_remotePort ON port_remappings(hostId, remotePort);
        CREATE TABLE port_usage (
            hostId INTEGER NOT NULL,
            remotePort INTEGER NOT NULL,
            clickCount INTEGER NOT NULL,
            totalBytes INTEGER NOT NULL,
            lastUsedAt INTEGER NOT NULL,
            PRIMARY KEY(hostId, remotePort),
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_port_usage_hostId ON port_usage(hostId);
        CREATE TABLE project_roots (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            label TEXT NOT NULL,
            path TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_project_roots_hostId ON project_roots(hostId);
        CREATE UNIQUE INDEX index_project_roots_hostId_path ON project_roots(hostId, path);
        CREATE TABLE sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            name TEXT NOT NULL,
            lastSeenAt INTEGER NOT NULL,
            tags TEXT,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_sessions_hostId ON sessions(hostId);
        CREATE UNIQUE INDEX index_sessions_hostId_name ON sessions(hostId, name);
        CREATE TABLE snippets (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            label TEXT,
            body TEXT NOT NULL,
            kind TEXT NOT NULL,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_snippets_hostId ON snippets(hostId);
        CREATE TABLE agent_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            paneRef TEXT NOT NULL,
            agent TEXT NOT NULL,
            jsonlPath TEXT,
            detectedAt INTEGER NOT NULL
        );
        CREATE UNIQUE INDEX index_agent_sessions_paneRef ON agent_sessions(paneRef);
        CREATE TABLE ai_api_call_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            timestampMillis INTEGER NOT NULL,
            provider TEXT NOT NULL,
            feature TEXT NOT NULL,
            inputUnits INTEGER NOT NULL,
            outputUnits INTEGER NOT NULL,
            unitCostUsdMillicents INTEGER NOT NULL,
            computedCostUsdMillicents INTEGER NOT NULL,
            metadataJson TEXT
        );
        CREATE INDEX index_ai_api_call_log_timestampMillis ON ai_api_call_log(timestampMillis);
        CREATE INDEX index_ai_api_call_log_provider_feature ON ai_api_call_log(provider, feature);
        CREATE TABLE pending_transcriptions (
            id TEXT NOT NULL,
            audioPath TEXT NOT NULL,
            recordingTimestampMs INTEGER NOT NULL,
            destinationContext TEXT NOT NULL,
            retryCount INTEGER NOT NULL,
            lastErrorMessage TEXT,
            audioByteSize INTEGER NOT NULL,
            createdAtMs INTEGER NOT NULL,
            PRIMARY KEY(id)
        );
        CREATE INDEX index_pending_transcriptions_recordingTimestampMs ON pending_transcriptions(recordingTimestampMs);
        INSERT INTO ssh_keys(id, name, privateKeyPath, hasPassphrase, createdAt)
            VALUES(1, 'upgrade-key-v8', '/keys/upgrade-v8', 1, 100);
        INSERT INTO hosts(
            id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
            scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
            lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
            usageCommandOverride, pathOverride
        ) VALUES(
            1, 'upgrade-host-v8', 'upgrade-v8.example.com', 2222, 'alexey', 1, 10000, 1000,
            5, 1, 101, 102, 1, 103, 1, 104, 'pocketshell usage --json', '~/legacy/bin'
        );
        INSERT INTO project_roots(id, hostId, label, path, createdAt)
            VALUES(1, 1, 'repo', '~/git/upgrade-v8', 110);
        INSERT INTO sessions(id, hostId, name, lastSeenAt, tags)
            VALUES(1, 1, 'main-v8', 120, 'work');
        INSERT INTO snippets(id, hostId, label, body, kind)
            VALUES(1, 1, 'preserve', 'echo upgrade preserved', 'command');
        INSERT INTO port_remappings(id, hostId, remotePort, localPort)
            VALUES(1, 1, 5000, 15000);
        INSERT INTO port_usage(hostId, remotePort, clickCount, totalBytes, lastUsedAt)
            VALUES(1, 5000, 4, 168, 130);
        INSERT INTO agent_sessions(id, paneRef, agent, jsonlPath, detectedAt)
            VALUES(1, 'upgrade-host-v8:main:0:0', 'codex', '/logs/upgrade-v8.jsonl', 140);
        INSERT INTO ai_api_call_log(
            id, timestampMillis, provider, feature, inputUnits, outputUnits,
            unitCostUsdMillicents, computedCostUsdMillicents, metadataJson
        ) VALUES(1, 150, 'openai', 'whisper', 12, 34, 10, 789, '{"requestId":"upgrade-v8"}');
        INSERT INTO pending_transcriptions(
            id, audioPath, recordingTimestampMs, destinationContext, retryCount,
            lastErrorMessage, audioByteSize, createdAtMs
        ) VALUES('pending-upgrade-v8', '/audio/pending-upgrade-v8.wav', 160, 'composer', 1, 'offline', 2048, 161);
        PRAGMA user_version = 8;
        """
    )
    conn.commit()
finally:
    conn.close()
PY

"$ADB" wait-for-device
"$ADB" shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
"$ADB" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
wait_package_manager_idle
install_apk "$OLD_APK_PATH"
"$ADB" shell pm clear "$PACKAGE_NAME"
wait_package_manager_idle
"$ADB" push "$seed_db_host" "$seed_db_device" >/dev/null
"$ADB" shell run-as "$PACKAGE_NAME" sh -c "'mkdir -p databases && cp $seed_db_device databases/pocketshell.db && chmod 600 databases/pocketshell.db && rm -f databases/pocketshell.db-wal databases/pocketshell.db-shm databases/pocketshell.db-journal'"
"$ADB" shell rm -f "$seed_db_device" >/dev/null 2>&1 || true

printf 'Upgrading with adb install -r; no pm clear or uninstall occurs after this point.\n'
install_apk "$NEW_APK_PATH"

"$ADB" logcat -c || true
"$ADB" shell am start -W -n "$ACTIVITY_NAME"
sleep 5
"$ADB" logcat -d -v time -t 5000 > "$logcat_file" 2>&1 || true
if grep -Eiq 'Room cannot verify|Expected identity hash|FATAL EXCEPTION|AndroidRuntime.*com[.]pocketshell[.]app' "$logcat_file"; then
  printf 'Crash signature found after upgrade launch. See %s\n' "$logcat_file" >&2
  exit 1
fi

"$ADB" exec-out run-as "$PACKAGE_NAME" cat databases/pocketshell.db > "$verified_db_host"

"$PYTHON3" - "$verified_db_host" <<'PY'
import sqlite3
import sys

db_path = sys.argv[1]
conn = sqlite3.connect(db_path)
try:
    checks = {
        "schema version": "PRAGMA user_version",
        "host": "SELECT name, hostname, usageCommandOverride, pocketshellCliVersion, pocketshellDaemonRunning FROM hosts",
        "key": "SELECT name, privateKeyPath, fingerprint FROM ssh_keys",
        "root": "SELECT path FROM project_roots",
        "session": "SELECT name FROM sessions",
        "snippet": "SELECT body FROM snippets",
        "cost": "SELECT computedCostUsdMillicents FROM ai_api_call_log",
        "pending": "SELECT id FROM pending_transcriptions",
        "remapping": "SELECT remotePort, localPort FROM port_remappings",
        "usage": "SELECT remotePort, totalBytes FROM port_usage",
    }
    observed = {name: conn.execute(sql).fetchall() for name, sql in checks.items()}
    expected = {
        "schema version": [(12,)],
        "host": [("upgrade-host-v8", "upgrade-v8.example.com", "pocketshell usage --json", None, None)],
        "key": [("upgrade-key-v8", "/keys/upgrade-v8", "")],
        "root": [("~/git/upgrade-v8",)],
        "session": [("main-v8",)],
        "snippet": [("echo upgrade preserved",)],
        "cost": [(789,)],
        "pending": [("pending-upgrade-v8",)],
        "remapping": [(5000, 15000)],
        "usage": [(5000, 168)],
    }
    for name, expected_rows in expected.items():
        if observed[name] != expected_rows:
            raise SystemExit(f"{name} mismatch: expected {expected_rows!r}, observed {observed[name]!r}")
    columns = [row[1] for row in conn.execute("PRAGMA table_info(hosts)").fetchall()]
    if "pathOverride" in columns:
        raise SystemExit("hosts.pathOverride still exists after migration")
finally:
    conn.close()

print("upgrade preservation verified")
PY

printf 'Upgrade preservation gate passed. Artifacts: %s\n' "$RUN_DIR"
