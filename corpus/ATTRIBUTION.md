# Demo corpus attribution

The demo corpus is third-party documentation used to make retrieval and evaluation demonstrable. It
is **not committed to this repository** — `scripts/fetch-corpus.sh` downloads it from
[`MANIFEST.yml`](MANIFEST.yml), so provenance stays auditable and no third-party content is
redistributed here.

**Status:** populated in Phase 4. Fetched for real via `scripts/fetch-corpus.sh`; see
[`MANIFEST.yml`](MANIFEST.yml) for the exact URLs, sha256 hashes and retrieval timestamp actually
recorded.

## Selection criteria

1. **Permissive license** allowing use and redistribution with attribution.
2. **Technical documentation**, matching the system's intended domain.
3. **Verifiable answers** — factual content, so golden-dataset cases have unambiguous ground truth.
4. **Exact-term density** — class names, configuration keys, error codes. This is where dense
   retrieval is weakest and where hybrid search must prove itself.

## Sources

| Source | License | Retrieved | Notes |
|---|---|---|---|
| [pgvector README](https://raw.githubusercontent.com/pgvector/pgvector/master/README.md) | PostgreSQL | 2026-08-22 | Directly relevant to ADR-0003 (this project's own vector store) |
| [UI for Apache Kafka README](https://raw.githubusercontent.com/provectus/kafka-ui/master/README.md) | Apache-2.0 | 2026-08-22 | Swapped in for `apache/kafka`'s own README, which turned out to be pure Gradle build/test tooling documentation with no conceptual content to write golden questions against; kafka-ui is already deployed in this project's own `infrastructure/docker-compose.yml` |

Licenses and terms are re-verified at fetch time, not assumed from this table. `MANIFEST.yml` records
the exact URLs, sha256 hashes and retrieval timestamp actually used — `scripts/fetch-corpus.sh`
rewrites it in place on every run.

## Limitations

This corpus is small (2 sources) and narrow. Retrieval quality measured on it does not generalise to
a large or heterogeneous corpus, and [`docs/ai-evaluation.md`](../docs/ai-evaluation.md) states so
among its methodological limits. `eval/dataset/core.yaml`'s 28 golden cases were hand-authored
against this exact fetched content, with `gold_chunk_refs` (`title#ordinal`) verified against the
real, deterministic chunk boundaries `Chunker` produces for it — not guessed.
