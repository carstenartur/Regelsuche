package de.regelsuche.dockere2e;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Shared, immutable PostgreSQL boundary for Maven and Gradle integration tests. */
final class PinnedPostgresContainer {
    static final String IMAGE = "postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777";
    private static final long MEMORY_BYTES = 512L * 1024L * 1024L;

    private PinnedPostgresContainer() {
    }

    static GenericContainer<?> create() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withEnv("POSTGRES_DB", "regelsuche")
            .withEnv("POSTGRES_USER", "regelsuche")
            .withEnv("POSTGRES_PASSWORD", "regelsuche-demo")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(
                ".*database system is ready to accept connections.*\\s", 2))
            .withStartupTimeout(Duration.ofMinutes(2))
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                .withMemory(MEMORY_BYTES)
                .withMemorySwap(MEMORY_BYTES));
    }
}
