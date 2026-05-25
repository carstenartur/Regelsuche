# syntax=docker/dockerfile:1.7

# ---------- Stage 1: Build with Gradle ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy Gradle wrapper and build scripts first to leverage Docker layer caching
# for dependency downloads.
COPY gradlew gradle.properties settings.gradle ./
COPY gradle ./gradle
COPY app/build.gradle ./app/build.gradle
COPY regelsuche-core/build.gradle ./regelsuche-core/build.gradle
COPY regelsuche-egraph/build.gradle ./regelsuche-egraph/build.gradle
COPY regelsuche-search/build.gradle ./regelsuche-search/build.gradle
COPY regelsuche-validation/build.gradle ./regelsuche-validation/build.gradle
COPY regelsuche-persistence/build.gradle ./regelsuche-persistence/build.gradle
COPY regelsuche-learning/build.gradle ./regelsuche-learning/build.gradle
COPY regelsuche-experiments/build.gradle ./regelsuche-experiments/build.gradle
COPY regelsuche-cli/build.gradle ./regelsuche-cli/build.gradle
COPY regelsuche-discovery/build.gradle ./regelsuche-discovery/build.gradle

# Pre-warm the Gradle distribution and dependency cache. The build itself
# fails (no sources yet), but the wrapper, distribution and dependencies are
# downloaded, so the actual build below runs faster on rebuilds when only
# sources change.
RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon --version

COPY app ./app
COPY regelsuche-core ./regelsuche-core
COPY regelsuche-egraph ./regelsuche-egraph
COPY regelsuche-search ./regelsuche-search
COPY regelsuche-validation ./regelsuche-validation
COPY regelsuche-persistence ./regelsuche-persistence
COPY regelsuche-learning ./regelsuche-learning
COPY regelsuche-experiments ./regelsuche-experiments
COPY regelsuche-cli ./regelsuche-cli
COPY regelsuche-discovery ./regelsuche-discovery

# Build the runnable distribution.
RUN ./gradlew --no-daemon :app:installDist -x test


# ---------- Stage 2: Runtime ----------
FROM eclipse-temurin:21-jre AS runtime
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_OPTS=""
WORKDIR /opt/regelsuche

# Non-root user for the runtime.
RUN useradd --create-home --uid 10001 regelsuche
COPY --from=build /workspace/app/build/install/app/ ./
RUN chown -R regelsuche:regelsuche /opt/regelsuche
USER regelsuche

EXPOSE 8080

# Killer-demo default: file-backed local persistence under
# /opt/regelsuche/data so `docker run --rm -p 8080:8080 regelsuche` works
# without any external infrastructure. Override these env vars to switch to
# IN_MEMORY (ephemeral) or REMOTE_NEO4J (Full Mode via docker-compose).
ENV REGELSUCHE_PERSISTENCE_MODE=JSON_FILE \
    REGELSUCHE_PERSISTENCE_PATH=/opt/regelsuche/data
RUN mkdir -p /opt/regelsuche/data
VOLUME ["/opt/regelsuche/data"]

# `serve --host 0.0.0.0` binds the embedded web workbench on all interfaces so
# the container is reachable from the host. Override by passing different
# arguments to `docker run` if you need basic-auth, TLS or a different port.
ENTRYPOINT ["./bin/app"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8080"]
