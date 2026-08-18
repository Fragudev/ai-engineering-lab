# ADR-0006: Fixed-size, paragraph-aware chunking

- **Status:** Accepted
- **Date:** 2026-08-18
- **Phase:** 2

## Context

The chunker consumes `ingestion.document.parsed.v1` and must turn plain text into a sequence of
chunks small enough to embed and retrieve usefully, without splitting sentences or paragraphs in a
way that destroys their meaning. Phase 2's parsing scope is plain text and Markdown (see the
roadmap's Phase 2 scope notes); no layout, heading structure, or semantic signal beyond paragraph
breaks is available to lean on yet.

## Decision

Split text on blank-line paragraph boundaries, then greedily pack consecutive paragraphs into a
chunk until adding the next one would exceed a **2000-character budget** (roughly 500 tokens at the
commonly-cited ~4-characters-per-token rule of thumb for English text — an estimate, not a measured
figure; see AGENTS.md rule 2 on invented numbers). A paragraph that alone exceeds the budget is hard
-split at the nearest word boundary rather than mid-word. Implementation: `Chunker` in
`modules/ingestion/.../internal`.

## Alternatives considered

### Semantic / embedding-based chunking

Splits text where meaning actually shifts, typically by embedding sentences and cutting where
similarity drops. Produces better-bounded chunks for retrieval.

Rejected for this phase on cost-benefit: it requires embedding calls just to decide *how* to chunk,
before the real embedding step even runs, roughly doubling embedding-provider load for a local model
that is already the pipeline's bottleneck. It is also a meaningfully different technique from the
pipeline mechanics (Kafka, outbox, retry, idempotency, dead-lettering) this phase exists to
demonstrate, and evaluating whether it actually improves retrieval quality only means anything once
the evaluation harness (Phase 4) exists to measure it.

### Fixed-size chunking with no paragraph awareness

Simplest possible: cut every N characters, full stop. Rejected because it routinely splits sentences
and even words mid-way, which is a strictly worse input to an embedding model for no implementation
savings over paragraph-aware packing — the paragraph-boundary check is a handful of lines.

### Token-exact chunking via a real tokenizer

Chunk by actual token count for the target embedding model, not an estimated character budget.
Rejected because it means embedding the specific model's tokenizer (or a compatible approximation)
into the ingestion pipeline as a new dependency, to buy precision that does not matter yet: the
1024-dimension embedding model's real context window is generous relative to a 2000-character chunk,
so the estimate's slack is harmless at this stage.

## Trade-offs

- **The character budget is an estimate, not an exact token count.** A chunk could occasionally run
  somewhat over or under the intended ~500 tokens depending on the text's actual token density.
- **No semantic boundary awareness.** Two paragraphs that are packed together purely because they
  fit the budget may not be topically related; retrieval quality from this is unmeasured until
  Phase 4's evaluation harness exists.
- **Plain text/Markdown only.** Nothing here parses headings, tables, or other structural signal a
  richer format (or a smarter chunker) could use.

## Consequences

- `Chunk.ordinal` reflects packing order, not necessarily one paragraph per chunk.
- Revisiting this once the evaluation harness (Phase 4) can measure whether semantic chunking
  actually improves recall/precision is the natural next step — this ADR is deliberately the
  simple, defensible baseline that decision would be measured against, not a claim that it is
  optimal.
- Reversing to a different strategy touches only `Chunker` and its unit tests; nothing about the
  event contracts (`ChunksCreatedEvent`) or downstream consumers depends on the packing algorithm.
