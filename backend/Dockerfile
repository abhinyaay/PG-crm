# syntax=docker/dockerfile:1

# ---- build: compile and package (tests run in CI, not in the image build) ----
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /workspace
COPY pom.xml checkstyle.xml ./
# dependency layer is cached until pom.xml changes
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests -Dcheckstyle.skip -Djacoco.skip=true \
    && java -Djarmode=tools -jar target/pgms-backend.jar extract --layers --launcher --destination target/extracted

# ---- runtime: JRE only, non-root, layered for fast rebuilds/pulls ----
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="pgms-backend" \
      org.opencontainers.image.description="PG management platform - transactional core API"
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./
USER app
EXPOSE 8080
# container-aware heap sizing; exit on OOM so the orchestrator restarts a clean JVM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
