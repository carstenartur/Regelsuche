# ---------- Stage 1: Build with Gradle ----------
FROM eclipse-temurin:21.0.11_10-jdk-noble AS build
WORKDIR /workspace

# Copy Gradle wrapper and build scripts first to leverage Docker layer caching
# for dependency downloads.
COPY gradlew gradle.properties settings.gradle build.gradle ./
COPY gradle ./gradle
COPY app/build.gradle ./app/build.gradle
COPY regelsuche-core/build.gradle ./regelsuche-core/build.gradle
COPY regelsuche-egraph/build.gradle ./regelsuche-egraph/build.gradle
COPY regelsuche-search/build.gradle ./regelsuche-search/build.gradle
COPY regelsuche-validation/build.gradle ./regelsuche-validation/build.gradle
COPY regelsuche-solver-ir/build.gradle ./regelsuche-solver-ir/build.gradle
COPY regelsuche-solver-portfolio/build.gradle ./regelsuche-solver-portfolio/build.gradle
COPY regelsuche-math-algorithms/build.gradle ./regelsuche-math-algorithms/build.gradle
COPY regelsuche-math-jas/build.gradle ./regelsuche-math-jas/build.gradle
COPY regelsuche-persistence/build.gradle ./regelsuche-persistence/build.gradle
COPY regelsuche-persistence-hibernate/build.gradle ./regelsuche-persistence-hibernate/build.gradle
COPY regelsuche-learning/build.gradle ./regelsuche-learning/build.gradle
COPY regelsuche-experiments/build.gradle ./regelsuche-experiments/build.gradle
COPY regelsuche-autopilot/build.gradle ./regelsuche-autopilot/build.gradle
COPY regelsuche-release/build.gradle ./regelsuche-release/build.gradle
COPY regelsuche-cli/build.gradle ./regelsuche-cli/build.gradle
COPY regelsuche-discovery/build.gradle ./regelsuche-discovery/build.gradle
COPY regelsuche-quality/build.gradle ./regelsuche-quality/build.gradle
COPY regelsuche-benchmarks/build.gradle ./regelsuche-benchmarks/build.gradle

# Pre-warm the Gradle distribution and dependency cache.
RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon --version

COPY app ./app
COPY regelsuche-core ./regelsuche-core
COPY regelsuche-egraph ./regelsuche-egraph
COPY regelsuche-search ./regelsuche-search
COPY regelsuche-validation ./regelsuche-validation
COPY regelsuche-solver-ir ./regelsuche-solver-ir
COPY regelsuche-solver-portfolio ./regelsuche-solver-portfolio
COPY regelsuche-math-algorithms ./regelsuche-math-algorithms
COPY regelsuche-math-jas ./regelsuche-math-jas
COPY regelsuche-persistence ./regelsuche-persistence
COPY regelsuche-persistence-hibernate ./regelsuche-persistence-hibernate
COPY regelsuche-learning ./regelsuche-learning
COPY regelsuche-experiments ./regelsuche-experiments
COPY regelsuche-autopilot ./regelsuche-autopilot
COPY regelsuche-release ./regelsuche-release
COPY regelsuche-cli ./regelsuche-cli
COPY regelsuche-discovery ./regelsuche-discovery
COPY regelsuche-quality ./regelsuche-quality
COPY regelsuche-benchmarks ./regelsuche-benchmarks

# Gradle verification tasks declared by the module build scripts reference
# repository-owned verifiers and public schemas during project configuration.
# Keep those inputs inside the isolated Docker build context as well, even
# though installDist does not execute the verification lifecycle itself.
COPY scripts ./scripts
COPY docs ./docs

# Build the runnable distribution.
RUN ./gradlew --no-daemon :app:installDist -x test


# ---------- Optional qualified autonomous-discovery walkthrough ----------
# Build explicitly with `docker build --target walkthrough ...`. This stage is
# deliberately not last: an ordinary `docker build .` must produce the normal
# Web Workbench runtime image documented by the README and Dockerfile comments.
FROM build AS walkthrough
ARG REGELSUCHE_REPOSITORY_REVISION
ENV REGELSUCHE_REPOSITORY_REVISION=${REGELSUCHE_REPOSITORY_REVISION}
RUN mkdir -p /out
VOLUME ["/out"]
ENTRYPOINT ["./gradlew", "--no-daemon", ":regelsuche-release:runAutonomousDiscoveryWalkthrough", "-PwalkthroughOutput=/out"]


# ---------- Default Web Workbench runtime ----------
FROM eclipse-temurin:21.0.11_10-jre-noble AS runtime
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
