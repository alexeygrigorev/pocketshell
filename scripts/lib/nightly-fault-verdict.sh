#!/usr/bin/env bash
# nightly-fault-verdict.sh — the authoritative fault-injection safety verdict
# helper (issue #1201, coverage visibility #2141).
#
# WHY THIS EXISTS
# ---------------
# The release gate's nightly-fault guard (scripts/check-nightly-fault-run.sh,
# #851) used to block the release on the WHOLE "Nightly Extensive Tests"
# extensive-job conclusion. But that shard mixes THREE unrelated things:
#
#   1. phase 1 — the full connected journey/E2E suite (chronic emulator /
#      toxiproxy infra flakes + stale-test debt);
#   2. phase 2 — the toxiproxy network-fault proofs (the ACTUAL fault suite);
#   3. phase 3 — the bootstrap setup-scenario matrix (also a release-gating
#      safety journey);
#
#   plus an intentional TDD "expected-fail" lane (#822 Slice C/D unbuilt-feature
#   journeys, designed RED until the slice lands).
#
# Because phase 1's chronic flakes and the #822 expected-fail lane are ALWAYS
# red, the extensive job conclusion was essentially never `success`, so the
# release gate had to be waived with NIGHTLY_FAULT_GATE_DISABLED=1 on every
# recent release — a permanently-waived safety gate protects nothing.
#
# This helper computes a verdict that reflects ONLY the fault-injection safety
# phases (network-fault + bootstrap), EXCLUDING the flaky journey suite AND the
# non-gating #822 expected-fail lane. `nightly-extensive-suite.sh` writes that
# verdict to a machine-readable file on the shard that runs those phases; the
# workflow's dedicated `Fault-injection safety verdict` job reads it and turns it
# into a job conclusion; and `check-nightly-fault-run.sh` reads THAT job's
# conclusion (not the mixed extensive-job conclusion).
#
# Issue #2141: a phase-status PASS is not a clean pass while a member of the
# gating set was skipped or never executed. The brief-cut ride-through proof
# (`RideThroughInterruptionE2eTest#briefLinkCutRidesThroughWithoutDisconnectOrTeardown`)
# currently self-skips via `Assume.assumeTrue(..., false)` until #1678 lands.
# #2141 owns making that hole visible in `fault-verdict.txt` (and refusing
# `fault_verdict=PASS` while it is open). #1678 owns deleting the Assume and
# making the five-second journey non-vacuous. Neither covers the other.
#
# Everything here is PURE (no gradle, no emulator, no network) so it is
# unit-testable with `scripts/lib/nightly-fault-verdict.sh --self-test`.

# ---------------------------------------------------------------------------
# compute_fault_verdict <network_fault_status> <bootstrap_status>
#
# The two arguments are the PASS/FAIL/INFRA results of the network-fault (phase 2) and
# bootstrap (phase 3) phases. The journey phase (phase 1) and the #822
# expected-fail lane are DELIBERATELY not arguments — they must never influence
# the fault-injection safety verdict.
#
# FAIL dominates INFRA so a real assertion can never be downgraded by a later
# device loss. Otherwise INFRA is preserved as a distinct blocking verdict.
# Prints "PASS" / "FAIL" / "INFRA"; returns 0 / 1 / 2.
#
# Coverage (skipped / unreached gating members) is applied later by
# apply_gating_coverage / write_fault_verdict_file — this function stays the
# phase-status combiner so INFRA/FAIL classification tests stay independent.
# ---------------------------------------------------------------------------
compute_fault_verdict() {
  local network_fault_status="$1"
  local bootstrap_status="$2"

  if [[ "$network_fault_status" == "FAIL" || "$bootstrap_status" == "FAIL" ]]; then
    echo "FAIL"
    return 1
  fi
  if [[ "$network_fault_status" == "INFRA" || "$bootstrap_status" == "INFRA" ]]; then
    echo "INFRA"
    return 2
  fi
  if [[ "$network_fault_status" == "PASS" && "$bootstrap_status" == "PASS" ]]; then
    echo "PASS"
    return 0
  fi
  # Unknown status is never a release-green signal.
  echo "FAIL"
  return 1
}

# ---------------------------------------------------------------------------
# apply_gating_coverage <phase_verdict> <coverage_status>
#
# Issue #2141: a phase-status PASS is not a clean pass unless the gating set
# was fully evaluated. `complete` is the only coverage status that may keep
# PASS. `incomplete` / `unknown` / anything else downgrade PASS to INCOMPLETE.
# FAIL and INFRA dominate — a skipped method must not hide a product failure
# or a lost device.
# Prints PASS / FAIL / INFRA / INCOMPLETE; returns 0 / 1 / 2 / 3.
# ---------------------------------------------------------------------------
apply_gating_coverage() {
  local phase_verdict="$1"
  local coverage_status="$2"

  case "$phase_verdict" in
    FAIL)
      echo "FAIL"
      return 1
      ;;
    INFRA)
      echo "INFRA"
      return 2
      ;;
    PASS)
      if [[ "$coverage_status" == "complete" ]]; then
        echo "PASS"
        return 0
      fi
      echo "INCOMPLETE"
      return 3
      ;;
    *)
      echo "FAIL"
      return 1
      ;;
  esac
}

# ---------------------------------------------------------------------------
# assess_fault_gating_coverage <results_root> <expected_class> [...]
#
# Reads preserved phase-2 JUnit XML and compares it to the expected gating
# class list (NETWORK_FAULT_CLASSES). Prints machine-readable coverage fields:
#
#   expected_gating_classes=N
#   executed_gating_classes=M
#   missing_gating_classes=A,B
#   skipped_gating_methods=Class#method,...
#   gating_coverage=complete|incomplete
#
# A class is executed when at least one of its testcases appears in the XML.
# A method is skipped when every observed result for that Class#method is a
# JUnit <skipped> (an evaluated pass/fail wins over a skip on retry).
# Coverage is complete only when every expected class executed, none is
# missing, no expected-class method is skipped, and the expected set is
# non-empty. Returns 0 if complete, 1 if incomplete.
# ---------------------------------------------------------------------------
assess_fault_gating_coverage() {
  local results_root="$1"
  shift
  local expected_classes=("$@")
  local listing expected_n executed_n
  local -a missing_list=() skipped_list=() executed_list=()
  local line classname method status cls found

  expected_n="${#expected_classes[@]}"
  listing="$(
    python3 - "$results_root" <<'PY'
from collections import defaultdict
from pathlib import Path
from xml.etree import ElementTree
import sys

def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]

results_root = Path(sys.argv[1])
# Single load-bearing skip tag. The #2141 G6 mutant rewrites this literal so a
# skipped gating method is treated as evaluated; that must redden the skip
# fixture and only the skip fixture.
skip_tag = "skipped"
seen: dict[tuple[str, str], set[str]] = defaultdict(set)
if results_root.is_dir():
    for path in results_root.rglob("TEST-*.xml"):
        try:
            root = ElementTree.parse(path).getroot()
        except (OSError, ElementTree.ParseError):
            continue
        suite_name = root.get("name") or ""
        for node in root.iter():
            tag = local_name(node.tag)
            if tag in {"testsuite", "testsuites"}:
                suite_name = node.get("name") or suite_name
                continue
            if tag != "testcase":
                continue
            classname = node.get("classname") or suite_name
            name = node.get("name") or ""
            if not classname or not name:
                continue
            child_tags = {local_name(child.tag) for child in node}
            if child_tags & {"failure", "error"}:
                status = "fail"
            elif skip_tag in child_tags:
                status = "skip"
            else:
                status = "pass"
            seen[(classname, name)].add(status)

for (classname, name), statuses in sorted(seen.items()):
    if statuses & {"pass", "fail"}:
        status = "evaluated"
    elif "skip" in statuses:
        status = "skip"
    else:
        status = "evaluated"
    print(f"{classname}\t{name}\t{status}")
PY
  )"

  local -A executed_set=()
  local -A skip_set=()
  while IFS=$'\t' read -r classname method status; do
    [[ -n "$classname" ]] || continue
    executed_set["$classname"]=1
    if [[ "$status" == "skip" ]]; then
      skip_set["$classname#$method"]=1
    fi
  done <<<"$listing"

  for cls in "${expected_classes[@]}"; do
    found=0
    if [[ -n "${executed_set[$cls]+x}" ]]; then
      found=1
    fi
    if [[ "$found" -eq 1 ]]; then
      executed_list+=("$cls")
    else
      missing_list+=("$cls")
    fi
  done

  for cls in "${expected_classes[@]}"; do
    for line in "${!skip_set[@]}"; do
      if [[ "$line" == "$cls#"* ]]; then
        skipped_list+=("$line")
      fi
    done
  done

  if ((${#skipped_list[@]} > 0)); then
    local sorted_skips=""
    sorted_skips="$(printf '%s\n' "${skipped_list[@]}" | sort -u)"
    skipped_list=()
    while IFS= read -r line; do
      [[ -n "$line" ]] && skipped_list+=("$line")
    done <<<"$sorted_skips"
  fi

  executed_n="${#executed_list[@]}"
  local coverage="incomplete"
  if [[ "$expected_n" -gt 0 \
        && "$executed_n" -eq "$expected_n" \
        && "${#missing_list[@]}" -eq 0 \
        && "${#skipped_list[@]}" -eq 0 ]]; then
    coverage="complete"
  fi

  local missing_joined="" skipped_joined=""
  if ((${#missing_list[@]} > 0)); then
    missing_joined="$(IFS=,; echo "${missing_list[*]}")"
  fi
  if ((${#skipped_list[@]} > 0)); then
    skipped_joined="$(IFS=,; echo "${skipped_list[*]}")"
  fi

  printf 'expected_gating_classes=%s\n' "$expected_n"
  printf 'executed_gating_classes=%s\n' "$executed_n"
  printf 'missing_gating_classes=%s\n' "$missing_joined"
  printf 'skipped_gating_methods=%s\n' "$skipped_joined"
  printf 'gating_coverage=%s\n' "$coverage"

  [[ "$coverage" == "complete" ]]
}

# ---------------------------------------------------------------------------
# write_fault_verdict_file <path> <nf_status> <nf_exit> <bootstrap_status> \
#                          <bootstrap_exit> <expectedfail_status> <expectedfail_exit> \
#                          [coverage_file]
#
# Writes the machine-readable verdict file the CI `Fault-injection safety
# verdict` job reads. The expected-fail fields are recorded for TRACKING only
# and are explicitly NON-GATING (they do not feed `fault_verdict`).
#
# coverage_file is the output of assess_fault_gating_coverage. It is GATING:
# a missing file, or gating_coverage other than `complete`, refuses
# fault_verdict=PASS (issue #2141). FAIL/INFRA still dominate.
# ---------------------------------------------------------------------------
write_fault_verdict_file() {
  local path="$1"
  local nf_status="$2"
  local nf_exit="$3"
  local bootstrap_status="$4"
  local bootstrap_exit="$5"
  local expectedfail_status="$6"
  local expectedfail_exit="$7"
  local coverage_file="${8:-}"

  local phase_verdict verdict
  local coverage_status="unknown"
  local expected_n="" executed_n="" missing="" skipped=""
  # `compute_fault_verdict` returns non-zero on FAIL; capture the string without
  # letting a caller's `set -e` abort here (the assignment inherits its status).
  phase_verdict="$(compute_fault_verdict "$nf_status" "$bootstrap_status")" || true

  if [[ -n "$coverage_file" && -f "$coverage_file" ]]; then
    coverage_status="$(grep -E '^gating_coverage=' "$coverage_file" | head -1 | cut -d= -f2-)"
    expected_n="$(grep -E '^expected_gating_classes=' "$coverage_file" | head -1 | cut -d= -f2-)"
    executed_n="$(grep -E '^executed_gating_classes=' "$coverage_file" | head -1 | cut -d= -f2-)"
    missing="$(grep -E '^missing_gating_classes=' "$coverage_file" | head -1 | cut -d= -f2-)"
    skipped="$(grep -E '^skipped_gating_methods=' "$coverage_file" | head -1 | cut -d= -f2-)"
    coverage_status="${coverage_status:-unknown}"
  fi

  verdict="$(apply_gating_coverage "$phase_verdict" "$coverage_status")" || true

  {
    echo "# Fault-injection safety verdict (issue #1201) — machine-readable."
    echo "# GATING inputs: network-fault (phase 2) + bootstrap (phase 3) ONLY."
    echo "# The journey/E2E suite (phase 1) and the #822 expected-fail lane"
    echo "# (phase 2b) are DELIBERATELY excluded and never gate this verdict."
    echo "fault_phase_ran=yes"
    echo "network_fault_status=$nf_status"
    echo "network_fault_exit=$nf_exit"
    echo "bootstrap_status=$bootstrap_status"
    echo "bootstrap_exit=$bootstrap_exit"
    echo "# NON-GATING (tracked only): the #822 Slice C/D expected-fail lane."
    echo "expected_fail_status=$expectedfail_status"
    echo "expected_fail_exit=$expectedfail_exit"
    echo "# Issue #2141: skipped / unreached gating members cannot hide behind PASS."
    echo "# #2141 owns visibility of this hole; #1678 owns filling it (delete the Assume)."
    echo "expected_gating_classes=$expected_n"
    echo "executed_gating_classes=$executed_n"
    echo "missing_gating_classes=$missing"
    echo "skipped_gating_methods=$skipped"
    echo "gating_coverage=$coverage_status"
    echo "fault_verdict=$verdict"
  } > "$path"
}

# ---------------------------------------------------------------------------
# Self-test: exercises the pure verdict function across the full matrix with NO
# gradle/emulator. This is the dry-run proof (issue #1201 acceptance) that the
# verdict GREENs when the fault phases passed even though the journey suite /
# expected-fail lane are red, and REDs when a fault phase itself failed.
#
# Issue #2141 adds the coverage fixtures: a skipped gating method or a
# truncated class set must refuse fault_verdict=PASS, and a mutant that
# re-skips / ignores those holes must redden only the matching assertion.
# ---------------------------------------------------------------------------
_fault_verdict_self_test() {
  local failures=0
  local out rc tmp coverage_tmp fixture_root
  local BRIEF_CLASS="com.pocketshell.app.proof.RideThroughInterruptionE2eTest"
  local BRIEF_METHOD="briefLinkCutRidesThroughWithoutDisconnectOrTeardown"
  local SUSTAINED_METHOD="sustainedLinkCutReconnectsCleanlyWithoutHang"

  assert_verdict() {
    local label="$1" expect="$2" expect_rc="$3" nf="$4" bootstrap="$5"
    out="$(compute_fault_verdict "$nf" "$bootstrap")" && rc=0 || rc=$?
    if [[ "$out" != "$expect" || "$rc" != "$expect_rc" ]]; then
      printf 'FAIL [%s]: expected %s(rc=%s) got %s(rc=%s)\n' \
        "$label" "$expect" "$expect_rc" "$out" "$rc"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] -> %s (rc=%s)\n' "$label" "$out" "$rc"
    fi
  }

  assert_file_field() {
    local label="$1" file="$2" field="$3" expect="$4"
    local actual
    actual="$(grep -E "^${field}=" "$file" | head -1 | cut -d= -f2-)"
    if [[ "$actual" != "$expect" ]]; then
      printf 'FAIL [%s]: %s expected %s got %s\n' "$label" "$field" "$expect" "$actual"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] %s=%s\n' "$label" "$field" "$actual"
    fi
  }

  write_complete_coverage() {
    cat > "$1" <<'EOF'
expected_gating_classes=13
executed_gating_classes=13
missing_gating_classes=
skipped_gating_methods=
gating_coverage=complete
EOF
  }

  write_testcase() {
    local file="$1" classname="$2" method="$3" kind="$4"
    {
      printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
      if [[ "$kind" == "skip" ]]; then
        printf '<testsuite name="%s" tests="1" failures="0" errors="0" skipped="1">\n' "$classname"
        printf '  <testcase name="%s" classname="%s"><skipped /></testcase>\n' \
          "$method" "$classname"
      elif [[ "$kind" == "fail" ]]; then
        printf '<testsuite name="%s" tests="1" failures="1" errors="0" skipped="0">\n' "$classname"
        printf '  <testcase name="%s" classname="%s"><failure>assertion</failure></testcase>\n' \
          "$method" "$classname"
      else
        printf '<testsuite name="%s" tests="1" failures="0" errors="0" skipped="0">\n' "$classname"
        printf '  <testcase name="%s" classname="%s" />\n' "$method" "$classname"
      fi
      printf '%s\n' '</testsuite>'
    } > "$file"
  }

  # Both gating phases green -> PASS.
  assert_verdict "nf+bootstrap green"                 PASS 0 PASS PASS
  # A gating phase red -> FAIL.
  assert_verdict "nf red"                             FAIL 1 FAIL PASS
  assert_verdict "bootstrap red"                      FAIL 1 PASS FAIL
  assert_verdict "both gating red"                    FAIL 1 FAIL FAIL
  # Exact #1991 direction: infra is distinct unless a substantive product
  # failure also exists, in which case product-red dominates.
  assert_verdict "network-fault device offline"       INFRA 2 INFRA PASS
  assert_verdict "bootstrap device offline"           INFRA 2 PASS INFRA
  assert_verdict "product red dominates infra"        FAIL 1 FAIL INFRA

  out="$(apply_gating_coverage PASS complete)" && rc=0 || rc=$?
  if [[ "$out" != PASS || "$rc" != 0 ]]; then
    printf 'FAIL [apply complete]: expected PASS(rc=0) got %s(rc=%s)\n' "$out" "$rc"
    failures=$((failures + 1))
  else
    echo "ok   [apply complete] -> PASS"
  fi
  out="$(apply_gating_coverage PASS incomplete)" && rc=0 || rc=$?
  if [[ "$out" != INCOMPLETE || "$rc" != 3 ]]; then
    printf 'FAIL [apply skip]: expected INCOMPLETE(rc=3) got %s(rc=%s)\n' "$out" "$rc"
    failures=$((failures + 1))
  else
    echo "ok   [apply skip] -> INCOMPLETE"
  fi
  out="$(apply_gating_coverage PASS unknown)" && rc=0 || rc=$?
  if [[ "$out" != INCOMPLETE || "$rc" != 3 ]]; then
    printf 'FAIL [apply unknown]: expected INCOMPLETE(rc=3) got %s(rc=%s)\n' "$out" "$rc"
    failures=$((failures + 1))
  else
    echo "ok   [apply unknown] -> INCOMPLETE"
  fi
  out="$(apply_gating_coverage FAIL incomplete)" && rc=0 || rc=$?
  if [[ "$out" != FAIL || "$rc" != 1 ]]; then
    printf 'FAIL [apply fail dominates]: expected FAIL(rc=1) got %s(rc=%s)\n' "$out" "$rc"
    failures=$((failures + 1))
  else
    echo "ok   [apply fail dominates skip]"
  fi
  out="$(apply_gating_coverage INFRA incomplete)" && rc=0 || rc=$?
  if [[ "$out" != INFRA || "$rc" != 2 ]]; then
    printf 'FAIL [apply infra dominates]: expected INFRA(rc=2) got %s(rc=%s)\n' "$out" "$rc"
    failures=$((failures + 1))
  else
    echo "ok   [apply infra dominates skip]"
  fi

  # THE load-bearing #1201 direction, at the FILE level: the journey suite and
  # the #822 expected-fail lane are red, but the fault phases passed AND the
  # gating set was fully evaluated -> the written verdict is PASS and does NOT
  # reflect the unrelated red.
  echo
  echo "--- file-level dry run: fault phases green, journey + expected-fail RED ---"
  tmp="$(mktemp)"
  coverage_tmp="$(mktemp)"
  write_complete_coverage "$coverage_tmp"
  write_fault_verdict_file "$tmp" PASS 0 PASS 0 FAIL 1 "$coverage_tmp"
  cat "$tmp"
  if grep -q '^fault_verdict=PASS$' "$tmp"; then
    echo "ok   [file] fault_verdict=PASS despite expected-fail lane red"
  else
    echo "FAIL [file] fault_verdict should be PASS despite expected-fail lane red"
    failures=$((failures + 1))
  fi
  rm -f "$tmp" "$coverage_tmp"

  echo
  echo "--- file-level dry run: fault phase device-offline -> verdict INFRA ---"
  tmp="$(mktemp)"
  write_fault_verdict_file "$tmp" INFRA 1 PASS 0 FAIL 1
  cat "$tmp"
  if grep -q '^fault_verdict=INFRA$' "$tmp"; then
    echo "ok   [file] fault_verdict=INFRA for device-offline fault phase"
  else
    echo "FAIL [file] fault_verdict should distinguish device-offline infrastructure"
    failures=$((failures + 1))
  fi
  rm -f "$tmp"

  echo
  echo "--- file-level dry run: a fault phase itself RED -> verdict FAIL ---"
  tmp="$(mktemp)"
  write_fault_verdict_file "$tmp" FAIL 1 PASS 0 FAIL 1
  cat "$tmp"
  if grep -q '^fault_verdict=FAIL$' "$tmp"; then
    echo "ok   [file] fault_verdict=FAIL when the network-fault phase failed"
  else
    echo "FAIL [file] fault_verdict should be FAIL when the network-fault phase failed"
    failures=$((failures + 1))
  fi
  rm -f "$tmp"

  echo
  echo "--- #2141: omitted coverage file refuses a clean PASS (fail closed) ---"
  tmp="$(mktemp)"
  write_fault_verdict_file "$tmp" PASS 0 PASS 0 FAIL 1
  cat "$tmp"
  assert_file_field "no coverage file" "$tmp" fault_verdict INCOMPLETE
  assert_file_field "no coverage file marks unknown" "$tmp" gating_coverage unknown
  rm -f "$tmp"

  echo
  echo "--- #2141: skipped brief-cut method is listed and refuses PASS ---"
  fixture_root="$(mktemp -d)"
  local gating_expected=(
    "$BRIEF_CLASS"
    "com.pocketshell.app.proof.WithinGraceResumeRideThroughE2eTest"
    "com.pocketshell.app.proof.StaleLeaseSwitchRecoveryE2eTest"
    "com.pocketshell.app.proof.DisconnectFlapSoakE2eTest"
    "com.pocketshell.app.proof.DisconnectBlackholeE2eTest"
    "com.pocketshell.app.proof.NetworkLatencyModelE2eTest"
    "com.pocketshell.app.proof.PacketLossNetworkFaultE2eTest"
    "com.pocketshell.app.proof.OutboundAttachmentOffsetResumeJourneyE2eTest"
    "com.pocketshell.app.proof.ColdDialUnderBandwidthLimitE2eTest"
    "com.pocketshell.app.proof.CodexRedrawOverflowReconnectE2eTest"
    "com.pocketshell.app.proof.PushResumeDeadSocketMainResponsiveE2eTest"
    "com.pocketshell.app.proof.NatIdleMappingSurvivalE2eTest"
    "com.pocketshell.app.proof.MobileLatencyStormSelfInflictedCloseE2eTest"
  )
  local cls
  for cls in "${gating_expected[@]}"; do
    if [[ "$cls" == "$BRIEF_CLASS" ]]; then
      {
        printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
        printf '<testsuite name="%s" tests="2" failures="0" errors="0" skipped="1">\n' "$cls"
        printf '  <testcase name="%s" classname="%s"><skipped /></testcase>\n' \
          "$BRIEF_METHOD" "$cls"
        printf '  <testcase name="%s" classname="%s" />\n' "$SUSTAINED_METHOD" "$cls"
        printf '%s\n' '</testsuite>'
      } >"$fixture_root/TEST-$cls.xml"
    else
      write_testcase "$fixture_root/TEST-$cls.xml" "$cls" "loadBearing" pass
    fi
  done
  coverage_tmp="$(mktemp)"
  assess_fault_gating_coverage "$fixture_root" "${gating_expected[@]}" >"$coverage_tmp" || true
  cat "$coverage_tmp"
  assert_file_field "brief skip coverage" "$coverage_tmp" gating_coverage incomplete
  assert_file_field "brief skip expected" "$coverage_tmp" expected_gating_classes 13
  assert_file_field "brief skip executed" "$coverage_tmp" executed_gating_classes 13
  assert_file_field "brief skip missing" "$coverage_tmp" missing_gating_classes ""
  assert_file_field "brief skip methods" "$coverage_tmp" skipped_gating_methods \
    "${BRIEF_CLASS}#${BRIEF_METHOD}"
  tmp="$(mktemp)"
  write_fault_verdict_file "$tmp" PASS 0 PASS 0 FAIL 1 "$coverage_tmp"
  cat "$tmp"
  assert_file_field "brief skip verdict" "$tmp" fault_verdict INCOMPLETE
  assert_file_field "brief skip listed in verdict" "$tmp" skipped_gating_methods \
    "${BRIEF_CLASS}#${BRIEF_METHOD}"
  rm -f "$tmp" "$coverage_tmp"

  echo
  echo "--- #2141: 1 of 13 classes executed refuses PASS ---"
  local truncated_root
  truncated_root="$(mktemp -d)"
  write_testcase \
    "$truncated_root/TEST-only.xml" \
    "$BRIEF_CLASS" "$SUSTAINED_METHOD" pass
  coverage_tmp="$(mktemp)"
  assess_fault_gating_coverage "$truncated_root" "${gating_expected[@]}" >"$coverage_tmp" || true
  cat "$coverage_tmp"
  assert_file_field "truncated coverage" "$coverage_tmp" gating_coverage incomplete
  assert_file_field "truncated expected" "$coverage_tmp" expected_gating_classes 13
  assert_file_field "truncated executed" "$coverage_tmp" executed_gating_classes 1
  if grep -q "^missing_gating_classes=.*WithinGraceResumeRideThroughE2eTest" "$coverage_tmp"; then
    echo "ok   [truncated missing] lists unreached gating classes"
  else
    echo "FAIL [truncated missing] did not list unreached gating classes"
    failures=$((failures + 1))
  fi
  tmp="$(mktemp)"
  write_fault_verdict_file "$tmp" PASS 0 PASS 0 FAIL 1 "$coverage_tmp"
  assert_file_field "truncated verdict" "$tmp" fault_verdict INCOMPLETE
  assert_file_field "truncated executed in verdict" "$tmp" executed_gating_classes 1
  assert_file_field "truncated expected in verdict" "$tmp" expected_gating_classes 13
  rm -f "$tmp" "$coverage_tmp"
  rm -rf "$truncated_root"

  echo
  echo "--- #2141: complete 13/13 with no skips may PASS ---"
  local complete_root
  complete_root="$(mktemp -d)"
  for cls in "${gating_expected[@]}"; do
    if [[ "$cls" == "$BRIEF_CLASS" ]]; then
      {
        printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
        printf '<testsuite name="%s" tests="2" failures="0" errors="0" skipped="0">\n' "$cls"
        printf '  <testcase name="%s" classname="%s" />\n' "$BRIEF_METHOD" "$cls"
        printf '  <testcase name="%s" classname="%s" />\n' "$SUSTAINED_METHOD" "$cls"
        printf '%s\n' '</testsuite>'
      } >"$complete_root/TEST-$cls.xml"
    else
      write_testcase "$complete_root/TEST-$cls.xml" "$cls" "loadBearing" pass
    fi
  done
  coverage_tmp="$(mktemp)"
  if assess_fault_gating_coverage "$complete_root" "${gating_expected[@]}" >"$coverage_tmp"; then
    echo "ok   [complete assess] returns 0"
  else
    echo "FAIL [complete assess] should return 0"
    failures=$((failures + 1))
  fi
  assert_file_field "complete coverage" "$coverage_tmp" gating_coverage complete
  assert_file_field "complete skipped" "$coverage_tmp" skipped_gating_methods ""
  tmp="$(mktemp)"
  write_fault_verdict_file "$tmp" PASS 0 PASS 0 FAIL 1 "$coverage_tmp"
  assert_file_field "complete verdict" "$tmp" fault_verdict PASS
  rm -f "$tmp" "$coverage_tmp"
  rm -rf "$complete_root" "$fixture_root"

  echo
  echo "--- #2141 G6: ignoring <skipped> greens the brief-cut fixture ---"
  local lib_src mutant
  lib_src="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/nightly-fault-verdict.sh"
  if [[ ! -f "$lib_src" ]]; then
    lib_src="${BASH_SOURCE[0]}"
  fi
  mutant="$(mktemp)"
  if ! python3 - "$lib_src" "$mutant" <<'PY'
from pathlib import Path
import sys

src, dest = sys.argv[1:]
text = Path(src).read_text()
old = 'skip_tag = "skipped"'
idx = text.find(old)
if idx < 0:
    raise SystemExit("G6 needle missing from production parser")
if "_fault_verdict_self_test" in text[:idx]:
    raise SystemExit("G6 needle is in the self-test, not the production parser")
Path(dest).write_text(text[:idx] + 'skip_tag = "skip-disabled"' + text[idx + len(old):])
PY
  then
    echo "FAIL [G6 needle]: could not plant the skip-detector mutant"
    failures=$((failures + 1))
  else
    fixture_root="$(mktemp -d)"
    {
      printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
      printf '<testsuite name="%s" tests="1" failures="0" errors="0" skipped="1">\n' "$BRIEF_CLASS"
      printf '  <testcase name="%s" classname="%s"><skipped /></testcase>\n' \
        "$BRIEF_METHOD" "$BRIEF_CLASS"
      printf '%s\n' '</testsuite>'
    } >"$fixture_root/TEST-brief.xml"
    local baseline_cov mutant_cov
    baseline_cov="$(mktemp)"
    mutant_cov="$(mktemp)"
    assess_fault_gating_coverage "$fixture_root" "$BRIEF_CLASS" >"$baseline_cov" || true
    bash -c '
      set -uo pipefail
      # shellcheck disable=SC1090
      source "$1"
      assess_fault_gating_coverage "$2" "$3"
    ' _ "$mutant" "$fixture_root" "$BRIEF_CLASS" >"$mutant_cov" || true
    assert_file_field "G6 baseline skip" "$baseline_cov" gating_coverage incomplete
    assert_file_field "G6 mutant hides skip" "$mutant_cov" gating_coverage complete
    rm -f "$mutant" "$baseline_cov" "$mutant_cov"
    rm -rf "$fixture_root"
  fi

  echo
  echo "--- #2141 G6: dropping executed==expected greens a 1-of-13 run ---"
  mutant="$(mktemp)"
  if ! python3 - "$lib_src" "$mutant" <<'PY'
from pathlib import Path
import sys

src, dest = sys.argv[1:]
text = Path(src).read_text()
old = '''        && "$executed_n" -eq "$expected_n" \\
        && "${#missing_list[@]}" -eq 0 \\'''
new = '''        && "$executed_n" -ge 1 \\
        && true \\'''
idx = text.find(old)
if idx < 0:
    raise SystemExit("G6 executed-vs-expected needle missing")
if "_fault_verdict_self_test" in text[:idx]:
    raise SystemExit("G6 executed-vs-expected needle is in the self-test")
Path(dest).write_text(text[:idx] + new + text[idx + len(old):])
PY
  then
    echo "FAIL [G6 executed-vs-expected]: could not plant the mutant"
    failures=$((failures + 1))
  else
    truncated_root="$(mktemp -d)"
    write_testcase \
      "$truncated_root/TEST-only.xml" \
      "$BRIEF_CLASS" "$SUSTAINED_METHOD" pass
    local baseline_trunc mutant_trunc
    baseline_trunc="$(mktemp)"
    mutant_trunc="$(mktemp)"
    assess_fault_gating_coverage "$truncated_root" "${gating_expected[@]}" \
      >"$baseline_trunc" || true
    bash -c '
      set -uo pipefail
      # shellcheck disable=SC1090
      source "$1"
      shift
      assess_fault_gating_coverage "$@"
    ' _ "$mutant" "$truncated_root" "${gating_expected[@]}" >"$mutant_trunc" || true
    assert_file_field "G6 baseline truncated" "$baseline_trunc" gating_coverage incomplete
    assert_file_field "G6 mutant hides truncated" "$mutant_trunc" gating_coverage complete
    rm -f "$mutant" "$baseline_trunc" "$mutant_trunc"
    rm -rf "$truncated_root"
  fi

  echo
  if [[ "$failures" -eq 0 ]]; then
    echo "FAULT-VERDICT SELF-TEST PASS: all cases produced the expected verdict."
    return 0
  fi
  echo "FAULT-VERDICT SELF-TEST FAIL: $failures case(s) wrong."
  return 1
}

# Allow running directly for the self-test; stays a pure library when sourced.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  if [[ "${1:-}" == "--self-test" ]]; then
    _fault_verdict_self_test
    exit $?
  fi
  echo "usage: source this file, or run with --self-test" >&2
  exit 2
fi
