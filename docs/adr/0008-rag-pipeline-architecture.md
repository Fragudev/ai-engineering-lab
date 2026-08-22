# ADR-0008: RAG pipeline architecture — orchestration, citations, and the abstention gate

- **Status:** Accepted
- **Date:** 2026-08-18
- **Phase:** 3

## Context

Phase 3 has to wire three already-separately-designed pieces together: `knowledge`'s hybrid search
(ADR-0007), `ai-provider`'s `ChatProvider` streaming abstraction (ADR-0004), and `conversation`'s
message persistence (Phase 1). docs/architecture.md #3's module table doesn't list `conversation` as
depending on `rag`, or the reverse — so *who calls whom* wasn't fully settled by the existing design
and had to be decided here, along with three narrower questions the roadmap's acceptance criteria
force: how citations reach the client without embedding markers in the token stream (already a stated
constraint in docs/architecture.md #5), how "insufficient context" is detected rather than hoped for,
and how a RAG turn coexists with Phase 1's already-shipped, tested plain-chat endpoint.

## Decision

**Orchestration: `app` composes `rag` and `conversation`; neither depends on the other.** This
matches the existing dependency table's silence on a `conversation`↔`rag` edge, and "app wires
everything, owns no domain logic" (docs/architecture.md #3's own invariant). Concretely,
`ConversationController.sendMessage`: fetches history via `conversationService.
getHistoryAsChatMessages`, appends the user turn via `conversationService.appendUserMessage`, calls
`ragPipeline.answer(history, query, profile)`, and on completion persists the reply + citations via
`conversationService.recordAssistantAnswer`. `rag` itself is stateless — no entities, no Flyway
migration — a pipeline over `knowledge` and `ai-provider`.

**Citations are resolved from the full generated answer, not streamed incrementally per marker.**
The model is instructed (system prompt, via numbered context chunks) to cite `[1]`, `[2]`, etc.
`CitationExtractor.stripDelta` removes any marker from `token` deltas in real time — buffering a
possible partial marker across a chunk boundary (a delta ending in `"["` or `"[1"`) so one is never
half-forwarded — but the actual `citation` SSE events are computed once, from the complete aggregate
text, right before `usage`. This matches the sequence architecture.md #10 already documented (token
loop, then citations, then usage/done) and avoids "is this really a new citation or the same marker
repeated" bookkeeping mid-stream.

**Abstention is a deterministic gate on raw vector distance, not a fused score or a prompt
instruction alone.** `RagPipeline` skips generation entirely when retrieval returns nothing, or when
the closest vector match across all returned candidates is farther than
`RagProfile.maxVectorDistance` — see ADR-0007's "alternatives considered" for why the fused RRF score
can't do this job. A prompt instruction to decline is still present as defense in depth (the same
layered-mitigation philosophy as docs/threat-model.md), but the structural gate is what the
acceptance criterion actually rests on, matching Phase 2's own preference for a structural check
(the idempotency table) over trusting behavior.

**`Conversation.ragProfile == null` still means exactly what it meant in Phase 1: plain chat, no
retrieval.** `ConversationService.sendMessage` (the Phase 1 method) is untouched. Setting a
`ragProfile` — at conversation creation, or overridden per-message via a new field on
`SendMessageRequest` — is what turns retrieval on. `ConversationFlowIntegrationTest` needed no
changes.

**Retrieved content is framed as untrusted data, in a system message, never concatenated into user
content** (docs/threat-model.md T2): `ContextBuilder` wraps the numbered chunks in explicit
delimiters with an instruction that they are reference data to summarize and cite, never instructions
to follow. Tool-invocation confirmation, T2's other structural control, isn't applicable yet — tools
don't exist until Phase 5.

## Alternatives considered

### `conversation` depends on `rag` (or the reverse), and one module owns the whole turn

Would have meant a shorter call chain, but contradicts the already-accepted module table's silence on
that edge, and blurs a boundary that's currently clean: `conversation` is pure persistence/history,
`rag` is a pure pipeline, neither needs to know the other exists. Rejected in favor of `app` as
composition root, consistent with how `DocumentController`/`IngestionService` already relate in
Phase 2.

### Inline citation markers reaching the client, parsed there

Simpler server-side (no stripping, no buffering). Rejected because docs/architecture.md #5 already
committed to the opposite, explicitly for the reason stated there: the client never has to parse the
answer to render sources, and a truncated stream still leaves the citations it already delivered
intact.

### Streaming a `citation` event the instant its marker completes mid-answer

More granular, and was the initial design. Rejected during implementation: it requires tracking which
markers have already fired to avoid duplicates while the model can legally repeat `[1]` multiple
times, adds meaningfully more state to `CitationExtractor`, and the roadmap's acceptance criterion
only requires citations to arrive as discrete events — not that they arrive progressively. The
simpler "resolve from the aggregate, emit as a batch before `usage`" design meets the same contract.

### A second LLM call to grade whether the context is sufficient

More thorough than a distance threshold, closer to what a judge model does in Phase 4's evaluation
methodology. Rejected as the *gate* mechanism specifically: it doubles latency/cost on every turn,
and depends on the same local model reliably following instructions that the citation and rerank
prompts already ask a lot of. A raw distance threshold is cheaper, deterministic, and testable without
a live model — exactly what a gate needs to be trustworthy.

## Trade-offs

- **`maxVectorDistance` is an unmeasured starting heuristic** (0.6 across all four named profiles, for
  now) — a real false-negative/false-positive rate on this needs Phase 4's golden dataset, not
  invented (AGENTS.md rule 2).
- **Query normalization (stage 1) is an LLM call on every turn with history**, adding latency even
  when the query was already self-contained. It falls back to the original query on any failure, but
  the cost is real and always paid when history exists.
- **`RagPipeline.answer`'s `Flux<RagAnswerChunk>` is a second streaming-chunk shape**, parallel to
  `ai-provider`'s `ChatChunk` rather than reusing it, because citations need a slot `ChatChunk` has no
  business knowing about. Deliberately mirrors `ChatChunk`'s exact idiom (static factories, boolean
  `last` + nullable `aggregate`) so it doesn't read as a new pattern, just the same one extended.

## Consequences

- A conversation's mode (plain chat vs. RAG-augmented) is a single nullable field, not a second
  endpoint — `POST /conversations/{id}/messages` behaves identically to Phase 1 whenever no profile
  is in play, so nothing about that contract needed to change to add this one.
- Adding a genuinely different orchestration later (e.g. multi-step retrieval, agentic RAG in Phase 6)
  extends `RagPipeline` or adds a sibling service in `rag`, without touching `conversation` or the
  module boundary this ADR settled.
- The abstention threshold and MMR's `λ` are both named as tuning targets for Phase 4's evaluation
  harness — this ADR is the defensible starting point that work would measure against, not a claim
  that either constant is right.
