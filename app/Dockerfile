# syntax=docker/dockerfile:1

# Builds with a real Java 25 toolchain regardless of what's on the host — see
# AGENTS.md, Environment constraints. Pinned by digest, not tag (docs/threat-model.md Supply chain) —
# re-resolve with `docker pull eclipse-temurin:25-jdk` / `:25-jre` and update both lines together.
FROM eclipse-temurin:25-jdk@sha256:dcf835e52330939b6c9f90ecab8aafcbcaa8fbf48423db44de884cf978c10144 AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -q -pl app -am package -DskipTests

FROM eclipse-temurin:25-jre@sha256:15090d159279e5c158473eccb48cd87f57b3e3a47511a797eb5a7a7ea6f86b0f AS run
WORKDIR /app
RUN useradd --system --uid 1001 --create-home spring
COPY --from=build /workspace/app/target/ai-engineering-lab.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
