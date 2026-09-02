package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.polynomial
    .ExactNestedFactorizationTransformationPipeline;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityTerminalStatusTest {
    @Test
    void rejectsPartialSuccessThatWouldHideAnInconclusiveOccurrence() {
        assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                .terminalStatus(
                    1,
                    List.of(
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TRANSFORMED,
                        ExactNestedFactorizationTransformationPipeline.Status
                            .BUDGET_INCONCLUSIVE
                    )
                )
        );
    }

    @Test
    void rejectsPartialSuccessThatWouldHideATechnicalOccurrence() {
        assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                .terminalStatus(
                    1,
                    List.of(
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TRANSFORMED,
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TECHNICAL_FAILURE
                    )
                )
        );
    }

    @Test
    void requiresEveryOccurrenceForAValidatedTransition() {
        assertEquals(
            PolynomialTheoryUtilityCandidateResult.TerminalStatus
                .VALIDATED_TRANSITION,
            PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                .terminalStatus(
                    2,
                    List.of(
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TRANSFORMED,
                        ExactNestedFactorizationTransformationPipeline.Status
                            .TRANSFORMED
                    )
                )
        );
    }
}
