# ADR-0010: Agent orchestration — where determinism beats autonomy, and why

- **Status:** Accepted
- **Date:** 2026-08-22
- **Phase:** 6

## Context

`docs/roadmap.md`'s Phase 6 is one workflow — *documentation research*: plan sub-queries → retrieve
in parallel → extract per source → synthesise → self-check against citations → answer — built as
"an explicit state machine with persisted state, compensation, and resumability." Four acceptance
criteria: a run survives an application restart and resumes from its last completed step; each
step's input, output, attempts and cost are inspectable; a failing step triggers compensation rather
than leaving a partial result; this ADR documents which steps genuinely need an LLM and which were
kept deterministic.

Research before implementation confirmed several things the design had to respect: `docs/architecture.md`
§3 already grants `workflow → {shared, ai-provider, rag, tools}`; §4 names `WorkflowRun`/`WorkflowStep`
with only a one-line field sketch, no status enum values; §2's sync/async table already states the
intent — *"Agentic workflow | Asynchronous, persisted state | Runs for minutes; must survive a
restart"*; no Kafka topic exists for workflow anywhere in the docs (resumability is framed purely as
persisted state, not event-sourced); and no state-machine library (Spring Statemachine or otherwise)
is referenced anywhere in this repo. Nothing in Phase 7 (MCP) or Phase 8 (hardening) is documented as
depending on a `workflow` surface, so this phase is scoped to satisfying its own four criteria, not
building speculative extension points.

## Decision

**An explicit, project-owned state machine — not a library.** Matches this codebase's consistent
pattern of hand-rolling a concern it can reason about directly instead of adopting a framework
(hand-written recursive-descent calculator instead of `ScriptEngine`; hand-rolled JSON-envelope
tool-call parsing instead of a tool-calling framework). The roadmap's own wording — "an explicit
state machine" — reads as a design instruction, not a gap to fill with a dependency.

**`workflow` owns its own run loop internally**, the same precedent ADR-0009 set for `tools`
(`ToolCallingChatService` owns the tool-calling loop, rather than `app` composing it), not the
precedent ADR-0008 set for `rag`/`conversation` (`app` composes two peers). The evidence: `workflow`'s
own dependency list already includes `rag` and `tools` directly — if `app` were meant to be the
composition root the way it is for RAG+conversation, `workflow` wouldn't need those edges at all
(`app` already depends on everything). `app` stays a thin caller: `WorkflowsController` starts a run
and reads a run back through the public `WorkflowService` façade.

**Real dependency edge found during implementation: `workflow → knowledge`.** `RagPipeline.search`'s
own return type (`RetrievalTrace`) carries `knowledge.SearchResult`/`knowledge.Chunk` directly, and
the `retrieve` stage genuinely reads them (chunk id, document id, content) to deduplicate and cap
sources before extraction. `docs/architecture.md` §3's table didn't list this edge — the same kind of
contradiction ADR-0009 found and fixed for `tools → ai-provider` in Phase 5. Fixed here, for real,
rather than relying on Maven's transitive resolution silently: `modules/workflow/pom.xml` now
declares `knowledge` directly, and architecture.md §3 is corrected to match.

**Stage-level persistence granularity, not sub-task-level.** The pipeline is a fixed sequence of six
named stages — `plan-sub-queries`, `retrieve`, `extract-per-source`, `synthesise`, `self-check`,
`answer` — and each becomes exactly one `WorkflowStep` row. Stages that fan out internally
(`retrieve` runs one retrieval per sub-query; `extract-per-source` runs one LLM call per retrieved
chunk) record their fan-out results inside that one step's `input`/`output` JSON, not as separate
rows. This reads the roadmap's "resumes from its last completed **step**" as stage, not sub-task, and
matches the schema architecture.md §7 already sketched without inventing a second, finer-grained
entity the docs never named.

**Which steps need an LLM, and which were kept deterministic** — the ADR's required call-out:

| Stage | LLM? | Why |
|---|---|---|
| `plan-sub-queries` | Yes | Breaking a question into sub-questions needs judgment; the response is plain-text-constrained (one sub-query per line), following `rag.internal.QueryNormalizer`/`knowledge.internal.LlmReranker`/`evaluation.internal.LlmJudge`'s house style: hand-rolled parser, graceful fallback (the original query, if parsing yields nothing), `log.warn` on every failure path. |
| `retrieve` | No | Plain `RagPipeline.search(...)` calls, run in parallel via `Executors.newVirtualThreadPerTaskExecutor()` (Java 25) — no model involved. |
| `extract-per-source` | Yes | Deciding what in a passage is relevant to the question needs judgment. Same house style; a source whose extraction fails, times out, or turns out irrelevant is dropped, not fatal, as long as one other source survives. |
| `synthesise` | Yes | Combining extracted facts into one cited answer needs judgment. Unlike the two stages above, there's no sensible fallback for "no answer" — a provider failure propagates to `StageRunner`'s own retry/compensation, not caught and degraded. |
| `self-check` | No | Verifying every `[n]` marker the synthesis cites actually corresponds to a source that survived `extract-per-source` is a lookup, not a judgment call — the same reasoning ADR-0008 already used for RAG citations. No second LLM self-critique call. |
| `answer` | No | Packages the final result: the synthesised text, the citation list, and a per-stage cost/attempts rollup already computed by the stages above. |

**`synthesise`'s corrective retry stays inside the stage — the pipeline never cycles back.** A naive
reading of "synthesise → self-check against citations" suggests self-check, on failure, loops back to
re-trigger synthesis. Instead, `synthesise` itself runs `citationChecker.invalidMarkers(...)` right
after its first LLM call; if any marker is invalid, it makes one corrective retry (naming the bad
markers) before moving on. `self-check` remains a distinct, real, deterministic step — usually a
pass, since the corrective retry already ran — but it's still what actually gates progress to
`answer`: if the corrective retry didn't produce valid citations either, `self-check` fails for real,
and the run is compensated. This keeps the state machine a strictly linear sequence, not a cycle,
which is simpler to persist and resume than a graph.

**The `tools` dependency edge is real (per architecture.md §3) but unexercised by this one
workflow — named honestly, not forced.** Routing `retrieve` through the registered
`knowledge-base-search` tool via `ToolInvoker.invokeOrThrow` was considered and rejected: that
machinery (JSON-Schema validation, scope authorization, `tool_invocation` audit rows) exists for tool
calls a *model* decides to make mid-turn (T2's threat model) — this pipeline's steps are
orchestrator-decided and fixed, so the machinery doesn't apply, and forcing it in would be surface
area for its own sake. The edge is kept (removing it means another docs correction for no benefit,
and a later workflow type whose steps let the model choose a tool will need it) — but Phase 6 doesn't
exercise it.

**Compensation is a real, explicit, tested action.** Every stage runs through a shared `StageRunner`
that persists the step `RUNNING` before executing, retries transient failures up to
`ai.workflow.stage-retry-attempts` (default 2) additional times, and on final exhaustion marks the
step `FAILED` (with the error captured in its `output`) and the run `FAILED` (with a summary reason)
— then simply stops; no further `WorkflowStep` rows are created. No literal "undo" of an external
side effect exists or is needed: every stage is read-only or computational (LLM calls, retrieval,
in-memory text checks), so there is nothing else to roll back. This is the deliberate reading of
"triggers compensation rather than leaving a partial result" — the run ends in a clean, inspectable
`FAILED` terminal state instead of silently returning a truncated or fabricated answer. `retrieve` and
`extract-per-source` tolerate partial internal failure on their own (succeeding if at least one
sub-task succeeds) — compensation only fires once a stage has exhausted *all* of its own tolerance.

**Retries are selective and back off — added in the post-roadmap review (issue #25, B1), not part of
Phase 6's original design.** The first cut of `StageRunner` retried every `catch (Exception e)`
immediately, with no delay. Both halves were wrong for the failure modes this harness actually faces:
a model-server timeout or rate limit gains nothing from three instant retries against the same
condition (Phase 8's own live run demonstrated exactly that — `LlmReranker` timed out on every call
against a 27B model, burning `1 + stage-retry-attempts` full timeouts back to back before falling
back), and a programming error or malformed-response parse failure isn't fixed by retrying at all, only
delayed. Now: only a `shared.ProviderException` (timeout, rate limit, connection failure — the actual
transient cases) is retried; anything else fails the stage on its first attempt, mirroring
`NonRetryableIngestionException`'s equivalent distinction already established in the ingestion
pipeline (docs/adr/0005-kafka.md). A retried attempt backs off exponentially —
`ai.workflow.retry-base-delay` (default 500ms), doubling each attempt — instead of firing immediately.
Both the retryable/non-retryable split and the backoff are pinned by `StageRunnerTest`, this class's
first real unit test (task #89 had recorded it as written; it wasn't — see post-roadmap review T2).

**Resumability: persisted-step-driven resume, triggered by `ApplicationReadyEvent`.**
`POST /api/v1/workflows/{type}/runs` persists a `WorkflowRun` (`PENDING`) and returns `202` +
`Location` immediately — matching §2's documented "asynchronous, persisted state" mode and the
existing `202`+`Location` pattern already used for document upload — then submits execution to a
background virtual-thread executor. `DocumentationResearchEngine.run(WorkflowRunId)` is idempotent by
design: it loads whichever `WorkflowStep` rows already exist for the run, skips any already
`SUCCEEDED`, and continues from the next one — the exact same entry point serves a brand-new run and
a resumed one. `WorkflowResumer`, an `ApplicationReadyEvent` listener, queries every run left
`PENDING` or `RUNNING` at startup and re-invokes the engine for each — precisely how a real restart
recovers.

**T5 (denial of wallet) gets a real, enforced bound.** `docs/threat-model.md` T5 already names "hard
step limit on workflow runs" as a planned mitigation. `ai.workflow.max-llm-calls-per-run` (default
20) is checked incrementally as the engine issues each LLM call; exceeding it compensates the run
with a clear reason, the same path as any other stage failure — moved here from *planned* to
*implemented*.

## Alternatives considered

### Spring Statemachine (or another workflow-engine library)

Would provide transition-graph tooling out of the box. Rejected: nothing in this repo or its docs
names one, the roadmap's own phrasing ("an explicit state machine") reads as a design instruction to
hand-roll it, and a fixed six-stage linear pipeline doesn't need a general graph engine's machinery —
the same "don't add a framework for something this codebase can reason about directly" judgment
behind the hand-written calculator and JSON-envelope tool-call parsing.

### `app` as the composition root (ADR-0008's precedent) instead of `workflow` owning its loop

Simpler mental model — one composition pattern for every orchestration in the codebase. Rejected: the
dependency-table evidence above (`workflow → rag, tools` already granted) structurally implies
`workflow` is meant to call into its own dependencies directly, matching ADR-0009's `tools`
precedent, not ADR-0008's `rag`/`conversation` one.

### Route `retrieve` through the `knowledge-base-search` tool via `ToolInvoker`

Would reuse existing scope/schema/audit infrastructure and make real use of the `tools` dependency
edge. Rejected: that infrastructure is built for model-initiated tool calls under T2's threat model:
gating and auditing a call the model *chose* to make mid-turn. This pipeline's steps are fixed and
orchestrator-decided — there is no model deciding to call anything — so the JSON-Schema round trip
and `tool_invocation` audit row would be ceremony without the risk they exist to mitigate. Direct
`RagPipeline.search(...)` calls are the natural, already-public, LLM-free retrieval seam.

### A second LLM call for self-check (an "LLM-as-judge" faithfulness check)

Would catch subtler issues than marker validity (e.g. a citation that's technically numbered
correctly but doesn't actually support the claim next to it). Rejected for this phase: citation
*validity* — does marker `[n]` correspond to a real, extracted source — is a lookup, not a judgment
call, and a deterministic check is exactly what ADR-0008 already chose for RAG citations. A
faithfulness judge is a real, separate capability (`evaluation.internal.LlmJudge` already builds one
for the eval harness) that could be layered on later without changing this stage's contract.

### A cyclic state machine — `self-check` loops back to `synthesise` on failure

More directly matches "synthesise → self-check against citations" read as two independent stages with
a feedback edge. Rejected: a cycle complicates persistence and resume (which `WorkflowStep` row does
a second `synthesise` attempt become — a new row, breaking the fixed six-stage index, or an overwrite,
losing the first attempt's audit trail?) for a correction that's cheap to do as one bounded retry
inside `synthesise` itself. The linear pipeline stays simple to persist, inspect and resume; `self-check`
still independently gates progress and can still fail for real.

### Sub-task-level persistence (a `WorkflowStep` row per sub-query or per extracted source)

Finer-grained inspectability — each retrieval or extraction call individually queryable. Rejected:
architecture.md §7's own schema sketch names only `WorkflowStep` at stage granularity, and nothing in
the acceptance criteria asks for sub-task-level rows; a second, finer-grained entity would be
speculative surface area for this one workflow. Sub-task results are still fully inspectable — just
nested inside the owning stage's `output` JSON rather than as separate rows.

## Trade-offs

- **The LLM-call budget (`ai.workflow.max-llm-calls-per-run`) resets per engine invocation, not
  cumulatively across a restart+resume.** A pathological repeated-crash-and-resume pattern could
  exceed the nominal per-run bound. Accepted given T5's own "planned mitigation" framing — a real,
  reasoned bound, not a precisely engineered guarantee, the same honesty as `ai.tools.max-calls-per-turn`
  (AGENTS.md rule 2).
- **`max-sub-queries` (4), `max-sources-to-extract` (8), `max-llm-calls-per-run` (20),
  `stage-retry-attempts` (2) and `retry-base-delay` (500ms) are unmeasured starting bounds**, not
  tuned against any dataset.
- **Fan-out sub-task attempts aren't separately queryable rows** — a `retrieve` or `extract-per-source`
  stage's individual sub-query/source outcomes are visible inside that one step's `output` JSON, not
  as their own inspectable `WorkflowStep` rows.
- **The automated test suite doesn't include a genuine process-restart proof of resumability.**
  Reliably engineering a deterministic mid-flight interruption inside a JUnit test proved impractical:
  the `recorded` profile's fixture-backed LLM calls resolve in sub-millisecond time (`RecordedChatProvider.complete`
  has no artificial delay, unlike `.stream()`), leaving no reliable window to interrupt a run
  mid-stage without adding test-only hooks to production code or hand-writing a fake `JpaRepository`
  (no precedent anywhere in this codebase — every other module's persistence-touching logic is
  verified via real Testcontainers integration tests in `app`, not fakes). The mechanism itself —
  idempotent, persisted-step-driven resume via `ApplicationReadyEvent` — is implemented and reasoned
  about above, and is verified live (restarting the real `docker compose` app container mid-run and
  observing recovery via `GET /api/v1/workflows/runs/{id}`) rather than solely by an automated test.
  This mirrors ADR-0009's own accepted limitation around confirmation-state non-resumability — a real,
  named gap between what's automated and what's live-verified, not a hidden one.
- **No separate `COMPENSATING`/`COMPENSATED` status.** Nothing exists to compensate beyond the run's
  own terminal-state transition into `FAILED`, since every stage is read-only/computational.
- **One workflow type only (`documentation-research`)** — `WorkflowType` is a one-value enum, per the
  roadmap's own "One workflow" framing, not built out as a generic pluggable-workflow-type registry.
- **No list-runs or list-steps endpoint** — not required by the acceptance criteria, not documented
  anywhere already; `GET /api/v1/workflows/runs/{id}` nests every step inline.

## Consequences

- `WorkflowService`'s public façade (`startDocumentationResearch`, `findRun`) is the seam a future
  workflow type would extend — a new `WorkflowType` value, a new engine implementation, both reusing
  `StageRunner`, the JSON converter, and the resumer's `PENDING`/`RUNNING` query as-is.
- `workflow → tools` staying unexercised this phase means a future step type that lets the model
  choose which tool to call would route through `ToolInvoker` the way chat's tool-calling loop does —
  the edge is already there, waiting.
- Reversing `workflow`'s ownership of its own run loop later (moving orchestration into `app`) would
  mean re-threading `StageRunner`/`DocumentationResearchEngine`'s wiring through a new composition
  root — expensive enough that, like ADR-0009's equivalent call, this is meant to be durable, not a
  placeholder.
- `docs/threat-model.md` T5's "hard step limit on workflow runs" bullet is now backed by a real,
  enforced config value, not just a stated intent — the first of T5's several planned mitigations to
  move to implemented.
