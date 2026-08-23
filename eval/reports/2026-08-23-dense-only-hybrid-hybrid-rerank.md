# Evaluation report

- **Date:** 2026-08-23T09:08:53.436317Z
- **Dataset:** core v1
- **Chat model:** none
- **Hardware:** Apple M4 Pro, 48GB RAM
- **Repetitions per profile:** 1

## Profile comparison

| Profile | Recall@k | MRR | Cite prec. | Cite recall | Abstention acc. | p50 (ms) | p95 (ms) | Prompt tokens | Completion tokens |
|---|---|---|---|---|---|---|---|---|---|
| dense-only | 0.10 ± 0.00 | 0.07 ± 0.00 | n/a | 0.00 ± 0.00 | 1.00 ± 0.00 | 28 | 37 | 0 | 0 |
| hybrid | 0.19 ± 0.00 | 0.13 ± 0.00 | n/a | 0.00 ± 0.00 | 1.00 ± 0.00 | 30 | 36 | 0 | 0 |
| hybrid-rerank | 0.17 ± 0.00 | 0.10 ± 0.00 | n/a | 0.00 ± 0.00 | 1.00 ± 0.00 | 30 | 35 | 0 | 0 |

## Methodology limitations

- **Judge scores are from a local model judging another local model** — a weak instrument. Judges show self-preference and verbosity bias, and scores are not comparable across judge models or prompt versions (docs/ai-evaluation.md §3).
- **Small, narrow corpus** (2 sources) — retrieval quality here does not generalise to a large or heterogeneous corpus (corpus/ATTRIBUTION.md).
- No human evaluation; no comparison against other RAG systems; no adversarial or red-team evaluation (docs/ai-evaluation.md §7).
- Deterministic metrics are a mean over the configured repetitions with the spread shown (± after each figure) — a single-run number with no variance stated is an incomplete measurement (docs/ai-evaluation.md §5).
