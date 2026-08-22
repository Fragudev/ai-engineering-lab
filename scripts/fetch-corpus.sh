#!/usr/bin/env bash
# Downloads every source listed in corpus/MANIFEST.yml, verifies each is actually reachable,
# computes its sha256, and rewrites MANIFEST.yml in place with the real sha256 + retrieved_at — the
# audit trail that makes the corpus reproducible (corpus/ATTRIBUTION.md). The corpus content itself
# is never committed to the repository; only this manifest is (see its own header comment).
#
# No yq/python dependency: MANIFEST.yml's shape is fixed and small, so a short awk pass is enough
# and keeps this script portable across dev machines and the CI runner alike.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="${REPO_ROOT}/corpus/MANIFEST.yml"
CORPUS_DIR="${CORPUS_DIR:-${REPO_ROOT}/corpus/documents}"

log()  { printf '%s\n' "$*"; }
fail() { printf 'fetch-corpus: %s\n' "$*" >&2; exit 1; }

[ -f "${MANIFEST}" ] || fail "No manifest at ${MANIFEST}"
mkdir -p "${CORPUS_DIR}"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# "<id><TAB><url>" per source, read from the sources: block.
IDS_URLS="$(awk '
  /^sources:/ { insources=1; next }
  insources && /^  - id: / { id=$3 }
  insources && /^    url: / { url=$2; print id "\t" url }
' "${MANIFEST}")"

[ -n "${IDS_URLS}" ] || fail "No sources found in ${MANIFEST}"

while IFS=$'\t' read -r id url; do
  [ -n "${id}" ] || continue
  dest="${CORPUS_DIR}/${id}.md"
  log "Fetching ${id} <- ${url}"
  curl -fsS --max-time 30 "${url}" -o "${dest}" \
    || fail "Failed to fetch ${url} for source '${id}'. Check the URL is still valid and re-verify" \
            "its license before using it (corpus/ATTRIBUTION.md)."
  [ -s "${dest}" ] || fail "Fetched an empty file for '${id}' (${url})"

  env_key="SHA_$(printf '%s' "${id}" | tr '-' '_')"
  export "${env_key}=$(sha256_of "${dest}")"
  log "  -> ${dest} (sha256 ${!env_key})"
done <<< "${IDS_URLS}"

log "Rewriting ${MANIFEST} with real sha256 + retrieved_at ..."
TMP_MANIFEST="$(mktemp)"
trap 'rm -f "${TMP_MANIFEST}"' EXIT

RETRIEVED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)" awk '
  BEGIN { current_id = "" }
  /^retrieved_at:/ {
    print "retrieved_at: " ENVIRON["RETRIEVED_AT"] " # set by fetch-corpus.sh"
    next
  }
  /^  - id: / {
    current_id = $3
    print
    next
  }
  /^    sha256: / {
    key = "SHA_" current_id
    gsub(/-/, "_", key)
    if (key in ENVIRON) {
      print "    sha256: " ENVIRON[key]
    } else {
      print
    }
    next
  }
  { print }
' "${MANIFEST}" > "${TMP_MANIFEST}"

mv "${TMP_MANIFEST}" "${MANIFEST}"
log "Done. Downloaded $(printf '%s\n' "${IDS_URLS}" | wc -l | tr -d ' ') source(s) into ${CORPUS_DIR} (not committed)."
