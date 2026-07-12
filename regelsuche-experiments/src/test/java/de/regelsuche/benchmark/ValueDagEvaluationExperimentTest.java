package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.benchmark.SearchBenchmark.ValueDagEvaluationExperiment;
import de.regelsuche.benchmark.SearchBenchmark.ValueDagEvaluationExperiment.Result;
import de.regelsuche.parse.ExpressionParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValueDagEvaluationExperimentTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void repeatedSubexpressionIsEvaluatedOncePerDistinctValue() {
        Expr syntax = parser.parseTerm("(a + b) * (a + b)");
        Map<String, Double> variables = Map.of("a", 2.0, "b", 3.0);
        TreeResult tree = tree(syntax, variables);
        try (ValueDagEvaluationExperiment experiment = new ValueDagEvaluationExperiment(100, 4)) {
            Result first = experiment.evaluate(syntax, variables);
            assertEquals(tree.value(), first.value());
            assertEquals(7, tree.evaluations());
            assertEquals(4, first.evaluationCount());
            assertTrue(first.evaluationCount() < tree.evaluations());
            assertFalse(first.planCacheHit());
            assertEquals(1, first.totalPlanCacheMisses());
            assertTrue(first.internedValues() >= first.distinctValues());

            Result second = experiment.evaluate(syntax, Map.of("a", 4.0, "b", 1.0));
            assertEquals(25.0, second.value());
            assertTrue(second.planCacheHit());
            assertEquals(1, second.totalPlanCacheHits());
            assertEquals(0, second.planConstructionNanos());
        }
    }

    @Test
    void equivalentRootsReuseOneValueKeyedPlan() {
        try (ValueDagEvaluationExperiment experiment = new ValueDagEvaluationExperiment(100, 4)) {
            Result first = experiment.evaluate(
                    parser.parseTerm("(a + b) + c"), Map.of("a", 1.0, "b", 2.0, "c", 3.0));
            Result second = experiment.evaluate(
                    parser.parseTerm("c + a + b"), Map.of("a", 2.0, "b", 3.0, "c", 4.0));
            assertEquals(6.0, first.value());
            assertEquals(9.0, second.value());
            assertFalse(first.planCacheHit());
            assertTrue(second.planCacheHit());
            assertEquals(1, second.cachedPlans());
        }
    }

    @Test
    void planCacheIsBoundedAndAccessOrdered() {
        try (ValueDagEvaluationExperiment experiment = new ValueDagEvaluationExperiment(100, 1)) {
            Map<String, Double> variables = Map.of("a", 2.0, "b", 3.0);
            Result first = experiment.evaluate(parser.parseTerm("a + b"), variables);
            Result second = experiment.evaluate(parser.parseTerm("a * b"), variables);
            Result third = experiment.evaluate(parser.parseTerm("a + b"), variables);
            assertFalse(first.planCacheHit());
            assertFalse(second.planCacheHit());
            assertFalse(third.planCacheHit());
            assertEquals(1, third.cachedPlans());
            assertEquals(3, third.totalPlanCacheMisses());
            assertEquals(2, third.totalPlanCacheEvictions());
        }
    }

    @Test
    void functionsAndOrderedOperatorsMatchTreeEvaluation() {
        Expr syntax = parser.parseTerm("sin(a + b) + (a - b) / (b + 1)");
        Map<String, Double> variables = Map.of("a", 4.0, "b", 2.0);
        try (ValueDagEvaluationExperiment experiment = new ValueDagEvaluationExperiment(100, 4)) {
            Result result = experiment.evaluate(syntax, variables);
            assertEquals(tree(syntax, variables).value(), result.value(), 1e-12);
            assertEquals(result.distinctValues(), result.evaluationCount());
            assertTrue(result.projectionNanos() >= 0);
            assertTrue(result.planConstructionNanos() >= 0);
            assertTrue(result.executionNanos() >= 0);
        }
    }

    @Test
    void ownershipAndBindingsAreExplicit() {
        ValueDagEvaluationExperiment experiment = new ValueDagEvaluationExperiment(10, 2);
        assertThrows(
                IllegalArgumentException.class,
                () -> experiment.evaluate(parser.parseTerm("a + b"), Map.of("a", 1.0)));
        experiment.close();
        assertThrows(
                IllegalStateException.class,
                () -> experiment.evaluate(parser.parseTerm("a + 1"), Map.of("a", 2.0)));
        assertThrows(IllegalStateException.class, experiment::cachedPlanCount);
    }

    private static TreeResult tree(Expr expression, Map<String, Double> variables) {
        if (expression instanceof NumberExpr number) {
            return new TreeResult(number.value(), 1);
        }
        if (expression instanceof VariableExpr variable) {
            Double value = variables.get(variable.name());
            if (value == null) {
                throw new IllegalArgumentException("missing variable binding: " + variable.name());
            }
            return new TreeResult(value, 1);
        }
        if (expression instanceof FunctionExpr function) {
            TreeResult argument = tree(function.arguments().getFirst(), variables);
            double value = switch (function.name()) {
                case "sin" -> Math.sin(argument.value());
                case "cos" -> Math.cos(argument.value());
                case "tan" -> Math.tan(argument.value());
                case "log" -> Math.log10(argument.value());
                case "ln" -> Math.log(argument.value());
                case "sqrt" -> Math.sqrt(argument.value());
                case "exp" -> Math.exp(argument.value());
                case "abs" -> Math.abs(argument.value());
                default -> throw new IllegalArgumentException("unsupported function: " + function.name());
            };
            return new TreeResult(value, argument.evaluations() + 1);
        }
        BinaryExpr binary = (BinaryExpr) expression;
        TreeResult left = tree(binary.left(), variables);
        TreeResult right = tree(binary.right(), variables);
        double value = switch (binary.operator()) {
            case ADD -> left.value() + right.value();
            case SUB -> left.value() - right.value();
            case MUL -> left.value() * right.value();
            case DIV -> left.value() / right.value();
            case POW -> Math.pow(left.value(), right.value());
        };
        return new TreeResult(value, left.evaluations() + right.evaluations() + 1);
    }

    private record TreeResult(double value, int evaluations) {
    }
}
