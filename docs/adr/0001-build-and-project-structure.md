# ADR-0001: Maven multi-module build on Java 25

- **Status:** Accepted
- **Date:** 2026-08-18
- **Phase:** 0

## Context

The project needs enforced module boundaries and a build a reviewer can run without installing
anything beyond a JDK. Two orthogonal choices: the build tool, and the language and framework
baseline.

At the time of writing, Spring Boot 4.1.0 (June 2026) ships on Spring Framework 7, requires Java 17
as a minimum and supports up to Java 26. Spring AI 2.0.0 reached GA in June 2026 and targets Spring
Boot 4.0/4.1. Java 25 is the current LTS.

This is a portfolio project, so the baseline is itself a signal: too old reads as out of touch, too
bleeding-edge reads as reckless.

## Decision

Maven multi-module with the wrapper committed, on **Java 25 LTS**, **Spring Boot 4.1.x** and
**Spring AI 2.0.x**. Package root `io.github.fragudev.ailab`.

## Alternatives considered

### Gradle with the Kotlin DSL

Better multi-module ergonomics, faster incremental builds, and the version catalog is a genuinely
nicer way to manage dependency versions than a parent POM's `dependencyManagement`.

Rejected on audience. Maven remains the default in the enterprise Java world this project is aimed
at, and a reviewer skimming the repository should not have to parse a build DSL to find the module
list. Maven's `<modules>` block is legible to everyone; a Gradle build with convention plugins is
legible to Gradle users. The build tool is not what this project is demonstrating, so it should cost
the reader nothing.

### Java 21 LTS with Spring Boot 3.5 and Spring AI 1.1

The safer stack: mature, widely deployed, abundant documentation. Rejected because a portfolio piece
built in 2026 on the previous generation invites the question of why. The risk being accepted is
real — Spring AI 2.0 has been GA for two months and there will be sharp edges — but the provider
abstraction (ADR-0004) limits how far a Spring AI problem can propagate.

### A single-module project

Simplest build, and the modules could be enforced by package convention plus ArchUnit alone.
Rejected because module boundaries that exist only in a test are easy to erode. Separate Maven
modules make a boundary violation a compilation failure rather than a test failure, which is the
stronger guarantee and the whole point of ADR-0002.

## Trade-offs

- Maven is more verbose and slower than Gradle. Accepted; build time is not a bottleneck here.
- Java 25 and Spring Boot 4.1 mean fewer StackOverflow answers and a higher chance of hitting an
  unfixed framework bug.
- Spring AI 2.0's recency is the largest technical risk in the stack. It also brings Jackson 3 and
  JSpecify annotations, which are worth having.
- Multi-module builds make cross-cutting refactors more tedious. That friction is the intended
  effect.

## Consequences

- Every module is its own Maven module with its own POM; the parent manages versions and plugins.
- `./mvnw verify` is the single command for the whole build: Spotless, static analysis, ArchUnit,
  unit and integration tests.
- The wrapper is committed, so the build is reproducible without a local Maven installation.
- Contributors need a Java 25 toolchain. The Docker build path covers those who do not have one.
- Reversing the framework baseline downward would be a substantial migration — Spring Framework 7
  and Jackson 3 are not drop-in reversible. Reversing the build tool is mechanical but touches every
  module.
