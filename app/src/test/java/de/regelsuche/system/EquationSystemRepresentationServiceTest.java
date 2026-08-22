package de.regelsuche.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.linalg.ExactLinearSystem;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposer;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposition;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver;
import de.regelsuche.math.algorithms.linalg.LinearSystemRepresentationBridge;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge;
import org.junit.jupiter.api.Test;

class EquationSystemRepresentationServiceTest {

    @Test
    void analyzesSystemAsOneExactObjectAndExposesExecutableCapabilities() {
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
        ExactRrefReduction rref = analysis.rref().orElseThrow();
        assertEquals(
            java.util.List.of(
                de.regelsuche.math.algorithms.equivalence.Rational.ONE,
                de.regelsuche.math.algorithms.equivalence.Rational.of(2)),
            rref.particularSolution().orElseThrow().values());
        assertTrue(analysis.unlockedCapabilities().contains(
            ExactLinearSystemBlockDecomposition
                .CAPABILITY_INDEPENDENT_SUBSYSTEMS));
        assertTrue(analysis.unlockedCapabilities().contains(
            ExactRrefReduction.CAPABILITY_EXACT_RREF));
        assertTrue(analysis.unlockedCapabilities().contains(
            ExactRrefReduction.CAPABILITY_UNIQUE_SOLUTION));

        String rendered = analysis.renderSummary();
        assertTrue(rendered.contains(
            "Recognized exact matrix representation"));
        assertTrue(rendered.contains("A = [[1, 0], [0, 1]]"));
        assertTrue(rendered.contains("x = [x, y]^T"));
        assertTrue(rendered.contains("b = [1, 2]^T"));
        assertTrue(rendered.contains("Independent components: 2"));
        assertTrue(rendered.contains("INDEPENDENT_LINEAR_SUBSYSTEMS"));
        assertTrue(rendered.contains("RREF(A|b) = [[1, 0 | 1], [0, 1 | 2]]"));
        assertTrue(rendered.contains("Exact solution: [x=1, y=2]"));
        assertTrue(rendered.contains("EXACT_RREF_AVAILABLE"));
    }

    @Test
    void connectedSystemRetainsMatrixRepresentationWithoutInventingBlocks() {
        EquationSystemRepresentationService.Analysis analysis =
            new EquationSystemRepresentationService().analyze(
                "x + y = 1; x - y = 0");

        assertTrue(analysis.represented());
        assertTrue(analysis.blocks().isEmpty());
        assertTrue(analysis.rref().isPresent());
        assertTrue(analysis.renderSummary().contains(
            "Independent components: none (NOT_APPLICABLE)"));
        assertTrue(analysis.renderSummary().contains(
            "Exact solution: [x=1/2, y=1/2]"));
    }

    @Test
    void inconsistentSystemExposesAConcreteContradictionRow() {
        EquationSystemRepresentationService.Analysis analysis =
            new EquationSystemRepresentationService().analyze(
                "x + y = 1; x + y = 2");

        assertEquals(
            ExactLinearSystem.SolutionClassification.INCONSISTENT,
            analysis.exactSystem().orElseThrow().solutionClassification());
        assertEquals(
            java.util.List.of(1),
            analysis.rref().orElseThrow().contradictionRows());
        assertTrue(analysis.renderSummary().contains(
            "Contradiction rows: [1]"));
        assertTrue(analysis.unlockedCapabilities().contains(
            ExactRrefReduction.CAPABILITY_INCONSISTENCY_WITNESS));
    }

    @Test
    void nonlinearSystemReportsItsExactTerminalStatus() {
        EquationSystemRepresentationService.Analysis analysis =
            new EquationSystemRepresentationService().analyze(
                "x*y = 1; x + y = 2");

        assertFalse(analysis.represented());
        assertTrue(analysis.decomposition().isEmpty());
        assertTrue(analysis.rowReduction().isEmpty());
        assertEquals(
            RepresentationBridge.Status.NONLINEAR,
            analysis.representation().status());
        assertTrue(analysis.renderSummary().contains(
            "Exact matrix representation: NONLINEAR"));
        assertTrue(analysis.renderSummary().contains(
            "PRODUCT_OF_NON_CONSTANT_FORMS"));
    }

    @Test
    void configuredZeroRepresentationBudgetRemainsVisibleAndInconclusive() {
        EquationSystemRepresentationService service =
            new EquationSystemRepresentationService(
                new ExpressionParser(),
                new LinearSystemRepresentationBridge(),
                new ExactLinearSystemBlockDecomposer(),
                new ExactRrefSolver(),
                new RepresentationBridge.Budget(0),
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
    void exhaustedRrefBudgetDoesNotHideTheExactMatrixRepresentation() {
        EquationSystemRepresentationService service =
            new EquationSystemRepresentationService(
                new ExpressionParser(),
                new LinearSystemRepresentationBridge(),
                new ExactLinearSystemBlockDecomposer(),
                new ExactRrefSolver(),
                new RepresentationBridge.Budget(20_000),
                new RepresentationBridge.Budget(20_000),
                new RepresentationBridge.Budget(0));

        EquationSystemRepresentationService.Analysis analysis =
            service.analyze("x = 1");

        assertTrue(analysis.represented());
        assertEquals(
            ExactRrefSolver.Status.BUDGET_INCONCLUSIVE,
            analysis.rowReduction().orElseThrow().status());
        assertTrue(analysis.rref().isEmpty());
        assertTrue(analysis.renderSummary().contains(
            "Exact RREF: BUDGET_INCONCLUSIVE"));
        assertTrue(analysis.renderSummary().contains("RREF work: 0/0"));
    }

    @Test
    void blankInputIsRejectedBeforeAnyMathematicalClaim() {
        EquationSystemRepresentationService service =
            new EquationSystemRepresentationService();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> service.analyze("  "));
        assertEquals(
            "equation-system input must not be blank",
            exception.getMessage());
    }

    @Test
    void separatorOnlyInputIsRejectedImmediatelyAndClearly() {
        EquationSystemRepresentationService service =
            new EquationSystemRepresentationService();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> service.analyze(";\n;"));
        assertEquals(
            "equation-system input must contain at least one equation",
            exception.getMessage());
    }
}
