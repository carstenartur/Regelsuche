package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.scalar.ExactRational;
import java.util.List;
import org.junit.jupiter.api.Test;

class FactorizationStructuralLimitsTest {
    @Test
    void verifierRejectsOversizedInputBeforeCallingAnEngine() {
        PolynomialRing<ExactRational> ring =
            new PolynomialRing<>(
                ExactRationalField.INSTANCE,
                List.of(new PolynomialVariable("x")),
                PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<ExactRational> source =
            UnivariatePolynomialView.of(
                ring,
                List.of(
                    ExactRational.ONE,
                    ExactRational.ZERO,
                    ExactRational.ONE))
                .toSparsePolynomial();
        FactorizationRequest<ExactRational> request =
            FactorizationRequest.verifiedDecomposition(
                source,
                new FactorizationRequest.StructuralLimits(
                    1,
                    1,
                    8,
                    128),
                1,
                32);
        FactorizationEngine<ExactRational> engine =
            new FactorizationEngine<>() {
                @Override
                public String engineId() {
                    return "test.never-called/v1";
                }

                @Override
                public String coefficientDomainId() {
                    return ExactRationalField.DOMAIN_ID;
                }

                @Override
                public EngineResult<ExactRational> propose(
                    FactorizationRequest<ExactRational> ignored
                ) {
                    throw new AssertionError(
                        "structural limits must precede engine execution");
                }
            };

        FactorizationVerifier.Report<ExactRational> report =
            FactorizationVerifier.execute(engine, request);

        assertEquals(
            FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            report.status());
        assertEquals(
            "MAX_TOTAL_DEGREE_EXCEEDED",
            report.detailCode());
        assertEquals(0, report.work().totalWorkUnits());
    }
}
