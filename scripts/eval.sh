#!/usr/bin/env bash
# Runs the evaluation harness for real against the app's own RagPipeline (docs/roadmap.md Phase 4)
# — no mocking, same discipline as everything else in this project. Builds the app jar if it
# doesn't exist yet, then runs it under the eval-cli profile that app's EvalCliRunner is gated on.
# Every argument is forwarded through, e.g.:
#   ./scripts/eval.sh --profiles=dense-only,hybrid --repetitions=3
#   ./scripts/eval.sh --profiles=hybrid-rerank --judge --hardware="M4 Pro, 48GB"
#
# Requires a running Postgres reachable per the active Spring profile (e.g. docker compose up
# postgres) — this hits the real database, same as any other app run. Defaults to the `recorded`
# provider profile (no live model needed); set SPRING_PROFILES_ACTIVE to use `lmstudio` instead for
# a real quality report, e.g. SPRING_PROFILES_ACTIVE=lmstudio,eval-cli ./scripts/eval.sh ...
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${REPO_ROOT}/app/target/ai-engineering-lab.jar"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-recorded,eval-cli}"

log() { printf 'eval: %s\n' "$*"; }

if [ ! -f "${JAR}" ]; then
  log "No app jar at ${JAR}, building it first ..."
  (cd "${REPO_ROOT}" && ./mvnw -B -q -pl app -am package -DskipTests)
fi

log "Running with SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}"
java -jar "${JAR}" "--spring.profiles.active=${SPRING_PROFILES_ACTIVE}" "$@"
