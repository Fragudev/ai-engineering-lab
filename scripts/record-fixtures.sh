#!/usr/bin/env bash
# Refreshes modules/ai-provider/src/main/resources/fixtures/chat/fixtures.json from a live LM
# Studio. Not a general-purpose HTTP cassette recorder — a fixed, small, reviewable set of
# canonical prompts, matching the `recorded` provider's own fixture-matching keys
# (docs/adr/0004-ai-provider-abstraction.md). Requires jq.
set -euo pipefail

LM_STUDIO_URL="${LM_STUDIO_URL:-http://localhost:1234}"
CHAT_MODEL="${CHAT_MODEL:?Set CHAT_MODEL to the model currently loaded in LM Studio}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURES_FILE="${REPO_ROOT}/modules/ai-provider/src/main/resources/fixtures/chat/fixtures.json"

command -v jq >/dev/null 2>&1 || { echo "record-fixtures: jq is required." >&2; exit 1; }

log() { printf '%s\n' "$*"; }

# matchContains key -> canonical prompt to send. The empty key is the default/catch-all fixture.
declare -A PROMPTS=(
  [""]="Give a one-sentence answer for a fixture with no specific match."
  ["hello"]="Hello!"
  ["spring boot"]="What is Spring Boot?"
  ["what is this project"]="What is this project?"
)

record_one() {
  local match_contains="$1" prompt="$2"
  log "Recording fixture for prompt: '${prompt}'"

  local request response content usage_json prompt_tokens completion_tokens
  request=$(jq -n --arg model "${CHAT_MODEL}" --arg prompt "${prompt}" \
    '{model: $model, messages: [{role: "user", content: $prompt}], stream: false}')

  response=$(curl -fsS --max-time 60 "${LM_STUDIO_URL}/v1/chat/completions" \
    -H 'Content-Type: application/json' -d "${request}") \
    || { echo "record-fixtures: request failed for '${prompt}'." >&2; exit 1; }

  content=$(printf '%s' "${response}" | jq -r '.choices[0].message.content')
  usage_json=$(printf '%s' "${response}" | jq -c '.usage // {prompt_tokens: 0, completion_tokens: 0}')
  prompt_tokens=$(printf '%s' "${usage_json}" | jq -r '.prompt_tokens')
  completion_tokens=$(printf '%s' "${usage_json}" | jq -r '.completion_tokens')

  jq -n \
    --arg matchContains "${match_contains}" \
    --arg response "${content}" \
    --argjson promptTokens "${prompt_tokens}" \
    --argjson completionTokens "${completion_tokens}" \
    '{matchContains: $matchContains, response: $response, promptTokens: $promptTokens, completionTokens: $completionTokens}'
}

default_case="$(record_one "" "${PROMPTS[""]}" | jq 'del(.matchContains)')"
cases="[]"
for key in "${!PROMPTS[@]}"; do
  [ -z "${key}" ] && continue
  case_json="$(record_one "${key}" "${PROMPTS[$key]}")"
  cases="$(printf '%s' "${cases}" | jq --argjson c "${case_json}" '. + [$c]')"
done

jq -n --argjson default "${default_case}" --argjson cases "${cases}" '{default: $default, cases: $cases}' \
  > "${FIXTURES_FILE}"

log "Wrote $(printf '%s' "${cases}" | jq 'length') case(s) + 1 default fixture to ${FIXTURES_FILE}"
