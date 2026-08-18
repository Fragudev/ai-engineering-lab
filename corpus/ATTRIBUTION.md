# Demo corpus attribution

The demo corpus is third-party documentation used to make retrieval and evaluation demonstrable. It
is **not committed to this repository** — `scripts/fetch-corpus.sh` downloads it from
[`MANIFEST.yml`](MANIFEST.yml), so provenance stays auditable and no third-party content is
redistributed here.

**Status:** planned. Populated in Phase 2, when ingestion exists.

## Selection criteria

1. **Permissive license** allowing use and redistribution with attribution.
2. **Technical documentation**, matching the system's intended domain.
3. **Verifiable answers** — factual content, so golden-dataset cases have unambiguous ground truth.
4. **Exact-term density** — class names, configuration keys, error codes. This is where dense
   retrieval is weakest and where hybrid search must prove itself.

## Sources

| Source | License | Retrieved | Notes |
|---|---|---|---|
| Spring Boot reference documentation | Apache-2.0 | — | To be confirmed in Phase 2 |
| Spring AI reference documentation | Apache-2.0 | — | To be confirmed in Phase 2 |
| Apache Kafka documentation | Apache-2.0 | — | To be confirmed in Phase 2 |

Licenses and terms are re-verified at fetch time, not assumed from this table. `MANIFEST.yml` records
the exact URLs, license identifiers and retrieval dates actually used.

## Limitations

This corpus is small and narrow. Retrieval quality measured on it does not generalise to a large or
heterogeneous corpus, and [`docs/ai-evaluation.md`](../docs/ai-evaluation.md) states so among its
methodological limits.
