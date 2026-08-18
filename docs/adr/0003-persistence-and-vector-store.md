# ADR-0003: PostgreSQL with pgvector as the single datastore

- **Status:** Accepted
- **Date:** 2026-08-18
- **Phase:** 0

## Context

The system stores relational data (conversations, messages, documents, jobs, workflow runs,
evaluation results) and vector data (chunk embeddings for similarity search). These could live in one
store or two.

A related decision cannot be deferred: the embedding model determines the vector dimension, and the
dimension is part of the schema. Changing it invalidates every stored vector.

## Decision

**PostgreSQL 17 with the pgvector extension** as the single datastore, with an HNSW index on
embeddings. Migrations via Flyway, forward-only.

The embedding model is fixed project-wide at **`bge-m3`, 1024 dimensions**, verified at startup
against the schema.

## Alternatives considered

### A dedicated vector database (Qdrant, Weaviate, Milvus)

Better recall and latency at scale, richer filtering, purpose-built operational tooling.

Rejected because it introduces a second datastore and therefore a consistency problem: a document
and its chunks would live in PostgreSQL while its vectors lived elsewhere, with no transaction
spanning both. Every write path would need reconciliation for partial failures, and the failure modes
are unpleasant — orphaned vectors, chunks with no embeddings, a delete that half-succeeds.

At this corpus size the performance argument does not apply. Buying a distributed-consistency problem
to solve a performance problem that does not exist is the wrong trade. The `knowledge` module's
interface is deliberately the seam where this decision could be revisited under real load.

### An in-memory or file-based vector store

Simplest, no extra infrastructure. Rejected as unserious for a project positioning itself as
production-oriented, and it eliminates the hybrid-search option entirely.

### Separate operational and vector databases, both PostgreSQL

Two instances, isolating vector query load from transactional load. Rejected as premature: it doubles
the operational surface to solve a contention problem that has not been observed.

## Trade-offs

- **pgvector is slower than a dedicated vector database at scale.** Fine at thousands of chunks,
  a real constraint at tens of millions. Accepted, with the migration seam identified.
- **HNSW indexes are memory-hungry** and slower to build than IVFFlat. Chosen anyway: IVFFlat needs
  a representative training set, which does not exist when the index starts empty — a footgun that
  produces quietly poor recall rather than an error.
- **The vector dimension is a schema commitment.** Changing embedding models means a full reindex.
- **Everything shares a failure domain.** PostgreSQL down means the entire system is down.

## Consequences

- Documents, chunks and vectors are written in a single transaction. No reconciliation, no orphans,
  no partial-delete cleanup job.
- **Hybrid search comes for free**: PostgreSQL full-text search sits in the same table as the
  vectors, so combining lexical and semantic retrieval requires no additional infrastructure
  (ADR-0007).
- Metadata filtering is ordinary SQL against JSONB with a GIN index, rather than a vector database's
  bespoke filter dialect.
- Backup and restore is one `pg_dump`.
- `scripts/reindex.sh` must exist from Phase 2, and `scripts/bootstrap.sh` must verify the loaded
  model's dimensions before startup. A dimension mismatch that reaches runtime produces symptoms that
  look nothing like their cause, so it is caught at the door.
- Migrating to a dedicated vector store later means reimplementing the `knowledge` module's
  repository behind its existing interface, plus a dual-write or backfill period. Contained, but not
  trivial.
