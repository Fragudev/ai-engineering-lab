# Demo

`docs/roadmap.md`'s Phase 8 calls for "a recorded demo." This repository cannot produce an actual
screen-recording video — nothing in this environment can capture and encode video. What it can
produce, and what this is, is a **scripted, reproducible walkthrough**: [`scripts/demo.sh`](scripts/demo.sh)
drives every capability below against a real, running instance over the same HTTP API a human would
use, and this file is the narration to read (or say out loud) while it runs, if you want to record
your own screen doing it.

## Running it

```bash
./scripts/bootstrap.sh
docker compose -f infrastructure/docker-compose.yml up -d
./mvnw -pl app -am package -DskipTests
docker compose -f infrastructure/docker-compose.yml run -d --name ailab-demo -p 8080:8080 app
./scripts/demo.sh
```

Runs against the `recorded` provider profile by default (the same one CI uses) — deterministic, no
LM Studio required, same output every time. Set `APP_URL` to point at a different instance.

## Narration, section by section

**1. Plain chat.** A conversation with no `ragProfile` streams tokens back over SSE — this is Phase
1's original contract, unchanged since. Point out the `usage` event: token counts and cost are
tracked from the very first phase, not bolted on later.

**2. Ingestion + hybrid RAG with citations.** A document goes through the real pipeline — upload,
parse, chunk, embed, index — asynchronously over Kafka (Phase 2), not synchronously in the request.
The script polls the job resource until `INDEXED`, the same way a real client would. Then a
RAG-profile conversation asks a question the just-indexed document answers: watch for the `citation`
event — every claim in the answer traces back to a specific chunk of a specific document, not a
model's unverified assertion (Phase 3, [ADR-0008](docs/adr/0008-rag-pipeline-architecture.md)).

**3. Tool calling with confirmation, via MCP.** `GET /api/v1/tools` lists the registry — built-in
tools (`calculator`, `mock-weather`, `knowledge-base-search`) plus, if the app was started with its
MCP self-connect enabled, `mcp:self:calculator` — the same calculator, but reached over a real MCP
JSON-RPC handshake instead of an in-process call (Phase 7). Either way, the interesting part is the
confirmation gate: the model's tool-call request pauses the stream (`tool_call_pending`) until a
separate `POST /api/v1/tool-calls/{callId}:confirm` approves it — a structural control against
prompt injection driving a tool call the user never agreed to (Phase 5, docs/threat-model.md T2), and
for an MCP-sourced tool specifically, gated on *every* call rather than just the turn's first one,
since the call itself reaches a process this application doesn't control (docs/threat-model.md T9).

**4. Agentic workflow.** `POST /api/v1/workflows/documentation-research/runs` starts a six-stage
state machine — plan sub-queries, retrieve, extract per source, synthesise, self-check, answer — each
stage persisted with its own input, output, attempts and cost, not just a final answer (Phase 6,
[ADR-0010](docs/adr/0010-agent-orchestration.md)). The script polls until the run reaches a terminal
status and prints every stage. What it *doesn't* show live — because it would mean killing the
container mid-script — is that this state survives an application restart and resumes from the last
completed step rather than starting over; that was verified live during Phase 6 and is narrated in
ADR-0010 itself.

## What this doesn't cover

The evaluation harness (`scripts/eval.sh`, Phase 4) and the CI/security-scanning pipeline
(`.github/workflows/`, Phase 8) aren't part of this script — they're not a live-request flow, they're
either a batch run against a golden dataset or something that only makes sense to watch happen inside
GitHub Actions. Both are documented and reproducible on their own terms: `docs/ai-evaluation.md` for
the first, `docs/threat-model.md` §5 for the second.
