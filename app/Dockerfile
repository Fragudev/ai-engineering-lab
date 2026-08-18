# syntax=docker/dockerfile:1

# Builds with a real Java 25 toolchain regardless of what's on the host — see
# AGENTS.md, Environment constraints.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -q -pl app -am package -DskipTests

FROM eclipse-temurin:25-jre AS run
WORKDIR /app
RUN useradd --system --uid 1001 --create-home spring
COPY --from=build /workspace/app/target/ai-engineering-lab.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
