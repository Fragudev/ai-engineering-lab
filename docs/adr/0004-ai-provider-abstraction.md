# ADR-0004: Project-owned provider interfaces with capability negotiation

- **Status:** Accepted
- **Date:** 2026-08-18
- **Phase:** 1

## Context

The system needs chat completion and embeddings. The default is LM Studio — a local, OpenAI-compatible
server — but the project must also run against OpenAI or Anthropic, and must run in CI where no model
server exists.

Spring AI already provides a portability layer. Using its types directly throughout the codebase
would be the path of least resistance, and would mean every module depends on a framework that
reached GA two months ago.

There is a second, subtler problem. Model backends are not interchangeable in the way an abstraction
implies. Local 7B models support tool calling unreliably or not at all; frontier models support it
well. An abstraction that pretends otherwise does not remove the difference, it just relocates the
failure to somewhere harder to diagnose.

## Decision

Thin project-owned interfaces in the `ai-provider` module — `ChatProvider` and `EmbeddingProvider` —
with Spring AI used inside the adapters as an implementation detail.

`ChatProvider` exposes `ProviderCapabilities`, so callers can query support for native tool calling,
structured output and context limits, and degrade explicitly rather than fail mysteriously.

Four adapters: `lmstudio` (default), `openai`/`anthropic`, `recorded` (fixtures, used by CI), and
`deterministic` (unit tests).

## Alternatives considered

### Use Spring AI types directly throughout

Less code, fewer layers, and Spring AI is already an abstraction — wrapping it looks like
architecture for its own sake.

Rejected for two reasons. First, blast radius: a breaking change in a two-month-old GA library would
reach every module instead of four adapter classes. Second, and more important, Spring AI's
abstraction does not expose capability differences, which is precisely the thing this system needs to
reason about. The wrapper exists to add something, not to insulate for insulation's sake.

### A raw HTTP client against the OpenAI-compatible API

Maximum control, zero dependency on Spring AI's maturity. Rejected because it means reimplementing
streaming, tool-call protocol handling, token accounting and retry semantics — significant work that
demonstrates plumbing rather than AI engineering. The abstraction keeps the option open if Spring AI
proves troublesome.

### Runtime provider switching within a single process

Rejected as unnecessary. Provider selection is a Spring profile, resolved at startup. Nothing in the
requirements calls for switching mid-run, and per-request provider selection would complicate token
accounting and evaluation reproducibility for no gain.

## Trade-offs

- **An extra layer to maintain**, and adapter tests to keep honest.
- **The abstraction can only expose the intersection of provider features.** Provider-specific
  capabilities — Anthropic's extended thinking, OpenAI's structured output modes — are reachable
  only by extending the interface or bypassing it.
- **Capability negotiation adds branching** at every call site that depends on a capability. That
  complexity is real, and it is the honest representation of a real difference.
- **`recorded` fixtures drift.** A prompt change silently invalidates them until a test fails
  confusingly. Mitigated by `scripts/record-fixtures.sh` and by reviewing fixture diffs.

## Consequences

- Switching providers is a profile change, not a refactor.
- CI is deterministic, offline and free, because `recorded` replays fixtures.
- Token accounting, cost estimation, timeout, retry with jitter and circuit breaking live in one
  place and apply to every provider uniformly.
- Every model call emits an OpenTelemetry span with model, token counts and latency — because the
  instrumentation is in the abstraction, not scattered across call sites.
- The `tools` module (Phase 5) depends on `ProviderCapabilities` for its structured-output fallback.
  Without capability negotiation, tool calling would work on some models and fail opaquely on others.
- The evaluation harness can run the same dataset against different providers, making "how much
  worse is the local model" a measured answer rather than a guess.
- Reversing this — collapsing to Spring AI types directly — would be a mechanical change confined to
  the adapters and their call sites, but would forfeit capability negotiation.
