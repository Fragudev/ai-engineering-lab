# Improvement plan

> **Status: closed. All 17 `audit` issues raised from this review are merged and shipped** — every
> finding in the priority summary below, S1 through O1. The findings are kept in present tense as
> they were written, because the file/line evidence behind each one is the record of what was
> actually wrong; read them as history, not as open work. What deliberately remains is the three
> findings that never got an issue (D2, S5, O3) — each argued below as not worth acting on, or
> already tracked in its own ADR.
>
> Left standing rather than deleted for the same reason an ADR is never rewritten to reverse itself:
> a review that quietly vanishes once its findings are fixed leaves no evidence the codebase was ever
> examined this closely.

A post-roadmap review of the codebase, done after all 9 phases closed. Every finding below was
verified against the actual code, not inferred from the documentation — file and line references are
given so each one can be checked independently.

**Tracked as issues.** Each actionable finding has its own issue, labelled
[`audit`](https://github.com/Fragudev/ai-engineering-lab/issues?q=is%3Aissue+label%3Aaudit) and
carrying a `severity:` and `effort:` label, written as a spec with acceptance criteria. This document
is the analysis; the issues are the work. Findings deliberately left without an issue are marked as
such below (D2, S5, O3).

**Method and its limits.** This was a targeted read of the highest-risk areas (the tool-calling
gate, the workflow retry harness, the ingestion entry points, the UI, configuration handling) plus
systematic greps across all 225 main source files. It is **not** exhaustive: no dynamic analysis, no
fuzzing, no dependency-by-dependency audit beyond what CI already runs, and no review of the 25-file
`ingestion` module beyond its HTTP entry point. Absence from this list is not evidence of absence.

**One theme worth naming up front.** Phase 8 audited the documentation for claims that outran the
code and corrected several. That audit was incomplete: three more false claims survive (S4, Q1), all
in the same documents. The pattern is not carelessness — it is that the docs were written as a
specification first and only partially reconciled afterwards. A recurring check is worth more than
another one-off audit.

---

## Priority summary

| # | Finding | Severity | Effort |
|---|---|---|---|
| [S1](#s1--stored-xss-in-the-document-list) | Stored XSS in the document list | **High** — exploitable today | S |
| [S2](#s2--the-confirmation-gate-and-the-executor-use-different-sources-of-truth) | Confirmation gate and executor disagree on the tool set | Medium — latent | S |
| [B1](#b1--stagerunner-retries-with-no-backoff-and-retries-everything) | `StageRunner` retries with no backoff, and retries non-retryable errors | Medium | S |
| [T1](#t1--six-of-twelve-modules-have-no-tests-at-all) | 6 of 12 modules have zero tests | Medium | L |
| [B3](#b3--no-validation-on-any-configuration-property) | No validation on any `@ConfigurationProperties` | Medium | S |
| [S4](#s4--the-docs-still-claim-log-redaction-that-does-not-exist) | Docs claim log redaction that does not exist | Medium — honesty | S |
| [Q1](#q1--four-rows-of-the-testing-table-name-tools-that-are-not-in-the-project) | Testing table names 4 tools not in the project | Medium — honesty | S |
| [B5](#b5--the-rag-abstention-threshold-is-still-miscalibrated) | RAG abstention threshold miscalibrated | Medium — known | M |
| [O1](#o1--silent-degradation-is-invisible) | Silent LLM degradation has no metric | Medium | S |
| [T5](#t5--the-dead-letter-topic-is-never-actually-asserted) | Dead-letter topic never asserted in any test | Medium | S |
| [T3](#t3--there-is-no-coverage-measurement-at-all) | No coverage measurement exists | Low | S |
| [D1](#d1--the-call-the-model-and-degrade-pattern-is-repeated-six-times) | LLM-call-and-degrade duplicated 6× | Low | M |
| [S3](#s3--no-input-size-limit-was-ever-chosen) | Input size limits are accidental, not chosen | Low | S |

---

## Security

### S1 — Stored XSS in the document list

**The most serious finding in this review, and the only one exploitable as the code stands.**

`app/src/main/resources/static/index.html:286-288` builds a row with `innerHTML` and interpolates
two attacker-controlled values directly:

```js
row.innerHTML =
    `<span class="doc-title">${doc.title}</span>` +
    `<span class="stage-badge stage-${stage}">${stage}${job && job.lastError ? ": " + job.lastError : ""}</span>`;
```

`doc.title` traces straight back to user input. `DocumentController.upload`
(`app/.../DocumentController.java:34-37`) takes it from either the `title` request parameter or
`file.getOriginalFilename()`, applies no sanitization, and stores it. Uploading a file named
`<img src=x onerror=...>.md` — or simply passing that as `title` — executes script in the browser of
anyone who opens the document list.

Two things make this worse:

- **Nothing mitigates it.** There is no `Content-Security-Policy`, `X-Content-Type-Options`, or any
  other security header anywhere in the application.
- **`job.lastError` is interpolated too**, and error messages can embed fragments of document
  content — so a document whose *contents* break the parser is a second injection path.

A third, lower-severity instance: `addMeta()` (line 154-157) also uses `innerHTML`, and its only
caller (line 250-253) passes `usage.model`, which comes from the model server's response. A
compromised or malicious model server can inject markup — the same trust boundary
`docs/threat-model.md` T9 was written for.

This is an **oversight, not an accepted risk**: `addBubble()` on line 148 correctly uses
`textContent` for chat content, so the escaping question was clearly considered elsewhere.
`threat-model.md` T8 lists output sanitization as *planned*, but frames it around model output —
it does not disclose a live stored-XSS path from a filename.

Worth stating plainly: this is a single-user application on localhost, so the practical blast radius
today is small. But the threat model's own opening assumption is that *"the deployment context is not
the threat context"* and that user-supplied documents may be deliberately malicious. By the
project's own stated standard, this counts.

**Fix.** Build the row with `createElement` + `textContent` the way `addBubble` already does, or
escape at minimum. Add a `Content-Security-Policy` header. Both are small.

### S2 — The confirmation gate and the executor use different sources of truth

`ToolCallingChatService.handleToolCall` decides whether a call needs user confirmation by looking the
tool up in `allTools`, the list handed to `stream()`
(`modules/tools/.../ToolCallingChatService.java:107-111`):

```java
Optional<ToolDefinition> definition = allTools.stream()
        .filter(candidate -> candidate.name().equals(call.name()))
        .findFirst();
boolean requiresConfirmation =
        definition.isPresent() && (state.untrusted || definition.get().alwaysRequiresConfirmation());
```

But `ToolInvoker.invokeForChat` resolves the tool from the **global** `ToolRegistry`
(`modules/tools/.../ToolInvoker.java:107`). The two sets are not guaranteed to match, and when they
diverge the code **fails open**: `definition.isEmpty()` makes `requiresConfirmation` false, and the
call proceeds straight to the executor at line 146 without a gate.

As written this is not exploitable — both callers pass `toolRegistry.definitions()`, so the sets are
identical. What makes it worth fixing is that Phase 7 made `ToolRegistry` mutable (`register()`, for
runtime MCP discovery) without revisiting this assumption. A tool registered *after* `stream()`
captured `allTools` lands in exactly this gap — and MCP-sourced tools are precisely the ones
`docs/threat-model.md` T9 requires to be confirmed on *every* call. The window is narrow today
(registration happens at `ApplicationReadyEvent`), but the invariant is enforced nowhere.

**Fix.** Fail closed: if `definition.isEmpty()`, return an `ERROR` result immediately instead of
handing an unrecognised name to the executor. Two lines, and it makes the security property
structural rather than incidental.

### S3 — No input size limit was ever chosen

There is no `spring.servlet.multipart.*` configuration in any `application*.yml`. Spring Boot's
defaults (1 MB per file, 10 MB per request) therefore apply — so this is not unbounded, but it is
**accidental rather than decided**, and a 1 MB ceiling on a document-ingestion product is a strange
default to inherit silently. `DocumentController.upload` also calls `file.getBytes()`, loading the
whole upload into memory, which becomes a real concern the moment someone raises the limit without
noticing that.

There is likewise no length limit on a chat message anywhere in the request path.
`docs/threat-model.md` T5 lists both as planned.

### S4 — The docs still claim log redaction that does not exist

There is no redaction code in the codebase. A case-insensitive search for `redact`, `sanitiz` or
`mask` across all 225 main source files returns exactly one hit, and it is an unrelated comment in
`IdempotencyGuard`.

Yet two documents state redaction as a live fact:

- `docs/architecture.md:669` — *"**Prompts and completions are redacted by default**, with a
  local-only flag to enable them for debugging."*
- `docs/threat-model.md:205` — the STRIDE table's Information-disclosure row lists *"redaction by
  default"* among its mitigations.

`threat-model.md:159` correctly marks T7's redaction as *planned* — so the threat model contradicts
itself two sections apart. Phase 8's audit caught the security-scanning claims and missed these.

**Fix.** Either implement it or correct both lines. Correcting is a five-minute change; implementing
is the better outcome but is a real piece of work, so it should not block the correction.

### S5 — No authentication (already documented)

No Spring Security dependency exists; `ScopeAuthorizer` checks tool scopes against a static
config list (`ai.tools.granted-scopes`) that stands in for a principal. This is accurately and
repeatedly documented (ADR-0009, architecture.md §13, threat-model.md §4) and is the correct call for
a single-user local project. It is restated here only because it is the single largest blocker to
this application being run anywhere other than localhost.

---

## Bugs

### B1 — `StageRunner` retries with no backoff, and retries everything

`modules/workflow/.../StageRunner.java:56-69` is the retry harness for all six workflow stages:

```java
for (int attempt = 1; attempt <= totalAttempts; attempt++) {
    step.markRunning(input);
    stepRepository.save(step);
    try {
        StageOutcome outcome = function.execute();
        ...
    } catch (Exception e) {
        lastError = e;
        log.warn("Stage '{}' attempt {}/{} failed for run {}", name, attempt, totalAttempts, runId, e);
    }
}
```

Two distinct problems:

1. **No delay between attempts.** The loop retries immediately. For the failure modes this harness
   actually faces — a model-server timeout, a rate limit, a transient connection error — three
   instant retries will hit the same condition three times and accomplish nothing but burning the
   attempt budget. The ingestion module's Kafka consumers do use backoff, so the project already
   knows better; this path just did not get it.
2. **`catch (Exception e)` retries non-retryable failures.** A `NullPointerException`, a schema
   violation, a malformed-response parse error — all get retried the full budget before failing.
   That converts a fast, clear failure into a slow, noisy one.

This matters more than it looks: Phase 8's live run showed `LlmReranker` timing out on every call
against a 27B model. A stage wrapping that behaviour would burn `1 + stage-retry-attempts` full
timeouts back to back before giving up.

### B2 — `StageRunner` throws `NullPointerException` on a negative retry setting

Same file, line 53 and line 71:

```java
int totalAttempts = 1 + properties.stageRetryAttempts();
...
step.markFailed(Map.of("error", String.valueOf(lastError.getMessage())));
```

Configure `ai.workflow.stage-retry-attempts: -1` and the loop body never executes, `lastError` stays
`null`, and line 71 dereferences it. Unlikely to be hit by accident, but it is only unreachable
because nothing validates the property — see B3. (Minor, same line: `String.valueOf(getMessage())`
writes the literal string `"null"` into the persisted error when the exception has no message.)

### B3 — No validation on any configuration property

All four `@ConfigurationProperties` classes — `ToolsProperties`, `LmStudioProperties`,
`McpProperties`, `WorkflowsProperties` — declare zero constraints. No `@Validated`, no `@Min`, no
`@NotBlank`, anywhere in the project.

The consequence is that a typo or a bad environment override surfaces as a confusing runtime failure
deep in a request, instead of a clear refusal to start. That directly contradicts the philosophy the
project states for itself in the README, where `scripts/bootstrap.sh` *"fails with a clear message
rather than a confusing distance error three steps later."* The same standard should apply to
configuration.

This is also a live concern rather than a theoretical one: Phase 8's latency work required
overriding `AI_PROVIDER_LMSTUDIO_TIMEOUT`, `..._CHAT_MODEL` and `..._EMBEDDING_MODEL` by hand, and a
typo in any of them would have failed obscurely.

### B4 — `PendingConfirmationRegistry` registers before subscription

`modules/tools/.../PendingConfirmationRegistry.java:29-33` inserts into the map inside `await()`,
but cleanup happens in `doFinally` — which only runs if the returned `Mono` is subscribed. Every
current caller does subscribe, so nothing leaks today. It is noted because the entry's lifetime is
coupled to a caller obligation that nothing enforces; moving the `put` into a `Mono.defer` would
make it self-contained. There is also no cap on concurrent pending confirmations, which is a minor
denial-of-service consideration given S3 and the absence of rate limiting.

### B5 — The RAG abstention threshold is still miscalibrated

Already found and documented during Phase 8 (`docs/ai-evaluation.md` §8): `RagProfiles`'
`maxVectorDistance` of 0.6 was calibrated implicitly against the `recorded` profile's synthetic
embeddings. Real `bge-m3` distances on this corpus run ≈0.95, so the pipeline abstains on every
query when pointed at a real embedding model.

Listed here because it remains **the single biggest functional defect in the product**: with a real
model, RAG does not work. It was correctly left unfixed in a hardening phase — recalibrating needs
the full golden dataset's real distance distribution, not the one query that exposed it — but it
should be the first functional item picked up.

---

## Test coverage

### T1 — Six of twelve modules have no tests at all

225 main source files, 36 test files.

| Module | Main files | Test files |
|---|---|---|
| `ingestion` | 25 | **0** |
| `shared` | 20 | **0** |
| `knowledge` | 15 | **0** |
| `rag` | 12 | **0** |
| `conversation` | 9 | **0** |
| `platform` | 4 | **0** |
| `tools` | 27 | 6 |
| `workflow` | 27 | 5 |
| `evaluation` | 26 | 6 |
| `ai-provider` | 20 | 1 |
| `mcp` | 6 | 1 |
| `app` | 34 | 17 |

Some of this is genuinely covered from the `app` integration tests — `IngestionFlowIntegrationTest`,
`IngestionFailureIntegrationTest` and `IdempotencyGuardIntegrationTest` exercise the Kafka pipeline
end to end, and `RagFlowIntegrationTest` covers the RAG path. That is real coverage and it caught
real bugs during the build.

But it is all coarse-grained: every one of those tests needs Postgres and Kafka containers, so the
cheap fast feedback loop for `knowledge`'s fusion maths, `rag`'s context building and abstention
logic, or `shared`'s typed IDs simply does not exist. `ContextBuilder`'s greedy packing and
`ReciprocalRankFusion`'s scoring are pure functions with zero dependencies and zero tests — they are
the easiest possible unit-test wins in the codebase.

### T2 — The workflow retry and resume core is untested

`StageRunner` (the retry/compensation harness) and `WorkflowResumer` (crash recovery) have no tests.
These are the two classes where Phase 6's headline claims — resumable, compensable — actually live.
Both bugs in B1/B2 above are in `StageRunner`, and a unit test would have caught the missing backoff
immediately.

Worth noting for process reasons: this project's own task list recorded these tests as written
(task #89 names `StageRunner` and `WorkflowResumer` explicitly). They were not. A completed task
item is not evidence.

### T3 — There is no coverage measurement at all

No JaCoCo, no coverage plugin of any kind in any `pom.xml`. So "test coverage" is currently an
opinion. Adding JaCoCo with a deliberately low initial threshold would turn it into a number that
can be argued about, which is the point.

### T5 — The dead-letter topic is never actually asserted

`IngestionFailureIntegrationTest` has one failure test,
`embeddingFailureExhaustsRetriesAndFailsTheJob`, and it asserts what its name says: retries are
exhausted and the job ends `FAILED`. A search for `dlt`, `DLT` or `DeadLetter` across the **entire**
test suite returns nothing. **No test asserts that anything ever lands in the dead-letter topic.**

This matters more than a normal coverage gap because the DLT is the specific thing this project
holds up as its most reviewable feature. `architecture.md` §11 says so directly:

> *"Demonstrating that a document whose embedding stage fails three times lands in the dead-letter
> topic with its job in `FAILED`, a populated `last_error`, and a complete trace showing all three
> attempts — that is the part worth reviewing."*

Half of that sentence is tested. The dead-letter landing — the half that makes it interesting — is
not. Adding the assertion to the existing test is small; it already has the containers running and
has driven the failure.

### T4 — No true end-to-end test tier

The integration tests drive the real HTTP API against real containers, which is strong. What does
not exist is a test that exercises the full user journey the way `scripts/demo.sh` does manually —
upload, index, ask, cite, confirm a tool, run a workflow — as an automated check. `demo.sh` is
effectively that test, run by hand. Promoting it to a tagged, CI-runnable suite (against the
`recorded` profile) would close the gap cheaply.

---

## Duplication and code quality

### D1 — The "call the model and degrade" pattern is repeated six times

The same shape appears in `QueryNormalizer`, `LlmReranker`, `SubQueryPlanner`, `SourceExtractor`,
`AnswerSynthesiser` and `LlmJudge` — six classes across four modules: build a prompt, call
`chatProvider.complete`, parse the response, and on `RuntimeException` log a warning and return a
domain-specific fallback.

```
QueryNormalizer.java:42   catch (RuntimeException e) { log.warn("Query normalization failed, ..."); return query; }
LlmReranker.java:52       catch (RuntimeException e) { log.warn("LLM reranking failed, ...");       return ...; }
SubQueryPlanner.java:48   catch (RuntimeException e) { log.warn("Sub-query planning failed, ...");  return ...; }
```

The fallbacks are legitimately different, so this is not copy-paste — but the *structure* is
identical, and that structure is where cross-cutting concerns belong. A small helper in `ai-provider`
(prompt in, parsed result or fallback out) would give one place to add the degradation metric from
O1, per-call timeouts, and the redaction from S4 — instead of six.

### D2 — Response DTO mapping boilerplate

The `app` module's response records each carry a static `from(...)` mapper. It is repetitive but
explicit, boundary-appropriate, and cheap to read. Noted for completeness; **not** recommended for
change — a mapping framework here would cost more clarity than it saves lines.

### Q1 — Four rows of the testing table name tools that are not in the project

`docs/architecture.md` §11 presents a nine-row testing-strategy table. Phase 8 corrected the Security
row. Four of the remaining rows name tooling that appears in **no** `pom.xml`:

| Row | Claim | Reality |
|---|---|---|
| API contract | "OpenAPI validator, MockMvc" | No OpenAPI validation dependency; no contract test |
| Provider | "WireMock + fixtures" | **WireMock is not a dependency** |
| Failure path | "Testcontainers + Toxiproxy" | **Toxiproxy is not a dependency** |
| Static | "Spotless, Error Prone, NullAway" | Only Spotless; neither Error Prone nor NullAway is configured |

The described *coverage* is partly real by other means — provider timeouts are tested in
`LmStudioChatProviderTest` with a hand-written fake, and retry exhaustion is covered by
`IngestionFailureIntegrationTest` with Testcontainers. The **tooling column is fiction**. Same class
of problem as S4, in the same document. The Failure-path row's claimed coverage is also overstated
in a second way — see T5.

---

## Functional improvements

### O1 — Silent degradation is invisible

Exactly two counters exist in the entire codebase: `tool_invocation_total` and `workflow_run_total`.
Nothing counts the graceful-degradation paths from D1.

This is not hypothetical. During Phase 8's live run, `LlmReranker` fell back to fused order on
**every single call** — a complete, silent failure of a headline feature — and the only signal was a
`WARN` line in the log. A `llm_degradation_total{component, reason}` counter would have surfaced it
as a flat line at 100%.

Cheapest high-value observability work available, and it pairs naturally with the D1 refactor.

### O2 — Retrieval quality is unmeasurable until B5 is fixed

The evaluation harness is complete and correct, but while every query abstains, every retrieval
metric it produces is 0 and every latency figure measures the abstention short-circuit rather than
the pipeline. B5 is therefore a prerequisite for the evaluation harness to be useful against a real
model — the two should be scheduled together.

### O3 — Documented-and-deferred items still open

Restated without re-litigating; each is already argued in its own ADR or in `roadmap.md`:
eight Prometheus metrics named in `architecture.md` §12 but not built (ADR-0012), no provisioned
Grafana dashboards, no rate limiting or token budgets (T5), no SSRF controls (T4, currently moot —
no tool performs real egress), and LLM reranking being impractical on consumer hardware with a
27B-class model.

---

## Suggested sequencing

Ordered by value per unit of effort, not by severity alone.

**1 — Correctness and honesty (a day or so, all small)**
- S1: escape the document list, add a CSP header
- S2: fail closed on an unrecognised tool name
- S4 + Q1: correct the three remaining false documentation claims
- B2 + B3: add `@Validated` and bounds to the four properties classes

**2 — Make the workflow harness trustworthy (small)**
- B1: exponential backoff, and a retryable/non-retryable distinction
- T2: unit tests for `StageRunner` and `WorkflowResumer` — write them alongside the fix
- T5: assert the dead-letter landing in the test that already provokes the failure

**3 — Make quality measurable again (medium)**
- B5: recalibrate `maxVectorDistance` against the golden dataset's real `bge-m3` distance
  distribution, then re-run the evaluation harness for figures that mean something
- O1: `llm_degradation_total`, ideally via the D1 helper

**4 — Raise the testing floor (larger, incremental)**
- T3: JaCoCo with a low starting threshold, ratcheted upward
- T1: unit tests for the pure functions first — `ContextBuilder`, `ReciprocalRankFusion`,
  `CitationExtractor`, the `shared` typed IDs. Highest value per line of test code in the project.
- T4: promote `scripts/demo.sh` into a tagged end-to-end suite

**Deliberately not recommended**
- D2 (DTO mapping) — the duplication is the clearer option
- Adding WireMock/Toxiproxy/Error Prone just to make Q1's table true — correcting the table is
  honest and free; adopting four tools to retrofit a claim is the tail wagging the dog
