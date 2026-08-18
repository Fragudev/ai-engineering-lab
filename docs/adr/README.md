# Architecture Decision Records

Every significant decision is recorded here: what was decided, what else was considered, what it
cost, and what it makes true. Each follows [the template](0000-template.md) — Context, Decision,
Alternatives, Trade-offs, Consequences.

An ADR is not deleted when it stops being true. It is marked superseded, with a link to the record
that replaced it. The history of decisions is more informative than the current state alone.

## Accepted

| # | Decision | Phase |
|---|---|---|
| [0001](0001-build-and-project-structure.md) | Maven multi-module build on Java 25 | 0 |
| [0002](0002-modular-monolith.md) | Modular monolith with Spring Modulith | 0 |
| [0003](0003-persistence-and-vector-store.md) | PostgreSQL with pgvector as the single datastore | 0 |
| [0004](0004-ai-provider-abstraction.md) | Project-owned provider interfaces with capability negotiation | 1 |
| [0005](0005-kafka.md) | Kafka for ingestion, with a Modulith outbox and JSON Schema contracts | 2 |
| [0006](0006-chunking-strategy.md) | Fixed-size, paragraph-aware chunking | 2 |
| [0007](0007-hybrid-retrieval-and-fusion.md) | Hybrid retrieval, Reciprocal Rank Fusion, and reranking without a cross-encoder | 3 |
| [0008](0008-rag-pipeline-architecture.md) | RAG pipeline architecture: orchestration, citations, and the abstention gate | 3 |

## Planned

Written when the phase that needs them begins — not before. An ADR written ahead of the work it
describes is a guess wearing a decision's clothes.

| # | Decision | Phase |
|---|---|---|
| 0009 | Tool design, schemas and security boundaries | 5 |
| 0010 | Agent orchestration: state machines over autonomy | 6 |
| 0011 | Internal tools vs MCP vs external tool servers | 7 |
| 0012 | Observability conventions and GenAI semantic attributes | 8 |

## Writing one

Copy [`0000-template.md`](0000-template.md), take the next number, and be honest in the Alternatives
and Trade-offs sections. An ADR whose alternatives are all obviously wrong tells a reader the
decision was never examined — which is worse than not writing one at all.
