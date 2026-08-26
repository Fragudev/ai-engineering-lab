# Evaluation report

- **Date:** 2026-08-26T09:12:17.754736Z
- **Dataset:** core v1
- **Chat model:** qwen/qwen3.8-27b
- **Hardware:** Apple M4 Pro, 48GB RAM
- **Repetitions per profile:** 1

## Coverage: 84 of 84, and why that changes the numbers downward

**Every case ran.** 28 per profile, three profiles, zero skipped — the first complete run this
project has produced against a live model. The run took 2h21m.

The previous report (`2026-08-25-…md`) completed 46 of 84 and blamed the 38 failures on *"LM Studio
degrading under sustained sequential load"*. **That diagnosis was wrong.** The cause was
[#65](https://github.com/Fragudev/ai-engineering-lab/issues/65): `ai.provider.lmstudio.timeout` was
inert and every call was capped at ~60s by Spring AI's own `DEFAULT_TIMEOUT`. With that fixed and
nothing else changed — same corpus, same hardware, same models — the failures disappeared entirely.

The proof is in `dense-only`, which makes a single generation call per case:

| | Before the fix (2026-08-25) | After (this run) |
|---|---|---|
| Cases completed | 15 of 28 | **28 of 28** |
| Max case latency | 58,443 ms — never crossed 60s | **324,621 ms** |
| Cases over 60s | 0 | **14 of 28** |

Half of `dense-only`'s cases need more than 60 seconds. Before the fix, not one of them could finish.

### The metrics went down, and that is the honest result

| Metric | 2026-08-25 (46 cases) | This run (84 cases) |
|---|---|---|
| Recall@k, `dense-only` | 0.93 | **0.81** |
| Recall@k, `hybrid` | 0.93 | **0.85** |
| Recall@k, `hybrid-rerank` | 0.93 | **0.71** |
| Citation precision, `dense-only` | 0.64 | **0.52** |

**The earlier numbers were survivorship-biased.** The 38 dropped cases were not a random sample —
they were precisely the ones whose generation took longest, and those are disproportionately the
harder questions. Scoring only the cases that finished inside an invisible 60-second cap flattered
every figure in that table. These lower numbers are measured over the whole dataset and are the ones
to trust.

### What the comparison now says, and it is not what it said before

`hybrid-rerank` is now the **worst** profile on recall (0.71) — below plain `dense-only` (0.81). In
the partial run all three tied at 0.93, which hid this entirely. On this corpus, MMR reranking is
discarding relevant chunks rather than reordering them. That is a real finding about
`RerankStrategy.MMR`, and it deserves its own investigation rather than a conclusion drawn here from
a single repetition.

`hybrid` remains best on MRR (0.52), consistent with the previous run's ordering.

### Still one repetition

`± 0.00` is one sample, not determinism (docs/ai-evaluation.md §5). At 2h21m per pass, a
variance-bearing `--repetitions=3` is roughly a 7-hour run.

## Profile comparison

| Profile | Recall@k | MRR | Cite prec. | Cite recall | Gate abstention | Refusal correctness | p50 (ms) | p95 (ms) | Prompt tokens | Completion tokens |
|---|---|---|---|---|---|---|---|---|---|---|
| dense-only | 0.81 ± 0.00 | 0.46 ± 0.00 | 0.52 ± 0.00 | 0.69 ± 0.00 | 0.00 ± 0.00 | n/a | 57923 | 291325 | 88698 | 15390 |
| hybrid | 0.85 ± 0.00 | 0.52 ± 0.00 | 0.48 ± 0.00 | 0.69 ± 0.00 | 0.00 ± 0.00 | n/a | 71552 | 267239 | 98160 | 15550 |
| hybrid-rerank | 0.71 ± 0.00 | 0.43 ± 0.00 | 0.48 ± 0.00 | 0.65 ± 0.00 | 0.00 ± 0.00 | n/a | 59119 | 271524 | 90465 | 15170 |

## How to read the two declining columns

Both cover the `UNANSWERABLE` cases only, and they measure **different mechanisms**:

- **Gate abstention** — how often the *deterministic* gate refused to generate, because the best vector match was farther than the profile's `maxVectorDistance`. Structural and exact. **A low value here is not a failure.** The gate is designed to catch "this corpus does not cover the topic", never "this specific fact is not stated" (docs/adr/0013-rag-abstention-threshold.md), so on a dataset whose unanswerable cases sit *inside* the corpus it is expected to stay silent.
- **Refusal correctness** — whether the turn actually declined *correctly*, by whichever mechanism, including the model declining in its own prose. Scored by the judge against the refusal-shaped `expectedAnswer` the dataset provides for these cases.

Refusal correctness reads `n/a` in this report because the judge was not run (`--judge`). That is **not measured**, not zero — and it is the column that would tell you whether the answers were right. Nothing else here does.

## Methodology limitations

- **Judge scores are from a local model judging another local model** — a weak instrument. Judges show self-preference and verbosity bias, and scores are not comparable across judge models or prompt versions (docs/ai-evaluation.md §3).
- **Small, narrow corpus** (2 sources) — retrieval quality here does not generalise to a large or heterogeneous corpus (corpus/ATTRIBUTION.md).
- No human evaluation; no comparison against other RAG systems; no adversarial or red-team evaluation (docs/ai-evaluation.md §7).
- Deterministic metrics are a mean over the configured repetitions with the spread shown (± after each figure) — a single-run number with no variance stated is an incomplete measurement (docs/ai-evaluation.md §5).
