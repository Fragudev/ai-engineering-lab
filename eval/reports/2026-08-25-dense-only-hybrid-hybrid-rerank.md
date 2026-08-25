# Evaluation report

- **Date:** 2026-08-25T11:50:50.505754Z
- **Dataset:** core v1
- **Chat model:** qwen/qwen3.8-27b
- **Hardware:** Apple M4 Pro, 48GB RAM
- **Repetitions per profile:** 1

## Coverage and how to read these numbers

**This is the project's first real three-profile comparison against a live model.** The prior report
(`2026-08-24-dense-only.md`) covered one profile and 10 cases; this one covers all three profiles.
Read the four caveats below before drawing conclusions from any figure in the table.

**1. 46 of 84 case-runs completed — 38 were skipped, not silently dropped.** Per profile: dense-only
15/28, hybrid 15/28, hybrid-rerank 16/28. Every skip was the same fault —
`ProviderUnavailableException` wrapping `InterruptedIOException` → `SocketException` — LM Studio
degrading under sustained sequential load. First skip 6 minutes into the run, continuing
intermittently until the end. Verified immediately afterwards that LM Studio answered a trivial
prompt in **1.3s**, so this is transient degradation under load, not a crash. `EvalRunner`'s per-case
fault isolation (post-roadmap issue #29) is why a complete three-profile report exists at all: before
that fix, the same fault discarded an entire run's results.

**2. `abstentionAccuracy` of 0.00 does not mean the system hallucinated.** It means the
*deterministic* gate never fired: `AbstentionMetrics.abstained()` detects only
`RagPipeline`'s vector-distance abstention path (structurally, via `model == "none"`). All four
`UNANSWERABLE` cases that ran were in fact answered **correctly** — the model declined in its own
prose ("The retrieved documentation doesn't mention a maximum message size…", "I can't confirm
that…"). The gate correctly stayed silent, because those cases are topically *inside* the corpus
(their vector distances sit near 0.38, well under the 0.55 threshold) — exactly the distinction
[ADR-0013](../../docs/adr/0013-rag-abstention-threshold.md) documents. **The metric measures one of
the two mechanisms that produce a correct refusal, and reports the other one's success as failure.**
Filed as its own finding rather than papered over here.

**3. The p95 figures for `hybrid` (260s) and `hybrid-rerank` (268s) measure the degradation, not the
pipeline.** They are dominated by calls made while LM Studio was failing. The p50 figures (40.6s,
47.1s, 42.9s) are the defensible latency numbers from this run.

**4. `± 0.00` is one sample, not determinism.** With `--repetitions=1` there is no spread to report;
`docs/ai-evaluation.md` §5 asks for mean+spread precisely because a local model is not deterministic
even at temperature zero. A variance-bearing figure needs `--repetitions=3`.

**What the comparison does support, with those caveats:** recall@k is identical across all three
profiles (0.93), while MRR differs — `hybrid` 0.57, `hybrid-rerank` 0.51, `dense-only` 0.47. Adding
the lexical retriever changed *ranking*, not *what was found*, on this corpus. Both figures rest on
the partial coverage above and a single repetition; this is a signal worth a fuller run, not a
settled result.

## Profile comparison

| Profile | Recall@k | MRR | Cite prec. | Cite recall | Abstention acc. | p50 (ms) | p95 (ms) | Prompt tokens | Completion tokens |
|---|---|---|---|---|---|---|---|---|---|
| dense-only | 0.93 ± 0.00 | 0.47 ± 0.00 | 0.64 ± 0.00 | 0.86 ± 0.00 | 0.00 ± 0.00 | 40584 | 58443 | 36558 | 3078 |
| hybrid | 0.93 ± 0.00 | 0.57 ± 0.00 | 0.67 ± 0.00 | 0.86 ± 0.00 | 0.00 ± 0.00 | 47060 | 260018 | 43291 | 2750 |
| hybrid-rerank | 0.93 ± 0.00 | 0.51 ± 0.00 | 0.65 ± 0.00 | 0.86 ± 0.00 | 0.00 ± 0.00 | 42857 | 267938 | 51093 | 3344 |

## Methodology limitations

- **Judge scores are from a local model judging another local model** — a weak instrument. Judges show self-preference and verbosity bias, and scores are not comparable across judge models or prompt versions (docs/ai-evaluation.md §3).
- **Small, narrow corpus** (2 sources) — retrieval quality here does not generalise to a large or heterogeneous corpus (corpus/ATTRIBUTION.md).
- No human evaluation; no comparison against other RAG systems; no adversarial or red-team evaluation (docs/ai-evaluation.md §7).
- Deterministic metrics are a mean over the configured repetitions with the spread shown (± after each figure) — a single-run number with no variance stated is an incomplete measurement (docs/ai-evaluation.md §5).
