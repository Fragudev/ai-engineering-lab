# ADR-0005: Kafka for ingestion, with a Modulith outbox and JSON Schema contracts

- **Status:** Accepted
- **Date:** 2026-08-18
- **Phase:** 2

## Context

Document ingestion is multi-stage — parse, chunk, embed, index — and slow. Embedding a large document
takes seconds to minutes against a local model. It cannot run inside an HTTP request.

Three sub-decisions follow: what carries the work, how events get published without losing them on
crash, and how event payloads are serialised.

The honest framing on the first: a database-backed job queue with `@Async` workers would satisfy the
functional requirement. Kafka is partly a portfolio decision, and pretending otherwise would be the
kind of unexamined justification this project's ADRs exist to avoid. But there is a real argument
alongside it, and it is the one below.

## Decision

**Apache Kafka in KRaft mode**, with one topic per pipeline stage.

Events enter Kafka through **Spring Modulith's event publication registry** acting as a transactional
outbox. Payloads are **versioned JSON Schema**, not Avro. Consumers are idempotent via a
`processed_event` table. Retries use exponential backoff with jitter, then a dead-letter topic.

## Alternatives considered

### A database-backed job queue

`SELECT ... FOR UPDATE SKIP LOCKED` with `@Async` workers. No extra infrastructure, transactional
with the domain data, trivially debuggable.

This is the strongest alternative and would be the right answer for many projects. Rejected because
it collapses the pipeline into one unit of work, losing per-stage retry policies, per-stage
back-pressure, and the ability to replay a single stage. When embedding is the bottleneck — which it
always is with a local model — Kafka's consumer-group model makes that visible as consumer lag and
addressable by scaling one consumer. A job table makes it visible as "things are slow".

It also forecloses the demonstration of exactly the mechanics this project exists to show: consumer
groups, partitioning, at-least-once delivery, idempotent consumers, dead-lettering, and lag as a
first-class signal.

### RabbitMQ

Lighter, simpler operationally, mature dead-letter support. Rejected because it lacks the log
semantics — replaying a stage after fixing a bug means the messages are still there, which is a
property a queue does not offer. Kafka is also the more common expectation in the JVM ecosystem this
project targets.

### Avro with a Schema Registry

Compact, schema-enforced at the broker, with formal compatibility checking.

Rejected on cost-benefit. It adds a container, a build-time code generation step, and a runtime
dependency, to solve a schema-drift problem that exists when many independent teams produce to the
same topic. Here there is one producer per topic and one consumer group. Versioned JSON Schema files
in `docs/events/` with validation on both sides covers the same ground at a fraction of the weight.
JSON is also readable in Kafka UI, which matters for the demo.

### Publishing to Kafka directly from application code

Rejected outright. Writing to the database and publishing to the broker are not atomic. A crash
between the two either loses the event or publishes one for a transaction that rolled back. This is
a known-broken pattern, not a trade-off.

## Trade-offs

- **Operational weight**: a broker, plus Kafka UI for inspection.
- **Contracts must be versioned from the first commit.** Changing an event shape after the fact is
  more expensive than changing a method signature.
- **At-least-once delivery means every consumer must be idempotent** — a permanent tax on every
  consumer written, forever.
- **Debugging spans a process boundary**, which is why correlation-id propagation into trace context
  is not optional.
- **Kafka before the domain model is stable** risks freezing contracts around a design that is still
  moving. Accepted with eyes open; mitigated by versioned topics.

## Consequences

- The partition key is `documentId`: ordering guaranteed per document, parallelism across documents.
- Non-retryable failures — unsupported MIME type, corrupt file, schema violation — bypass retries and
  go straight to the dead-letter topic. Retrying a permanently broken document three times delays the
  inevitable and pollutes the metrics.
- The `processed_event(consumer_group, event_id)` table makes redelivery a no-op.
- Modulith's registry persists the event in the same transaction as the state change and externalises
  it after commit, retrying incomplete publications on restart. The outbox pattern without a
  hand-written outbox.
- Consumer lag and dead-letter depth become first-class metrics with dashboard panels and alert
  thresholds.
- Adding a required field to an event means a new topic version and a migration period with both
  consumers running.
- Reversing to a job queue is feasible — the consumers are already isolated behind interfaces — but
  would forfeit replay and per-stage scaling.
