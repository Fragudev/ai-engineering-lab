# Working on this repository

Instructions for anyone implementing here, human or AI agent. Read this and
[`docs/architecture.md`](docs/architecture.md) before writing code.

The design work is done. The architecture, module boundaries, contracts and phase plan are settled
and recorded. **The job is implementation, not redesign.** If something in the design looks wrong,
say so and propose an ADR amendment — do not silently build something else.

---

## Locked decisions

Do not revisit these without a superseding ADR.

| | |
|---|---|
| Java | 25 (LTS) |
| Framework | Spring Boot 4.1.x, Spring Framework 7 |
| AI integration | Spring AI 2.0.x, **model-calling (`ChatModel`/`EmbeddingModel`) only inside `ai-provider` adapters** — Spring AI's MCP Boot starters are a separate concern (protocol support, not model access) and are used directly in `mcp` (Phase 7, [ADR-0011](docs/adr/0011-mcp-tool-exposure-boundaries.md)) |
| Build | Maven multi-module, wrapper committed |
| Package root | `io.github.fragudev.ailab` |
| Modularity | Spring Modulith + ArchUnit, boundaries enforced in the build |
| Database | PostgreSQL 17 + pgvector, HNSW index, Flyway forward-only |
| Embeddings | `bge-m3`, **1024 dimensions, fixed project-wide** |
| Messaging | Kafka (KRaft), versioned JSON Schema payloads, no Avro |
| Model server | LM Studio at `host.docker.internal:1234` (OpenAI-compatible) |
| Language | **English** for all code, comments, docs and commit messages |

Rationale for each is in [`docs/adr/`](docs/adr/).

---

## Rules that are not negotiable

**1. Verify library APIs before using them.** Spring Boot 4.1 and Spring AI 2.0 are recent. Method
signatures from Spring Boot 3.x and Spring AI 1.x frequently do not apply. Check the current
documentation rather than recalling an API. A confidently wrong import costs more time than a lookup.

**2. Never invent numbers.** No latency figure, throughput claim, cost estimate or quality metric
goes into code comments or documentation unless it came from a measurement that can be reproduced,
with the model and hardware recorded. If it was not measured, write that it was not measured.

**3. Failure paths need tests.** A Kafka consumer without a retry-exhaustion test, a provider call
without a timeout test, or an endpoint without an error-path test is not finished. The happy path is
the easy half.

**4. Do not weaken a boundary to unblock yourself.** If a module needs something from another
module's `internal` package, either the API is wrong or the boundary is. Fix the design; do not add
an exclusion to the ArchUnit rules.

**5. Documentation ships with the change.** A change that makes the README, architecture doc or an
ADR stale is incomplete.

**6. Ask before scope-creeping.** The phase plan in [`docs/roadmap.md`](docs/roadmap.md) is
deliberate. Adding Phase 5 work during Phase 2 is not helpfulness; it is what leaves projects
half-finished.

---

## Module boundaries

Each module exposes its API in the root package and hides everything else under `internal`:

```
io.github.fragudev.ailab.knowledge          ← public API
io.github.fragudev.ailab.knowledge.internal ← implementation, off-limits to others
```

Cross-module communication is a public API call or a domain event. Nothing else. The dependency
graph is in [`docs/architecture.md`](docs/architecture.md#3-modules) and is acyclic — keep it that
way.

`platform` is depended upon by others and depends on no domain module. No domain module depends on
`app`.

---

## Conventions

**Formatting** — Spotless with Palantir style. `./mvnw spotless:apply`.

**Errors** — domain errors are typed exceptions from `shared`, translated to RFC 9457 Problem Details
at the API edge. Never return a bare 500 with a stack trace. Never swallow an exception without
either handling it or logging it with context.

**Nullability** — JSpecify annotations. Prefer `Optional` in return types over nullable returns.
**Nothing enforces this**: there is no NullAway, no Error Prone, no static-analysis gate of any kind —
only Spotless, which formats. The annotations document intent for readers and IDEs; keeping them
honest is a review responsibility, not a build one. Said plainly because this line previously claimed
NullAway enforced it, and it has never been a dependency (post-roadmap review issue #35's finding,
one file over).

**Logging** — structured, with `traceId` and `correlationId`. **Never log prompt or completion
content by default**; that path goes through the redaction helper in `platform`.

**Tests** — one behaviour per test, named as a sentence: `rejectsUploadWhenMimeTypeUnsupported`.
Testcontainers for anything touching PostgreSQL or Kafka; no H2, no embedded Kafka.

**Commits** — Conventional Commits, module-scoped: `feat(ingestion): add DLT routing for
non-retryable failures`.

**Migrations** — Flyway, forward-only. Never edit an applied migration; add a new one.

---

## Working through a phase

1. Read the phase section in [`docs/roadmap.md`](docs/roadmap.md), including its acceptance criteria.
2. Write or update the OpenAPI spec / event JSON Schema **before** the implementation.
3. Implement, in small increments that compile.
4. Write tests, including the failure paths.
5. Write the phase's ADRs — after the decision was made in practice, not as speculation beforehand.
6. Update the README capability table and the roadmap status.
7. Confirm `./mvnw verify` is green and the acceptance criteria are met.

A phase is done when every acceptance criterion in the roadmap is demonstrably satisfied. Not when
the code exists.

---

## Environment constraints

**The build cannot be verified in every environment.** Some contributors — including AI agents in
sandboxes — have no Maven, no Docker and no network access to Maven Central. In that situation:

- Do not guess at dependency versions. Look them up, and state which ones are unverified.
- Work in small increments so a compilation failure has a small search space.
- Say plainly that the code has not been compiled. Do not claim a build passed that was never run.

**CI never calls a live model.** It uses the `recorded` provider profile. If a change alters a prompt
or a request shape, re-record the fixtures with `scripts/record-fixtures.sh` and review the diff —
an unexpected fixture change is telling you something.

**LM Studio runs on the host**, not in Compose. Containers reach it at `host.docker.internal:1234`.
On Linux this needs an explicit `host-gateway` mapping in the Compose file.

---

## Reference

| Document | Read it when |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | Before writing anything |
| [`docs/roadmap.md`](docs/roadmap.md) | At the start of every phase |
| [`docs/adr/`](docs/adr/) | Before questioning a decision |
| [`docs/threat-model.md`](docs/threat-model.md) | Before touching tools, ingestion or prompt assembly |
| [`docs/ai-evaluation.md`](docs/ai-evaluation.md) | Before touching retrieval or the RAG pipeline |
| [`docs/operations.md`](docs/operations.md) | When something is broken |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | For workflow and commit conventions |
