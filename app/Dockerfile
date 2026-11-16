# syntax=docker/dockerfile:1

# Builds with a real Java 25 toolchain regardless of what's on the host — see
# AGENTS.md, Environment constraints. Pinned by digest, not tag (docs/threat-model.md Supply chain) —
# re-resolve with `docker pull eclipse-temurin:25-jdk` / `:25-jre` and update both lines together.
FROM eclipse-temurin:25-jdk@sha256:b8705e936964c228dfce359e63dbf08f6c69cb380ed1d576b1f6b1064f15567d AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -q -pl app -am package -DskipTests

FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699 AS run
WORKDIR /app
RUN useradd --system --uid 1001 --create-home spring
COPY --from=build /workspace/app/target/ai-engineering-lab.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
