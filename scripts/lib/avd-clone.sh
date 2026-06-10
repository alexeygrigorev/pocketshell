#!/usr/bin/env bash

# Idempotently clone the base `test` AVD into pool copies `test-1..test-N`.
#
# The same AVD cannot boot twice (its image files are locked by the running
# emulator), so a pool of concurrent emulators needs one *distinct* AVD per
# slot. This helper copies the base AVD's directory + ini, then rewrites the
# id/name/path fields so each clone is a fully independent, bootable AVD.
#
# A clone is created fresh from the base AVD's on-disk image set. To avoid
# copying transient runtime state from a *running* base AVD (locks, live
# qcow2 deltas, snapshots), the clone strips lock/runtime files and lets the
# clone cold-boot from the committed base images on first launch. Pool
# emulators always launch with -no-snapshot, so a clean cold boot is expected.
#
# Usage:
#   source scripts/lib/avd-clone.sh
#   pocketshell_avd_clone_one <base-avd-name> <clone-index>   # e.g. test 1 -> test-1
#   pocketshell_avd_clone_name <base-avd-name> <clone-index>  # prints "test-1"
#   pocketshell_avd_clone_exists <clone-name>                 # 0 if present

POCKETSHELL_AVD_HOME="${POCKETSHELL_AVD_HOME:-${ANDROID_AVD_HOME:-$HOME/.android/avd}}"

pocketshell_avd_clone_name() {
  local base="$1"
  local index="$2"
  printf '%s-%s\n' "$base" "$index"
}

pocketshell_avd_clone_exists() {
  local clone_name="$1"
  [[ -f "$POCKETSHELL_AVD_HOME/$clone_name.ini" && -d "$POCKETSHELL_AVD_HOME/$clone_name.avd" ]]
}

# Rewrite the path/id/name fields in a clone's .ini and config.ini so the AVD
# tooling treats it as an independent AVD rooted at its own directory.
_pocketshell_avd_rewrite_clone_meta() {
  local clone_name="$1"
  local ini_path="$POCKETSHELL_AVD_HOME/$clone_name.ini"
  local avd_dir="$POCKETSHELL_AVD_HOME/$clone_name.avd"
  local config_path="$avd_dir/config.ini"
  local hw_ini="$avd_dir/hardware-qemu.ini"

  # Top-level <name>.ini: path + path.rel point at the clone's own .avd dir.
  {
    printf 'avd.ini.encoding=UTF-8\n'
    printf 'path=%s\n' "$avd_dir"
    printf 'path.rel=avd/%s.avd\n' "$clone_name"
    # Preserve the target line from the base ini if present.
    grep -E '^target=' "$ini_path" 2>/dev/null || true
  } > "$ini_path.tmp"
  mv "$ini_path.tmp" "$ini_path"

  # config.ini: AvdId / avd.id / avd.name must match the clone name. The base
  # carries `<build>` placeholders; rewrite them (and any stale clone name).
  if [[ -f "$config_path" ]]; then
    # Drop any existing id/name lines, then append the correct ones.
    grep -vE '^(AvdId|avd\.id|avd\.name)[[:space:]]*=' "$config_path" > "$config_path.tmp" || true
    {
      printf 'AvdId = %s\n' "$clone_name"
      printf 'avd.id = %s\n' "$clone_name"
      printf 'avd.name = %s\n' "$clone_name"
    } >> "$config_path.tmp"
    mv "$config_path.tmp" "$config_path"
  fi

  # hardware-qemu.ini (if copied) embeds an absolute path to the AVD dir; a
  # stale path makes the clone reuse the base's data dir. Remove it so the
  # emulator regenerates it for the clone on first boot.
  rm -f "$hw_ini" "$hw_ini.lock" 2>/dev/null || true
}

# Strip transient runtime state copied from a possibly-running base AVD so the
# clone cold-boots cleanly from the committed base image set.
_pocketshell_avd_strip_runtime() {
  local avd_dir="$1"
  rm -f \
    "$avd_dir/multiinstance.lock" \
    "$avd_dir/hardware-qemu.ini.lock" \
    "$avd_dir/snapshot.lock" \
    "$avd_dir"/*.img.lock \
    "$avd_dir/bootcompleted.ini" \
    "$avd_dir/read-snapshot.txt" \
    "$avd_dir/emu-launch-params.txt" \
    2>/dev/null || true
  # Remove any saved snapshots so a -no-snapshot launch starts from the base
  # image deltas, not stale per-clone snapshot state.
  rm -rf "$avd_dir/snapshots" 2>/dev/null || true
  rm -rf "$avd_dir/tmpAdbCmds" 2>/dev/null || true
}

pocketshell_avd_clone_one() {
  local base="$1"
  local index="$2"
  local clone_name
  clone_name="$(pocketshell_avd_clone_name "$base" "$index")"

  local base_ini="$POCKETSHELL_AVD_HOME/$base.ini"
  local base_dir="$POCKETSHELL_AVD_HOME/$base.avd"
  local clone_ini="$POCKETSHELL_AVD_HOME/$clone_name.ini"
  local clone_dir="$POCKETSHELL_AVD_HOME/$clone_name.avd"

  if [[ ! -f "$base_ini" || ! -d "$base_dir" ]]; then
    printf 'FAIL: base AVD "%s" not found under %s\n' "$base" "$POCKETSHELL_AVD_HOME" >&2
    return 1
  fi

  if pocketshell_avd_clone_exists "$clone_name"; then
    printf 'AVD clone already present, skipping: %s\n' "$clone_name" >&2
    return 0
  fi

  printf 'Cloning base AVD %s -> %s ...\n' "$base" "$clone_name" >&2
  cp -f "$base_ini" "$clone_ini"
  # Copy the AVD directory. Use rsync when available (handles sparse qcow2
  # efficiently); fall back to cp -a.
  rm -rf "$clone_dir"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --sparse "$base_dir/" "$clone_dir/"
  else
    cp -a "$base_dir" "$clone_dir"
  fi

  _pocketshell_avd_strip_runtime "$clone_dir"
  _pocketshell_avd_rewrite_clone_meta "$clone_name"
  printf 'Created AVD clone: %s\n' "$clone_name" >&2
}

# Remove a clone entirely (used by avd-pool.sh purge, not normal teardown).
pocketshell_avd_clone_remove() {
  local clone_name="$1"
  rm -f "$POCKETSHELL_AVD_HOME/$clone_name.ini"
  rm -rf "$POCKETSHELL_AVD_HOME/$clone_name.avd"
}
