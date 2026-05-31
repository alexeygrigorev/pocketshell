#!/usr/bin/env bash

pocketshell_app_version_name() {
  local root_dir="$1"
  local gradle_file="$root_dir/app/build.gradle.kts"
  local version_name

  version_name="$(
    sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' "$gradle_file" |
      head -n 1
  )"
  if [[ -z "$version_name" ]]; then
    printf 'could not parse versionName from %s\n' "$gradle_file" >&2
    return 1
  fi

  printf '%s\n' "$version_name"
}

pocketshell_agent_fixture_version_output() {
  local version_name="$1"
  printf 'pocketshell fixture %s\n' "$version_name"
}
