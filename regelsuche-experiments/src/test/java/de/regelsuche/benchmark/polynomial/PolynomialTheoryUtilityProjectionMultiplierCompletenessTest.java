package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityProjectionMultiplierCompletenessTest {
    @Test
    void normalizesEveryProjectorMultiplierWithoutOutcomeData() {
        var matching = ledger(
            "projection.root-source-hash-code-units", 20L,
            "projection.range-commitment-code-units", 12L,
            "projection.revalidation-literal-bindings", 2L,
            "projection.revalidation-literal-code-units", 1_036L
        );

        var projection =
            PolynomialTheoryUtilityCanonicalWorkProjection.project(
                input(),
                matchingOnly(matching)
            );

        assertEquals(
            "regelsuche.polynomial-theory-utility-work-projection/v2",
            projection.projectionRevision()
        );
        assertEquals(15L, projection.work().matchingWork());
        assertEquals(15L, projection.work().mechanicalWork());
    }

    @Test
    void rejectsProjectorLiteralWorkWithoutItsCompanionCount() {
        var matching = ledger(
            "projection.revalidation-literal-code-units",
            512L
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCanonicalWorkProjection.project(
                input(),
                matchingOnly(matching)
            )
        );
    }

    @Test
    void rejectsProjectorLiteralCountWithoutItsCompanionWork() {
        var matching = ledger(
            "projection.revalidation-literal-bindings",
            1L
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCanonicalWorkProjection.project(
                input(),
                matchingOnly(matching)
            )
        );
    }

    @Test
    void rejectsProjectorLiteralWorkThatCannotBeDecodedExactly() {
        var matching = ledger(
            "projection.revalidation-literal-bindings", 2L,
            "projection.revalidation-literal-code-units", 1_025L
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCanonicalWorkProjection.project(
                input(),
                matchingOnly(matching)
            )
        );
    }

    private static
            PolynomialTheoryUtilityCanonicalWorkProjection.RawWork matchingOnly(
                PolynomialWorkLedger matching
            ) {
        return new PolynomialTheoryUtilityCanonicalWorkProjection.RawWork(
            0L,
            matching,
            matching,
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty()
        );
    }

    private static PolynomialTheoryUtilityExecutionInput input() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value ->
                "ON_DEMAND_VERIFIED_FACTORIZATION".equals(value.profileId())
            )
            .filter(value -> "CP06_FULL".equals(value.checkpointId()))
            .filter(value ->
                "z02-difference-of-squares".equals(value.caseId())
            )
            .findFirst()
            .orElseThrow();
    }

    private static PolynomialWorkLedger empty() {
        return PolynomialWorkLedger.empty();
    }

    private static PolynomialWorkLedger ledger(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("stage entries must be pairs");
        }
        Map<String, Long> stages = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            stages.put((String) entries[index], (Long) entries[index + 1]);
        }
        return new PolynomialWorkLedger(stages);
    }
}
