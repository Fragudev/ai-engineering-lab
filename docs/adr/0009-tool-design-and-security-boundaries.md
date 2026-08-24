# ADR-0009: Tool design and security boundaries

- **Status:** Accepted
- **Date:** 2026-08-22
- **Phase:** 5

## Context

Phase 5 has to resolve a real contradiction in the already-committed design, not just implement a
feature. `docs/architecture.md` §3's module table says `tools` depends on `shared` only. But
`docs/adr/0004-ai-provider-abstraction.md`'s own Consequences section says, in writing: *"The
`tools` module (Phase 5) depends on `ProviderCapabilities` for its structured-output fallback."*
`architecture.md` §8 says the same thing in prose. Two committed documents already assumed an edge
the dependency table doesn't grant — this ADR is where that gets fixed for real, per AGENTS.md rule
4 ("if a module needs something from another module's boundary, either the API is wrong or the
boundary is — fix the design, don't add an exclusion").

Two further, larger questions were resolved by explicit user choice before implementation, not
inferred: (1) `docs/threat-model.md`'s T2 (indirect prompt injection) names tool-call confirmation
as *"the structural control"* for tool calls sourced from retrieved content — Phase 5 builds the
real interactive pause/approve/resume mechanism, not a lighter synchronous stand-in; (2) tool
calling applies to both the plain-chat and RAG-chat paths, not RAG-chat only.

## Decision

**New dependency edges: `tools → ai-provider`, `conversation → tools`, `rag → tools`.** `tools` now
depends on `ProviderCapabilities`/`ChatProvider`/`ChatMessage` to drive the tool-calling loop and
query native-tool-calling support for its structured-output fallback — resolving the contradiction
above for real. `conversation` and `rag` both need to invoke that loop from their respective chat
paths. Verified acyclic: `tools → {shared, ai-provider}`; nothing downstream of `tools` depends back
on it.

**The tool-calling loop lives inside `tools`, as `ToolCallingChatService`**, called by both
`ConversationService.sendMessage` (plain chat) and `RagPipeline.answer` (RAG chat) in place of their
previous direct `chatProvider.stream(...)` call. It owns: prompting the model with available tool
definitions, detecting a tool-call attempt via `internal.ToolCallSniffer` (peeks only the first
non-empty delta — if it starts with `{`, buffer and parse the whole response as a tool-call envelope
once it completes; otherwise stream live, unmodified — this is what keeps an ordinary answer
streaming token-by-token instead of always buffering), validating/authorizing/executing via
`ToolInvoker`, appending the `TOOL`-role result, and looping (bounded by
`ai.tools.max-calls-per-turn`) until a plain-text answer or the bound is hit.

**`ChatChunk` (ai-provider) is not extended for tool calls — the same call ADR-0008 already made
for citations.** That ADR states it directly: *"`RagPipeline.answer`'s `Flux<RagAnswerChunk>` is a
second streaming-chunk shape, parallel to `ai-provider`'s `ChatChunk` rather than reusing it,
because citations need a slot `ChatChunk` has no business knowing about."* Tool calls get the same
treatment: `tools.ToolChatChunk`, mirroring `ChatChunk`'s exact static-factory idiom
(delta/toolCall/toolResult/pendingConfirmation/last), consumed by `ConversationService` directly and
by `RagPipeline` (which folds its events into `RagAnswerChunk`'s existing discriminated-union
pattern, alongside `citation`).

**Confirmation gate: latches for the rest of the turn, not just at its start.** The naive reading of
"gate calls originating from a RAG-context turn" misses that the knowledge-base-search tool can
inject retrieved content into a *plain-chat* turn mid-loop. Fix: `ToolDefinition` gets
`introducesRetrievedContent` (`true` only for knowledge-base-search); the loop tracks a mutable
"context now untrusted" bit, seeded `true` for RAG-context origin, latched permanently `true` the
moment any tool with that flag actually gets called — every confirmation decision for the rest of
the turn reads the current value, not the origin the turn started with. So: a plain-chat turn's
*first* call to knowledge-base-search is correctly ungated (nothing untrusted yet); its *second*
tool call, of any kind, is gated, because the model's context now contains retrieved content.

**The gate and the executor must resolve a tool call from the same set — enforced, not assumed.**
`handleToolCall` looks up a call's `ToolDefinition` in `allTools`, the exact list `stream()` was
called with, and that lookup decides both whether confirmation is required *and* whether the call
may reach `ToolInvoker` at all: `definition.isEmpty()` returns an `ERROR` result immediately,
fail-closed, never falling through to `toolInvoker.invokeForChat`. `ToolInvoker` also resolves the
tool it actually executes from a second, wider set — the mutable, global `ToolRegistry` (`register()`
exists since Phase 7, for runtime MCP discovery). Nothing currently constructs those two sets
differently — both `ConversationService` and `RagPipeline` pass `toolRegistry.definitions()`
straight through as `allTools` — but that's a property of today's callers, not something the code
enforced, and a discrepancy would have failed open: a name absent from `allTools` but present in the
registry used to make `requiresConfirmation` false by the same short-circuit that should have
blocked it, sending the call straight to the executor with no gate at all (post-roadmap review S2,
issue #22). The fix makes `allTools` the sole source of truth for *whether a call is reachable*, not
just how it's gated — `ToolInvoker` resolving from the registry is now redundant-but-harmless
defense in depth, never the deciding check. Concretely: any tool registered after `stream()` already
captured `allTools` — an MCP server discovered mid-turn — cannot execute for that turn at all, gated
or not, until a later turn's `allTools` includes it. That's a stricter reading than "gated on every
call" (`ToolDefinition.alwaysRequiresConfirmation`, above) for exactly the class of tool T9 is
written for, and it's covered by `ToolCallingChatServiceTest.unknownToolForThisTurnFailsClosedInsteadOfExecuting`.

**Confirmation mechanics.** `internal.PendingConfirmationRegistry` holds a
`Map<UUID, Sinks.One<Boolean>>` — `await(callId, timeout)` registers a sink and errors with a
`TimeoutException` if `timeout` elapses first, distinct from a tool's own execution timeout and
deliberately not a `false` fallback (a real live run caught the two cases — "denied" and "nobody
answered" — being conflated when both once shared one `false` signal); a new
`POST /api/v1/tool-calls/{callId}:confirm` resolves it, resuming the paused stream on the confirming
request's thread. `SseEmitter.send(...)` calls in `ConversationController` are now wrapped in
`synchronized (emitter)`, since this is the first place two different threads (the original stream,
and the confirm-request's resuming thread) can call it. **Not resumable across an app restart** —
the map is in-memory and the SSE connection dies with the process anyway; real cross-restart
resumability is Phase 6/`workflow`'s job, named here rather than silently promised.

**The map is bounded; registration stays eager, deliberately — post-roadmap review B4, issue #28.**
`await` inserted into the map eagerly, before the returned `Mono` was ever subscribed, coupling the
entry's lifetime to a caller obligation (`doFinally`-based cleanup) that nothing enforced; every real
caller happened to subscribe, so nothing leaked in practice, but the invariant was implicit and the
map had no size bound at all. `ai.tools.max-pending-confirmations` (default 100) fixes the size
bound directly, rejecting a new `await` cleanly once that many calls are already pending — a minor
denial-of-service consideration given the absence of rate limiting elsewhere in this codebase.

Deferring registration to subscription (`Mono.defer`, the textbook fix for "a caller that never
subscribes shouldn't leak") was tried and reverted: it broke a real caller. `handleToolCall` emits
`tool_call_pending` before it ever subscribes to this `Mono` — sequenced behind it via `concatWith`
— so a client fast enough to `POST :confirm` before the server's chain reached the subscription found
nothing registered, and `ConversationToolConfirmationIntegrationTest` caught it flaking with a
genuine 404, not a hypothetical. Eager registration is what makes "the client was told this call is
confirmable" and "the server can resolve it" atomic; this codebase's one real caller always
subscribes anyway, so the theoretical leak the deferred version guarded against isn't reachable in
practice, while the race the deferred version introduced was.

**Where concrete tools live.** `tools` stays domain-agnostic — Phase 7's MCP server re-exposes its
registry, so it shouldn't need `knowledge` on its classpath to exist. Calculator (hand-written
recursive-descent evaluator — no `eval()`/`ScriptEngine`, per the threat model's no-code-execution
rule) and the mock external API (canned, hash-seeded responses, zero real network egress) both live
in `tools.internal`. Knowledge-base-search needs `knowledge`, which `tools` still can't depend on —
its implementation is a package-private `app.KnowledgeBaseSearchTool` (`@Component implements
tools.Tool`), picked up automatically by `ToolRegistry`'s injected `List<Tool>` via Spring's
component scan. This is a new composition pattern for this codebase — `app` already depends on
every domain module and is documented as composition-only, so a thin `Tool` adapter there is the
natural seam, the same way `ConversationController` already composes `rag` and `conversation`.

**Scopes: a config-declared list, not a real auth system.** No Spring Security, no principal, exists
anywhere in this codebase. `ai.tools.granted-scopes` stands in for "the one authenticated user's
permissions" — matches AGENTS.md's locked single-user/no-multi-tenancy stance. Building real
authentication is `platform`'s future territory, not this phase's job.

**JSON Schema validation: `com.networknt:json-schema-validator:3.0.6`**, verified against the
project's real GitHub releases during planning (the Maven Central search index lags and still
surfaces an old `1.x` line as "latest"). The `3.x` line targets Jackson 3, matching this project's
JDK 25 baseline and Spring Boot 4.1's Jackson-3-by-default posture. `tools.internal.SchemaValidator`
exposes only `String`/`Map<String,Object>` across its boundary — never a raw schema-library type —
mirroring how `Chunk.metadata` already keeps a Jackson-generation choice contained to one file.

## Alternatives considered

### Keep `tools` capability-agnostic; put the fallback decision in `app`/`conversation` instead

Would have avoided the new `tools → ai-provider` edge. Rejected: two already-accepted documents
(ADR-0004, architecture.md §8) explicitly assign this responsibility to `tools`, and duplicating the
capability-query/fallback logic into every caller (`conversation`, `rag`, and eventually
`workflow`/`mcp`) is worse than adding one real dependency edge that the design already assumed.

### A lightweight synchronous confirmation control (a required "confirmed" flag on the request) instead of the full pause/approve/resume flow

Smaller build, still a real, testable structural control. This was the recommended option going
into planning. Rejected by explicit user choice in favor of the full interactive flow, which is more
faithful to the threat model's own wording ("explicit user confirmation") and gives a real mid-turn
UX rather than requiring the caller to somehow know in advance that a tool call will need
confirming.

### Extend `ChatChunk`/`ChatMessage` with tool-call fields now, for both fallback and future native calling

Would unify the two code paths eventually. Rejected this phase: no adapter in this codebase
(`recorded`, `lmstudio`) can produce `supportsNativeToolCalling() == true`, so extending the wire
format now is speculative surface area with nothing to exercise it, and ADR-0008's own precedent
argues against widening `ChatChunk` for a concern it doesn't need to carry.

### Gate every tool call, regardless of origin or history

Simpler to reason about — no latching logic needed. Rejected: T2 is specifically about *retrieved*
content reaching the tool layer, not about tool calls in general; gating a plain-chat turn's very
first, harmless calculator call for no reason would be friction with no security benefit, and
contradicts the threat model's own scoped framing of the risk.

## Trade-offs

- **The peek-based sniffer can misdetect a legitimate answer that happens to start with `{`.**
  Accepted, not solved — named here rather than hidden. A real occurrence would show up as an
  answer being silently swallowed and treated as a failed tool-call parse (falls through to being
  treated as the final answer as-is, so the user still sees the raw JSON-looking text — not a crash,
  but not ideal).
- **`max-calls-per-turn` (default 3) is an unmeasured starting bound**, not tuned against any
  dataset — a real number chosen for "enough to retry once after a validation error, not enough to
  loop indefinitely," not invented precision (AGENTS.md rule 2).
- **`granted-scopes` is one global config list** — no per-user or per-conversation scoping, since no
  principal exists yet to scope it to.
- **Confirmation state is not resumable across an app restart.**

## Consequences

- `GET /api/v1/tools` and `POST /api/v1/tools/{name}:invoke` give a way to exercise the full
  validate→authorize→timeout→execute pipeline outside a chat turn entirely — useful for testing and
  for Phase 7's MCP server, which will want the same registry without a chat context.
- The confirmation flow's `POST /api/v1/tool-calls/{callId}:confirm` is new API surface not
  previously named in architecture.md §5 — added there and to the SSE event list
  (`tool_call_pending`) as part of this phase, per AGENTS.md's "spec before implementation" rule.
- `tool_invocation` rows are written for every attempted call, including ones denied by scope or
  rejected for invalid arguments — not just successful executions — matching architecture.md §4's
  own four-valued `outcome` (`ok`/`timeout`/`denied`/`error`), so the audit trail this table exists
  for is real rather than partial.
- Reversing the `tools → ai-provider` edge later (e.g. if capability negotiation moves elsewhere)
  would require re-threading the fallback decision through every caller again — expensive enough
  that this is intended to be a durable structural choice, not a placeholder.
