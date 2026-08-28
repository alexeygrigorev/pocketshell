#!/usr/bin/env bash
# Issue #2374: does this shard's summary name a class that failed BOTH attempts
# for a reason the BUILD attributions do not already explain?
#
# WHY. `verdict_reason_for` in .github/workflows/tests.yml's classify step
# prefers the most specific attribution a shard carries, and #1814's
# `cold_build_timeout` / #1840's `build_level_failure` sit at the top of that
# order. That order is right when a build artefact is the ONLY thing the shard
# has to say. It is wrong when the same shard also failed real journeys.
#
# The scheduled full-suite run 33157272170, shard 2: EIGHT journey classes ran
# twice and were listed under `Failed BOTH attempts`, the suite hit its 4200s
# budget, and the LAST class's two attempts were cut while Gradle was still
# building (289s of budget left, daemons restarted after eight aborted builds).
# The shard's verdict reason came out `cold_build_timeout` — "investigate the
# build, not the journey" — while eight genuine product failures sat in the same
# summary. Together with the sibling `insufficient_remaining_budget` denial that
# is why the whole batch was triaged as a recurrence of #1814/#1833 rather than
# as the product regressions it was.
#
# THE DISCRIMINATOR, and why it is not simply "the summary has a failed-both
# section". #1840 exists precisely because a class whose Gradle BUILD died is
# ALSO listed under `Failed BOTH attempts` — the whole point of that issue was
# that a self-inflicted build cascade got typed as a product defect. So the test
# is set SUBTRACTION: a genuine journey failure is a failed-both class that
# neither build attribution names. That keeps #1814 and #1840 intact (a shard
# whose only failed-both classes ARE the build victims still reports the build
# reason) while stopping a build artefact from speaking for unrelated failures.
#
# Reporting only: this changes no verdict's severity — every case here is
# already RED — and it always exits 0, so missing evidence degrades to `false`
# (the pre-#2374 behaviour) rather than breaking the classifier.
#
# Usage:
#   ci-journey-genuine-journey-failure.sh SUMMARY_MD \
#     [BUILD_PHASE_CLASSES_CSV] [BUILD_FAILURE_CLASSES_CSV]
#
# Output (key=value on stdout):
#   genuine_journey_failure=true|false
#   genuine_journey_failure_classes=<comma-separated FQCNs, or empty>
set -uo pipefail

summary="${1:-}"
build_phase_classes="${2:-}"
build_failure_classes="${3:-}"

genuine=""

if [[ -n "$summary" && -f "$summary" ]]; then
  # The `- ` bullets under the `Failed BOTH attempts` header, backticks stripped.
  #
  # THE SECTION MUST BE TERMINATED, not read to EOF. summary.md keeps writing
  # after this section (scripts/ci-journey-summary-functions.sh): #2355's
  # `Quarantined failures (non-blocking — issue #2355 / policy D36):` and #2143's
  # `Shared SSH/tmux fixture was WEDGED during these classes …:` both follow it
  # and both use `- ` bullets. An unterminated scan swallows them, and then:
  #   * a QUARANTINED failure — deliberately excluded from the blocking section,
  #     and live on main today (scripts/journey-quarantine.txt) — reads as a
  #     genuine journey failure and flips `build_level_failure`/
  #     `cold_build_timeout` back to a product-defect reason in exactly the
  #     #1814/#1840 shape the subtraction below exists to preserve;
  #   * #2355's invariant that the quarantine wording "deliberately avoids every
  #     phrase the classifier keys on" is defeated by re-coupling this classifier
  #     to the section that follows it.
  # So `f` is cleared at the first non-bullet, non-blank line — i.e. at the next
  # section's header — and only bullets INSIDE the failed-both section are read.
  #
  # Each bullet is also cut at its first space, so what comes out is an FQCN and
  # never a bullet's trailing metadata. #1827's core-terminal bullets are
  # `- `<class>` (<label> — status <X>)` and the quarantine bullets carry
  # `(tracked: …, expires: …) — <reason>`; a class name is the only thing the
  # set subtraction below (and the classify step's annotation) can use.
  while IFS= read -r class; do
    [[ -n "$class" ]] || continue
    # A class named by either build attribution is explained by it (#1814/#1840).
    case ",${build_phase_classes}," in *",${class},"*) continue ;; esac
    case ",${build_failure_classes}," in *",${class},"*) continue ;; esac
    case ",${genuine}," in *",${class},"*) continue ;; esac
    genuine="${genuine:+$genuine,}$class"
  done < <(
    awk '/Failed BOTH attempts/ { f = 1; next }
         f && !/^- / && NF       { f = 0 }
         f && /^- / {
           gsub(/`/, "")
           sub(/^- /, "")
           sub(/[[:space:]].*$/, "")
           if (length($0)) print
         }' "$summary" 2>/dev/null
  )
fi

if [[ -n "$genuine" ]]; then
  printf 'genuine_journey_failure=true\n'
else
  printf 'genuine_journey_failure=false\n'
fi
printf 'genuine_journey_failure_classes=%s\n' "$genuine"
