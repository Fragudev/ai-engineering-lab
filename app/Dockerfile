# syntax=docker/dockerfile:1

# Builds with a real Java 25 toolchain regardless of what's on the host — see
# AGENTS.md, Environment constraints. Pinned by digest, not tag (docs/threat-model.md Supply chain) —
# re-resolve with `docker pull eclipse-temurin:25-jdk` / `:25-jre` and update both lines together.
FROM eclipse-temurin:25-jdk@sha256:e787e08ef76f4c16866108cd7f9fcd96a68eef3ac6cc76866897d4d02d5a2262 AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -q -pl app -am package -DskipTests

FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112 AS run
WORKDIR /app
RUN useradd --system --uid 1001 --create-home spring
COPY --from=build /workspace/app/target/ai-engineering-lab.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
