#!/usr/bin/env bash
# Uploads every document scripts/fetch-corpus.sh downloaded into a running app, via the real
# POST /api/v1/documents API (not a direct DB write) — the golden dataset's gold_chunk_ids depend on
# chunks that actually went through the real ingestion pipeline. Each document's title is set to its
# corpus/MANIFEST.yml id (e.g. "pgvector"), which is what lets a golden-dataset case reference a
# stable "pgvector#3" (title + chunk ordinal) instead of a random UUID — see docs/roadmap.md, Phase 4.
set -euo pipefail

APP_URL="${APP_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORPUS_DIR="${CORPUS_DIR:-${REPO_ROOT}/corpus/documents}"
MANIFEST="${REPO_ROOT}/corpus/MANIFEST.yml"
POLL_TIMEOUT_S="${POLL_TIMEOUT_S:-60}"

log()  { printf '%s\n' "$*"; }
fail() { printf 'seed: %s\n' "$*" >&2; exit 1; }

[ -d "${CORPUS_DIR}" ] || fail "No fetched corpus at ${CORPUS_DIR}. Run scripts/fetch-corpus.sh first."
curl -fsS --max-time 5 "${APP_URL}/actuator/health" >/dev/null 2>&1 \
  || fail "App is not reachable at ${APP_URL}. Start it (docker compose up) first."

json_field() {
  # $1=json body, $2=jq filter, $3=grep -o fallback pattern
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$1" | jq -r "$2"
  else
    printf '%s' "$1" | grep -o "$3" | head -1 | sed -E 's/.*"([^"]*)"$/\1/'
  fi
}

# Same fixed-shape awk parse as fetch-corpus.sh, id-only this time.
IDS="$(awk '/^sources:/ { insources=1; next } insources && /^  - id: / { print $3 }' "${MANIFEST}")"
[ -n "${IDS}" ] || fail "No sources found in ${MANIFEST}"

while IFS= read -r id; do
  [ -n "${id}" ] || continue
  file="${CORPUS_DIR}/${id}.md"
  [ -f "${file}" ] || fail "No downloaded file for '${id}' at ${file}. Run scripts/fetch-corpus.sh first."

  log "Uploading '${id}' (${file}) ..."
  response_headers="$(mktemp)"
  body="$(curl -fsS --max-time 30 -D "${response_headers}" -X POST "${APP_URL}/api/v1/documents" \
    -F "file=@${file};type=text/markdown" \
    -F "title=${id}")" || { rm -f "${response_headers}"; fail "Upload failed for '${id}'"; }

  # grep exits 1 when a document is already indexed (200, no Location header) — under this script's
  # own `set -e pipefail`, that would otherwise kill the script instead of reaching the dedup branch
  # below (found and fixed alongside the same bug in scripts/demo.sh, Phase 8).
  location="$({ grep -i '^location:' "${response_headers}" || true; } | sed -E 's/^[Ll]ocation:[[:space:]]*//' | tr -d '\r')"
  rm -f "${response_headers}"

  if [ -z "${location}" ]; then
    log "  '${id}' already indexed (content-hash dedup) — nothing to poll."
    continue
  fi

  log "  Job at ${location}, polling for INDEXED ..."
  elapsed=0
  while true; do
    job="$(curl -fsS --max-time 10 "${APP_URL}${location}")"
    stage="$(json_field "${job}" '.stage' '"stage"[[:space:]]*:[[:space:]]*"[^"]*"')"
    [ "${stage}" != "INDEXED" ] || { log "  '${id}' INDEXED."; break; }
    [ "${stage}" != "FAILED" ] || fail "'${id}' ingestion FAILED: ${job}"
    [ "${elapsed}" -lt "${POLL_TIMEOUT_S}" ] || fail "Timed out waiting for '${id}' to index (last stage: ${stage})"
    sleep 2
    elapsed=$((elapsed + 2))
  done
done <<< "${IDS}"

log "Seeded $(printf '%s\n' "${IDS}" | wc -l | tr -d ' ') document(s)."
