# AI evaluation

How answer and retrieval quality are measured, and — equally important — where the methodology is
weak.

**Status:** design. The harness is built in Phase 4.

---

## 1. Why this document is blunt about its own limits

Evaluating a RAG system is easy to do badly and hard to do well. The common failure is an impressive
table of numbers whose methodology, on inspection, does not support them: a judge model grading its
own family's output, a test set sampled from the training corpus, a "95% accuracy" with no definition
of accuracy.

The position taken here: **deterministic, reproducible metrics are primary; model-graded metrics are
secondary and explicitly caveated.** A smaller set of trustworthy numbers is worth more than a large
set of flattering ones — and if a reviewer's first instinct is to attack the methodology, this
document should have already made their argument for them.

---

## 2. The golden dataset

Roughly fifty cases over the demo corpus, in `eval/dataset/`, versioned as YAML.

```yaml
- id: kafka-consumer-lag-001
  question: "What does consumer lag measure in Kafka?"
  expected_answer: "The difference between the last produced offset and the
    last committed offset for a consumer group in a partition."
  gold_chunk_ids: [doc-kafka-monitoring#c12, doc-kafka-monitoring#c13]
  tags: [factual, single-hop, kafka]
```

**Case categories**, chosen so failure modes are distinguishable rather than averaged away:

| Category | What it tests | Share |
|---|---|---|
| Factual single-hop | Basic retrieval and grounding | ~40% |
| Multi-hop | Synthesis across documents | ~20% |
| Exact-term | Class names, configuration keys, error codes — where dense retrieval is weakest | ~15% |
| Unanswerable | **The model must decline**, not invent | ~15% |
| Ambiguous | Clarification or an acknowledged assumption | ~10% |

The unanswerable set is the most informative and the most frequently omitted. A system that scores
well on answerable questions and confabulates on unanswerable ones is worse than one that scores
slightly lower on both, and only this category exposes that.

Gold chunk ids are assigned by hand against the ingested corpus and re-verified whenever the chunking
strategy changes — a real maintenance cost, recorded because it is the kind of cost that gets omitted
from methodology sections.

---

## 3. Metrics

### Primary: deterministic

Computable without a model. Reproducible, cheap, and defensible.

| Metric | Definition | What a regression means |
|---|---|---|
| **Recall@k** | Share of gold chunks appearing in the top *k* | The generator cannot succeed; retrieval is starving it |
| **MRR** | Mean reciprocal rank of the first gold chunk | Right chunks retrieved but ranked poorly — a reranking problem |
| **Citation precision** | Share of cited chunks that are gold | The model cites sources that do not support the claim |
| **Citation recall** | Share of gold chunks actually cited | Answers correct but under-attributed |
| **Abstention accuracy** | Correct declines on unanswerable cases | Hallucination rate, measured directly |
| **p50 / p95 latency** | End to end and per pipeline stage | Where the time goes |
| **Token cost** | Prompt and completion tokens per answer | Context bloat, usually from an over-large top-k |

Abstention accuracy is the closest thing here to a direct hallucination measurement, which is why the
unanswerable category exists at all.

### Secondary: model-graded, with caveats

| Metric | Method |
|---|---|
| Answer correctness | Judge model compares the answer to the expected answer |
| Faithfulness | Judge model checks each claim against the provided context |

**Known weaknesses, stated up front:**

1. A local model judging another local model is a weak instrument. Judge quality bounds metric
   quality.
2. Judges show self-preference — a model rates output from its own family higher.
3. Judges are position- and verbosity-biased, favouring longer answers.
4. Scores are not comparable across judge models or judge prompt versions.

**Therefore:** judge scores are reported with the judge model named, used only for relative
comparison within a single report, and never quoted as an absolute quality figure. A prompt-version
identifier is recorded so a score's provenance is traceable.

---

## 4. Profile comparison

The harness runs the dataset against multiple named `RagProfile` configurations, which is the
mechanism that makes retrieval quality a measured property rather than an opinion.

```bash
./scripts/eval.sh --profiles dense-only,hybrid,hybrid-rerank
```

Illustrative output shape — **these are column headers, not results:**

| Profile | Recall@5 | MRR | Cite prec. | Abstention | p95 (ms) | Tokens/answer |
|---|---|---|---|---|---|---|
| `dense-only` | — | — | — | — | — | — |
| `hybrid` | — | — | — | — | — | — |
| `hybrid-rerank` | — | — | — | — | — | — |

This is what justifies the hybrid-retrieval decision (ADR-0007, [planned for Phase 3](adr/README.md))
after the fact. Hybrid search *should* outperform dense-only
on exact-term queries. If the numbers say otherwise, the ADR gets amended and the reason recorded —
that is what the harness is for.

---

## 5. Reproducibility

Every report in `eval/reports/` records:

- Date and commit SHA
- Chat model, embedding model, quantisation
- **Hardware** — evaluation runs on an RTX 5090 and an M4 Pro produce different latencies, and a
  latency figure without a machine attached is not a measurement
- RAG profile configuration
- Dataset version
- Judge model and prompt version, where model-graded metrics are included
- Sampling parameters (temperature, seed where the backend supports it)

Local models are not fully deterministic even at temperature zero, so deterministic metrics are
reported as a mean over three runs with the spread shown. A recall figure with no variance stated is
an incomplete measurement.

---

## 6. Continuous evaluation

CI runs the harness nightly against the `recorded` provider — deterministic, offline, free. It
cannot detect model quality changes, only regressions in retrieval and pipeline code, which is the
larger source of accidental breakage.

The build fails on a regression beyond threshold: recall@5 down more than 5 points, citation
precision down more than 5 points, or abstention accuracy down more than 10 points. A retrieval
change that quietly degrades quality should break the build the same way a failing unit test does.

Live-model evaluation is a manual step, run before any release or portfolio update, with its report
committed.

---

## 7. What this evaluation does not do

- **No comparison against other RAG systems.** No fair harness exists for that, and an unfair
  comparison is worse than none.
- **No absolute quality claims.** Numbers are meaningful relative to other configurations on this
  corpus with this model.
- **No human evaluation.** The reliable ground truth, and out of reach for a one-person project.
  Its absence is a real limitation, not a rounding error.
- **No adversarial or red-team evaluation** before Phase 8. Injection resistance is currently
  addressed by design, not by measurement.
- **No corpus-scale testing.** Fifty cases over a small corpus. Behaviour at a million documents is
  an open question, and the honest answer to "how does it scale" is *this was not tested*.

---

## 8. A real finding from the first live-model run (Phase 8), recalibrated post-roadmap (issue #29)

Every evaluation run before Phase 8 — the ones in `eval/reports/` before this — used the `recorded`
provider profile: fixture-replayed responses, and embeddings from `RecordedEmbeddingProvider`, which
is hash-seeded to produce near-zero cosine distance for exact-text matches (deliberately, so
integration tests can assert a retrieval hit deterministically). `RagProfiles`' `maxVectorDistance`
thresholds (0.6 for `dense-only`/`hybrid`) were never checked against a *real* embedding model's
actual distance distribution before Phase 8, because no prior phase had a live LM Studio run to check
it against.

Running the first-ever live evaluation against real `bge-m3` embeddings surfaced this immediately: a
direct, unambiguous, answerable question against the corpus ("What is pgvector and what does it do?")
returned a real top match with `vectorDistance` ≈ **0.95** — comfortably past the 0.6 threshold — so
the pipeline abstained on every golden-dataset case, confirmed at both the harness level
(`eval/reports/2026-08-23-dense-only-hybrid-hybrid-rerank.md`: `abstentionAccuracy` 1.0,
`totalPromptTokens` 0 for every profile) and via a direct `POST /conversations/{id}/messages` call
against the running app, which returned "The knowledge base doesn't contain enough information to
answer this question" for the same clearly-answerable query.

**Fixed post-roadmap, issue #29 (docs/adr/0013-rag-abstention-threshold.md).** Phase 8's single-query
check wasn't a broad enough sample to recalibrate responsibly, so this was named an open gap rather
than fixed there. The real recalibration measured vector distance across the full 28-case golden
dataset plus four constructed, genuinely off-topic control queries (capital of France, a cake recipe,
chess rules, general relativity — outside the pgvector/kafka-ui corpus entirely):

| Set | n | Distance range |
|---|---|---|
| Golden dataset (all categories) | 28 | 0.2989–0.4682 |
| Off-topic control queries | 4 | 0.5992–0.6866 |

`maxVectorDistance` was set to **0.55** for all four profiles (they share `candidatesPerRetriever`/
`topK`, so — confirmed live, not assumed — they all read the same raw vector-candidate pool for this
gate; one threshold is correct, not four). A live evaluation run against the recalibrated threshold
produced real, non-zero recall/citation metrics (`eval/reports/2026-08-24-dense-only.md`) — though
scoped to 10 of 28 cases, `dense-only` only, after LM Studio's chat pipeline degraded mid-run on this
hardware; the report's own "Coverage note" names that constraint plainly rather than hiding it or
waiting indefinitely on unhealthy local infrastructure. `RagPipelineAbstentionTest`
(`modules/rag/src/test/java/...`) now gives the gate itself direct, repeatable regression coverage
against the real measured distance distribution — the first test coverage this gate has had since
ADR-0008 introduced it.

Two further real, load-bearing infrastructure bugs surfaced and were fixed while getting this live
measurement, both named in full in ADR-0013 rather than only mentioned here: `ai.provider.lmstudio.
timeout` never reached the underlying OkHttp client (only an outer reactive `Mono.timeout()`), and
`EvalRunner` had no per-case fault isolation, so a single hung live-model call discarded an entire
run's already-collected results (confirmed losing everything at 0, 3, 7, and 12 completed cases
across four consecutive attempts before the fix). Named here because the same lesson from Phase 8
applies again, one layer deeper: **a single live-model check surfacing a real gap is not the same as
that gap being fixed responsibly — fixing it needs the broader measurement, done for real.**
