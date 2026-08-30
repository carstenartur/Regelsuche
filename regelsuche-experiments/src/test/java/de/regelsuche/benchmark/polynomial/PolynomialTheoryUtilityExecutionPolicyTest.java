package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialTheoryUtilityExecutionPolicyTest {
    @Test
    void freezesThirtyIsolatedRunsAndOneCacheLifetimePerRun() {
        var artifact = PolynomialTheoryUtilityExecutionPlan.freeze();
        var grouped = artifact.rows().stream().collect(
            java.util.stream.Collectors.groupingBy(
                PolynomialTheoryUtilityExecutionRow::runId,
                java.util.stream.Collectors.counting()
            )
        );

        assertEquals(30, grouped.size());
        assertTrue(grouped.values().stream().allMatch(count -> count == 20L));
        for (String binding : java.util.List.of(
                "\"runGrouping\":\"PROFILE_AND_CHECKPOINT\"",
                "\"caseOrder\":\"FROZEN_FORMATION_ORDER\"",
                "\"profileIsolation\":\"INDEPENDENT_RUNS\"",
                "\"checkpointIsolation\":\"INDEPENDENT_RUNS\"",
                "\"cacheInitialState\":\"EMPTY_AT_RUN_START\"",
                "\"cacheLifetime\":"
                    + "\"WITHIN_PROFILE_CHECKPOINT_RUN\"",
                "\"qualificationAccess\":\"FORBIDDEN\"",
                "\"backendSubstitution\":\"FORBIDDEN\"")) {
            assertTrue(artifact.canonicalJson().contains(binding));
        }
    }

    @Test
    void freezesExplicitBackendsWithoutHiddenBestOf() {
        var artifact = PolynomialTheoryUtilityExecutionPlan.freeze();
        var baseline = profile("NO_FACTORIZATION");
        var onDemand = profile("ON_DEMAND_VERIFIED_FACTORIZATION");
        var cache = profile("VERIFIED_DERIVED_MACRO_CACHE");
        var specialized = profile("SPECIALIZED_BINARY_QUARTIC_CONTROL");
        var external = profile(
            "OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION"
        );

        assertEquals("NONE", baseline.engineId());
        assertEquals(
            "regelsuche.factorization.native-univariate-rational/v1",
            onDemand.engineId()
        );
        assertEquals("NONE", onDemand.fallbackMode());
        assertEquals("READ_WRITE", cache.cacheMode());
        assertEquals("NATIVE_ON_CACHE_MISS_ONLY", cache.fallbackMode());
        assertEquals(
            "regelsuche.factorization.binary-quartic-2x2/v1",
            specialized.engineId()
        );
        assertEquals(
            "regelsuche.factorization.sympy-graalpy.rational/v1",
            external.engineId()
        );
        assertEquals("NONE", external.fallbackMode());
        assertTrue(
            PolynomialTheoryUtilityExecutionPlan.PROFILES.stream()
                .noneMatch(value ->
                    value.candidateSelection().contains("BEST_OF"))
        );
        assertTrue(artifact.canonicalJson().contains(
            "\"externalRuntimeId\":\""
                + PolynomialTheoryUtilityExecutionPlan.EXTERNAL_RUNTIME_ID
                + "\""
        ));
        assertTrue(artifact.canonicalJson().contains(
            "\"cacheRevision\":\""
                + PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION
                + "\""
        ));
    }

    @Test
    void writesOnlyThePlanAndRejectsQualification(
        @TempDir Path directory
    ) throws IOException {
        var artifact = PolynomialTheoryUtilityExecutionPlan.write(directory);
        Path plan = directory.resolve(
            PolynomialTheoryUtilityExecutionPlan.FILE_NAME
        );
        Path qualification = directory.resolve(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
        );
        assertEquals(
            artifact.canonicalJson(),
            Files.readString(plan, StandardCharsets.UTF_8)
        );
        assertFalse(Files.exists(qualification));

        Files.writeString(qualification, "sealed\n", StandardCharsets.UTF_8);
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityExecutionPlan.write(directory)
        );
        assertTrue(failure.getMessage().contains("sealed qualification"));
    }

    @Test
    void rejectsInvalidBudgetsPoliciesAndRows() {
        var checkpoint =
            PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.get(0);
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityExecutionPlan.scale(0, checkpoint)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityExecutionProfile(
                "profile",
                "adapter",
                "scope",
                "mode",
                "engine",
                "transform",
                "DISABLED",
                "NONE",
                "HIDDEN_BEST_OF"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityExecutionRow(
                "sha256:invalid",
                "sha256:invalid",
                "case",
                "profile",
                "checkpoint",
                1,
                1,
                1,
                "NOT_EXECUTED"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityExecutionPlan.main(new String[0])
        );
    }

    private static PolynomialTheoryUtilityExecutionProfile profile(
        String profileId
    ) {
        return PolynomialTheoryUtilityExecutionPlan.PROFILES.stream()
            .filter(value -> profileId.equals(value.profileId()))
            .findFirst()
            .orElseThrow();
    }
}
