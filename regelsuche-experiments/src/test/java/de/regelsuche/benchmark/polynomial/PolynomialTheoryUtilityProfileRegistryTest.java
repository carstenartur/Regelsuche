package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialTheoryUtilityProfileRegistryTest {
    @Test
    void fiveProfileReadinessDoesNotInventExternalCanonicalWork() {
        var entries = PolynomialTheoryUtilityProfileRegistry.entries();
        assertEquals(5, entries.size());
        var adapters = PolynomialTheoryUtilityProfileRegistry.availableAdapters();
        assertEquals(4, adapters.size());
        assertTrue(adapters.stream()
            .allMatch(value -> value.resultSchema().equals(PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA)));
        var failure = assertThrows(IllegalStateException.class,
            PolynomialTheoryUtilityProfileRegistry::requireRunnableAdapters);
        assertTrue(failure.getMessage().contains("EXTERNAL_CANONICAL_INTERNAL_WORK_UNAVAILABLE"));
    }

    @Test
    void aMatchingLockCannotSubstituteForCanonicalInternalWork(@TempDir Path root) throws Exception {
        Path lock = root.resolve(PolynomialTheoryUtilityExecutionPlan.EXTERNAL_LOCK_PATH);
        Files.createDirectories(lock.getParent());
        Files.writeString(lock, "# graalpy-version: 25.1.3\n# generator comment\n"
            + "mpmath==1.3.0\nsympy==1.14.0\n");
        var failure = assertThrows(IllegalStateException.class,
            () -> PolynomialTheoryUtilityProfileRegistry.requireRunnableAdapters(root));
        assertTrue(failure.getMessage().contains("EXTERNAL_CANONICAL_INTERNAL_WORK_UNAVAILABLE"));
        assertFalse(failure.getMessage().contains("EXTERNAL_RUNTIME_LOCK_MISMATCH"));
    }

    @Test
    void reportsUnusableRuntimeLocksAlongsideTheDistinctMeteringBlocker(@TempDir Path root) throws Exception {
        Path lock = root.resolve(PolynomialTheoryUtilityExecutionPlan.EXTERNAL_LOCK_PATH);
        Files.createDirectories(lock.getParent());
        String version = "# graalpy-version: 25.1.3\n";
        String packages = "mpmath==1.3.0\nsympy==1.14.0\n";
        for (String material : List.of("", version + "sympy==1.14.0\n",
                version + packages.replace("1.14.0", "1.14.1"),
                version.replace("25.1.3", "25.5.5.0.3") + packages,
                version + version + packages, version + packages + "sympy==1.14.0\n",
                version + packages + "other==1.0\n")) {
            if (!material.isEmpty()) Files.writeString(lock, material);
            var failure = assertThrows(IllegalStateException.class,
                () -> PolynomialTheoryUtilityProfileRegistry.requireRunnableAdapters(root));
            assertTrue(failure.getMessage().contains("EXTERNAL_RUNTIME_LOCK_MISMATCH"), material);
            assertTrue(failure.getMessage().contains("EXTERNAL_CANONICAL_INTERNAL_WORK_UNAVAILABLE"), material);
        }
    }
}
