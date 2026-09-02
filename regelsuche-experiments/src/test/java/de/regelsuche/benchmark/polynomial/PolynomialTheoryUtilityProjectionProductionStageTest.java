package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityProjectionProductionStageTest {
    @Test
    void pricesTheActualRootProjectorLedgerWithRevisionTwo() {
        var parsed = new ExpressionParser().parseExactTerm("x^2-1");
        String expected = ExpressionFormatter.format(parsed.expression());
        var projected = new ExactParsedSubtermProjector().project(
            parsed,
            List.of(),
            expected
        );
        assertEquals(
            ExactParsedSubtermProjector.Status.PROJECTED,
            projected.status()
        );

        PolynomialWorkLedger matching = new PolynomialWorkLedger(
            projected.work().stages()
        );
        var measured = PolynomialTheoryUtilityCanonicalWorkProjection.project(
            pricingAuthority(),
            matchingOnly(matching)
        );

        assertEquals(
            expectedMatchingWork(projected.work().stages()),
            measured.work().matchingWork()
        );
        assertEquals(
            "regelsuche.polynomial-theory-utility-work-projection/v2",
            measured.projectionRevision()
        );
    }

    private static long expectedMatchingWork(Map<String, Long> stages) {
        long literals = stages.getOrDefault(
            "projection.revalidation-literal-bindings",
            0L
        );
        long result = 0L;
        for (Map.Entry<String, Long> entry : stages.entrySet()) {
            String stage = entry.getKey();
            long units = entry.getValue();
            result = Math.addExact(result, switch (stage) {
                case "projection.root-source-hash-code-units",
                        "projection.range-commitment-code-units" ->
                    divideRoundUp(units, 4L);
                case "projection.revalidation-literal-code-units" ->
                    Math.addExact(
                        literals,
                        Math.subtractExact(
                            units,
                            Math.multiplyExact(512L, literals)
                        ) / 4L
                    );
                default -> units;
            });
        }
        return result;
    }

    private static long divideRoundUp(long value, long divisor) {
        return value == 0L ? 0L : 1L + (value - 1L) / divisor;
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

    /**
     * Supplies room to characterize production-stage pricing independently of
     * any one frozen study row. Frozen-row rejection remains covered by the
     * projection contract tests; this test measures the conversion itself.
     */
    private static PolynomialTheoryUtilityExecutionInput pricingAuthority() {
        var frozen = frozenInput();
        int budget = 1_000_000;
        return new PolynomialTheoryUtilityExecutionInput(
            PolynomialTheoryUtilityExecutionIdentity.sha256(
                "projection-production-stage-pricing-authority/v1"
                    .getBytes(StandardCharsets.UTF_8)
            ),
            frozen.rowId(),
            frozen.runId(),
            frozen.caseId(),
            frozen.profileId(),
            frozen.checkpointId(),
            frozen.adapterId(),
            budget,
            budget,
            budget,
            frozen.inputStatus()
        );
    }

    private static PolynomialTheoryUtilityExecutionInput frozenInput() {
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
}
