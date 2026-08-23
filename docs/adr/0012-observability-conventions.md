# ADR-0012: Observability conventions and GenAI semantic attributes

- **Status:** Accepted
- **Date:** 2026-08-23
- **Phase:** 8

## Context

`docs/roadmap.md`'s Phase 8 is the project's last: "Complete threat model, security scanning and
SBOM, a measured latency baseline, sequence diagrams, the theoretical cloud deployment section,
README polish, and a recorded demo." This ADR's slot in `docs/adr/README.md`'s Planned table names it
"Observability conventions and GenAI semantic attributes" — written now, at the point real
observability decisions have accumulated across five prior phases and need a single record, per
`docs/adr/README.md`'s own rule: "Written when the phase that needs them begins — not before."

Auditing `docs/architecture.md` §12 against the actual code (grep for `Observation`/`setAttribute`/
metric names across every module, not assumed) surfaced two real gaps this ADR exists to resolve, not
just describe:

**A naming drift from the current OTel GenAI semantic convention.** `ai-provider`'s
`LmStudioChatProvider` is the only place in the codebase with real `gen_ai.*` span attributes
(`gen_ai.system`, `gen_ai.request.model`, `gen_ai.response.model`, plus token-usage counts) — built in
Phase 1, before this project had a name for the convention it was already following. Checking the
*current* spec (opentelemetry.io/docs/specs/semconv, "Gen AI" registry) shows token-usage attributes
are now named `gen_ai.usage.input_tokens`/`gen_ai.usage.output_tokens`; the code used the older
`prompt_tokens`/`completion_tokens` naming. GenAI semconv is still marked "Development" status, so
this kind of drift is expected, not a mistake — but it should be corrected rather than left, since
nothing depends on the old names outside this one file.

**An overclaimed trace/metric list.** §12 named `rag.top_k`, `rag.retrieved_ids`,
`rag.rerank.enabled`, `llm.model`, `llm.prompt_tokens`, `llm.completion_tokens`, `llm.cost_usd` as
real span attributes, and ten Prometheus metrics. Grepping every module found: the `rag`/`knowledge`
modules have **zero** `Observation` instrumentation — the entire `rag.*` list was aspirational, and
the `llm.*` names don't match what `ai-provider` actually emits (`gen_ai.*`, above). Of the ten listed
Prometheus metrics, only two exist: `tool_invocation_total`/`tool_duration_seconds`
(`tools.internal.ToolMetrics`, Phase 5). The other eight
(`rag_request_duration_seconds`, `llm_tokens_total`, `llm_cost_usd_total`,
`ingestion_job_duration_seconds`, `ingestion_jobs_active`, `kafka_consumer_lag`,
`dlt_messages_total`, `retrieval_score_distribution`) don't exist anywhere. The list also *omits* two
metrics that **do** exist — `workflow_run_total`/`workflow_step_duration_seconds`
(`workflow.internal.WorkflowMetrics`, Phase 6) were never added to §12 when Phase 6 built them. This
is the same class of problem Phase 8 is already correcting in `docs/threat-model.md` and
`CONTRIBUTING.md` (security-scanning tools claimed as already running when they were not) — an
aspirational or simply stale doc statement left uncorrected long enough to be read as current state.

## Decision

**The `gen_ai.*` namespace is this project's standing convention for anything OTel's GenAI semantic
conventions define**, tracking the spec's current attribute names rather than freezing to whatever
was current when a given phase shipped — the Development status means it moves, and there's no cost
to following it since nothing outside this project consumes these span names. `LmStudioChatProvider`'s
token-usage attributes are renamed `gen_ai.usage.input_tokens`/`gen_ai.usage.output_tokens` to match.

**For concepts GenAI semconv doesn't cover — retrieval, tool execution, agentic workflow steps — this
project uses its own `<domain>.<attribute>` namespace for span attributes** (`rag.top_k`,
`rag.rerank.enabled`, `rag.retrieved_chunk_count`, all new this phase) **and a
`<module>_<noun>_total`/`<module>_<noun>_duration_seconds` pattern for Prometheus metrics** — already
the shape `tool_invocation_total`/`tool_duration_seconds` and `workflow_run_total`/
`workflow_step_duration_seconds` took independently in Phases 5 and 6, formalized here rather than
invented. Reasoning: Prometheus metric names are conventionally `snake_case` with a unit suffix
(`_total` for counters, `_seconds` for time histograms) regardless of language ecosystem, so following
that convention costs nothing and makes the metrics readable by anyone who has used Prometheus
before, independent of this project.

**A real `rag.retrieve` `Observation` was added this phase** (`rag.RagPipeline`, mirroring
`LmStudioChatProvider`'s exact `Observation.createNotStarted(...).observe(...)` idiom — no new
instrumentation pattern introduced), carrying `rag.top_k`, `rag.rerank.enabled` (low cardinality — a
small, profile-bounded set of values) and `rag.retrieved_chunk_count` (high cardinality — a per-request
count). This is the one attribute cluster judged worth building for real this phase: `README.md`
already promises "the chat UI links each answer to its trace in Grafana," and RAG is this project's
centerpiece — a reviewer following that link during the demo should see a real, meaningful span, not
an empty gap between the API span and the `gen_ai.chat` span.

**The eight other previously-claimed Prometheus metrics are not built this phase, and
`architecture.md` §12 is corrected to say so plainly** rather than either building all of them or
leaving the overclaim in place — and the two real, undocumented workflow metrics are added to the
list. None of Phase 8's four roadmap acceptance criteria need `rag_request_duration_seconds`,
`llm_tokens_total`, `llm_cost_usd_total`, `ingestion_job_duration_seconds`, `ingestion_jobs_active`,
`kafka_consumer_lag`, `dlt_messages_total`, or `retrieval_score_distribution` — instrumenting
ingestion and knowledge module-by-module for metrics nothing currently reads is a separate, larger
body of work than an already-large final phase should absorb without a concrete consumer driving it
(a dashboard panel, an alert) — the same reasoning `docs/roadmap.md`'s "Deliberately deferred" table
already applies to semantic caching: "meaningful only once real query patterns exist."

## Alternatives considered

### Adopt `gen_ai.usage.prompt_tokens`/`completion_tokens` permanently, treat the spec's rename as not worth chasing

Would avoid a two-line code change. Rejected: the whole point of adopting a named semantic convention
is that a Grafana dashboard, a trace query, or a future contributor's mental model can assume the
current spec's names apply — silently keeping a name the spec itself moved away from defeats that,
and the fix touches exactly one file.

### Build out all eight previously-claimed Prometheus metrics this phase, so every existing doc claim becomes true

Would fully close the doc/reality gap in one pass. Rejected: four of the eight
(`ingestion_job_duration_seconds`, `ingestion_jobs_active`, `kafka_consumer_lag`, `dlt_messages_total`)
require touching the `ingestion` module's Kafka consumers, none of which this phase otherwise changes,
for metrics with no current dashboard panel or alert to consume them. Named as an explicit scope
reduction instead — the doc now states what's real, and the gap is a legitimate future addition once
something needs those numbers, not a silently abandoned promise.

### Invent an `ai_lab.*` root namespace for every custom attribute, instead of a bare `rag.*`/`tool.*`-style prefix

Would make every project-specific attribute unambiguously distinguishable from a future official
semantic convention that happens to reuse a short name. Rejected as unnecessary ceremony: OTel's own
semconv namespaces (`gen_ai.*`, `db.*`, `http.*`) are already short domain words, and a collision
between `rag.*` (a concept OTel has no reason to standardize) and a future official namespace is a
low-probability, low-cost-to-fix-later risk not worth a longer prefix on every attribute today.

## Trade-offs

- Renaming `gen_ai.usage.prompt_tokens`/`completion_tokens` breaks any dashboard or trace query
  written against the old names — accepted, since none exist yet (§12's own Prometheus/Grafana panels
  never referenced these span attribute names, only the metrics, which are unaffected).
- The `rag.retrieve` observation only wraps the retrieval stage, not the full RAG pipeline including
  generation — deliberate: `gen_ai.chat` (via `ChatProvider`) already covers generation, and a single
  span covering both would conflate two different systems' latency (this application's retrieval vs.
  the model server's response time) into one number.
- Five metrics remain undocumented-as-built rather than built — a real, named gap, not a silent one.

## Consequences

- A future contributor adding a new span attribute has a rule to follow: check GenAI semconv first: if
  it defines the concept, use its current name under `gen_ai.*`; otherwise, a short domain prefix
  matching the owning module (`rag.*`, `tool.*`, `workflow.*`).
- `docs/architecture.md` §12 now states only what a `grep` across the codebase would confirm — closing
  the same category of gap this phase already closes in `docs/threat-model.md` and
  `CONTRIBUTING.md` for security scanning.
