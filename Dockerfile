# syntax=docker/dockerfile:1
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 ticketengine \
    && useradd --uid 10001 --gid ticketengine --no-create-home ticketengine
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/ticket-engine.jar /app/ticket-engine.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ticket-engine.jar"]
