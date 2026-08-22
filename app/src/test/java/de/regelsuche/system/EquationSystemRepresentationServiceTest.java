package de.regelsuche.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.linalg.ExactLinearSystem;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposer;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposition;
import de.regelsuche.math.algorithms.linalg.LinearSystemRepresentationBridge;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge;
import org.junit.jupiter.api.Test;

class EquationSystemRepresentationServiceTest {

    @Test
    void analyzesSystemAsOneExactObjectAndExposesIndependentBlocks() {
        EquationSystemRepresentationService.Analysis analysis =
            new EquationSystemRepresentationService().analyze(
                "x = 1; y = 2");

        assertTrue(analysis.represented());
        ExactLinearSystem system = analysis.exactSystem().orElseThrow();
        assertEquals(2, system.equationCount());
        assertEquals(2, system.variableCount());
        assertEquals(
            ExactLinearSystem.SolutionClassification.UNIQUE,
            system.solutionClassification());

        ExactLinearSystemBlockDecomposition blocks =
            analysis.blocks().orElseThrow();
        assertEquals(2, blocks.components().size());
        assertEquals(
            java.util.List.of(
                ExactLinearSystemBlockDecomposition
                    .CAPABILITY_INDEPENDENT_SUBSYSTEMS),
            analysis.unlockedCapabilities());

        String rendered = analysis.renderSummary();
        assertTrue(rendered.contains(
            "Recognized exact matrix representation"));
        assertTrue(rendered.contains("A = [[1, 0], [0, 1]]"));
        assertTrue(rendered.contains("x = [x, y]^T"));
        assertTrue(rendered.contains("b = [1, 2]^T"));
        assertTrue(rendered.contains("Independent components: 2"));
        assertTrue(rendered.contains("INDEPENDENT_LINEAR_SUBSYSTEMS"));
    }

    @Test
    void connectedSystemRetainsMatrixRepresentationWithoutInventingBlocks() {
        EquationSystemRepresentationService.Analysis analysis =
            new EquationSystemRepresentationService().analyze(
                "x + y = 1; x - y = 0");

        assertTrue(analysis.represented());
        assertTrue(analysis.blocks().isEmpty());
        assertTrue(analysis.renderSummary().contains(
            "Independent components: none (NOT_APPLICABLE)"));
    }

    @Test
    void nonlinearSystemReportsItsExactTerminalStatus() {
        EquationSystemRepresentationService.Analysis analysis =
            new EquationSystemRepresentationService().analyze(
                "x*y = 1; x + y = 2");

        assertFalse(analysis.represented());
        assertTrue(analysis.decomposition().isEmpty());
        assertEquals(
            RepresentationBridge.Status.NONLINEAR,
            analysis.representation().status());
        assertTrue(analysis.renderSummary().contains(
            "Exact matrix representation: NONLINEAR"));
        assertTrue(analysis.renderSummary().contains(
            "PRODUCT_OF_NON_CONSTANT_FORMS"));
    }

    @Test
    void configuredZeroBudgetRemainsVisibleAndInconclusive() {
        EquationSystemRepresentationService service =
            new EquationSystemRepresentationService(
                new ExpressionParser(),
                new LinearSystemRepresentationBridge(),
                new ExactLinearSystemBlockDecomposer(),
                new RepresentationBridge.Budget(0),
                new RepresentationBridge.Budget(0));

        EquationSystemRepresentationService.Analysis analysis =
            service.analyze("x = 1");

        assertEquals(
            RepresentationBridge.Status.BUDGET_INCONCLUSIVE,
            analysis.representation().status());
        assertFalse(analysis.represented());
        assertTrue(analysis.renderSummary().contains(
            "Representation work: 0/0"));
    }

    @Test
    void blankInputIsRejectedBeforeAnyMathematicalClaim() {
        EquationSystemRepresentationService service =
            new EquationSystemRepresentationService();

        assertThrows(IllegalArgumentException.class,
            () -> service.analyze("  "));
    }
}
