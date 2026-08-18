#!/usr/bin/env bash
# Verifies LM Studio is reachable and correctly configured before anything else starts.
# A reviewer who hits an opaque error on first run never reaches the second screen
# (docs/architecture.md #14).
set -euo pipefail

LM_STUDIO_URL="${LM_STUDIO_URL:-http://localhost:1234}"
EXPECTED_EMBEDDING_DIMENSIONS=1024
EMBEDDING_MODEL_HINT="bge-m3"

log()  { printf '%s\n' "$*"; }
fail() { printf 'bootstrap: %s\n' "$*" >&2; exit 1; }

MODELS_JSON="$(mktemp)"
trap 'rm -f "${MODELS_JSON}"' EXIT

log "Checking LM Studio at ${LM_STUDIO_URL} ..."
if ! curl -fsS --max-time 5 "${LM_STUDIO_URL}/v1/models" -o "${MODELS_JSON}"; then
  fail "LM Studio is not reachable at ${LM_STUDIO_URL}. Start LM Studio, load a chat model and" \
       "an embedding model (${EMBEDDING_MODEL_HINT}), then re-run this script. See README.md, Getting started."
fi

if command -v jq >/dev/null 2>&1; then
  model_ids() { jq -r '.data[].id' "${MODELS_JSON}"; }
else
  model_ids() { grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' "${MODELS_JSON}" | sed -E 's/.*"([^"]*)"$/\1/'; }
fi

MODEL_IDS="$(model_ids || true)"
[ -n "${MODEL_IDS}" ] || fail "LM Studio responded but has no models loaded." \
  "Load a chat model and ${EMBEDDING_MODEL_HINT} in LM Studio."

log "Loaded models:"
printf '  - %s\n' ${MODEL_IDS}

EMBEDDING_MODEL="$(printf '%s\n' "${MODEL_IDS}" | grep -i "${EMBEDDING_MODEL_HINT}" | head -1 || true)"
[ -n "${EMBEDDING_MODEL}" ] || fail "No model matching '${EMBEDDING_MODEL_HINT}' is loaded." \
  "The embedding model is fixed project-wide (docs/adr/0003-persistence-and-vector-store.md) —" \
  "load ${EMBEDDING_MODEL_HINT} in LM Studio."

CHAT_MODEL_COUNT="$(printf '%s\n' "${MODEL_IDS}" | grep -vi "${EMBEDDING_MODEL_HINT}" | grep -c . || true)"
[ "${CHAT_MODEL_COUNT}" -ge 1 ] || fail "No chat model is loaded alongside ${EMBEDDING_MODEL_HINT}." \
  "Load a chat/instruct model too (see README.md for the minimum tier)."

log "Verifying embedding dimensions for '${EMBEDDING_MODEL}' ..."
EMBED_RESPONSE="$(curl -fsS --max-time 15 "${LM_STUDIO_URL}/v1/embeddings" \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"${EMBEDDING_MODEL}\",\"input\":\"bootstrap dimension check\"}")" \
  || fail "Embedding request to '${EMBEDDING_MODEL}' failed."

if command -v jq >/dev/null 2>&1; then
  DIMENSIONS="$(printf '%s' "${EMBED_RESPONSE}" | jq '.data[0].embedding | length')"
else
  DIMENSIONS="$(printf '%s' "${EMBED_RESPONSE}" \
    | grep -o '"embedding"[[:space:]]*:[[:space:]]*\[[^]]*\]' | head -1 \
    | tr ',' '\n' | grep -c '[0-9]')"
fi

[ "${DIMENSIONS}" -eq "${EXPECTED_EMBEDDING_DIMENSIONS}" ] || fail \
  "Embedding model '${EMBEDDING_MODEL}' returned ${DIMENSIONS} dimensions, expected ${EXPECTED_EMBEDDING_DIMENSIONS}." \
  "The database schema is fixed at ${EXPECTED_EMBEDDING_DIMENSIONS} dimensions" \
  "(docs/adr/0003-persistence-and-vector-store.md); changing models requires scripts/reindex.sh."

log "LM Studio OK: chat model loaded, embedding model '${EMBEDDING_MODEL}' loaded with ${DIMENSIONS} dimensions."
