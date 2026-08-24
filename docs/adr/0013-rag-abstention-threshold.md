# ADR-0013: Recalibrating the RAG abstention threshold against a real embedding model

- **Status:** Accepted
- **Date:** 2026-08-24
- **Phase:** Post-roadmap review (issue #29)

## Context

ADR-0008 named `RagProfile.maxVectorDistance` an "unmeasured starting heuristic (0.6 across all four
named profiles, for now)... a real false-negative/false-positive rate on this needs Phase 4's golden
dataset, not invented." Phase 4 built the evaluation harness but every run through Phase 8 used the
`recorded` provider profile — fixture-replayed chat responses, and embeddings from
`RecordedEmbeddingProvider`, which is hash-seeded to produce near-zero cosine distance for exact-text
matches (deliberately, so integration tests can assert a retrieval hit deterministically). The 0.6
threshold was never checked against a real embedding model's actual distance distribution, because no
prior phase had run the harness against a live LM Studio.

Running the first-ever live evaluation against real `bge-m3` embeddings (docs/ai-evaluation.md §8)
surfaced this immediately: a direct, unambiguous, answerable question against the corpus ("What is
pgvector and what does it do?") returned a top match with `vectorDistance` ≈ **0.95** — comfortably
past 0.6 — so the pipeline abstained on every case in the golden dataset. `recorded`-profile
integration tests passing was never evidence that the retrieval threshold was calibrated for a real
embedding model.

**The measurement, done for real:** a one-off script (`POST /api/v1/retrieval:search` with the
`dense-only` profile, one call per case) against the seeded corpus with LM Studio serving
`bge-m3`, run over the full 28-case golden dataset (`eval/dataset/core.yaml`) plus four constructed
control queries chosen to be genuinely outside the corpus's domain (capital of France, a cake recipe,
chess rules, general relativity — the corpus is pgvector and kafka-ui documentation only):

| Set | n | Distance range |
|---|---|---|
| Golden dataset (all categories, including `UNANSWERABLE`) | 28 | 0.2989–0.4682 |
| Off-topic control queries | 4 | 0.5992–0.6866 |

A real finding inside this measurement: `eval/dataset/core.yaml`'s `UNANSWERABLE` category is not
"off-corpus" — it's questions topically within the corpus's domain (pgvector RAM usage, kafka-ui's max
message size) whose specific fact just isn't stated in the docs. Those cases' distances were nearly
identical to the answerable cases' (unanswerable mean 0.384 vs. answerable mean 0.397) — the vector
distance gate is designed to catch "this corpus doesn't cover this topic," not "this specific fact
isn't stated," which is a different, LLM-faithfulness concern this gate was never meant to solve.
Genuine off-topic control queries were necessary to find where the real separation sits.

A second real finding, live-verified rather than assumed: the four named `RagProfile`s
(`dense-only`/`hybrid`/`hybrid-rerank`/`hybrid-rerank-llm`) all produce the *same* minimum
`vectorDistance` for a given query, because they share `candidatesPerRetriever=20`/`topK=5` —
`lexicalEnabled` and `rerankStrategy` change fusion/ranking downstream of the raw vector-candidate
pool `shouldAbstain` reads from, not what that pool contains. One threshold, not four, is correct.

## Decision

**Set `maxVectorDistance` to 0.55 for all four profiles** — inside the gap between the two measured
clusters (0.4682 top of answerable, 0.5992 bottom of off-topic), closer to the answerable side so a
borderline-relevant real query is more likely to get an answer than a false abstention, while still
sitting well clear of the off-topic cluster. `modules/rag/.../RagProfiles.java` and `RagProfile.java`'s
javadoc were updated to cite this measurement instead of the unmeasured 0.6.

**`RagPipeline.shouldAbstain` was made package-private** (from `private`) purely so
`RagPipelineAbstentionTest` can exercise the real gate directly with constructed `SearchResult`
distances — the real distribution above, and the boundary either side of 0.55 — without building
`RagPipeline`'s full seven-dependency graph for what is a pure function of two arguments. No behavior
changed.

**A live evaluation run confirmed the recalibrated gate produces non-zero metrics against a real
model**, though scoped down from the full 28-case × 3-profile comparison — see
`eval/reports/2026-08-24-dense-only.md`'s own "Coverage note" for the real infrastructure constraint
that forced the reduction (LM Studio's chat pipeline degrading mid-run on this hardware) and the two
real bugs fixed along the way (below).

## Two real bugs found and fixed while getting this measurement, both load-bearing for any future live
run — not scope creep, since neither Phase 4 nor Phase 8 had ever exercised a long-running live chat
completion before this issue's live evaluation run did

**`ai.provider.lmstudio.timeout` never reached the underlying HTTP client.**
`LmStudioProviderConfiguration`'s `OpenAiChatModel.builder()` only ever passed the configured timeout
into `LmStudioChatProvider`'s own outer `Mono.timeout(timeout)` — a reactive-layer cutoff that never
touched OkHttp's own, much shorter default read timeout underneath. A real 27B model's inter-token
gaps outlast that default long before the configured timeout would ever fire, surfacing as
`ProviderUnavailableException` wrapping `OpenAIIoException: Stream failed` ← `InterruptedIOException:
timeout` ← `SocketException: Socket closed`. Fixed by adding
`.httpClientBuilderCustomizer(builder -> builder.timeout(properties.timeout()))` to the builder chain
(found via `javap -p` on the compiled Spring AI 2.0 jar, not guessed).

**One hung case discarded an entire run's already-collected results.** `EvalRunner.runProfile`'s
per-case loop had no exception handling — a single case's `ProviderUnavailableException` propagated
out of `run()` entirely, discarding every prior case's results in that profile even though each had
already been persisted individually. Confirmed live and repeatedly: four consecutive full-run attempts
lost everything at 0, 3, 7, and 12 completed cases respectively before this fix. Fixed by catching
`RuntimeException` per case in the loop, logging a warning, and continuing — a live external model
call is a real, now-repeatedly-confirmed fault boundary, not a scenario that "can't happen."

## Alternatives considered

### Keep 0.6, treat the Phase 8 finding as a fixture-profile-only quirk

Rejected: the direct live measurement (0.95 for an unambiguously answerable question) was not a
fixture artifact — it was a real `bge-m3` distance the deployed threshold would hit in production on
day one. Leaving 0.6 in place would ship a RAG pipeline that always abstains against its own real
embedding model.

### Compute a threshold per profile, since each has its own name and could plausibly need its own tuning

Rejected once measured: all four profiles read from the same raw vector-candidate pool for this gate
(`candidatesPerRetriever`/`topK` are identical across all four), confirmed live, not assumed — four
separately-tuned thresholds would track a distinction that doesn't exist in this pipeline's current
configuration, adding config surface for no behavioral difference.

### A percentile/statistical threshold (e.g. "abstain if best match is more than 2σ from the corpus's mean chunk distance") instead of a fixed constant

Rejected as unnecessary complexity for a 2-document corpus: the measured separation between the
answerable and off-topic clusters (0.4682 vs. 0.5992) is wide enough that a fixed constant is not
fragile, and a statistical threshold would need per-corpus recalibration logic this project has no
current use for. Worth reconsidering if/when the corpus grows heterogeneous enough that a single
global distance scale stops making sense.

## Trade-offs

- **0.55 was measured against a 2-document corpus and 32 total queries** (28 golden + 4 control) — a
  real measurement, but a narrow one. A larger, more heterogeneous corpus could shift the answerable
  cluster's upper bound; this threshold is a defensible current value, not a permanent one.
- **The live evaluation evidence backing this change covers 10 of 28 cases, one profile, one
  repetition** — not the full comparison `scripts/eval.sh`'s own documentation describes. Named
  explicitly in `eval/reports/2026-08-24-dense-only.md` rather than either waiting indefinitely on
  unhealthy local infrastructure or silently presenting partial coverage as complete.
- **`shouldAbstain`'s visibility relaxation** (private → package-private) is a small, permanent surface
  change purely for testability — acceptable since it stays internal to the `rag` package and the
  public `RagPipeline` API is unchanged.

## Consequences

- A future corpus change (more documents, a different domain) should re-run the same measurement
  script's approach (search every golden-dataset query plus a handful of genuine off-topic controls,
  look at the real distance separation) rather than assume 0.55 still sits in the right gap.
- `RagPipelineAbstentionTest` is now the first real test coverage `rag`'s abstention gate has ever
  had — previously zero, across every phase since ADR-0008 introduced it.
- The `EvalRunner` per-case resilience fix means future live evaluation runs degrade gracefully under
  real infrastructure flakiness instead of losing all progress on the first bad case — relevant well
  beyond this one issue, for any future live run.
