package de.regelsuche.persistence.relational;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.persistence.GraphPersistenceMode;
import de.regelsuche.persistence.PersistenceConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceAdapterFactoryTest {

    @TempDir
    Path tempDirectory;

    @Test
    void nonPostgresModesDoNotInitializeRelationalInfrastructure() {
        ByteArrayOutputStream logBytes = new ByteArrayOutputStream();

        var adapters = PersistenceAdapterFactory.create(
            PersistenceConfig.inMemory(),
            new PrintStream(logBytes, true, StandardCharsets.UTF_8));

        assertTrue(adapters.isEmpty());
        assertTrue(logBytes.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void strictPostgresModeRejectsMissingCredentials() {
        PersistenceConfig config = new PersistenceConfig(
            GraphPersistenceMode.POSTGRESQL,
            tempDirectory,
            null, null, null,
            null, null, null);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> PersistenceAdapterFactory.create(config, null));

        assertTrue(failure.getMessage().contains(
            "POSTGRESQL mode requires POSTGRES_URL, POSTGRES_USER and POSTGRES_PASSWORD"));
    }

    @Test
    void hybridModeCreatesJsonFallbackWhenPostgresCredentialsAreIncomplete()
            throws Exception {
        Path fallback = tempDirectory.resolve("nested/fallback");
        PersistenceConfig config = PersistenceConfig.postgresqlWithJsonFallback(
            fallback,
            "jdbc:postgresql://example.invalid/regelsuche",
            "user",
            null);
        ByteArrayOutputStream logBytes = new ByteArrayOutputStream();

        var adapters = PersistenceAdapterFactory.create(
            config,
            new PrintStream(logBytes, true, StandardCharsets.UTF_8));

        assertTrue(adapters.isEmpty());
        assertTrue(Files.isDirectory(fallback));
        String log = logBytes.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("hybrid PostgreSQL config incomplete"));
        assertTrue(log.contains(fallback.toAbsolutePath().toString()));
    }

    @Test
    void hybridModeFailsClosedWhenFallbackPathCannotBeCreated()
            throws Exception {
        Path blockingFile = tempDirectory.resolve("blocking-file");
        Files.writeString(blockingFile, "not a directory", StandardCharsets.UTF_8);
        Path impossible = blockingFile.resolve("child");
        PersistenceConfig config = PersistenceConfig.postgresqlWithJsonFallback(
            impossible,
            null,
            null,
            null);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> PersistenceAdapterFactory.create(config, null));

        assertTrue(failure.getMessage().contains(
            "Hybrid mode requires a writable JSON fallback path"));
        assertFalse(Files.exists(impossible));
    }
}
