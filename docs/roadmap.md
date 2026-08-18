# Roadmap

Nine phases. **Every phase leaves the repository in a presentable state** — green CI, updated
README, a working demo, and ADRs for the decisions it introduced.

That constraint is the point. A project that stops cleanly at Phase 4 is more convincing than one
with nine half-finished phases, so each phase is scoped to be a defensible stopping point rather
than a step toward some distant completion.

| Phase | Theme | State |
|---|---|---|
| [0](#phase-0--foundations) | Foundations | Not started |
| [1](#phase-1--chat-vertical-slice) | Chat vertical slice | Not started |
| [2](#phase-2--asynchronous-ingestion) | Asynchronous ingestion | Not started |
| [3](#phase-3--rag) | RAG | Not started |
| [4](#phase-4--evaluation) | Evaluation | Not started |
| [5](#phase-5--tools) | Tools | Not started |
| [6](#phase-6--agentic-workflow) | Agentic workflow | Not started |
| [7](#phase-7--mcp) | MCP | Not started |
| [8](#phase-8--hardening-and-presentation) | Hardening and presentation | Not started |

---

## Phase 0 — Foundations

Repository skeleton, Maven multi-module build with wrapper, Docker Compose, health checks, OpenAPI
scaffolding, observability wiring, CI pipeline, README and first architecture diagram.

**ADRs:** [0001](adr/0001-build-and-project-structure.md) build and structure ·
[0002](adr/0002-modular-monolith.md) modular monolith ·
[0003](adr/0003-persistence-and-vector-store.md) persistence and vector store.

**Acceptance**

- `docker compose up` and `./mvnw verify` both succeed on a clean machine following only the README.
- ArchUnit and Spring Modulith boundary tests run and pass with the modules empty.
- A trace from a health check request is visible in Grafana.
- CI runs the full build on every push.

---

## Phase 1 — Chat vertical slice

One thin slice through every layer: provider abstraction with the `lmstudio` and `recorded`
adapters, conversations, messages, SSE streaming, minimal UI, tracing and token accounting.

**ADR:** [0004](adr/0004-ai-provider-abstraction.md) AI provider abstraction.

**Acceptance**

- A multi-turn conversation streams token-by-token in the browser.
- Every answer records its model, prompt and completion tokens, latency and estimated cost.
- Each answer links to its trace in Grafana.
- A provider timeout surfaces as a typed error in the UI, not a hung request.
- CI passes without a model server running.

---

## Phase 2 — Asynchronous ingestion

Upload endpoint, transactional outbox via Spring Modulith, Kafka topics, the
parse → chunk → embed → index pipeline, idempotency, retries, dead-letter topic, job status, and an
ingestion dashboard.

**ADRs:** [0005](adr/0005-kafka.md) Kafka and event contracts · 0006 chunking strategy.

**Acceptance**

- Uploading a document indexes it end to end, with job status observable throughout.
- Uploading the same file twice does not reindex it (content hash deduplication).
- A fault injected into the embedding stage exhausts its retries, lands in the dead-letter topic,
  leaves the job in `FAILED` with a populated `last_error`, and produces a trace showing every
  attempt.
- Redelivering a consumed event is a no-op.
- The whole flow is one connected trace from `POST /documents` to the final chunk.

---

## Phase 3 — RAG

Hybrid retrieval, filtering, optional reranking, context building, citation extraction, the
retrieval debug endpoint, and named RAG profiles.

**ADRs:** 0007 hybrid retrieval and fusion · 0008 RAG pipeline architecture.

**Acceptance**

- Questions about the demo corpus are answered with citations that link back to source documents.
- `POST /retrieval:search` shows candidates and scores before and after fusion and reranking.
- At least two RAG profiles are selectable per request.
- A question with no supporting context produces an explicit "insufficient context" answer rather
  than an invented one.

---

## Phase 4 — Evaluation

A golden dataset of roughly fifty cases over the demo corpus, an evaluation runner, deterministic
metrics, a secondary LLM judge, a profile comparison report, and a nightly CI job on the `recorded`
provider.

**Acceptance**

- `./scripts/eval.sh` produces a report comparing at least two RAG profiles on recall@k, MRR,
  citation precision, p50/p95 latency and token cost.
- Reports are committed with date, chat model and hardware recorded.
- The report states the limitations of its own methodology, including the weakness of local-model
  judging.
- Nightly CI runs the harness against recorded fixtures and fails on regression beyond a threshold.

> **This is the line.** At the end of Phase 4 the project supports a full architecture conversation:
> RAG, vector search, hybrid retrieval, event-driven processing, idempotency, retries, dead-lettering,
> observability, evaluation methodology and cost. Everything after this adds surface area. Nothing
> after this rescues it.

---

## Phase 5 — Tools

Tool registry, JSON Schema validation, scope-based authorization, timeouts, and tool calling in chat
with a fallback path for models lacking native support. Initial tools: calculator, knowledge base
search, and a mock external API.

**ADR:** 0009 tool design and security boundaries.

**Acceptance**

- A tool call with invalid arguments is rejected with a structured error the model can act on.
- An unauthorized tool request is denied by scope check before execution.
- A tool exceeding its timeout is cancelled and reported, without hanging the conversation.
- Tool calling works on a model without native support, via the structured-output fallback.

---

## Phase 6 — Agentic workflow

One workflow — *documentation research*: plan sub-queries → retrieve in parallel → extract per
source → synthesise → self-check against citations → answer. An explicit state machine with
persisted state, compensation, and resumability.

**ADR:** 0010 agent orchestration — where determinism beats autonomy, and why.

**Acceptance**

- A run survives an application restart and resumes from its last completed step.
- Each step's input, output, attempts and cost are inspectable.
- A failing step triggers compensation rather than leaving a partial result.
- The ADR documents which steps genuinely need an LLM and which were kept deterministic.

---

## Phase 7 — MCP

The tool registry exposed as an MCP server; an external MCP server consumed as a client.

**ADR:** 0011 internal tools vs MCP vs external tool servers, with the security boundary of each.

**Acceptance**

- An external MCP client can discover and invoke the tools.
- An external MCP server's tools are usable in chat, subject to the same authorization and timeouts.
- The ADR is explicit about the trust implications of a third-party tool server.

---

## Phase 8 — Hardening and presentation

Complete threat model, security scanning and SBOM, a measured latency baseline, sequence diagrams,
the theoretical cloud deployment section, README polish, and a recorded demo.

**Acceptance**

- Dependency, container and secret scanning run in CI.
- A CycloneDX SBOM is published with releases.
- Latency figures in the documentation come from a reproducible measurement, with hardware recorded.
- A technical reviewer understands the system in five minutes without opening a source file.

---

## Deliberately deferred

Not oversights. Each is a decision with a reason, and each would be the first candidate if the
project continued.

| Deferred | Why |
|---|---|
| Multi-tenancy and row-level authorization | Cross-cutting complexity that would slow every phase for a system with one user |
| Kubernetes | Demonstrates configuration, not architecture |
| Schema Registry with Avro | One producer per topic; JSON Schema covers it |
| Trained reranker | Model training is a different discipline from this project's focus |
| Semantic caching | Meaningful only once real query patterns exist |
| Rich UI | Would consume time disproportionate to what it demonstrates |
| Graph RAG, multi-hop retrieval | Worth doing only after the baseline is measured |
