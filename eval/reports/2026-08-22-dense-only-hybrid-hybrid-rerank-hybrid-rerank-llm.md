# Evaluation report

- **Date:** 2026-08-22T09:14:14.746136Z
- **Dataset:** core v1
- **Chat model:** none
- **Hardware:** M-series Mac, local JDK 25, recorded provider
- **Repetitions per profile:** 3

## Profile comparison

| Profile | Recall@k | MRR | Cite prec. | Cite recall | Abstention acc. | p50 (ms) | p95 (ms) | Prompt tokens | Completion tokens |
|---|---|---|---|---|---|---|---|---|---|
| dense-only | 0.25 ± 0.00 | 0.13 ± 0.00 | n/a | 0.00 ± 0.00 | 1.00 ± 0.00 | 6 | 9 | 0 | 0 |
| hybrid | 0.38 ± 0.00 | 0.21 ± 0.00 | n/a | 0.00 ± 0.00 | 1.00 ± 0.00 | 8 | 13 | 0 | 0 |
| hybrid-rerank | 0.21 ± 0.00 | 0.12 ± 0.00 | n/a | 0.00 ± 0.00 | 1.00 ± 0.00 | 7 | 9 | 0 | 0 |
| hybrid-rerank-llm | 0.38 ± 0.00 | 0.21 ± 0.00 | n/a | 0.00 ± 0.00 | 1.00 ± 0.00 | 7 | 9 | 0 | 0 |

## Methodology limitations

- **Judge scores are from a local model judging another local model** — a weak instrument. Judges show self-preference and verbosity bias, and scores are not comparable across judge models or prompt versions (docs/ai-evaluation.md §3).
- **Generated under the `recorded` profile** (fixture replay, no live model): proves the harness mechanics (retrieval, fusion, citation extraction, metric computation) run correctly, not real answer quality. Latency figures measure harness overhead, not real model latency. A real quality report requires running `./scripts/eval.sh` against a live LM Studio.
- **Small, narrow corpus** (2 sources) — retrieval quality here does not generalise to a large or heterogeneous corpus (corpus/ATTRIBUTION.md).
- No human evaluation; no comparison against other RAG systems; no adversarial or red-team evaluation (docs/ai-evaluation.md §7).
- Deterministic metrics are a mean over the configured repetitions with the spread shown (± after each figure) — a single-run number with no variance stated is an incomplete measurement (docs/ai-evaluation.md §5).
