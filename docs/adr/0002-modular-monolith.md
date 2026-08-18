# ADR-0002: Modular monolith with Spring Modulith

- **Status:** Accepted
- **Date:** 2026-08-18
- **Phase:** 0

## Context

The project spans several cohesive areas — conversation, ingestion, retrieval, tools, workflows,
evaluation — and one of its stated goals is demonstrating distributed-systems competence.

The tempting move is microservices, because that is what "demonstrates distributed systems" looks
like on a first reading. It is also the wrong move, and being able to say why is a more valuable
demonstration than the services themselves.

The actual conditions: one contributor, one deployment cadence, no component with an independent
scaling profile, and a local single-node target. None of the forces that justify distribution are
present.

## Decision

A **modular monolith**: one deployable Spring Boot process, modules with boundaries enforced at
build time by Spring Modulith and ArchUnit, asynchronous work over Kafka where it is genuinely
asynchronous.

Each module exposes an API in its root package and hides everything else under `internal`.
Cross-module communication is a public API call or a domain event — nothing else.

## Alternatives considered

### Microservices

Would demonstrate service decomposition, inter-service communication and independent deployability.

Rejected because it would be complexity theatre. With one team and one release cadence,
microservices buy network partitions, distributed transactions, eleven containers and a debugging
experience an order of magnitude worse, in exchange for capabilities this system will never use. A
reviewer who has actually run microservices in production will recognise unjustified decomposition
faster than they will recognise a well-argued monolith — and the argument is the harder thing to
demonstrate.

The interesting claim is not "I can split a system into services". It is "I can identify boundaries
correctly and know when distribution pays for itself".

### A layered monolith without module enforcement

Standard controller/service/repository layering. Rejected because horizontal layers say nothing about
the domain: every feature cuts across all of them, and nothing prevents the retrieval service from
reaching into the conversation repository. Within eighteen months, everything depends on everything.
Vertical modules with enforced boundaries are the structure worth demonstrating.

### Modules by Maven only, without Spring Modulith

Maven's compile-time boundaries alone would prevent cross-module internal access. Rejected because
Modulith adds three things that are worth the dependency: boundary verification with useful failure
messages, generated module documentation that cannot drift from the code, and — most importantly —
the event publication registry, which gives a transactional outbox without hand-writing one
(ADR-0005).

## Trade-offs

- **Scaling is all-or-nothing.** Heavy embedding load means scaling the entire application, chat
  endpoints included. Acceptable at this scope; would not be at real load.
- **A single point of failure.** An OOM in ingestion takes chat down with it.
- **The boundaries are only as good as their enforcement.** If Modulith checks were ever disabled to
  unblock a change, erosion would be immediate and invisible.
- **A framework dependency on Spring Modulith**, which is comparatively young.
- **It looks less impressive at a glance** than a diagram with nine services. Mitigated by making the
  reasoning the visible artifact.

## Consequences

- Module boundary violations fail the build, not a review.
- Module documentation is generated from the code, so the architecture docs cannot silently drift.
- Extracting a service later is mechanical: the module's public API is already the service contract,
  and its events are already the integration points. That is the standing option this decision buys.
- Domain events become the primary integration mechanism, which shapes the design toward
  event-driven thinking even inside the process.
- Spring Modulith's version is tied to the Spring Boot generation, adding a constraint to future
  upgrades.
- Reversing toward microservices is expensive but tractable — extract module, publish contract, split
  the database. Reversing toward an unstructured monolith would be a decision to abandon the point
  of the project.
