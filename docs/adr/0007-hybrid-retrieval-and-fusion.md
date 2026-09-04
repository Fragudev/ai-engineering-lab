# ADR-0007: Hybrid retrieval, Reciprocal Rank Fusion, and reranking without a cross-encoder model

- **Status:** Accepted; amended 2026-09-03 (see [Amendment](#amendment-2026-09-03-mmr-reranking-measured-worse-than-no-reranking))
- **Date:** 2026-08-18
- **Phase:** 3

## Context

Dense (vector) retrieval alone is weak precisely where technical documentation needs it most: exact
class names, configuration keys, error codes — terms whose embedding similarity to a query mentioning
them is often no higher than an unrelated sentence that happens to share topic. PostgreSQL full-text
search is exactly the complementary signal, and ADR-0003 already noted it comes "for free": the
`content_tsv` GENERATED tsvector column (V3 migration) sits in the same `chunk` table as the
embeddings, so combining lexical and semantic retrieval needs no second datastore.

Combining two retrievers' results into one ranking is its own decision, and so is reranking: no
dedicated cross-encoder reranker model is part of this project's stack — `scripts/bootstrap.sh` only
requires a chat model and `bge-m3` embeddings — so "optional reranking" (the roadmap's own phrase)
had to mean something buildable from what's actually available, not a placeholder.

## Decision

**Fusion: Reciprocal Rank Fusion**, `score = Σ 1/(k + rank)` over whichever retrievers found a chunk,
with the standard `k=60` from the original RRF paper. Implemented in
`knowledge.internal.ReciprocalRankFusion`, called from `HybridSearchService.search`.

**Reranking: two real, working implementations, not stubs**, selectable per `RagProfile`:
- **MMR** (Maximal Marginal Relevance) — greedily picks each next chunk maximizing
  `λ·relevance − (1−λ)·max_similarity(already_selected)` using the embeddings already computed
  during retrieval. Zero extra model calls; trades pure relevance for reduced redundancy so
  near-duplicate chunks don't crowd out distinct ones. `knowledge.internal.MmrReranker`.
- **LLM-based listwise reranking** — sends the query and numbered candidates to the chat model via
  the existing `ChatProvider` abstraction, asking for a relevance ordering. A real, used-in-practice
  technique, but real latency/cost on every reranked turn, and depends on the local model reliably
  following a plain-text instruction — on any malformed or unparsable response, falls back to the
  fused order rather than failing the request. `knowledge.internal.LlmReranker`.

Four named profiles cover both: `dense-only`, `hybrid`, `hybrid-rerank` (MMR),
`hybrid-rerank-llm` (LLM) — see `rag.RagProfiles`.

## Alternatives considered

### A single fusion weight (e.g. `score = α·vector + (1−α)·lexical`)

Rejected: it requires normalizing two scores on fundamentally different, unbounded scales (cosine
distance vs. `ts_rank`) onto a comparable range before any weighting means anything, and the weight
itself would need tuning against a golden dataset that doesn't exist until Phase 4. RRF sidesteps
both problems — it only cares about rank position, which is already comparable across retrievers with
no normalization step and no tuned constant beyond the well-established `k=60`.

### A cross-encoder reranker

The textbook choice for reranking, and meaningfully more accurate than either alternative built here.
Rejected for this phase because none is part of the project's model stack (see Context), and adding
one would mean a new model dependency + a new inference path solely for reranking — a larger
commitment than the roadmap's "optional reranking" implies. Named here as the natural next candidate
if Phase 4's evaluation harness shows MMR/LLM reranking underperforming.

### Filtering candidates by a minimum fused RRF score

Considered and rejected during implementation, not before it: fused RRF scores reflect rank
position, not absolute relevance — a chunk found in an otherwise-empty result set ranks first with
the same score band as a chunk that's genuinely a strong match. Thresholding on it can't distinguish
"nothing relevant exists" from "here's the best of what's there." The "insufficient context" gate
(ADR-0008) uses raw vector distance instead, which is a real absolute similarity signal.

## Trade-offs

- **RRF's `k=60` is a standard default, not tuned for this corpus.** Phase 4's golden dataset is
  where fusion behavior actually gets measured against alternatives.
- **MMR's `λ=0.7` is a starting heuristic** (AGENTS.md rule 2: not claimed as measured). Same for
  every `RagProfile`'s `maxVectorDistance` abstention threshold (ADR-0008). Since the
  [2026-09-03 amendment](#amendment-2026-09-03-mmr-reranking-measured-worse-than-no-reranking) it is
  `RagProfile.mmrLambda`, tunable per profile rather than a constant — still unmeasured.
- **LLM reranking roughly doubles model calls on that profile** (one for the ranking, one for
  generation) — a real latency cost, which is exactly why it's a separate, opt-in profile rather than
  the default.
- **Neither reranker is a cross-encoder**, so neither is expected to match one's accuracy; both exist
  because a real cross-encoder isn't available, not because they're claimed to be equivalent.

## Consequences

- `knowledge.HybridSearchService` is the one place all of this lives — `rag` calls it without knowing
  how fusion or reranking work internally, matching the module boundary in docs/architecture.md #3
  ("hybrid search, reranking" is `knowledge`'s stated responsibility, not `rag`'s).
- Swapping RRF for a different fusion function, or adding a cross-encoder reranker later, touches
  `ReciprocalRankFusion`/a new `Reranker` implementation and nothing about the `SearchResult` contract
  `rag` and the retrieval debug endpoint depend on.
- `SearchResult.rerankScore` is `null` for `RerankStrategy.LLM` and `.NONE` — an LLM reranker produces
  an ordering, not a calibrated score, and inventing one to fill the field would misrepresent it.

## Amendment 2026-09-03: MMR reranking measured worse than no reranking

The harness exists to check this ADR "after the fact" (Trade-offs, above). It now has, and the
result goes against the `hybrid-rerank` profile.

### What was measured

The first **complete** live run (`eval/reports/2026-08-26-…md`, 84 of 84 cases, one repetition,
`qwen/qwen3.8-27b` + `bge-m3`):

| Profile | Recall@k | MRR | Cases with recall 0.0 |
|---|---|---|---|
| dense-only | 0.81 | 0.46 | 4 |
| hybrid | **0.85** | **0.52** | **3** |
| hybrid-rerank (`hybrid` + MMR, λ=0.7) | **0.71** | 0.43 | **6** |

`hybrid-rerank` is last on recall — below plain `dense-only` — and doubles the number of cases that
retrieve nothing relevant. A partial earlier run (46 of 84, biased toward the cases that finished
inside an inert ~60s timeout, issue #65) had hidden this by tieing all three at 0.93.

### Why this is consistent with the code, not a surprise

`HybridSearchService` hands the reranker the **entire fused candidate pool** (`candidatesPerRetriever
= 20` per retriever, so up to ~40 chunks) and MMR selects `topK = 5` from it. MMR is therefore free
to drop a gold chunk that fused-score order would have kept in the top 5 — "recall can only stay
equal or improve" is not a property MMR has when it re-selects from a pool larger than `topK`. On a
two-source corpus, chunks that genuinely answer the same question are necessarily similar to each
other, so the `(1−λ)·max_similarity(already_selected)` diversity penalty is most aggressive exactly
when several gold chunks should all be returned. Lowest `citationRecall` for this profile in the
same run (0.65 vs 0.69) is the same effect seen downstream.

This does not contradict the **Decision** (MMR is still a real, correctly-implemented technique built
from what the stack has). It confirms the **Trade-offs** section's own hedge — "MMR's λ=0.7 is a
starting heuristic … not claimed as measured" and "neither reranker is a cross-encoder, so neither is
expected to match one's accuracy" — and it promotes the **Alternatives considered** note that a
cross-encoder is "the natural next candidate if Phase 4's evaluation harness shows MMR/LLM reranking
underperforming" from hypothetical to live.

### What changed in code with this amendment

`λ` was a hardcoded `private static final double LAMBDA = 0.7` in `MmrReranker`. It is now
`RagProfile.mmrLambda`, threaded through `HybridSearchOptions`, so it can be swept per profile
against the golden dataset without touching the `knowledge` module. `MmrReranker` keeps `0.7` as the
default for its 4-arg `Reranker`-interface method. No profile's behaviour changes: `hybrid-rerank`
still runs λ=0.7 until a measured value replaces it.

### What is NOT yet decided (tracked in issue #67)

Making λ configurable is scaffolding, not the fix. Still open, and each needs per-case data from a
live `./scripts/eval.sh --profiles=hybrid,hybrid-rerank --repetitions=3` run (≈7h) that has not been
done:

- whether a swept λ recovers `hybrid`-level recall on this corpus, or MMR is simply the wrong tool
  for a corpus this narrow;
- whether `hybrid-rerank` should keep being offered as a default-quality profile, or be reframed as a
  diversity-first profile with recall understood to be a secondary metric for it;
- whether the cross-encoder named above should now be added.
