# Event contracts

JSON Schema definitions for every Kafka topic payload. Schemas are the contract: the envelope shape
is fixed, each topic's schema is the full message.

**Status:** implemented in Phase 2. Producer: `@Externalized`-annotated event classes in
`modules/ingestion/.../internal`, one per topic, using Spring Modulith's event publication registry
as the transactional outbox (docs/adr/0005-kafka.md). Consumer-side validation against these schemas
and CI schema linting are not wired up yet — the Java event types are the enforced contract for now;
adding schema validation at the boundary is natural follow-up work, not done in this phase.

## Envelope

Every message shares an envelope, modelled on CloudEvents without adopting the full specification.
**Payload fields are inlined at the top level, not nested under a `payload` key** — a deliberate
simplification once the events became concrete Java records: a flat record serializes directly via
Jackson with no custom (de)serializer, and every field is still named and typed by the topic's own
schema below. Each topic's schema file (`<topic>.schema.json`) is the full message shape.

```json
{
  "eventId": "0192f3a4-...",
  "type": "ingestion.document.uploaded.v1",
  "source": "ai-lab/ingestion",
  "subject": "document/0192f3a4-...",
  "time": "2026-08-18T10:00:00Z",
  "correlationId": "0192f3a4-...",
  "causationId": "0192f3a4-...",
  "documentId": "0192f3a4-...",
  "title": "example.md",
  "mimeType": "text/markdown",
  "contentHash": "…sha-256 hex…",
  "contentBase64": "…"
}
```

| Field | Purpose |
|---|---|
| `eventId` | Unique per event. The idempotency key consumers deduplicate on. |
| `type` | Fully qualified event type, including version. Matches the topic name. |
| `source` | Producing module. |
| `subject` | The entity the event concerns. |
| `time` | Production timestamp, UTC. |
| `correlationId` | Constant across an entire causal chain. Ties the whole flow to one trace. |
| `causationId` | The `eventId` that directly caused this one. Reconstructs the chain. |

`correlationId` and `causationId` are the difference between debugging a distributed flow and
guessing at it: the first groups everything belonging to one user action, the second gives the
parent-child ordering within it.

## Topics

| Topic | Producer | Consumer | Schema |
|---|---|---|---|
| `ingestion.document.uploaded.v1` | ingestion (`IngestionService`) | ingestion · parser | [schema](ingestion.document.uploaded.v1.schema.json) |
| `ingestion.document.parsed.v1` | ingestion · parser | ingestion · chunker | [schema](ingestion.document.parsed.v1.schema.json) |
| `ingestion.chunks.created.v1` | ingestion · chunker | ingestion · embedder | [schema](ingestion.chunks.created.v1.schema.json) |
| `ingestion.document.indexed.v1` | ingestion · embedder | (no consumer this phase; job status is updated inline by the embedder, not via a separate listener — see docs/roadmap.md Phase 2 scope notes) | [schema](ingestion.document.indexed.v1.schema.json) |
| `ingestion.document.failed.v1` | whichever stage's retries were exhausted (`IngestionFailureRecoverer`) | same as above | [schema](ingestion.document.failed.v1.schema.json) |
| `<topic>.dlt` | retry infrastructure (`DeadLetterPublishingRecoverer`) | manual inspection via kafka-ui | inherits |

## Versioning

The version is in the topic name, so an incompatible change means a new topic.

**Compatible** (no new version): adding an optional field, relaxing a constraint, adding an enum
value a consumer already tolerates.

**Incompatible** (new topic version): adding a required field, removing or renaming a field,
narrowing a type, changing semantics.

Rolling out a new version means running both consumers until the old topic drains, then retiring it.

See [ADR-0005](../adr/0005-kafka.md) for why JSON Schema rather than Avro with a Schema Registry.
