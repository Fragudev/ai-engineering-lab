#!/usr/bin/env bash
# Fails when a new evaluation report regresses past docs/ai-evaluation.md §6's thresholds versus the
# committed baseline, for every RAG profile present in both. Run by
# .github/workflows/nightly-eval.yml after scripts/eval.sh produces its JSON sidecar report
# (modules/evaluation/.../internal/ReportWriter#renderJson).
#
# Requires jq, unlike this project's other scripts (which fall back to grep for portability across
# arbitrary dev machines): correctly matching profiles by name across two JSON documents and diffing
# nested floats is not something a grep fallback can do safely, and this script only ever runs on
# the CI runner, where jq is preinstalled.
set -euo pipefail

command -v jq >/dev/null 2>&1 || { echo "check-eval-regression: jq is required." >&2; exit 1; }

BASELINE="${1:?usage: check-eval-regression.sh <baseline.json> <report.json>}"
REPORT="${2:?usage: check-eval-regression.sh <baseline.json> <report.json>}"

[ -f "${REPORT}" ] || { echo "check-eval-regression: no report at ${REPORT}" >&2; exit 1; }

if [ ! -f "${BASELINE}" ]; then
  echo "check-eval-regression: no baseline at ${BASELINE} yet — nothing to compare against."
  echo "check-eval-regression: once a report's numbers look right, commit it as the baseline:" \
       "cp <report>.json ${BASELINE}"
  exit 0
fi

# docs/ai-evaluation.md §6: recall@5 down >5pts, citation precision down >5pts, gate abstention
# down >10pts. Metrics are 0..1 fractions here, so e.g. "5pts" means a drop of 0.05.
#
# Gates on gateAbstentionRate, renamed from abstentionAccuracy in post-roadmap review issue #61.
# What it watches is unchanged and still worth watching: a sudden drop means the deterministic
# retrieval gate stopped firing where it used to, i.e. the threshold moved. It is NOT a
# hallucination rate — see docs/ai-evaluation.md §3. The metric that would catch a hallucination
# regression is refusalCorrectness, which is judge-scored and therefore absent unless --judge ran,
# so it is deliberately not gated here: a nightly job cannot fail a build on a number that is
# usually not measured.
RECALL_THRESHOLD=0.05
CITATION_PRECISION_THRESHOLD=0.05
ABSTENTION_THRESHOLD=0.10

FAILED=0

check() {
  local profile="$1" label="$2" baseline="$3" new="$4" threshold="$5"
  if [ -z "${baseline}" ] || [ "${baseline}" = "null" ]; then
    echo "check-eval-regression: [${profile}] ${label} has no baseline value — skipping."
    return 0
  fi
  if [ -z "${new}" ] || [ "${new}" = "null" ]; then
    echo "check-eval-regression: [${profile}] ${label} is undefined (null) in the new report" >&2
    FAILED=1
    return 0
  fi
  local drop regressed
  drop="$(awk -v b="${baseline}" -v n="${new}" 'BEGIN { printf "%.4f", b - n }')"
  regressed="$(awk -v d="${drop}" -v t="${threshold}" 'BEGIN { print (d > t) ? "1" : "0" }')"
  if [ "${regressed}" = "1" ]; then
    echo "check-eval-regression: REGRESSION [${profile}] ${label}: ${baseline} -> ${new} (down ${drop}, threshold ${threshold})" >&2
    FAILED=1
  else
    echo "check-eval-regression: [${profile}] ${label}: ${baseline} -> ${new} (ok, delta ${drop})"
  fi
}

PROFILES="$(jq -r '.profiles[].ragProfile' "${REPORT}")"

while IFS= read -r profile; do
  [ -n "${profile}" ] || continue

  baseline_recall="$(jq -r --arg p "${profile}" '.profiles[] | select(.ragProfile == $p) | .recallAtK.mean' "${BASELINE}")"
  baseline_precision="$(jq -r --arg p "${profile}" '.profiles[] | select(.ragProfile == $p) | .citationPrecision.mean' "${BASELINE}")"
  baseline_abstention="$(jq -r --arg p "${profile}" '.profiles[] | select(.ragProfile == $p) | .gateAbstentionRate.mean' "${BASELINE}")"

  if [ -z "${baseline_recall}" ]; then
    echo "check-eval-regression: [${profile}] not present in baseline — skipping (first run for this profile)."
    continue
  fi

  # Coverage gate: a report that skipped cases (a hung/failing model call — EvalRunner logs and
  # continues) has metrics that are a mean over a subsample, so comparing them to a full-run
  # baseline is not a valid regression check in either direction. Warn loudly and skip this
  # profile rather than emit a falsely reassuring "ok, delta ...". This is the #65/#67 failure
  # mode; the ReportWriter Markdown carries the same warning for a human reader.
  cov_completed="$(jq -r --arg p "${profile}" '.profiles[] | select(.ragProfile == $p) | .coverage.completed // empty' "${REPORT}")"
  cov_attempted="$(jq -r --arg p "${profile}" '.profiles[] | select(.ragProfile == $p) | .coverage.attempted // empty' "${REPORT}")"
  if [ -n "${cov_attempted}" ] && [ "${cov_completed}" != "${cov_attempted}" ]; then
    echo "check-eval-regression: WARNING [${profile}] incomplete coverage ${cov_completed}/${cov_attempted} —" \
         "metrics are a subsample; skipping regression comparison for this profile." >&2
    continue
  fi

  new_recall="$(jq -r --arg p "${profile}" '.profiles[] | select(.ragProfile == $p) | .recallAtK.mean' "${REPORT}")"
  new_precision="$(jq -r --arg p "${profile}" '.profiles[] | select(.ragProfile == $p) | .citationPrecision.mean' "${REPORT}")"
  new_abstention="$(jq -r --arg p "${profile}" '.profiles[] | select(.ragProfile == $p) | .gateAbstentionRate.mean' "${REPORT}")"

  check "${profile}" "recall@k" "${baseline_recall}" "${new_recall}" "${RECALL_THRESHOLD}"
  check "${profile}" "citation precision" "${baseline_precision}" "${new_precision}" "${CITATION_PRECISION_THRESHOLD}"
  check "${profile}" "gate abstention" "${baseline_abstention}" "${new_abstention}" "${ABSTENTION_THRESHOLD}"
done <<< "${PROFILES}"

if [ "${FAILED}" -eq 1 ]; then
  echo "check-eval-regression: one or more profiles regressed beyond threshold." >&2
  exit 1
fi

echo "check-eval-regression: no regressions detected."
