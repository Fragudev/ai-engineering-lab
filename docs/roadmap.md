# Roadmap

Nine phases. **Every phase leaves the repository in a presentable state** — green CI, updated
README, a working demo, and ADRs for the decisions it introduced.

That constraint is the point. A project that stops cleanly at Phase 4 is more convincing than one
with nine half-finished phases, so each phase is scoped to be a defensible stopping point rather
than a step toward some distant completion.

| Phase | Theme | State |
|---|---|---|
| [0](#phase-0--foundations) | Foundations | Complete |
| [1](#phase-1--chat-vertical-slice) | Chat vertical slice | Complete |
| [2](#phase-2--asynchronous-ingestion) | Asynchronous ingestion | Complete |
| [3](#phase-3--rag) | RAG | Complete |
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

- [x] `docker compose up` succeeds: postgres (pgvector), Kafka (KRaft), kafka-ui and the
      `grafana/otel-lgtm` observability stack all reach a healthy state, and `app` builds and starts
      against them. Verified locally with a real Java 25 toolchain, via the app's own multi-stage
      Docker build — the host in this session only had Java 17, so `./mvnw verify` could not be run
      there directly; see AGENTS.md, Environment constraints.
- [x] ArchUnit and Spring Modulith boundary tests run and pass with the modules empty. Verified with
      a real JDK 25 container: `ModuleBoundaryTest` (`ApplicationModules.verify()`) and
      `ArchitectureTest` both green.
- [x] A trace from a health check request is visible in Grafana. Verified: `GET /actuator/health`
      produced a trace queried back through both Tempo's API and Grafana's own datasource proxy,
      with `rootServiceName: ai-engineering-lab`.
- [x] CI runs the full build on every push. `.github/workflows/ci.yml` (JDK 25, `./mvnw verify`) is
      green on [PR #1](https://github.com/Fragudev/ai-engineering-lab/pull/1): all 3 tests pass,
      including `HealthCheckIntegrationTest` — the one that couldn't be verified locally because this
      session's machine only runs Docker inside a VM (Rancher Desktop), which breaks
      Docker-outside-of-Docker for Testcontainers; GitHub Actions' native Docker has no such
      limitation.

Three real Spring Boot 4.1 / Testcontainers 2.x breaking changes were found and fixed by actually
building against them, not by inspection: `org.testcontainers:postgresql` and `:junit-jupiter`
renamed to `testcontainers-postgresql` / `testcontainers-junit-jupiter`; `TestRestTemplate` moved to
the `spring-boot-resttestclient` module (replaced with `RestTestClient`); and Flyway's
auto-configuration moved out of `spring-boot-autoconfigure` into its own `spring-boot-starter-flyway`
module — without it, Flyway silently did nothing (no error, no log line, no migration).

Error Prone + NullAway (AGENTS.md's nullability convention) are deliberately not wired into the build
yet: enforcing them now would add build fragility with nothing real to check, since every module is
still an empty package-info skeleton. They'll be added when Phase 1 introduces real logic to enforce
against, to avoid the scope-creep AGENTS.md rule 6 warns against.

---

## Phase 1 — Chat vertical slice

One thin slice through every layer: provider abstraction with the `lmstudio` and `recorded`
adapters, conversations, messages, SSE streaming, minimal UI, tracing and token accounting.

**ADR:** [0004](adr/0004-ai-provider-abstraction.md) AI provider abstraction.

**Acceptance**

- [x] A multi-turn conversation streams token-by-token. Verified live end to end: `POST
      /api/v1/conversations` then `POST .../messages` (`Accept: text/event-stream`) streams
      `token` events word by word, then `usage`, then `done`. The static `index.html` UI consumes
      this via `fetch()` + a hand-rolled SSE parser, since native `EventSource` can't do POST.
- [x] Every answer records its model, prompt and completion tokens, latency and estimated cost.
      Verified via `GET .../messages` after a live streamed exchange: both messages persisted, the
      assistant one carrying `model`, `promptTokens`, `completionTokens`, `latencyMs` and
      `estimatedCostUsd` (genuinely `$0.00` for `lmstudio`/`recorded` — no invented pricing table;
      see AGENTS.md rule 2). `message.estimated_cost_usd` is a new column beyond architecture.md
      §7's original listing, added here and reflected there.
- [x] Each answer links to its trace in Grafana. Verified: the `usage` event's `traceId` matched
      exactly what Tempo/Grafana returned for that request when queried the same way as Phase 0's
      health-check trace.
- [x] A provider timeout surfaces as a typed error in the UI, not a hung request. Verified two ways:
      a fast unit test (`LmStudioChatProviderTest`, a `ChatModel` that never emits, real
      `ProviderTimeoutException` after the configured timeout) and live against an unreachable
      LM Studio endpoint end-to-end, which surfaced an `error` SSE event immediately (~0.5s, not
      hung). The live check caught a real bug: the openai-java SDK's connection failure did not
      always arrive as the exact `com.openai.errors.OpenAIIoException` type `onErrorMap` was
      matching on — it can arrive wrapped — so the error fell through to a bare 500 instead of 502.
      Fixed by mapping anything that isn't already one of this adapter's own typed exceptions,
      rather than matching the SDK's exception type exactly.
- [x] CI passes without a model server running. Confirmed on the real GitHub Actions run for
      [PR #2](https://github.com/Fragudev/ai-engineering-lab/pull/2): `ConversationFlowIntegrationTest`
      (Testcontainers, `recorded` profile) passes under CI's native Docker — this session's local
      Docker-outside-of-Docker limitation doesn't apply there. Getting to green also caught two real
      issues that local spot-checks had missed: `spotless:check` formatting violations (verification
      had only run targeted `compile`/`test` goals locally, never the full `mvnw verify` CI actually
      runs), and a brittle test assertion checking for a multi-word phrase in the raw SSE response
      body — the fixture text streams one word per `data:` line, so the phrase is never contiguous;
      fixed to check single words and rely on the persisted-message assertions for the full text.

CI was also split into three steps instead of one opaque `mvnw verify`: a fail-fast formatting check,
the build/test, and a `docker build` of `app/Dockerfile` — the actual shippable artifact, which the
Maven build alone never exercised and which is exactly where this session's Phase 0 and Phase 1 real
bugs surfaced.

Scope notes: only the `lmstudio` and `recorded` adapters were built, per the roadmap's own wording
(`openai`/`anthropic` deferred); Resilience4j retry/circuit-breaking deferred to Phase 2, where
retry-exhaustion is the roadmap's own acceptance criterion; the `RecordedChatProvider` does not emit
the same `gen_ai.chat` Micrometer span the `lmstudio` adapter does (nothing exercises it under
`recorded`, and adding it wasn't required by any acceptance criterion) — a known, minor asymmetry
rather than a gap in the criteria above.

---

## Phase 2 — Asynchronous ingestion

Upload endpoint, transactional outbox via Spring Modulith, Kafka topics, the
parse → chunk → embed → index pipeline, idempotency, retries, dead-letter topic, job status, and an
ingestion dashboard.

**ADRs:** [0005](adr/0005-kafka.md) Kafka and event contracts ·
[0006](adr/0006-chunking-strategy.md) chunking strategy.

**Acceptance**

- [x] Uploading a document indexes it end to end, with job status observable throughout. Verified
      live against the full `docker compose` stack (`recorded` embedding profile, since this
      environment has no LM Studio running): `POST /api/v1/documents` → `202` with a `Location` →
      polling `GET /api/v1/ingestion/jobs/{id}` through `UPLOADED → PARSED → CHUNKED → INDEXED` →
      `chunk` row persisted with a real 1024-dim embedding. Also covered by
      `IngestionFlowIntegrationTest` (Testcontainers, real Kafka + Postgres).
- [x] Uploading the same file twice does not reindex it (content hash deduplication). Verified live:
      re-uploading identical bytes returned `200` with no `Location`, the same document id, and no
      new `chunk`/`document` rows. Also covered by `IngestionFlowIntegrationTest`.
- [x] A fault injected into the embedding stage exhausts its retries, lands in the dead-letter topic,
      leaves the job in `FAILED` with a populated `last_error`, and produces a trace showing every
      attempt. Verified live: LM Studio being unreachable in this environment is itself a real fault
      — retries backed off, the message landed on `ingestion.chunks.created.v1.dlt` (inspected via
      `kafka-console-consumer`), and the job reached `FAILED` with `last_error: "Connection refused"`.
      Also covered by `IngestionFailureIntegrationTest` (a `@TestConfiguration`-overridden
      `EmbeddingProvider` that always throws), asserting `last_error` contains the injected message.
- [x] Redelivering a consumed event is a no-op. Covered by `IdempotencyGuardIntegrationTest`, which
      exercises `IdempotencyGuard` directly against a real Postgres rather than a full raw-Kafka
      redelivery — see the test's own javadoc for why (avoiding a module-boundary violation from
      importing `ingestion.internal` types into `app`'s tests, and the separate risk of hand-crafting
      Spring's exact Kafka JSON+type-header wire format). The two other ingestion tests already prove
      real Kafka consumption works end to end.
- [x] The whole flow is one connected trace from `POST /documents` to the final chunk — interpreted
      and verified precisely, not assumed: every event carries the upload's `correlationId`, and every
      stage's active span is tagged with it (`io.micrometer.tracing.Tracer`). Queried Tempo directly
      for a `correlationId` and found every related span across the flow, confirming the filtering
      guarantee works — but genuinely as **two separate trace IDs** (the HTTP upload request, and the
      Kafka-externalized publish chain), not one unbroken W3C trace, since Modulith's event
      externalization runs the Kafka publish asynchronously after transaction commit. That's the
      honest mechanism this criterion actually rests on.

Five real bugs surfaced only by actually running the stack, not by compiling or code review:

- `spring-modulith-events-jpa`'s outbox needs a Jackson-based `EventSerializer` bean
  (`spring-modulith-events-jackson`), which isn't pulled in transitively — without it the app fails
  to start at all.
- The `document`/`chunk` `metadata` `jsonb` columns, mapped via a hand-written
  `AttributeConverter<Map, String>` (to sidestep the Hibernate 7 / Jackson 3 `FormatMapper` gap noted
  during planning), also need `@JdbcTypeCode(SqlTypes.JSON)` on the field — without it Postgres
  rejects the bind as `varchar` being assigned to a `jsonb` column.
- The event-publication registry table does not auto-provision itself when
  `spring.jpa.hibernate.ddl-auto=none` (this project is Flyway-only) — the original plan assumed it
  would; a V4 migration now creates it explicitly, with `serialized_event` as `TEXT` rather than
  Hibernate's raw default of `VARCHAR(255)` (too small for a base64-encoded file payload).
- `IngestionFailureRecoverer`'s `instanceof EventEnvelope` check on the raw Kafka record never
  matched, so job failures were silently never recorded (the message still reached the DLT correctly,
  masking the bug): Boot's default listener setup only converts the record to its typed event at
  `@KafkaListener` parameter binding, so the error-handling path — which runs earlier — only ever
  sees raw JSON bytes. Rewritten to pull `documentId`/`correlationId`/`eventId` out by field name
  instead, which every one of the 3 triggering event types names identically.
- `FailureRecording` was capturing the wrapping `ListenerExecutionFailedException`'s generic message
  instead of the actual root cause; fixed with `NestedExceptionUtils.getMostSpecificCause`.

Scope notes, named rather than silently dropped: parsing is limited to `text/plain` and
`text/markdown` (real format parsing is a separate concern); the `Idempotency-Key` HTTP header on
`POST /documents` is still unhandled (a standing gap shared with Phase 1's endpoints, not a
Phase-2-specific regression); `scripts/fetch-corpus.sh` / `corpus/MANIFEST.yml` population is
deferred to Phase 3, when the demo corpus is actually needed.

---

## Phase 3 — RAG

Hybrid retrieval, filtering, optional reranking, context building, citation extraction, the
retrieval debug endpoint, and named RAG profiles.

**ADRs:** [0007](adr/0007-hybrid-retrieval-and-fusion.md) hybrid retrieval and fusion ·
[0008](adr/0008-rag-pipeline-architecture.md) RAG pipeline architecture.

**Acceptance**

- [x] Questions about the demo corpus are answered with citations that link back to source
      documents. Verified via `RagFlowIntegrationTest` (Testcontainers, real Postgres): a
      retrieval-augmented turn returns `citation` SSE events and persists `Citation` rows linked to
      the source chunk/document, with the `[N]` markers themselves stripped from both the streamed
      text and the persisted message content. Also live-verified structurally against the full
      `docker compose` stack: `GET /rag/profiles`, `POST /retrieval:search` (real per-candidate
      vector/lexical/fused/rerank scores against ingested chunks), and the abstention path below —
      the citation-bearing *happy* path specifically relies on `recorded`'s hash-seeded embeddings
      matching by exact string (documented in the test's own javadoc), which live curl verification
      can't stage as easily as a controlled test; the automated test is the real proof here.
- [x] `POST /retrieval:search` shows candidates and scores before and after fusion and reranking.
      Verified live: response includes `vectorDistance`/`lexicalRank` (per-retriever, before
      fusion), `fusedScore` (after fusion), and `rerankScore`/`finalRank` (after reranking) per
      candidate. Also covered by `RetrievalSearchIntegrationTest` across all 4 profiles.
- [x] At least two RAG profiles are selectable per request. Four named profiles ship:
      `dense-only`, `hybrid`, `hybrid-rerank` (MMR), `hybrid-rerank-llm` (LLM reranking) — see
      ADR-0007 for why two reranking techniques exist when no cross-encoder model is available.
      `GET /api/v1/rag/profiles` lists them live; `RetrievalSearchIntegrationTest` exercises all
      four, including the LLM-reranker's fallback-to-fused-order path when the local model's
      response can't be parsed as a ranking.
- [x] A question with no supporting context produces an explicit "insufficient context" answer
      rather than an invented one. Verified live: a real question against a real ingested document,
      under `recorded` embeddings (semantically meaningless, so no real match), correctly triggered
      the abstention gate — `"The knowledge base doesn't contain enough information to answer this
      question"`, `model: "none"`, zero citations persisted (confirmed against the `citation` table
      directly). Also covered by `RagFlowIntegrationTest`. The gate is a deterministic threshold on
      raw vector distance, not a fused score or a prompt instruction alone — see ADR-0008's
      "alternatives considered" for why.

Two design corrections made during implementation, not assumed going in: the original module-boundary
reading had `rag` owning hybrid search; `docs/architecture.md` #3 actually assigns "hybrid search,
reranking" to `knowledge`, so `HybridSearchService` and both rerankers live there, and `rag` is purely
the normalize → build-context → generate → extract-citations orchestration over it (ADR-0008). And a
filter-by-fused-score step, planned as part of the retrieve→filter→rerank pipeline, was dropped after
realizing RRF scores reflect rank position, not absolute relevance, and can't tell "nothing relevant
exists" from "here's the best of what's there" — the abstention gate uses raw vector distance instead
(ADR-0007, ADR-0008).

Scope notes: query normalization (rewriting a conversational follow-up into a standalone question) is
a real LLM call, not a stub, but only runs when conversation history exists, and falls back to the
original query on any failure; `RagProfile.maxVectorDistance` (0.6 across all four profiles) and
MMR's `λ` (0.7) are both starting heuristics, explicitly not claimed as measured (AGENTS.md rule 2) —
tuning them against a real golden dataset is Phase 4's job, not invented here.

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
