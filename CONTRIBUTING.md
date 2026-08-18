# Contributing

This is primarily a personal reference and portfolio project, but issues, questions and pull
requests are welcome — especially ones that challenge an architectural decision. If you think an ADR
is wrong, opening an issue that argues the other side is the most useful contribution you can make.

## Ground rules

**Every significant decision gets an ADR.** If a change alters a boundary, a contract, a dependency
choice or a failure-handling strategy, it needs a record in `docs/adr/` following
[the template](docs/adr/0000-template.md). "Significant" means: someone six months from now would
ask *why is it like this?*

**Documentation ships with the change, not after it.** A pull request that changes behaviour and
leaves the README or architecture doc stale is incomplete.

**No invented numbers.** Performance, latency and cost figures must come from a measurement that can
be reproduced, with the model and hardware recorded alongside. If it was not measured, it does not
go in the docs.

**Failure paths need tests too.** The happy path is the easy half. A new Kafka consumer without a
retry-exhaustion test, or a new provider call without a timeout test, is not done.

## Development workflow

```bash
./scripts/bootstrap.sh                                   # verify LM Studio and models
docker compose -f infrastructure/docker-compose.yml up -d
./mvnw verify                                            # unit, integration, architecture tests
```

`./mvnw verify` must pass before pushing. It runs Spotless, static analysis, ArchUnit boundary
checks and the Testcontainers integration suite. It never calls a real model — the `recorded`
provider profile replays fixtures, which is also what CI uses.

To run the AI evaluation suite against a live model:

```bash
./scripts/eval.sh --profile hybrid-rerank
```

## Conventions

- **Java 25**, formatted by Spotless (Palantir style). `./mvnw spotless:apply` fixes it.
- **Package root** `io.github.fragudev.ailab`, then the module name.
- **Module boundaries** are enforced by Spring Modulith and ArchUnit. A module exposes its API in
  its root package and hides everything else under `internal`. Cross-module communication goes
  through a public API or a domain event — never through another module's internals.
- **Conventional Commits** (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`), scoped by
  module where it helps: `feat(ingestion): add retry with exponential backoff`.
- **Branches** off `main`, named `phase-N/short-description` or `fix/short-description`.
- **API changes** start in the OpenAPI specification, not in a controller.
- **Event changes** start in the JSON Schema under `docs/events/`, and adding a required field means
  a new topic version.

## Security

Do not open a public issue for a security problem. See the reporting section of
[`docs/threat-model.md`](docs/threat-model.md).

Never commit an API key, token or `.env` file. `gitleaks` runs in CI, but it is a safety net, not a
strategy.
