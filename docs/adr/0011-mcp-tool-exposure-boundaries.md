# ADR-0011: Internal tools vs MCP vs external tool servers, with the security boundary of each

- **Status:** Accepted
- **Date:** 2026-08-22
- **Phase:** 7

## Context

`docs/roadmap.md`'s Phase 7 is: "The tool registry exposed as an MCP server; an external MCP server
consumed as a client." Acceptance criteria: an external MCP client can discover and invoke the
tools; an external MCP server's tools are usable in chat, subject to the same authorization and
timeouts; this ADR is explicit about the trust implications of a third-party tool server.

Two real tensions had to be resolved before any of that could be implemented, neither settled by
existing docs:

**AGENTS.md's locked decision** says *"Spring AI 2.0.x, only inside `ai-provider` adapters."* Spring
AI ships official MCP Boot starters (`spring-ai-starter-mcp-server-webmvc`,
`spring-ai-starter-mcp-client`) that would need to live in the new `mcp` module, not `ai-provider`.
Hand-wiring the raw `io.modelcontextprotocol.sdk` transport ourselves would keep the locked decision
technically intact, but at a real cost: Spring AI's starters are official, maintained, and already
correctly integrate MCP's JSON-RPC transport with Spring Boot's servlet container — reinventing that
plumbing contradicts this project's own habit of leaning on a framework for infrastructure it already
does well (Spring Data JPA, Spring Kafka), reserving hand-rolling for genuinely simple domain logic
(the calculator, the JSON-envelope tool-call parser). The user confirmed using Spring AI's MCP
starters, treating the locked decision's real intent as being about model-calling
(`ChatModel`/`EmbeddingModel`) specifically — MCP protocol support touches neither.

**`tools.ToolRegistry`'s `Map<String, Tool>` is built once, in its constructor**, from Spring's
injected `List<Tool>` — every tool it has ever known about is fixed at `ApplicationContext` startup.
An MCP client's tools are discovered at runtime, over a network connection, after a handshake — they
cannot be known at construction time the way `CalculatorTool`/`app.KnowledgeBaseSearchTool` are.

## Decision

**Spring AI's official MCP Boot starters are used directly in the new `mcp` module** — a real,
scoped amendment to AGENTS.md's locked decision, not a silent violation (AGENTS.md itself requires
"a superseding ADR" to revisit a locked decision; this is that ADR). `modules/mcp/pom.xml` declares
`spring-ai-starter-mcp-server-webmvc` (matching this app's Spring MVC/servlet stack, not WebFlux) and
`spring-ai-starter-mcp-client` — both resolve their version from the root `spring-ai-bom` already
imported, no explicit pin needed.

**`ToolRegistry` gains a `register(Tool)` method** — the backing map became a `ConcurrentHashMap`,
and `register` throws `IllegalStateException` on a duplicate name, the same fail-fast contract the
constructor already had. The alternative — making MCP client discovery happen eagerly during bean
construction, before `ToolRegistry` is built — was rejected: it would make application startup
depend on an external server's availability, which this codebase consistently avoids (LM Studio,
Kafka, and now an MCP peer all degrade gracefully rather than blocking boot).

**MCP-client tool discovery and registration happens on `ApplicationReadyEvent`**
(`mcp.internal.McpClientToolRegistrar`), mirroring Phase 6's `workflow.internal.WorkflowResumer`
precedent exactly: by the time the application is "ready," its own embedded server (including its
own MCP endpoint, in the self-connect case below) is already accepting connections, so the handshake
never races application startup. `spring.ai.mcp.client.initialized` is set to `false` specifically so
Spring AI's own autoconfiguration doesn't try to initialize the client during bean construction — the
registrar's `client.initialize()` call is what actually triggers it, at the right point in the
sequence. A connection or discovery failure is logged and skipped, never fatal — the same
graceful-degrade philosophy as `rag.internal.QueryNormalizer`.

**Spring AI wires every configured connection into one `List<McpSyncClient>` bean, not a per-connection
named bean** — confirmed against the real autoconfiguration (`McpClientAutoConfiguration#mcpSyncClients`),
not assumed; an initial `Map<String, McpSyncClient>` injection design (hoping for connection-name-keyed
beans) silently resolved to an empty map and cost a full debug cycle to find. The fix: derive the
connection's identity from the server's own advertised name (`McpSyncClient.getServerInfo().name()`,
only available once `initialize()` completes), not a Spring bean/config key — arguably more correct
anyway, since the prefix should name the server being talked to, not an internal wiring detail.

**MCP-client tool names are prefixed** (`mcp:<server-name>:<tool-name>`) so an externally-sourced
tool is never confused with a built-in one in `GET /api/v1/tools`, `tool_invocation` rows, logs, or
the confirmation UI.

**MCP-client tools are gated unconditionally — a deliberate excess over "the same authorization and
timeouts."** T2's existing confirmation gate (`ToolDefinition.introducesRetrievedContent` +
latching, ADR-0009) exists to catch a tool call a prompt-injection payload *inside retrieved
content* might trigger — by design it lets a turn's first, trusted-context call through ungated. An
MCP-client tool is a different risk (docs/threat-model.md T9): the call itself sends arguments to a
third-party process this application does not control, true from its very first invocation,
independent of whether the turn's context is otherwise untrusted. Reusing
`introducesRetrievedContent` would conflate two genuinely different concerns. Instead,
`ToolDefinition` gained a new field, `alwaysRequiresConfirmation` (`true` only for MCP-client-sourced
tools), and `ToolCallingChatService`'s gate-decision became
`definition.isPresent() && (state.untrusted || definition.get().alwaysRequiresConfirmation())` — an
MCP-client tool is confirmed every time, regardless of origin or turn history. The acceptance
criterion's literal wording ("the same authorization and timeouts") is satisfied for scope and
timeout — confirmation is deliberately stricter, and named as such rather than silently
reinterpreted.

**MCP server tool-call handler delegates straight to `ToolInvoker.invokeOrThrow`** — the exact same
validate→authorize→timeout→execute→persist pipeline `POST /api/v1/tools/{name}:invoke` already uses
(ADR-0009's own forward-looking line: *"useful for testing and for Phase 7's MCP server, which will
want the same registry without a chat context"*). `mcp.internal.McpServerToolConfiguration` exposes a
`@Bean List<McpServerFeatures.SyncToolSpecification>`, built once from `toolRegistry.definitions()`
at startup — this application's own calculator/mock-weather/knowledge-base-search, **not** anything
later pulled in via its own MCP client. Re-exposing a third party's tool through this server would
raise a "are we now vouching for it" trust-chaining question this phase doesn't need to answer;
`spring.ai.mcp.server.expose-mcp-client-tools` (already `false` by Spring AI's own default) is set
explicitly rather than relied on silently.

**No independent third-party MCP server exists in this project's infrastructure** — `docker-compose.yml`
has only Postgres, Kafka and the observability stack. Both the automated test
(`McpServerAndClientIntegrationTest`) and the live-verification step demonstrate the client against
this same application's own `/mcp` endpoint: a real MCP handshake, real tool discovery, real
invocation round-trip, just against a self-hosted peer, with `spring.ai.mcp.client.enabled: false` by
default in the shipped config (nothing to point at otherwise). Named honestly, not presented as a
genuine third party — mirroring how `RecordedChatProvider` already stands in for "a live model"
elsewhere in this codebase.

## Alternatives considered

### Raw `io.modelcontextprotocol.sdk` directly, keeping AGENTS.md's locked decision literally intact

Would avoid touching the locked decision at all. Rejected by explicit user choice: Spring AI's MCP
starters already correctly wire the transport/endpoint/request lifecycle onto Spring Boot's servlet
container; hand-rolling that plumbing is meaningfully more code for the same outcome, and contradicts
how this project already treats Spring Boot/Data/Kafka as infrastructure worth leaning on rather than
reinventing.

### Reuse `introducesRetrievedContent` for MCP-client tools instead of a new field

Would avoid adding a new `ToolDefinition` field. Rejected: `introducesRetrievedContent` answers "does
this tool's *result* inject untrusted content into context" (T2) — an MCP-client tool's risk is that
the *call itself* reaches a process this application doesn't control, true even on a turn with
nothing untrusted in context yet. Conflating the two would either under-gate (treat an MCP call like
knowledge-base-search's ungated-first-call default) or force every MCP-sourced tool to also carry
`introducesRetrievedContent`'s exact latching semantics, when what's actually needed is
unconditional, not latched-after-the-fact, confirmation.

### Federate: the MCP server re-exposes tools pulled in from its own MCP client

Would make the server surface richer automatically as new external connections are added. Rejected
for this phase: re-exposing a third party's tool through this application's own server is a genuine
trust-chaining question ("are we now vouching for it to whoever connects to us") this phase doesn't
need to answer, and `spring.ai.mcp.server.expose-mcp-client-tools` already defaults to `false` —
fighting the framework's own default for a feature nothing in the acceptance criteria asks for would
be scope creep.

### A per-connection `Map<String, McpSyncClient>` injection for the tool-name prefix

The original design — rejected not by choice but by fact: Spring AI's autoconfiguration wires every
connection into a single `List<McpSyncClient>` bean, confirmed against the real autoconfiguration
report (`McpClientAutoConfiguration#mcpSyncClients`) after the `Map` injection silently resolved to
empty and the registrar never ran. Fixed by deriving the connection's identity from the server's own
advertised name instead.

## Trade-offs

- **Connection-name derivation depends on the server's own advertised name being present and
  non-blank** — falls back to `conn<index>` if it isn't, which could collide across multiple
  unnamed connections (accepted: `ToolRegistry.register`'s own duplicate-name check would surface
  that collision loudly rather than silently, and this project's scope is a single self-connection).
- **No real, independent third-party MCP server exists to test or demo against** — the client is
  proven against this application's own server, honestly named as such, not a genuine external
  party.
- **No dynamic re-discovery.** If an external server later becomes unavailable, its tools stay
  registered until restart; invoking one then fails with `ToolCallOutcome.ERROR` — the existing,
  already-correct behavior for an unexpected exception from `Tool.execute()`, not special-cased
  further.
- **`ai.mcp.client.required-scope` is one config-declared scope for every external connection** — no
  per-connection or per-tool scoping, matching `ai.tools.granted-scopes`'s existing single-user,
  no-real-auth-system stance. `alwaysRequiresConfirmation`, not the scope check, is the primary
  control for an MCP-client tool.
- **The MCP server only exposes tools registered at its own startup** — a real, named scope
  reduction, not a technical limitation (`expose-mcp-client-tools` could be flipped on).

## Consequences

- `tools.Tool` is confirmed, again, as the one seam every tool source funnels through — calculator,
  knowledge-base-search, and now an MCP-client-discovered tool all implement the same interface and
  get the same `ToolInvoker` guarantees, with zero changes needed to `ToolCallingChatService`,
  `ConversationService`, or `RagPipeline` to make MCP-sourced tools "usable in chat." That's a direct
  payoff of Phase 5's original SPI design holding up under a use case it wasn't built for yet.
- AGENTS.md's locked-decision table now names the real scope of "Spring AI only inside `ai-provider`"
  precisely (model-calling), closing the ambiguity this phase's implementation surfaced rather than
  leaving it for the next phase that touches Spring AI to rediscover.
- Reversing "MCP-client tools are gated unconditionally" later (e.g. once T9's mitigations are judged
  sufficient without per-call confirmation) is a one-field, one-line change —
  `alwaysRequiresConfirmation` and the gate-decision expression — not a redesign.
