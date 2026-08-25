# AI Engineering Lab

[![CI](https://github.com/Fragudev/ai-engineering-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/Fragudev/ai-engineering-lab/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A production-grade reference application for AI engineering on the JVM: hybrid RAG over pgvector,
event-driven ingestion on Kafka, controlled tool calling, MCP, agentic workflows, end-to-end
OpenTelemetry tracing, and an evaluation harness that produces numbers instead of adjectives.

Runs entirely on your own machine against a local model server. No API key required.

> **Status: all 9 phases complete, plus a full post-roadmap review.** Every capability below is
> marked with its current state, and nothing is claimed to work until it does. See
> [`docs/roadmap.md`](docs/roadmap.md) for the phase plan and what each phase actually verified —
> including, in Phase 8, the doc/reality gaps that phase's own review found and corrected. The
> roadmap ending was not the project ending: a subsequent line-by-line review found **17 real
> defects** and fixed every one ([below](#the-post-roadmap-review)).

---

## Why this exists

Most RAG demos answer one question well and collapse under the second. They have no story for what
happens when the embedding step fails, no way to tell whether a retrieval change made things better
or worse, and no answer to "what does this cost per request".

This project is built to have those answers. It treats an LLM application as a distributed system
with an unusually unreliable dependency, and it applies the practices that implies: explicit
failure handling, idempotent asynchronous processing, observable pipelines, measurable retrieval
quality, and a threat model that takes prompt injection seriously.

It is a portfolio piece, a laboratory for AI engineering techniques, and the reference material
behind a set of architecture conversations.

---

## Capabilities

| Capability | What it does | Status |
|---|---|---|
| Chat | Multi-turn conversations, SSE streaming, token and cost accounting | Done — Phase 1 |
| Ingestion | Upload → parse → chunk → embed → index, asynchronously over Kafka, with retries and a dead-letter topic | Done — Phase 2 |
| Hybrid retrieval | Vector kNN + PostgreSQL full-text, fused with Reciprocal Rank Fusion | Done — Phase 3 |
| RAG with citations | Configurable pipeline, per-claim source attribution, deterministic "insufficient context" abstention | Done — Phase 3 |
| Retrieval debugging | An endpoint that shows what was retrieved, with scores before and after fusion and reranking | Done — Phase 3 |
| Evaluation | Golden dataset, recall@k, MRR, citation precision, latency, token cost, profile comparison | Done — Phase 4 |
| Tool calling | Schema-validated registry with scoped authorization, timeouts and full tracing | Done — Phase 5 |
| Agentic workflow | An explicit state machine with persisted state, resumable and compensable | Done — Phase 6 |
| MCP | Tool registry exposed as an MCP server; external MCP servers consumed as clients | Done — Phase 7 |

---

## The post-roadmap review

Every row above said "Done" when its phase closed. A line-by-line review afterwards found **17 real
defects across them** — and fixing those is the part of this project worth reading.

| Severity | Count | The ones that mattered most |
|---|---|---|
| High | 1 | Stored XSS in the document list ([#21](https://github.com/Fragudev/ai-engineering-lab/issues/21)) |
| Medium | 10 | A confirmation-gate bypass — the gate and the executor consulted different sources of truth ([#22](https://github.com/Fragudev/ai-engineering-lab/issues/22)); `StageRunner` retrying non-retryable errors with no backoff ([#25](https://github.com/Fragudev/ai-engineering-lab/issues/25)); a RAG abstention threshold so miscalibrated the pipeline refused to answer **every single query** against a real embedding model ([#29](https://github.com/Fragudev/ai-engineering-lab/issues/29)) |
| Low | 6 | An NPE on a negative retry setting ([#26](https://github.com/Fragudev/ai-engineering-lab/issues/26)); no coverage measurement anywhere ([#33](https://github.com/Fragudev/ai-engineering-lab/issues/33)); no end-to-end test tier ([#34](https://github.com/Fragudev/ai-engineering-lab/issues/34)) |

**Three findings are worth naming specifically, because each one contradicts something the project
had already claimed about itself:**

- **The abstention gate was calibrated against fixtures, not reality.** `RagProfiles`'
  `maxVectorDistance` was an unmeasured `0.6` inherited from the `recorded` provider's hash-seeded
  embeddings. Against real `bge-m3` vectors, an unambiguous, answerable question scored ≈ 0.95 — so
  the headline RAG feature abstained on everything. Recalibrated to `0.55` from a real measurement
  across all 28 golden-dataset queries plus four off-topic controls
  ([ADR-0013](docs/adr/0013-rag-abstention-threshold.md)). **Passing `recorded`-profile integration
  tests was never evidence the thresholds were right.**
- **Four rows of the testing-strategy table named tools that were in no `pom.xml`** — WireMock,
  Toxiproxy, Error Prone, NullAway. The coverage they described was partly real by other means; the
  tooling column was fiction. Corrected rather than retrofitted by adopting four libraries to make a
  sentence true ([#35](https://github.com/Fragudev/ai-engineering-lab/issues/35)).
- **A headline feature failed silently in production conditions and nothing counted it.** During
  Phase 8's live run, `LlmReranker` fell back to fused order on *every* call against a 27B model; the
  only signal was a `WARN` line. There is now an `llm_degradation_total{component, reason}` counter
  ([#37](https://github.com/Fragudev/ai-engineering-lab/issues/37)).

What the review added, beyond fixes: the project's first coverage measurement (**88.0% instruction
coverage**, measured — with the honest caveat that it leans on integration tests, not per-module unit
tests), its first automated end-to-end tier, and unit coverage for six modules that had none.

Full findings and reasoning: [`docs/improvement-plan.md`](docs/improvement-plan.md). Every issue is
closed and every fix is a merged, CI-verified pull request.

---

## Architecture at a glance

A **modular monolith** — one deployable process, hard internal boundaries verified at build time —
with genuinely asynchronous work pushed onto Kafka.

```mermaid
flowchart TB
    UI["Minimal web UI<br/>chat · citations · upload"]

    subgraph APP["ai-engineering-lab (single Spring Boot process)"]
        API["api"]
        CONV["conversation"]
        RAG["rag"]
        TOOLS["tools"]
        WF["workflow"]
        ING["ingestion"]
        KN["knowledge"]
        EVAL["evaluation"]
        MCP["mcp"]
        PROV["ai-provider"]
        PLAT["platform<br/>observability · security"]
    end

    PG[("PostgreSQL<br/>+ pgvector")]
    KAFKA[["Kafka"]]
    LM["LM Studio<br/>(host machine)"]
    OBS["Grafana<br/>Prometheus · Tempo · Loki"]

    UI -->|REST + SSE| API
    API --> CONV & ING & TOOLS & WF & EVAL & RAG
    RAG --> KN
    WF --> RAG & TOOLS
    MCP --> TOOLS
    ING -.->|events| KAFKA
    KAFKA -.->|events| ING
    ING --> KN
    KN --> PG
    CONV --> PG
    CONV & RAG & KN & ING --> PROV
    PROV --> LM
    PLAT -.->|traces · metrics · logs| OBS
```

Why a modular monolith and not microservices: this system has one team, one deployment cadence and
no component with an independent scaling profile. Microservices would buy distribution costs
without buying anything back. Spring Modulith enforces the boundaries in tests, so if the ingestion
pipeline ever does need to scale separately, extracting it is mechanical rather than archaeological.
The full argument is in [ADR-0002](docs/adr/0002-modular-monolith.md).

Detailed views, sequence diagrams and the reasoning behind each boundary are in
[`docs/architecture.md`](docs/architecture.md).

---

## Technology

| Layer | Choice |
|---|---|
| Language | Java 25 (LTS) |
| Framework | Spring Boot 4.1 · Spring Framework 7 |
| AI integration | Spring AI 2.0, behind project-owned interfaces |
| Modularity | Spring Modulith (boundary verification + transactional event publication) |
| Build | Maven multi-module, wrapper committed |
| Persistence | PostgreSQL 17 + pgvector (HNSW), Flyway migrations |
| Messaging | Apache Kafka (KRaft) |
| Model server | LM Studio (OpenAI-compatible API), swappable for OpenAI/Anthropic |
| Embeddings | `bge-m3`, 1024 dimensions — fixed project-wide |
| Observability | OpenTelemetry → Collector → Prometheus, Tempo, Loki, Grafana |
| Testing | JUnit 5, Testcontainers, ArchUnit, WireMock, Toxiproxy |

Spring AI is an implementation detail behind `ChatProvider` and `EmbeddingProvider`. Swapping model
providers is a configuration change, not a refactor — see
[ADR-0004](docs/adr/0004-ai-provider-abstraction.md).

---

## Getting started

The commands below are the actual, live-verified path — run end to end repeatedly across all 9
phases, most recently against a real LM Studio instance in Phase 8 (`docs/roadmap.md`).

**Prerequisites**

- Java 25 (or just Docker — the build runs in a container too)
- Docker with Compose
- [LM Studio](https://lmstudio.ai/) running on the host, with two models loaded:

| Role | Minimum tier | Recommended tier |
|---|---|---|
| Chat | a 7–8B instruct model, quantized | a ~30B MoE (e.g. 30B-A3B class) |
| Embeddings | `bge-m3` | `bge-m3` |

The embedding model is **not** interchangeable: the database schema is dimensioned to 1024 and
changing models requires a full reindex. `scripts/bootstrap.sh` verifies the loaded model and its
dimensions before anything else runs, and fails with a clear message rather than a confusing
distance error three steps later.

**Run**

```bash
git clone https://github.com/Fragudev/ai-engineering-lab.git
cd ai-engineering-lab

./scripts/bootstrap.sh      # checks LM Studio, models and embedding dimensions
docker compose -f infrastructure/docker-compose.yml up -d
./mvnw verify
./scripts/seed.sh           # ingests the demo corpus so the first question has an answer
```

Then open <http://localhost:8080>. Grafana is on `:3000`, Kafka UI on `:8081`.

Running a large reasoning model (20B+ parameters, or anything that emits `reasoning_content`)? The
default 60s provider timeout is tuned for smaller instruct models and is too short for real
RAG/reranking prompts against a large reasoning model — override it with
`AI_PROVIDER_LMSTUDIO_TIMEOUT=300s` (or higher). Found live in Phase 8 against a 27B model, where a
trivial prompt took ~3s but real prompts routinely exceeded 60s; see `docs/roadmap.md`'s Phase 8
section for the full story, including a real retrieval-abstention threshold finding from the same run.

Prefer not to install LM Studio? The `recorded` provider profile replays captured fixtures, so the
application is fully explorable without a model server. It is also what CI uses — see
[Testing strategy](docs/architecture.md#11-testing-strategy).

---

## Repository layout

```text
app/                  the single deployable Spring Boot application
modules/              domain modules with enforced boundaries
  shared/               ids, domain errors, event envelope
  ai-provider/          LLM and embedding abstraction + adapters
  conversation/         chat, history, streaming
  ingestion/            document lifecycle, Kafka consumers
  knowledge/            chunks, vector + lexical search, reranking
  rag/                  pipeline orchestration, context building, citations
  tools/                registry, schemas, authorization, execution
  workflow/             agentic workflow state machine
  mcp/                  MCP server and client
  evaluation/           datasets, runners, metrics
  platform/             observability, security, resilience, idempotency
docs/                 architecture, ADRs, threat model, evaluation methodology
infrastructure/       docker-compose, OTel config (see docs/architecture.md #12 for what
                         Grafana shows today vs. what's still a named gap)
corpus/               demo documents and license attribution
eval/                 golden dataset and generated reports
scripts/              bootstrap, seed, fetch-corpus, eval, demo (see DEMO.md)
```

---

## Documentation

| Document | What it answers |
|---|---|
| [Architecture](docs/architecture.md) | Structure, boundaries, contracts, data model, sequence diagrams |
| [Roadmap](docs/roadmap.md) | Phases, acceptance criteria, what is deliberately deferred |
| [Improvement plan](docs/improvement-plan.md) | The post-roadmap review: all 17 findings, their reasoning, and what was deliberately *not* changed |
| [ADRs](docs/adr/) | Why each significant decision was made, and what it cost |
| [Threat model](docs/threat-model.md) | STRIDE + OWASP LLM Top 10, mitigations, accepted risk |
| [Evaluation](docs/ai-evaluation.md) | Metrics, methodology, and where the methodology is weak |
| [Operations](docs/operations.md) | Runbook, what to look at when something breaks |
| [Events](docs/events/) | Topic contracts and JSON Schemas |
| [AGENTS.md](AGENTS.md) | Conventions and constraints for contributors, human or agent |
| [DEMO.md](DEMO.md) | A scripted, reproducible walkthrough of every capability — run it, or record yourself running it |

---

## Demo

[`scripts/demo.sh`](scripts/demo.sh) drives every capability above against a real running instance
over the same HTTP API a human would use — plain chat, ingestion with a real Kafka pipeline, RAG with
citations, tool calling through the confirmation gate, MCP, and a full agentic workflow run. See
[DEMO.md](DEMO.md) for how to run it and narration to read alongside. This is the "recorded demo"
this project's roadmap calls for, in the form this environment can actually produce: nothing here can
capture and encode an actual video file, so this is real, reproducible automation instead of one —
named plainly rather than presented as something it isn't.

---

## Evaluation

Retrieval quality is a measured property, not a claim. The harness runs a golden dataset against
named RAG profiles and produces a comparison table: recall@k, MRR, citation precision, p50/p95
latency and token cost per configuration.

Reports are committed to `eval/reports/` with the date, chat model and hardware recorded, because a
retrieval number without the model and machine behind it is not reproducible.

**Real, measured chat latency** (Phase 8): five direct chat completions against a locally-running
`qwen/qwen3.8-27b` on an Apple M4 Pro (48 GB RAM) — **10.3s, 12.1s, 26.3s, 51.4s, 59.7s**
(median 26.3s). Five samples, stated as median/mean/range rather than a manufactured p50/p95 — see
[`docs/roadmap.md`](docs/roadmap.md)'s Phase 8 section for the full methodology, including a real
retrieval-threshold miscalibration this same live run surfaced
([`docs/ai-evaluation.md` §8](docs/ai-evaluation.md#8-a-real-finding-from-the-first-live-model-run-phase-8-recalibrated-post-roadmap-issue-29)).

**Real retrieval metrics against a live model** (post-roadmap, [#29](https://github.com/Fragudev/ai-engineering-lab/issues/29)):
recall@k **1.00**, MRR **0.43**, citation precision **0.65**, citation recall **0.90**, p50 **51.3s**,
p95 **59.4s** — [`eval/reports/2026-08-24-dense-only.md`](eval/reports/2026-08-24-dense-only.md).

**That report covers 10 of 28 golden-dataset cases, one profile, one repetition** — not the full
three-profile comparison the harness is built for. LM Studio's chat pipeline degraded partway through
the run on this hardware and stopped answering, confirmed with a direct isolated health check. The
report says so in its own coverage note rather than presenting partial coverage as complete. These
are the first non-zero retrieval numbers this project has ever produced against a real embedding
model — before the threshold recalibration, every one of them was `0` because the pipeline abstained
on everything.

Two things this project will **not** do: publish performance figures that were not measured, and
present LLM-as-judge scores as primary evidence. Judge scores are reported as a secondary,
explicitly caveated signal — a local model grading another local model is a weak instrument, and
[`docs/ai-evaluation.md`](docs/ai-evaluation.md) says so plainly.

---

## Non-goals and known limitations

Stated up front, because a portfolio project that hides its edges is less convincing than one that
maps them.

- **No multi-tenancy.** Single user, simple authentication. Row-level isolation and per-tenant
  index partitioning are designed in `docs/architecture.md` but not built.
- **No Kubernetes, no cloud deployment.** Local Docker Compose only. The README's deployment
  section describes how it would map to a managed environment; it is analysis, not infrastructure.
- **No schema registry.** Event schemas are versioned JSON Schema files. With one producer, Avro
  plus a registry container is cost without benefit — see [ADR-0005](docs/adr/0005-kafka.md).
- **No trained reranker.** Reranking uses an off-the-shelf model or the LLM itself.
- **No semantic cache** before Phase 8.
- **Deliberately minimal UI.** Enough to demonstrate streaming, citations and ingestion progress.
  It is not the point of the project.
- **Local models are weaker than frontier models**, particularly at tool calling. The provider
  abstraction exposes model capabilities so the system degrades explicitly rather than mysteriously.

---

## License

[Apache License 2.0](LICENSE). The demo corpus is third-party content under its own permissive
licenses, listed in [`corpus/ATTRIBUTION.md`](corpus/ATTRIBUTION.md).
