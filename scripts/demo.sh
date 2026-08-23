#!/usr/bin/env bash
# A scripted, reproducible walkthrough of every capability in docs/roadmap.md's phase table — run
# it against a live `docker compose up` stack and narrate along with DEMO.md, or record your screen
# while it runs. This script is the "recorded demo" deliverable's substitute for an actual video
# file, which nothing in this environment can produce (docs/roadmap.md Phase 8) — it is real,
# reproducible automation against the live API, not a mock.
#
# Defaults to the `recorded` provider profile's deterministic fixtures (same profile CI uses), so it
# runs the same way every time without a live model server. Point APP_URL at a `lmstudio`-profile
# instance if you want to narrate real model responses instead — the flow is identical either way
# except the exact chat wording.
set -euo pipefail

APP_URL="${APP_URL:-http://localhost:8080}"
POLL_TIMEOUT_S="${POLL_TIMEOUT_S:-60}"

log()   { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
note()  { printf '   %s\n' "$*"; }
fail()  { printf 'demo: %s\n' "$*" >&2; exit 1; }

jqf() {
  # $1=json body, $2=jq filter — this project's demo scripts don't hard-require jq (seed.sh's own
  # convention), but jq makes this script far more readable, so ask for it plainly instead of a
  # grep-based fallback for every one of the ~10 fields this script reads.
  command -v jq >/dev/null 2>&1 || fail "jq is required by demo.sh (not by the app itself). Install it and re-run."
  printf '%s' "$1" | jq -r "$2"
}

# Uploads $1 as a document (content = $1, the same "content is exactly the query text" trick
# app/src/test/.../RagFlowIntegrationTest.java and WorkflowRunIntegrationTest.java use, so
# RecordedEmbeddingProvider's hash-seeded embeddings guarantee a deterministic exact-match retrieval
# hit under the `recorded` profile) via the real POST /api/v1/documents API, then polls until
# INDEXED.
upload_and_wait() {
  local text="$1" title="$2" file location job stage elapsed=0
  file="$(mktemp /tmp/ailab-demo-doc.XXXXXX.md)"
  printf '%s\n' "${text}" > "${file}"
  location="$(curl -fsS -D - -o /dev/null -X POST "${APP_URL}/api/v1/documents" \
    -F "file=@${file};type=text/markdown" -F "title=${title}" \
    | { grep -i '^location:' || true; } | sed -E 's/^[Ll]ocation:[[:space:]]*//' | tr -d '\r')"
  rm -f "${file}"
  [ -n "${location}" ] || { note "'${title}' already indexed (content-hash dedup)."; return 0; }
  note "Job at ${location}, polling for INDEXED ..."
  while true; do
    job="$(curl -fsS "${APP_URL}${location}")"
    stage="$(jqf "${job}" '.stage')"
    [ "${stage}" != "INDEXED" ] || { note "Indexed."; return 0; }
    [ "${stage}" != "FAILED" ] || fail "Ingestion FAILED: ${job}"
    [ "${elapsed}" -lt "${POLL_TIMEOUT_S}" ] || fail "Timed out waiting for ingestion (last stage: ${stage})"
    sleep 2
    elapsed=$((elapsed + 2))
  done
}

curl -fsS --max-time 5 "${APP_URL}/actuator/health" >/dev/null 2>&1 \
  || fail "App is not reachable at ${APP_URL}. Start it (docker compose up) first."

log "1. Plain chat"
note "POST /api/v1/conversations (no ragProfile — plain chat, unchanged since Phase 1)"
CONV_ID="$(jqf "$(curl -fsS -X POST "${APP_URL}/api/v1/conversations" -H 'Content-Type: application/json' -d '{}')" '.id')"
note "Conversation ${CONV_ID}"
note "POST .../messages \"hello\" — streaming the reply token by token"
curl -fsS -N -X POST "${APP_URL}/api/v1/conversations/${CONV_ID}/messages" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"content":"hello"}' | { grep -E '^(event|data):' || true; }

log "2. Ingestion + hybrid RAG with citations"
note "Uploading a short synthetic document via POST /api/v1/documents (real pipeline: parse, chunk, embed, index over Kafka)"
# Same query app/src/test/.../RagFlowIntegrationTest.java uses, and — like it — content equal to the
# query text, so RecordedEmbeddingProvider's hash-seeded embeddings guarantee a retrieval hit. The
# `recorded` chat fixture for this exact text includes real [1]/[2] citation markers (Phase 3).
DOC_TEXT="What does consumer lag measure?"
upload_and_wait "${DOC_TEXT}" "demo-doc"

note "POST /api/v1/conversations with ragProfile=dense-only, then asking a question the doc answers"
RAG_CONV_ID="$(jqf "$(curl -fsS -X POST "${APP_URL}/api/v1/conversations" -H 'Content-Type: application/json' -d '{"ragProfile":"dense-only"}')" '.id')"
curl -fsS -N -X POST "${APP_URL}/api/v1/conversations/${RAG_CONV_ID}/messages" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d "{\"content\":\"${DOC_TEXT}\"}" | { grep -E '^(event|data):' || true; }
note "A 'citation' event above points back at the uploaded document."

log "3. Tool calling with confirmation, via an MCP-sourced tool"
note "MCP-client tools are gated on every call, not just the turn's first one (docs/threat-model.md T9)"
note "GET /api/v1/tools — the registry"
TOOLS_JSON="$(curl -fsS "${APP_URL}/api/v1/tools")"
jqf "${TOOLS_JSON}" '[.[] | .name]'

if [ "$(jqf "${TOOLS_JSON}" '[.[] | select(.name | startswith("mcp:"))] | length')" = "0" ]; then
  note "No mcp:* tool is registered — this app ships with ai.mcp.client.enabled: false by default"
  note "(no real external MCP server exists to point at, docs/adr/0011-mcp-tool-exposure-boundaries.md)."
  note "Skipping the live MCP call. To exercise it, start the app with the self-connect overrides"
  note "documented in that ADR's live-verification section (SPRING_AI_MCP_CLIENT_ENABLED=true,"
  note "SPRING_AI_MCP_SERVER_NAME=self, and a streamable-http connection back at its own port)."
else
  TOOL_CONV_ID="$(jqf "$(curl -fsS -X POST "${APP_URL}/api/v1/conversations" -H 'Content-Type: application/json' -d '{}')" '.id')"
  STREAM_FILE="$(mktemp)"
  note "POST .../messages \"what is 6 times 7 via mcp\" — backgrounded so we can confirm mid-stream"
  curl -fsS -N -X POST "${APP_URL}/api/v1/conversations/${TOOL_CONV_ID}/messages" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d '{"content":"what is 6 times 7 via mcp"}' > "${STREAM_FILE}" 2>&1 &
  STREAM_PID=$!

  elapsed=0
  CALL_ID=""
  while [ -z "${CALL_ID}" ]; do
    CALL_ID="$({ grep -o '"callId":"[^"]*"' "${STREAM_FILE}" || true; } | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"
    [ -n "${CALL_ID}" ] && break
    [ "${elapsed}" -lt 20 ] || { kill "${STREAM_PID}" 2>/dev/null || true; fail "Never saw tool_call_pending"; }
    sleep 1
    elapsed=$((elapsed + 1))
  done
  note "Paused pending confirmation: callId=${CALL_ID}"
  note "POST /api/v1/tool-calls/{callId}:confirm {approved:true}"
  curl -fsS -X POST "${APP_URL}/api/v1/tool-calls/${CALL_ID}:confirm" \
    -H 'Content-Type: application/json' -d '{"approved":true}' >/dev/null
  wait "${STREAM_PID}" || true
  grep -E '^(event|data):' "${STREAM_FILE}" || true
  rm -f "${STREAM_FILE}"
fi

log "4. Agentic workflow (documentation-research), persisted and resumable"
# Same query (and sub-query chunk-seeding trick) app/src/test/java/.../WorkflowRunIntegrationTest.java
# uses — the `recorded` profile's plan/extract/synthesise fixtures were built around this exact
# string (Phase 6), and each sub-query needs its own exact-content chunk for retrieval to find it
# deterministically.
WORKFLOW_QUERY="What is the boiling point of water and what is dry ice made of?"
upload_and_wait "What is the boiling point of water?" "demo-subquery-a"
upload_and_wait "What is dry ice made of?" "demo-subquery-b"
note "POST /api/v1/workflows/documentation-research/runs"
RUN_LOCATION="$(curl -fsS -D - -o /dev/null -X POST "${APP_URL}/api/v1/workflows/documentation-research/runs" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"${WORKFLOW_QUERY}\"}" \
  | { grep -i '^location:' || true; } | sed -E 's/^[Ll]ocation:[[:space:]]*//' | tr -d '\r')"
[ -n "${RUN_LOCATION}" ] || fail "No Location header on the workflow run response"
note "Run at ${RUN_LOCATION}, polling until terminal (a real restart mid-run resumes from the last completed step — docs/adr/0010-agent-orchestration.md; not exercised by this script, see that ADR's own live-verification narrative)"
elapsed=0
while true; do
  RUN="$(curl -fsS "${APP_URL}${RUN_LOCATION}")"
  STATUS="$(jqf "${RUN}" '.status')"
  case "${STATUS}" in
    SUCCEEDED|FAILED) note "Run ${STATUS}."; break ;;
  esac
  [ "${elapsed}" -lt "${POLL_TIMEOUT_S}" ] || fail "Timed out waiting for the workflow run (last status: ${STATUS})"
  sleep 2
  elapsed=$((elapsed + 2))
done
note "Steps:"
jqf "${RUN}" '.steps[] | "  - \(.name): \(.status)"'

log "Demo complete."
