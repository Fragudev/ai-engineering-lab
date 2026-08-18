# Event contracts

JSON Schema definitions for every Kafka topic payload. Schemas are the contract: producers validate
before publishing, consumers validate on receipt, and CI validates the schemas themselves.

**Status:** planned. Populated in Phase 2 alongside the ingestion pipeline.

## Envelope

Every message shares an envelope, modelled on CloudEvents without adopting the full specification.
Topic-specific schemas define only `payload`.

```json
{
  "eventId": "0192f3a4-...",
  "type": "ingestion.document.uploaded.v1",
  "source": "ai-lab/ingestion",
  "subject": "document/0192f3a4-...",
  "time": "2026-08-18T10:00:00Z",
  "correlationId": "0192f3a4-...",
  "causationId": "0192f3a4-...",
  "payload": {}
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
| `ingestion.document.uploaded.v1` | api | ingestion · parser | Phase 2 |
| `ingestion.document.parsed.v1` | ingestion · parser | ingestion · chunker | Phase 2 |
| `ingestion.chunks.created.v1` | ingestion · chunker | ingestion · embedder | Phase 2 |
| `ingestion.document.indexed.v1` | ingestion · embedder | job status, UI | Phase 2 |
| `ingestion.document.failed.v1` | any stage | job status | Phase 2 |
| `<topic>.dlt` | retry infrastructure | manual inspection | inherits |

## Versioning

The version is in the topic name, so an incompatible change means a new topic.

**Compatible** (no new version): adding an optional field, relaxing a constraint, adding an enum
value a consumer already tolerates.

**Incompatible** (new topic version): adding a required field, removing or renaming a field,
narrowing a type, changing semantics.

Rolling out a new version means running both consumers until the old topic drains, then retiring it.

See [ADR-0005](../adr/0005-kafka.md) for why JSON Schema rather than Avro with a Schema Registry.
