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
| [4](#phase-4--evaluation) | Evaluation | Complete |
| [5](#phase-5--tools) | Tools | Complete |
| [6](#phase-6--agentic-workflow) | Agentic workflow | Complete |
| [7](#phase-7--mcp) | MCP | Complete |
| [8](#phase-8--hardening-and-presentation) | Hardening and presentation | Complete |

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

A golden dataset over the demo corpus, an evaluation runner, deterministic metrics, a secondary LLM
judge, a profile comparison report, and a nightly CI job on the `recorded` provider.

**Acceptance**

- [x] `./scripts/eval.sh` produces a report comparing at least two RAG profiles on recall@k, MRR,
      citation precision, p50/p95 latency and token cost. Verified live: `fetch-corpus.sh` +
      `seed.sh` against a real running app/Postgres/Kafka stack, then
      `eval.sh --profiles=dense-only,hybrid,hybrid-rerank,hybrid-rerank-llm --repetitions=3` against
      the real seeded corpus, producing
      [`eval/reports/2026-08-22-dense-only-hybrid-hybrid-rerank-hybrid-rerank-llm.md`](../eval/reports/2026-08-22-dense-only-hybrid-hybrid-rerank-hybrid-rerank-llm.md)
      with a real per-profile table (recall@k, MRR, citation precision/recall, abstention accuracy,
      p50/p95, token counts) computed from real `RagPipeline.search`/`.answer()` calls, not stubs.
- [x] Reports are committed with date, chat model and hardware recorded. The committed report and
      its JSON sidecar carry both, per-run.
- [x] The report states the limitations of its own methodology, including the weakness of
      local-model judging. `ReportWriter.limitations()` always renders a "Methodology limitations"
      section (judge weakness, recorded-profile mechanism-only caveat, small/narrow corpus, no human
      eval, mean±spread explanation), present in the committed report.
- [x] Nightly CI runs the harness against recorded fixtures and fails on regression beyond a
      threshold. `.github/workflows/nightly-eval.yml` + `scripts/check-eval-regression.sh`, verified
      two ways: locally against hand-built JSON fixtures (no baseline → pass; small delta → pass;
      large recall drop → fail, exit 1); and locally against the real committed report compared to
      itself as [`eval/baseline.json`](../eval/baseline.json) (all profiles: 0.0000 delta, "no
      regressions detected"). **Not yet verified:** an actual GitHub-hosted run of
      `nightly-eval.yml` itself — `gh workflow run` returned a 404 because GitHub's
      `workflow_dispatch` API only recognizes a workflow once its file exists on the repository's
      default branch, which `phase-4/evaluation` isn't yet. This is a real GitHub platform
      constraint, not a shortcut skipped here: once this PR merges to `main`, either its own
      `05:00 UTC` schedule or a manual `gh workflow run nightly-eval.yml` will produce the first
      real GitHub Actions run, and that should be watched once it happens.

The golden dataset (`eval/dataset/core.yaml`, 28 cases: 12 factual-single-hop, 5 multi-hop, 4
exact-term, 4 unanswerable, 3 ambiguous) is real, individually-verified content, not "roughly fifty"
as originally scoped — a smaller, honestly-stated set, the same kind of scope reduction Phase 2/3
named rather than silently shipped fewer than documented. Every case's `gold_chunk_refs` was checked
against the real fetched corpus content (`corpus/documents/*.md`, downloaded for real via
`scripts/fetch-corpus.sh` — see `corpus/MANIFEST.yml`'s real sha256/`retrieved_at`) and the exact
chunk boundaries `Chunker` deterministically produces for it, first reproduced with a faithful
standalone simulation of `Chunker`'s algorithm, then confirmed for real: after a live `seed.sh` run,
a direct query against the seeded `chunk` table showed exactly 24 chunks (ordinals 0–23) for
`pgvector` and 5 chunks (ordinals 0–4) for `kafka-ui` — matching the simulation exactly — and all 29
`gold_chunk_refs` across the 28 cases fall inside those real ranges, confirming
`GoldChunkResolver` never silently drops a case's gold chunk.

Two real bugs surfaced by actually compiling, not by code review: `EvalCliRunner`'s original
`System.exit(SpringApplication.exit(context, () -> exitCode))` captured a non-final local `exitCode`
mutated inside its own enclosing `try`/`catch` — doesn't compile; fixed by moving the whole run into
a helper method that returns the exit code instead of mutating a captured variable. And
`ReportWriter`'s first JSON-sidecar draft would have serialized an undefined (`Double.NaN`) metric
mean as the non-standard bare `NaN` token — invalid JSON, and exactly the kind of thing that would
silently break `scripts/check-eval-regression.sh`'s `jq` parsing months later; fixed to write `null`
instead, same discipline as `CaseMetrics.toMap()`'s existing NaN handling, and pinned by
`ReportWriterTest`. A third bug surfaced only by a real live run, not by any test: `ReportWriter.fmt()`
formatted numbers with `String.formatted()` under the platform default locale — on this machine that
rendered `0,25 ± 0,00` (comma decimal separators) in the Markdown table instead of `0.25 ± 0.00`,
which would have made a report committed from one machine render differently from one generated on
CI. Fixed with an explicit `Locale.ROOT`; the JSON sidecar (Jackson) was never affected, so
`check-eval-regression.sh`'s `jq`-based parsing was never at risk — but the human-facing report was
wrong until this was actually looked at.

**What the live report actually shows, honestly:** recall@k ranges 0.21–0.38 across profiles, with
`hybrid` and `hybrid-rerank-llm` tied highest and `hybrid-rerank` (MMR) lowest — under the `recorded`
provider's hash-seeded, semantically-meaningless embeddings, this measures whether hybrid retrieval
and reranking mechanically change which chunks surface, not real retrieval quality; a real quality
signal needs a live LM Studio run. Citation precision is `n/a` for every profile (never `0.00`) —
correct behavior: `recorded`'s embeddings essentially never produce a citation-worthy match, so
precision is genuinely undefined rather than zero, exactly as `CitationMetrics.precision`'s own
NaN-vs-zero distinction intends. Abstention accuracy is a real `1.00` across all four
profiles — the four `UNANSWERABLE`-tagged cases correctly triggered the abstention gate every time,
which is expected but not guaranteed under `recorded`'s effectively-random distances.

**Rancher Desktop / Testcontainers, for the next session:** this session's Docker daemon was hung for
its entire first half (VM process alive, socket returning `EOF`) and needed a hard restart (kill the
stale `lima`/`qemu` processes, relaunch the app) to recover — a `docker version` retry loop alone
never resolved it. Once Docker was back, `./mvnw verify` still failed twice more, for two more
Rancher-Desktop-specific reasons, neither a real code defect: (1) Testcontainers' Docker-environment
auto-detection didn't pick up Rancher Desktop's non-standard socket
(`~/.rd/docker.sock`) even though the `docker` CLI itself worked fine via its context —
fixed by exporting `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` to that socket path; (2)
the `testcontainers/ryuk` cleanup sidecar container failed its own startup wait strategy on this VM
— fixed with the standard `TESTCONTAINERS_RYUK_DISABLED=true` escape hatch (Testcontainers still
cleans up via JVM shutdown hooks without it; confirmed no leftover containers after the run). Both are
environment variables, not code changes, and both are widely-documented Rancher Desktop workarounds
— recorded here since they cost real time and will recur on this machine.

> **This is the line.** At the end of Phase 4 the project supports a full architecture conversation:
> RAG, vector search, hybrid retrieval, event-driven processing, idempotency, retries, dead-lettering,
> observability, evaluation methodology and cost. Everything after this adds surface area. Nothing
> after this rescues it.

---

## Phase 5 — Tools

Tool registry, JSON Schema validation, scope-based authorization, timeouts, and tool calling in chat
with a fallback path for models lacking native support. Initial tools: calculator, knowledge base
search, and a mock external API. Also builds the full interactive tool-call confirmation flow
docs/threat-model.md's T2 names as "the structural control" — a real user choice made during
planning to build the complete pause/approve/resume mechanism rather than a lighter stand-in, and to
cover both the plain-chat and RAG-chat paths rather than RAG-only.

**ADR:** [0009](adr/0009-tool-design-and-security-boundaries.md) tool design and security
boundaries — resolves a real contradiction between `docs/architecture.md` §3's dependency table and
ADR-0004/§8's own text (both already assumed `tools` depends on `ai-provider`, which §3 didn't
grant), documents the confirmation-gate's latching design, and the module-composition pattern for
`KnowledgeBaseSearchTool`.

**Acceptance**

- [x] A tool call with invalid arguments is rejected with a structured error the model can act on.
      Verified live and in `SchemaValidatorTest`/`ToolInvokeIntegrationTest`; in the chat loop, the
      structured error is fed back as a `TOOL`-role message and the model gets a real chance to
      retry (`ToolCallingChatServiceTest.invalidArgumentsAreFedBackAndTheModelRetries`).
- [x] An unauthorized tool request is denied by scope check before execution. Verified live
      (`GET /api/v1/tools/knowledge-base-search:invoke` → 403 with `knowledge-base:search` excluded
      from `ai.tools.granted-scopes`) and in the chat loop
      (`ToolCallingChatServiceTest.scopeDeniedShortCircuitsWithoutExecutingTheTool` — asserts the
      tool's own `execute()` is never called).
- [x] A tool exceeding its timeout is cancelled and reported, without hanging the conversation.
      Verified live (504 via a test-only slow tool) and in the chat loop
      (`ToolCallingChatServiceTest.timeoutIsReportedWithoutHangingTheStream` — a tool that sleeps 2s
      against a 50ms timeout still completes the test in well under 2s).
- [x] Tool calling works on a model without native support, via the structured-output fallback.
      This is the only path either real adapter (`recorded`, `lmstudio`) can exercise — both report
      `supportsNativeToolCalling() == false` — so every one of the above *is* the fallback path,
      proven end to end live (`curl` against a real running app: `what is 12 times 7` → real
      `tool_call`/`tool_result` SSE events → a real calculator evaluation of `12 * 7` → the fixture
      follow-up `"12 times 7 is 84."`) and via `ToolCallingInPlainChatIntegrationTest`.
- [x] (Not a stated roadmap criterion, but the reason this phase took the shape it did) The
      confirmation gate: a RAG-context turn's tool call pauses the SSE stream with a
      `tool_call_pending` event; `POST /api/v1/tool-calls/{callId}:confirm` resumes the *same*
      stream. Proven by the flagship `ConversationToolConfirmationIntegrationTest` — a raw
      `HttpClient` reads the SSE response on a background thread while the main thread posts the
      confirmation concurrently — covering both outcomes: confirmed (resumes, completes normally)
      and never confirmed (resolves to a real `TIMEOUT` outcome, still reaches `done`, never hangs).

Two real bugs surfaced only by running the confirmation flow live against a real Postgres/Testcontainers
stack, not by code review or the pure-unit-test suite (which used simpler synchronous stand-ins that
didn't exercise the actual thread-hopping): (1) `ToolInvoker`'s first implementation called `.block()`
on a `Mono` from inside `ToolCallingChatService`'s reactive chain — safe-looking in isolation, but
that chain runs on the same Reactor `parallel` scheduler the model's own streamed response flows
through, where Reactor's own blocking-call guard rejects a synchronous `block()` outright. Fixed by
making `ToolInvoker.invokeForChat` return `Mono<ToolCallResult>` and never block; `invokeOrThrow` (the
direct REST path, a plain servlet-thread MVC controller method, genuinely safe to block) still calls
`.block()` at its own boundary. (2) `PendingConfirmationRegistry.await`'s first version used Reactor's
timeout-with-fallback-value overload (`.timeout(timeout, Mono.just(false))`), which made "the user
explicitly rejected this call" and "nobody answered before the deadline" collapse into the same
`false` signal — an unconfirmed call was reported as `DENIED` instead of `TIMEOUT`. Fixed by letting
the timeout propagate as a real error instead, which `ToolCallingChatService`'s existing
`.onErrorReturn(TIMEOUT)` branch was already written to catch (dead code until this fix landed).

Scope reductions, named rather than silently dropped (see ADR-0009's Trade-offs section for the
reasoning behind each): native tool-calling is designed for but not exercised (no adapter can
produce `supportsNativeToolCalling() == true`); confirmation state is not resumable across an app
restart (Phase 6/`workflow`'s job); the tool-calling loop is bounded (`max-calls-per-turn`, default
3), not a general agentic loop; the mock external API performs no real network egress, so T4 (SSRF)
mitigations are deferred until a real external-API tool exists; `granted-scopes` is one global
config list, since no real principal exists yet; the peek-based tool-call sniffer can misdetect a
legitimate answer that happens to start with `{`.

---

## Phase 6 — Agentic workflow

One workflow — *documentation research*: plan sub-queries → retrieve in parallel → extract per
source → synthesise → self-check against citations → answer. An explicit state machine with
persisted state, compensation, and resumability.

**ADR:** [0010](adr/0010-agent-orchestration.md) agent orchestration — where determinism beats
autonomy, and why. Documents which steps need an LLM (`plan-sub-queries`, `extract-per-source`,
`synthesise`) vs. stayed deterministic (`retrieve`, `self-check`, `answer`), the workflow-owns-its-loop
composition pattern (ADR-0009's precedent), and a real dependency-table correction found during
implementation (`workflow → knowledge`, the same category of gap ADR-0009 fixed for `tools →
ai-provider`).

**Acceptance**

- [x] A run survives an application restart and resumes from its last completed step. Verified live
      against a real `docker compose` deployment (`recorded` profile): seeded a `WorkflowRun`
      directly in Postgres with `plan-sub-queries` and `retrieve` already `SUCCEEDED` (simulating an
      interrupted process), `docker kill -s SIGKILL` on the running app container, restarted it, and
      confirmed via `GET /api/v1/workflows/runs/{id}` that execution resumed from `extract-per-source`
      — the two already-completed steps' timestamps were untouched, proving they were skipped, not
      redone. `WorkflowRunIntegrationTest`/`WorkflowCompensationIntegrationTest` cover the
      surrounding mechanics (persistence, compensation) under Testcontainers; a genuine mid-flight
      process interruption isn't reproduced in the automated suite — see ADR-0010's Trade-offs for
      why (the `recorded` profile's fixture-backed LLM calls resolve too fast to reliably catch a run
      mid-stage without test-only production hooks this project doesn't add).
- [x] Each step's input, output, attempts and cost are inspectable. Verified in
      `WorkflowRunIntegrationTest` (all six steps present with real attempts/cost) and live via
      `GET /api/v1/workflows/runs/{id}`'s nested `steps` array.
- [x] A failing step triggers compensation rather than leaving a partial result. Verified in
      `WorkflowCompensationIntegrationTest` (an empty knowledge base's `retrieve` stage exhausts its
      retries, the run ends `FAILED` with `failedStage`/`reason` populated, not left `RUNNING`) and
      live, twice over — see the real bug below, caught by the exact same mechanism.
- [x] The ADR documents which steps genuinely need an LLM and which were kept deterministic — see
      ADR-0010's table.

One real bug surfaced only by the live restart check, not by the unit or integration test suite
(which never happened to exercise the corrective-retry path with a fixture that produces *invalid*
citation markers): `DocumentationResearchEngine.synthesiseStage`'s corrective-retry prompt was built
as `"A" + "B".formatted(x, y)` — Java parses this as `"A" + ("B".formatted(x, y))`, so `.formatted`
bound only to the second string literal, which has one `%d` placeholder, and got called with two
arguments (`Set<Integer>`, `int`) — `IllegalFormatConversionException`, surfaced as the compensated
run's `reason`: `"d != java.util.LinkedHashSet"`. Fixed by parenthesizing the full concatenated
string before calling `.formatted(...)`. The same live check, rerun after the fix, completed
end-to-end successfully. This is the same pattern Phase 5's two real bugs followed — caught by
running the thing for real, not by code review or the pure-unit-test suite, which used simpler
inputs that never happened to trigger the corrective-retry branch.

Scope reductions, named rather than silently dropped (see ADR-0010's Trade-offs section for the
reasoning behind each): the LLM-call budget resets per engine invocation, not cumulatively across a
restart+resume; fan-out sub-task attempts (one retrieval per sub-query, one extraction per source)
aren't separately queryable rows, only visible inside their owning stage's output JSON; no separate
`COMPENSATING`/`COMPENSATED` status, since every stage is read-only/computational; one workflow type
only (`documentation-research`); no list-runs or list-steps endpoint; `workflow → tools` stays a real,
declared dependency (matches architecture.md) that this one workflow doesn't exercise.

---

## Phase 7 — MCP

The tool registry exposed as an MCP server; an external MCP server consumed as a client.

**ADR:** [0011](adr/0011-mcp-tool-exposure-boundaries.md) internal tools vs MCP vs external tool
servers, with the security boundary of each — including a real, scoped amendment to AGENTS.md's
"Spring AI only inside `ai-provider`" locked decision (MCP protocol support isn't model-calling) and
a new T9 threat (docs/threat-model.md) for a malicious or compromised external tool server.

**Acceptance**

- [x] An external MCP client can discover and invoke the tools. Verified live against a real
      `docker compose` deployment: a raw `curl` (a genuine external client, not this app's own MCP
      client) did the full JSON-RPC handshake against `POST /mcp` — `initialize` → `notifications/initialized`
      → `tools/list` (returned all 3 built-in tools with real JSON Schemas) → `tools/call` on
      `calculator` with `{"expression":"15 * 3"}`, got back `{"result":45.0}`. Also covered by
      `McpServerAndClientIntegrationTest` under Testcontainers.
- [x] An external MCP server's tools are usable in chat, subject to the same authorization and
      timeouts. Verified live: this app's own MCP client (pointed at its own `/mcp` endpoint — no
      independent third-party MCP server exists in this project's infrastructure, named honestly in
      ADR-0011) discovered and registered `mcp:self:{knowledge-base-search,calculator,mock-weather}`
      into the same `ToolRegistry`; `GET /api/v1/tools` listed all 6 tools (3 built-in + 3
      MCP-sourced); a real chat turn asking to use `mcp:self:calculator` produced `event:tool_call` →
      `event:tool_call_pending` → (confirmed via `POST /api/v1/tool-calls/{callId}:confirm`) →
      `event:tool_result` with the correct computation (`6 × 7 = 42`) → a streamed answer → `done`.
      `tool_call_pending` fired on the very *first* call of a fresh plain-chat turn — proving
      `ToolDefinition.alwaysRequiresConfirmation`, the opposite of knowledge-base-search's
      ungated-first-call default (docs/threat-model.md T9).
- [x] The ADR is explicit about the trust implications of a third-party tool server — ADR-0011's
      Decision and the new threat-model.md T9 entry.

One real bug surfaced only by wiring `ToolRegistry` for runtime registration and testing it live, not
by planning: the original `McpClientToolRegistrar` design injected `Map<String, McpSyncClient>`,
expecting Spring to key each configured MCP connection by name — it silently resolved to an empty
map (Spring AI's autoconfiguration wires every connection into one `List<McpSyncClient>` bean, not
individually-named beans), so the registrar's loop body never ran and nothing was logged. Found via
Spring Boot's own condition-evaluation report (`debug=true`) after a first live-connect test simply
timed out with no diagnostic. Fixed by injecting `List<McpSyncClient>` and deriving each connection's
identity from the server's own advertised name (`McpSyncClient.getServerInfo().name()`, only
available after `.initialize()`) instead of a Spring config key.

Scope reductions, named rather than silently dropped (see ADR-0011's Trade-offs section for the
reasoning behind each): no independent third-party MCP server exists in this project's
infrastructure, so the client is demonstrated and tested against this same application's own server;
the MCP server only exposes tools registered at its own startup, never re-exposing anything later
pulled in via its own MCP client; no dynamic re-discovery if an external server becomes unavailable
after registration; `ai.mcp.client.required-scope` is one config-declared scope for every external
connection, matching `ai.tools.granted-scopes`'s existing single-user stance.

---

## Phase 8 — Hardening and presentation

Complete threat model, security scanning and SBOM, a measured latency baseline, sequence diagrams,
the theoretical cloud deployment section, README polish, and a recorded demo.

**ADR:** [0012](adr/0012-observability-conventions.md) observability conventions and GenAI semantic
attributes — documents the real `gen_ai.*`/`rag.*` span-attribute namespaces this project actually
uses (not the ones earlier docs merely claimed), and the metric-naming pattern Phases 5 and 6
independently converged on.

**Acceptance**

- [x] Dependency, container and secret scanning run in CI. `.github/workflows/ci.yml` gained a
      `security` job: `gitleaks/gitleaks-action` (secrets), `actions/dependency-review-action`
      (dependency scanning on PRs, first-party, zero-config) plus `dependency-check-maven` wired in
      (the tool `docs/threat-model.md` already named), and Trivy scanning the exact container image
      the `build` job produces. `.github/workflows/codeql.yml` runs CodeQL on push, PR and weekly.
      Every third-party action is pinned to a commit SHA, not a tag — verified via `git ls-remote
      --tags` against the real upstream repos, not guessed — motivated by a real, current incident:
      `aquasecurity/trivy-action`, one of the tools this project itself now uses, had 75 of 76 tags
      force-pushed to credential-stealing code for ~12 hours in March 2026 (CVE-2026-33634).
- [x] A CycloneDX SBOM is published with releases. New `.github/workflows/release.yml`
      (`on: push: tags: v*`, plus `workflow_dispatch` for a dry run) generates an aggregate SBOM via
      `cyclonedx-maven-plugin:2.9.2:makeAggregateBom` and attaches `bom.json`/`bom.xml` to the GitHub
      Release via `gh release upload`. The SBOM-generation step is verified for real (see the scope
      note below); the workflow's full run, including the release-creation/upload step, is not yet —
      a real GitHub platform constraint, not a shortcut, explained below. No container registry push
      — scoped out, matching the
      roadmap's own "deliberately deferred" stance on infrastructure the project doesn't need to
      demonstrate configuration for.
- [x] Latency figures in the documentation come from a reproducible measurement, with hardware
      recorded. LM Studio came up on this machine with `qwen/qwen3.8-27b` (chat) and `bge-m3`
      (embeddings) loaded — real hardware: **Apple M4 Pro, 48 GB RAM**
      (`system_profiler SPHardwareDataType`). Getting a real number took three real, live-only
      findings along the way, none reproducible without an actual model server running:
      1. **The default 60s provider timeout was too short.** A trivial prompt took 3.3s, but real
         RAG/reranking prompts against a 27B reasoning model routinely exceeded 60s — raised to 300s
         via `AI_PROVIDER_LMSTUDIO_TIMEOUT` for the run.
      2. **LLM-based reranking (`hybrid-rerank-llm`) is impractical with this model on this
         hardware** — `LlmReranker`'s call consistently took the full 300s timeout before falling
         back gracefully to fused order (by design, not a crash), which would have made a full,
         all-profile eval run take hours. Dropped from the profile list actually run; named here as
         a genuine, newly-discovered hardware/model-capability limit, not silently avoided.
      3. **The RAG pipeline abstained on every golden-dataset case even after seeding the real
         corpus** — the retrieval-abstention threshold (`RagProfiles.maxVectorDistance = 0.6`) was
         never checked against a real embedding model before this phase; real `bge-m3` distances for
         this corpus run ≈0.95. A real, load-bearing gap, written up in full in
         [`docs/ai-evaluation.md` §8](ai-evaluation.md#8-a-real-finding-from-the-first-live-model-run-phase-8-recalibrated-post-roadmap-issue-29)
         — not fixed here (needs a broader sample to recalibrate responsibly; out of a hardening
         phase's scope), but real and now documented rather than silently absent.

      Because the RAG-mediated path abstained, the actual latency figures come from direct
      `POST /conversations/{id}/messages` chat completions (no `ragProfile`, bypassing the threshold
      finding above) — a real, reproducible measurement in its own right, using five of the golden
      dataset's own questions as prompts against the real running app. Five real samples, `qwen/
      qwen3.8-27b`, Apple M4 Pro/48GB: **10.3s, 12.1s, 26.3s, 51.4s, 59.7s** (median 26.3s, mean
      31.9s) — end-to-end wall time including network, matching each response's own reported
      `latencyMs`. Later, larger latencies correlate with growing conversation-history size (up to
      ~4,078 prompt tokens by the second turn) more than with the specific question — a small,
      genuinely reproducible sample, not a rigorous p50/p95 (5 points is too few for a percentile
      claim, stated as median/mean/range instead of manufacturing false precision).
      `eval/reports/2026-08-23-dense-only-hybrid-hybrid-rerank.{md,json}` is committed as the actual
      RAG-profile evaluation run (`dense-only`/`hybrid`/`hybrid-rerank`, real `bge-m3` retrieval
      latency, 1 repetition) — real data, even though it reflects the abstention finding above rather
      than generation latency.
- [x] A technical reviewer understands the system in five minutes without opening a source file.
      `README.md`'s status banner was four phases stale ("Phases 0–3 complete" while the capability
      table below it already marked Phases 0–7 done) — fixed, plus CI/license badges, a Demo section,
      and a repository-layout fix (it referenced `reindex`/`demo` scripts under `scripts/`; `reindex`
      doesn't exist anywhere in the roadmap and was removed, `demo` now does exist for real).
      Two new sequence diagrams in `docs/architecture.md` §10 cover the two hardest-to-follow flows
      added since the two existing diagrams were written: tool calling with confirmation (Phase 5)
      and the MCP handshake (Phase 7).

**A recorded demo, honestly scoped.** Nothing in this environment can capture or encode an actual
video file. What exists instead: `scripts/demo.sh`, a real, reproducible script that drives every
capability — plain chat, ingestion, RAG with citations, tool calling through the confirmation gate,
MCP (gracefully skipped with an explanatory message if the self-connect override isn't active), and
a full six-stage agentic workflow run — against a live instance over the same HTTP API a human would
use, plus `DEMO.md` as narration to read while running or recording it yourself. Run live against a
fresh `docker compose` deployment (`recorded` profile) during this phase: all four sections completed
successfully, including a real `citation` event (verified the exact chunk/document ids and quoted
spans in the SSE payload) and a full workflow run where all six stages (`plan-sub-queries`,
`retrieve`, `extract-per-source`, `synthesise`, `self-check`, `answer`) reported `SUCCEEDED`.

**Three real bugs found only by running this phase's own new script against a live app, not by
planning:**

1. **`upload_and_wait`'s (and `scripts/seed.sh`'s pre-existing) `grep -i '^location:' | sed | tr`
   pipeline silently killed the script whenever a document was already indexed** (a `200` response
   with no `Location` header, the documented dedup case) — `grep` exits `1` on no match, and under
   `set -euo pipefail`, that terminates the script *before* reaching the `if [ -z "${location}" ]`
   branch written specifically to handle it. Confirmed live: the second `demo.sh` run against a
   database that already had the demo document indexed died silently mid-script with no error
   message. Fixed in both scripts by wrapping the `grep` in `{ grep ... || true; }`. The identical
   bug in `scripts/demo.sh`'s tool-confirmation polling loop (`grep -o '"callId"...'` on an SSE
   stream that hasn't emitted the field yet) would have killed the script on its very first polling
   iteration, every time — fixed the same way. `scripts/seed.sh` had carried this bug, unexercised,
   since Phase 4.
2. **`cyclonedx-maven-plugin`'s `makeAggregateBom` goal binds itself to the `package` phase even with
   no `<executions>` block declared** — confirmed via a real `./mvnw verify` run that produced a
   `*-cyclonedx.json` in every module's `target/` unprompted, contradicting the plan to invoke it only
   explicitly from `release.yml`. Fixed with the standard Maven override
   (`<execution><id>default</id><phase>none</phase></execution>`), confirmed by re-running
   `./mvnw package` and seeing no SBOM output, then confirming the explicit
   `./mvnw org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom` invocation still works.
3. **Replacing the `docker compose`-managed app container with a bare `docker run` (even on the same
   Docker network) broke Kafka consumer group formation** — repeated `Node ... disconnected` /
   `Rebootstrapping` cycles that never resolved. Not a product bug: `docker compose run`, which
   preserves the compose-managed service DNS and dependency ordering, worked correctly on the first
   try. Documented here because it's a real trap for anyone reproducing this phase's own live
   verification steps by hand.

**Four more real findings, from the actual GitHub Actions run on this PR — not from local rehearsal,
which had already passed clean:**

4. **`gitleaks/gitleaks-action` failed with `"failed to scan Git repository" error="stderr is not
   empty"`**, not an actual secret — it computes a commit-range diff and `actions/checkout`'s default
   `fetch-depth: 1` doesn't have the base commit available to diff against. Fixed with
   `fetch-depth: 0` on the `security` job's checkout.
5. **`actions/dependency-review-action` failed outright**: `"Dependency review is not supported on
   this repository. Please ensure that Dependency graph is enabled"` — a repository setting (Settings
   → Security) only the repository owner can toggle, not something this workflow can enable itself.
   Given `continue-on-error: true`; the prerequisite is documented in `docs/threat-model.md` §5 rather
   than left to fail silently every run.
6. **The container image scan found 9 real HIGH-severity CVEs**: `postgresql-42.7.11.jar`
   (CVE-2026-54291, a SCRAM-SHA-256-PLUS channel-binding downgrade, fixed in 42.7.12) and 8 in
   `usr/bin/pebble`, part of `eclipse-temurin`'s own Ubuntu base OS layer. Fixed the one within this
   project's control by pinning `org.postgresql:postgresql` to 42.7.13 in `pom.xml` (overriding
   Spring Boot's managed 42.7.11). The other 8 are upstream-owned and unfixable here short of a base
   image change — documented in a new `.trivyignore` and `docs/threat-model.md` §6's Accepted risk
   table, the same reasoning already applied to every other accepted item there.
7. **Even after fixing/ignoring every real finding, the Trivy step kept exiting 1** — a documented
   `trivy-action` bug (upstream issues #228, #309, #442): combining `format: sarif` with `exit-code`
   in one invocation doesn't reliably respect `severity`/`trivyignores` for the pass/fail decision,
   only for the report content, confirmed live (a run with a SARIF showing 0 error-level results and
   `.trivyignore` visibly loaded still failed). Fixed by splitting into two separate Trivy invocations
   in `ci.yml` — a `format: table` run that actually gates the build, and a separate `format: sarif`
   run purely to produce the file the Security-tab upload consumes — `trivy-action`'s own documented
   workaround.

Every one of the seven bugs above was found by actually running something — a script, a local build,
a real GitHub Actions job — never by inspection alone. Several (5, 6, 7) could only have been found by
pushing to a real PR: no local rehearsal reproduces GitHub's own repository settings, its shallow
default checkout, or `trivy-action`'s specific SARIF-mode behavior.

**A second, larger documentation/reality gap found via direct `grep`, beyond what was planned:**
`docs/architecture.md` §12 claimed Grafana dashboards were "provisioned from the repository" — none
are; `infrastructure/` has no dashboard-provisioning files at all, and the `grafana/otel-lgtm`
container in `docker-compose.yml` runs with zero custom config. Corrected to describe what's real
(Grafana's own Explore view over Tempo/Loki/Prometheus) rather than either building dashboards this
phase didn't scope for or leaving the claim in place. The same section's Prometheus metrics list
named eight metrics that don't exist and omitted two (`workflow_run_total`,
`workflow_step_duration_seconds`) that do — corrected; see [ADR-0012](adr/0012-observability-conventions.md).
`docs/architecture.md`'s own top-of-document status line ("design. No implementation yet.") had never
been updated since Phase 0 — fixed. The risks table (§16) named a `reindex.sh` script and CI
"documentation check" that don't exist — corrected to describe the real gap rather than the intended
one.

Scope reductions, named rather than silently dropped:

- `dependency-check-maven` needs an `NVD_API_KEY` repository secret for full-speed scanning — the
  workflow runs without one, just slower/best-effort; adding the secret is a step only the repository
  owner can take.
- Eight previously-claimed-but-unbuilt Prometheus metrics stay unbuilt this phase — see ADR-0012's
  Alternatives section for why.
- Grafana dashboard provisioning is a real, now-honestly-documented gap, not built this phase.
- `release.yml` itself has **not** been run on GitHub Actions yet — `gh workflow run release.yml`
  returned a 404, because GitHub's `workflow_dispatch` API only recognizes a workflow once its file
  exists on the repository's default branch, which `phase-8/hardening` isn't yet. The exact same real
  platform constraint Phase 4 hit for `nightly-eval.yml` (see that phase's own note above) —
  recognized this time, not rediscovered as a surprise, but still not something achievable before this
  PR merges. What *is* verified: the SBOM-generation step in isolation
  (`./mvnw org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom` run locally, producing a real
  `bom.json`/`bom.xml`) and that it does *not* run on a normal `mvnw verify`/`package` (the plugin's
  own intrinsic phase-binding bug, fixed in `pom.xml`, see below). The full workflow — including the
  `gh release create`/upload step — needs a real run once this merges to `main`, either via
  `workflow_dispatch` or an actual tag push (the latter is a publishing action, needing separate
  confirmation before it happens).

---

## Post-roadmap review — the phase after the phases

**Status: complete.** 17 findings, 17 fixes, all merged. Full reasoning in
[`improvement-plan.md`](improvement-plan.md); each finding is a closed issue (#21–#37) with a
CI-verified pull request behind it.

Phase 8 closed the roadmap. This project's own working rule — that a phase is done when it is
*verified*, not when it is written — then raised an obvious question the roadmap itself could not
answer: **does the finished thing actually hold up when someone reads it line by line?** A full
review says no, not entirely. That answer is recorded here rather than quietly fixed, because a
roadmap that ends with "all phases complete" and nothing after it is exactly the kind of claim this
document spends nine phases refusing to make.

**What it found:** 1 high-severity, 10 medium, 6 low.

| Category | Findings | The load-bearing ones |
|---|---|---|
| Security | 4 | Stored XSS in the document list (#21); the tool confirmation gate and the executor consulting different sources of truth, so the gate could be bypassed (#22) |
| Bugs | 5 | `StageRunner` retrying non-retryable errors with no backoff (#25); an NPE on a negative retry setting (#26); no validation on any configuration property (#27); the RAG abstention threshold miscalibrated against real embeddings (#29) |
| Test coverage | 5 | Six of twelve modules with no tests at all (#30); the retry/resume core untested (#31); the dead-letter topic never asserted anywhere (#32); no coverage measurement (#33); no end-to-end tier (#34) |
| Quality / observability | 3 | A testing-strategy table naming four tools absent from every `pom.xml` (#35); the call-the-model-and-degrade pattern duplicated five times (#36); silent LLM degradation with no metric (#37) |

**The three findings that contradicted something already claimed** — the reason this review earned
its place in the roadmap rather than a changelog entry:

1. **Fixture-calibrated thresholds are not calibrated.** `maxVectorDistance` was an unmeasured `0.6`
   carried over from the `recorded` provider's hash-seeded embeddings, where an exact-text match
   scores ≈ 0 by construction. Against real `bge-m3` vectors an unambiguous, answerable question
   scored ≈ 0.95, so the RAG pipeline abstained on every query — a total failure of the Phase 3
   headline feature that every Phase 3 and Phase 4 test passed straight through. Recalibrated to
   `0.55` against a real distribution measured over all 28 golden-dataset queries plus four
   deliberately off-topic controls ([ADR-0013](adr/0013-rag-abstention-threshold.md)).
2. **Documented tooling that was never added.** Four rows of `architecture.md` §11 named WireMock,
   Toxiproxy, Error Prone and NullAway. None appears in any `pom.xml`. The coverage those rows
   described was partly real by other means — hand-written fakes, Testcontainers — so the correction
   was to describe the real mechanism, not to adopt four libraries retroactively to make a sentence
   true (#35). Same class of gap Phase 8 already corrected for security scanning, found again one
   table over.
3. **A silent total failure with no counter behind it.** Phase 8's own live run had `LlmReranker`
   falling back to fused order on *every* call, visible only as a `WARN` line. There is now an
   `llm_degradation_total{component, reason}` counter across all five graceful-degradation sites
   (#36 extracted the shared helper; #37 instrumented it).

**Two further bugs surfaced only because the review insisted on a real live run** (#29), neither
reachable from any fixture-based test: `ai.provider.lmstudio.timeout` never reached the underlying
OkHttp client — it bounded only an outer reactive `Mono.timeout()`, so long generations died on the
HTTP client's own much shorter default — and `EvalRunner` had no per-case fault isolation, so a
single hung model call discarded an entire run's already-persisted results (confirmed losing
everything at 0, 3, 7 and 12 completed cases across four consecutive attempts before the fix).

**What it added, beyond fixes:** the project's first coverage measurement (88.0% instruction coverage,
enforced at a real floor — see `architecture.md` §11 for why the enforced floor is narrower than the
headline figure), its first automated end-to-end tier exercising the same journey `scripts/demo.sh`
narrates by hand, its first `llm_degradation_total` metric, and the first real retrieval metrics this
project has ever produced against a live embedding model
([`eval/reports/2026-08-24-dense-only.md`](../eval/reports/2026-08-24-dense-only.md) — partial
coverage, stated as such in the report's own coverage note).

**What it deliberately did not change**, each argued rather than skipped: the response-DTO `from(...)`
mappers (repetitive but explicit and boundary-appropriate — a mapping framework would cost more
clarity than it saves), and the items in the table below, which the review restated without
re-litigating.

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
