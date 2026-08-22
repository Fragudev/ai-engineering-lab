# ADR-0007: Hybrid retrieval, Reciprocal Rank Fusion, and reranking without a cross-encoder model

- **Status:** Accepted
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
  every `RagProfile`'s `maxVectorDistance` abstention threshold (ADR-0008).
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
