package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposition.ComponentKind;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.Result;
import de.regelsuche.representation.RepresentationBridge.Status;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactLinearSystemBlockDecomposerTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final LinearSystemRepresentationBridge representationBridge =
        new LinearSystemRepresentationBridge();
    private final ExactLinearSystemBlockDecomposer decomposer =
        new ExactLinearSystemBlockDecomposer();

    @Test
    void exposesTwoIndependentCoupledSubsystems() {
        ExactLinearSystem source = exact(
            "x + y = 3; 2*x - y = 0; z + w = 4; z - w = 2");

        Result<ExactLinearSystemBlockDecomposition,
            ExactLinearSystemBlockDecomposer.Certificate> result =
                decomposer.analyze(source, new Budget(2_000));

        assertEquals(Status.REPRESENTED, result.status());
        ExactLinearSystemBlockDecomposition decomposition =
            result.representation().orElseThrow();
        assertEquals(2, decomposition.components().size());
        assertEquals(List.of(0, 1), decomposition.components()
            .get(0).sourceRowIndices());
        assertEquals(List.of("x", "y"), decomposition.components()
            .get(0).variableNames());
        assertEquals(List.of(2, 3), decomposition.components()
            .get(1).sourceRowIndices());
        assertEquals(List.of("w", "z"), decomposition.components()
            .get(1).variableNames());
        assertEquals(List.of(0, 1, 2, 3), decomposition.rowPermutation());
        assertEquals(List.of(1, 2, 0, 3),
            decomposition.columnPermutation());
        assertEquals(
            List.of(ExactLinearSystemBlockDecomposition
                .CAPABILITY_INDEPENDENT_SUBSYSTEMS),
            decomposition.unlockedCapabilities());
        assertTrue(decomposer.verify(source, result));
    }

    @Test
    void retainsInterleavedSourceRowsInDeterministicBlockOrder() {
        ExactLinearSystem source = exact(
            "x + y = 3; z + w = 4; 2*x - y = 0; z - w = 2");

        ExactLinearSystemBlockDecomposition decomposition = decomposer
            .analyze(source, new Budget(2_000))
            .representation()
            .orElseThrow();

        assertEquals(List.of(0, 2), decomposition.components()
            .get(0).sourceRowIndices());
        assertEquals(List.of(1, 3), decomposition.components()
            .get(1).sourceRowIndices());
        assertEquals(List.of(0, 2, 1, 3), decomposition.rowPermutation());
    }

    @Test
    void connectedSystemDoesNotManufactureADecomposition() {
        ExactLinearSystem source = exact(
            "x + y = 1; y + z = 2; z + x = 3");

        var result = decomposer.analyze(source, new Budget(1_000));

        assertEquals(
            Status.DIRECT_REPRESENTATION_AVAILABLE,
            result.status());
        assertFalse(result.represented());
        assertEquals(
            "COEFFICIENT_INCIDENCE_GRAPH_IS_CONNECTED",
            result.detailCode());
    }

    @Test
    void exposesFreeCoordinateAndConstantConstraintComponents() {
        ExactLinearSystem source = exact("x - x = 0; y = 2");

        ExactLinearSystemBlockDecomposition decomposition = decomposer
            .analyze(source, new Budget(1_000))
            .representation()
            .orElseThrow();

        assertEquals(3, decomposition.components().size());
        assertEquals(
            ComponentKind.CONSTANT_CONSTRAINTS,
            decomposition.components().get(0).kind());
        assertEquals(
            ComponentKind.COUPLED_SUBSYSTEM,
            decomposition.components().get(1).kind());
        assertEquals(
            ComponentKind.FREE_VARIABLES,
            decomposition.components().get(2).kind());
        assertTrue(decomposition.hasFreeVariableComponents());
        assertTrue(decomposition.hasConstantConstraintComponents());
        assertFalse(decomposition.hasContradictoryConstantConstraint());
        assertTrue(decomposition.unlockedCapabilities().contains(
            ExactLinearSystemBlockDecomposition
                .CAPABILITY_FREE_VARIABLE_COMPONENTS));
        assertTrue(decomposition.unlockedCapabilities().contains(
            ExactLinearSystemBlockDecomposition
                .CAPABILITY_CONSTANT_CONSTRAINT_COMPONENTS));
    }

    @Test
    void localizesAContradictoryConstantConstraint() {
        ExactLinearSystem source = exact("x - x = 1; y = 2");

        ExactLinearSystemBlockDecomposition decomposition = decomposer
            .analyze(source, new Budget(1_000))
            .representation()
            .orElseThrow();

        assertTrue(decomposition.hasContradictoryConstantConstraint());
        assertTrue(decomposition.components().get(0)
            .contradictoryConstantConstraint());
        assertTrue(decomposition.unlockedCapabilities().contains(
            ExactLinearSystemBlockDecomposition
                .CAPABILITY_INCONSISTENCY_LOCALIZATION));
    }

    @Test
    void reportsBudgetExhaustionWithoutGuessingBlocks() {
        ExactLinearSystem source = exact("x = 1; y = 2");

        var result = decomposer.analyze(source, new Budget(0));

        assertEquals(Status.BUDGET_INCONCLUSIVE, result.status());
        assertEquals(0, result.work().consumedWorkUnits());
        assertFalse(result.represented());
    }

    @Test
    void independentVerificationRejectsTamperedPermutation() {
        ExactLinearSystem source = exact("x = 1; y = 2");
        Result<ExactLinearSystemBlockDecomposition,
            ExactLinearSystemBlockDecomposer.Certificate> original =
                decomposer.analyze(source, new Budget(1_000));
        ExactLinearSystemBlockDecomposition decomposition =
            original.representation().orElseThrow();
        List<Integer> changedColumns = new ArrayList<>(
            decomposition.columnPermutation());
        java.util.Collections.reverse(changedColumns);
        ExactLinearSystemBlockDecomposition changed =
            new ExactLinearSystemBlockDecomposition(
                decomposition.sourceRowCount(),
                decomposition.sourceColumnCount(),
                decomposition.components(),
                decomposition.rowPermutation(),
                changedColumns,
                decomposition.unlockedCapabilities());
        Result<ExactLinearSystemBlockDecomposition,
            ExactLinearSystemBlockDecomposer.Certificate> tampered =
                Result.represented(
                    changed,
                    original.certificate().orElseThrow(),
                    original.relation().orElseThrow(),
                    original.work(),
                    original.detailCode());

        assertFalse(decomposer.verify(source, tampered));
        assertTrue(decomposer.verify(source, original));
    }

    private ExactLinearSystem exact(String input) {
        List<Equation> equations = parser.parse(
            new InputRequest(InputType.SYSTEM, input)).equations();
        return representationBridge.analyze(equations, new Budget(5_000))
            .representation()
            .orElseThrow();
    }
}
