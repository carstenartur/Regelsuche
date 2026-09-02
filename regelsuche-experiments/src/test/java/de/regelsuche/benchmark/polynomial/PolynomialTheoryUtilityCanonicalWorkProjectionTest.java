package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityCanonicalWorkProjectionTest {
    @Test
    void projectsACompletePartitionWithFrozenQuanta() {
        PolynomialTheoryUtilityExecutionInput input = input(
            "z02-difference-of-squares"
        );
        var matching = ledger("projection.path-navigation", 2L);
        var source = ledger(
            "exact-parsed-view.ast-visits", 3L,
            "exact-parsed-view.arithmetic-operations", 4L,
            "transform.source-evidence-literal-validation", 1_024L,
            "transform.source-evidence-text-validation", 9L
        );
        var factorization = ledger("native.factor-operations", 5L);
        var verification = ledger("verify.product-comparisons", 1L);
        var rendering = ledger("render.output-code-units", 7L);
        var reparse = ledger(
            "transform.exact-reparse-input-code-units", 6L
        );
        var reconstruction = ledger(
            "transform.structural-change-comparison", 2L
        );
        var replacement = ledger(
            "nested.rewritten-structural-hash", 256L,
            "nested.rewritten-structural-hash-payload-utf8-bytes", 65L
        );
        var evidence = ledger(
            "study.evidence.projection-payload-utf8-bytes", 65L
        );

        var projection =
            PolynomialTheoryUtilityCanonicalWorkProjection.project(
                input,
                raw(
                    1L,
                    matching,
                    source,
                    factorization,
                    verification,
                    rendering,
                    reparse,
                    reconstruction,
                    replacement,
                    evidence
                )
            );

        assertEquals(
            PolynomialTheoryUtilityCanonicalWorkProjection.REVISION,
            projection.projectionRevision()
        );
        assertEquals(input.inputId(), projection.executionInputId());
        assertEquals(1L, projection.work().primitiveWork());
        assertEquals(2L, projection.work().matchingWork());
        assertEquals(12L, projection.work().sourceValidationWork());
        assertEquals(5L, projection.work().factorizationWork());
        assertEquals(1L, projection.work().verificationWork());
        assertEquals(7L, projection.work().renderingWork());
        assertEquals(6L, projection.work().reparseWork());
        assertEquals(2L, projection.work().reconstructionWork());
        assertEquals(4L, projection.work().occurrenceReplacementWork());
        assertEquals(2L, projection.work().evidenceConstructionWork());
        assertEquals(41L, projection.work().mechanicalWork());
    }

    @Test
    void rejectsAnIncompleteRawPartition() {
        var matching = ledger("projection.path-navigation", 2L);
        var total = merge(
            matching,
            ledger("native.factor-operations", 1L)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCanonicalWorkProjection.RawWork(
                0L,
                total,
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
            )
        );
    }

    @Test
    void rejectsAStageSplitAcrossStudyDimensions() {
        var firstPart = ledger("native.factor-operations", 2L);
        var secondPart = ledger("native.factor-operations", 3L);
        var total = ledger("native.factor-operations", 5L);

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCanonicalWorkProjection.RawWork(
                0L,
                total,
                empty(),
                empty(),
                firstPart,
                empty(),
                empty(),
                empty(),
                empty(),
                empty(),
                empty(),
                empty(),
                empty(),
                empty(),
                secondPart
            )
        );
    }

    @Test
    void rejectsAStageAssignedToAnotherDimension() {
        var verification = ledger("verify.product-comparisons", 1L);

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCanonicalWorkProjection.project(
                input("z02-difference-of-squares"),
                raw(
                    0L,
                    empty(),
                    empty(),
                    verification,
                    empty(),
                    empty(),
                    empty(),
                    empty(),
                    empty(),
                    empty()
                )
            )
        );
    }

    @Test
    void rejectsExactViewWorkAssignedToReconstruction() {
        var exactView = ledger("exact-parsed-view.ast-visits", 1L);

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCanonicalWorkProjection.project(
                input("z02-difference-of-squares"),
                raw(
                    0L,
                    empty(),
                    empty(),
                    empty(),
                    empty(),
                    empty(),
                    empty(),
                    exactView,
                    empty(),
                    empty()
                )
            )
        );
    }

    @Test
    void chargesUnknownNativeStagesConservativelyOneForOne() {
        var factorization = ledger("native.new-stage-v2", 11L);
        var projection =
            PolynomialTheoryUtilityCanonicalWorkProjection.project(
                input("z02-difference-of-squares"),
                raw(
                    0L,
                    empty(),
                    empty(),
                    factorization,
                    empty(),
                    empty(),
                    empty(),
                    empty(),
                    empty(),
                    empty()
                )
            );

        assertEquals(11L, projection.work().factorizationWork());
        assertEquals(11L, projection.work().mechanicalWork());
    }

    @Test
    void rejectsProjectedWorkAboveTheFrozenInputAuthority() {
        var factorization = ledger("native.factor-operations", 65L);

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCanonicalWorkProjection.project(
                input("z02-difference-of-squares"),
                raw(
                    0L,
                    empty(),
                    empty(),
                    factorization,
                    empty(),
                    empty(),
                    empty(),
                    empty(),
                    empty(),
                    empty()
                )
            )
        );
    }

    @Test
    void contentAddressesRawWorkAndProjectedInput() {
        var first = PolynomialTheoryUtilityCanonicalWorkProjection.project(
            input("z02-difference-of-squares"),
            raw(
                0L,
                empty(),
                empty(),
                ledger("native.factor-operations", 1L),
                empty(),
                empty(),
                empty(),
                empty(),
                empty(),
                empty()
            )
        );
        var repeated = PolynomialTheoryUtilityCanonicalWorkProjection.project(
            input("z02-difference-of-squares"),
            raw(
                0L,
                empty(),
                empty(),
                ledger("native.factor-operations", 1L),
                empty(),
                empty(),
                empty(),
                empty(),
                empty(),
                empty()
            )
        );
        var changed = PolynomialTheoryUtilityCanonicalWorkProjection.project(
            input("z03-cubic-unity"),
            raw(
                0L,
                empty(),
                empty(),
                ledger("native.factor-operations", 1L),
                empty(),
                empty(),
                empty(),
                empty(),
                empty(),
                empty()
            )
        );

        assertEquals(first, repeated);
        assertNotEquals(first.projectionId(), changed.projectionId());
    }

    private static PolynomialTheoryUtilityCanonicalWorkProjection.RawWork raw(
        long primitiveWork,
        PolynomialWorkLedger matching,
        PolynomialWorkLedger source,
        PolynomialWorkLedger factorization,
        PolynomialWorkLedger verification,
        PolynomialWorkLedger rendering,
        PolynomialWorkLedger reparse,
        PolynomialWorkLedger reconstruction,
        PolynomialWorkLedger replacement,
        PolynomialWorkLedger evidence
    ) {
        PolynomialWorkLedger total = merge(
            matching,
            source,
            factorization,
            verification,
            rendering,
            reparse,
            reconstruction,
            replacement,
            evidence
        );
        return new PolynomialTheoryUtilityCanonicalWorkProjection.RawWork(
            primitiveWork,
            total,
            matching,
            source,
            factorization,
            verification,
            rendering,
            reparse,
            reconstruction,
            replacement,
            empty(),
            empty(),
            empty(),
            empty(),
            evidence
        );
    }

    private static PolynomialTheoryUtilityExecutionInput input(
        String caseId
    ) {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value ->
                "ON_DEMAND_VERIFIED_FACTORIZATION".equals(value.profileId())
            )
            .filter(value -> "CP06_FULL".equals(value.checkpointId()))
            .filter(value -> caseId.equals(value.caseId()))
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

    private static PolynomialWorkLedger merge(
        PolynomialWorkLedger... values
    ) {
        Map<String, Long> stages = new LinkedHashMap<>();
        for (PolynomialWorkLedger value : values) {
            value.stages().forEach((stage, units) ->
                stages.merge(stage, units, Math::addExact)
            );
        }
        return new PolynomialWorkLedger(stages);
    }
}
