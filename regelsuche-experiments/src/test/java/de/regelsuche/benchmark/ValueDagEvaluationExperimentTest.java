package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.benchmark.ValueDagEvaluationExperiment.Comparison;
import de.regelsuche.parse.ExpressionParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValueDagEvaluationExperimentTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void repeatedSubexpressionIsEvaluatedOncePerDistinctValue() {
        Expr syntax = parser.parseTerm("(a + b) * (a + b)");
        try (ValueDagEvaluationExperiment experiment =
                new ValueDagEvaluationExperiment(100, 4)) {
            Comparison first = experiment.compare(syntax, Map.of("a", 2.0, "b", 3.0));

            assertEquals(25.0, first.treeValue());
            assertEquals(first.treeValue(), first.dagValue());
            assertEquals(7, first.treeNodeEvaluations());
            assertEquals(4, first.distinctValues());
            assertEquals(4, first.dagValueEvaluations());
            assertTrue(first.dagValueEvaluations() < first.treeNodeEvaluations());
            assertFalse(first.planCacheHit());
            assertEquals(1, first.totalPlanCacheMisses());
            assertEquals(0, first.totalPlanCacheHits());
            assertEquals(1, first.cachedPlans());
            assertTrue(first.internedValues() >= first.distinctValues());

            Comparison second = experiment.compare(syntax, Map.of("a", 4.0, "b", 1.0));
            assertEquals(25.0, second.dagValue());
            assertTrue(second.planCacheHit());
            assertEquals(1, second.totalPlanCacheHits());
            assertEquals(1, second.totalPlanCacheMisses());
            assertEquals(0, second.planConstructionNanos());
            assertEquals(second.distinctValues(), second.dagValueEvaluations());
        }
    }

    @Test
    void acEquivalentRootsReuseOneValueKeyedPlanAcrossSyntaxForms() {
        Expr grouped = parser.parseTerm("(a + b) + c");
        Expr permuted = parser.parseTerm("c + a + b");
        try (ValueDagEvaluationExperiment experiment =
                new ValueDagEvaluationExperiment(100, 4)) {
            Comparison first = experiment.compare(
                    grouped, Map.of("a", 1.0, "b", 2.0, "c", 3.0));
            Comparison second = experiment.compare(
                    permuted, Map.of("a", 2.0, "b", 3.0, "c", 4.0));

            assertEquals(6.0, first.dagValue());
            assertEquals(9.0, second.dagValue());
            assertFalse(first.planCacheHit());
            assertTrue(second.planCacheHit());
            assertEquals(1, second.cachedPlans());
            assertEquals(1, second.totalPlanCacheHits());
        }
    }

    @Test
    void boundedPlanCacheEvictsLeastRecentlyUsedRoot() {
        try (ValueDagEvaluationExperiment experiment =
                new ValueDagEvaluationExperiment(100, 1)) {
            Expr sum = parser.parseTerm("a + b");
            Expr product = parser.parseTerm("a * b");
            Map<String, Double> variables = Map.of("a", 2.0, "b", 3.0);

            Comparison first = experiment.compare(sum, variables);
            Comparison second = experiment.compare(product, variables);
            Comparison third = experiment.compare(sum, variables);

            assertFalse(first.planCacheHit());
            assertFalse(second.planCacheHit());
            assertFalse(third.planCacheHit());
            assertEquals(1, third.cachedPlans());
            assertEquals(3, third.totalPlanCacheMisses());
            assertEquals(2, third.totalPlanCacheEvictions());
        }
    }

    @Test
    void orderedOperatorsAndFunctionsMatchTreeEvaluation() {
        Expr syntax = parser.parseTerm("sin(a + b) + (a - b) / (b + 1)");
        try (ValueDagEvaluationExperiment experiment =
                new ValueDagEvaluationExperiment(100, 4)) {
            Comparison comparison = experiment.compare(syntax, Map.of("a", 4.0, "b", 2.0));

            assertEquals(comparison.treeValue(), comparison.dagValue(), 1e-12);
            assertEquals(comparison.distinctValues(), comparison.dagValueEvaluations());
            assertTrue(comparison.projectionNanos() >= 0);
            assertTrue(comparison.planConstructionNanos() >= 0);
            assertTrue(comparison.treeExecutionNanos() >= 0);
            assertTrue(comparison.dagExecutionNanos() >= 0);
        }
    }

    @Test
    void ownershipIsExplicitAndClosedExperimentsRejectFurtherUse() {
        ValueDagEvaluationExperiment experiment = new ValueDagEvaluationExperiment(10, 2);
        experiment.compare(parser.parseTerm("a + 1"), Map.of("a", 2.0));
        experiment.close();

        assertThrows(
                IllegalStateException.class,
                () -> experiment.compare(parser.parseTerm("a + 1"), Map.of("a", 2.0)));
        assertThrows(IllegalStateException.class, experiment::cachedPlanCount);
    }

    @Test
    void missingBindingsAreRejectedInsteadOfSilentlyUsingZero() {
        try (ValueDagEvaluationExperiment experiment =
                new ValueDagEvaluationExperiment(10, 2)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> experiment.compare(parser.parseTerm("a + b"), Map.of("a", 1.0)));
        }
    }
}
