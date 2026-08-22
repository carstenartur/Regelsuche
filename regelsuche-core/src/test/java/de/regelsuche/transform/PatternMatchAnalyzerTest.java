package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatternMatchAnalyzerTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final PatternMatchAnalyzer analyzer = new PatternMatchAnalyzer();

    @Test
    void reportsAnExactStructuralPatternMatch() {
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.var("A"),
            PatternExpr.num(0));

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            parser.parseTerm("x + 0"),
            RecognitionProfile.algebraicAc());

        assertEquals(
            PatternMatchAnalyzer.Status.EXACT_MATCH,
            analysis.status());
        assertTrue(analysis.matched());
        assertEquals(
            "EXACT_PATTERN_MATCH",
            analysis.detailCode());
        assertEquals(
            "x",
            ExpressionFormatter.format(analysis.bindings().get("A")));
        assertEquals(
            ExprMatcher.RecognitionStrength.EXACT,
            analysis.matches().getFirst().recognitionStrength());
        assertTrue(analysis.residualObligations().isEmpty());
    }

    @Test
    void distinguishesAMatchThatNeedsCommutativity() {
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.variable("x"),
            PatternExpr.var("A"));

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            parser.parseTerm("y + x"),
            RecognitionProfile.arithmeticAc());

        assertEquals(
            PatternMatchAnalyzer.Status.MATCH_MODULO_THEORY,
            analysis.status());
        assertEquals(
            "EQUIVALENCE_AWARE_PATTERN_MATCH",
            analysis.detailCode());
        assertEquals(
            "y",
            ExpressionFormatter.format(analysis.bindings().get("A")));
        assertEquals(
            ExprMatcher.RecognitionStrength.EQUIVALENCE_AWARE,
            analysis.matches().getFirst().recognitionStrength());
    }

    @Test
    void exposesTheCancellationFactorAsAResidualObligation() {
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.DIV,
            PatternExpr.op(
                BinaryOperator.MUL,
                PatternExpr.var("A"),
                PatternExpr.var("B")),
            PatternExpr.var("A"));

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            parser.parseTerm("(x^3 - 1) / (x - 1)"),
            RecognitionProfile.exact());

        assertEquals(
            PatternMatchAnalyzer.Status.RESIDUAL,
            analysis.status());
        assertTrue(analysis.residual());
        assertEquals(
            "x - 1",
            ExpressionFormatter.format(analysis.bindings().get("A")));
        assertEquals(1, analysis.residualObligations().size());
        PatternMatchAnalyzer.ResidualObligation obligation =
            analysis.residualObligations().getFirst();
        assertEquals(
            PatternMatchAnalyzer.ResidualKind.SHAPE_MISMATCH,
            obligation.kind());
        assertEquals("0", obligation.path());
        assertEquals(Set.of("B"), obligation.unboundPlaceholders());
        assertInstanceOf(
            PatternExpr.Operation.class,
            obligation.requiredPattern());
        assertEquals(
            "x ^ 3 - 1",
            ExpressionFormatter.format(obligation.actualExpression()));
        assertEquals(2, analysis.matchedPatternNodes());
        assertEquals(5, analysis.totalPatternNodes());
    }

    @Test
    void retainsARepeatedPlaceholderConflictForPreparation() {
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.var("A"),
            PatternExpr.var("A"));

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            parser.parseTerm("x + y"),
            RecognitionProfile.exact());

        assertEquals(
            PatternMatchAnalyzer.Status.RESIDUAL,
            analysis.status());
        assertEquals(
            "x",
            ExpressionFormatter.format(analysis.bindings().get("A")));
        assertEquals(1, analysis.residualObligations().size());
        assertEquals(
            PatternMatchAnalyzer.ResidualKind.BINDING_CONFLICT,
            analysis.residualObligations().getFirst().kind());
        assertEquals("1", analysis.residualObligations().getFirst().path());
        assertTrue(analysis.residualObligations().getFirst()
            .unboundPlaceholders().isEmpty());
    }

    @Test
    void retainsALiteralMismatchInsideAMatchedParent() {
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.var("A"),
            PatternExpr.num(0));

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            parser.parseTerm("x + 1"),
            RecognitionProfile.exact());

        assertEquals(
            PatternMatchAnalyzer.Status.RESIDUAL,
            analysis.status());
        assertEquals(
            "x",
            ExpressionFormatter.format(analysis.bindings().get("A")));
        PatternMatchAnalyzer.ResidualObligation obligation =
            analysis.residualObligations().getFirst();
        assertEquals(
            PatternMatchAnalyzer.ResidualKind.LITERAL_MISMATCH,
            obligation.kind());
        assertEquals("1", obligation.path());
        assertEquals("1", ExpressionFormatter.format(
            obligation.actualExpression()));
        assertTrue(obligation.unboundPlaceholders().isEmpty());
    }

    @Test
    void retainsANestedFunctionShapeMismatch() {
        PatternExpr pattern = PatternExpr.fn(
            "sin",
            PatternExpr.fn("cos", PatternExpr.var("A")));

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            parser.parseTerm("sin(tan(x))"),
            RecognitionProfile.exact());

        assertEquals(
            PatternMatchAnalyzer.Status.RESIDUAL,
            analysis.status());
        assertTrue(analysis.bindings().isEmpty());
        PatternMatchAnalyzer.ResidualObligation obligation =
            analysis.residualObligations().getFirst();
        assertEquals(
            PatternMatchAnalyzer.ResidualKind.FUNCTION_SHAPE_MISMATCH,
            obligation.kind());
        assertEquals("0", obligation.path());
        assertEquals(Set.of("A"), obligation.unboundPlaceholders());
        assertEquals(
            "tan(x)",
            ExpressionFormatter.format(obligation.actualExpression()));
    }

    @Test
    void retainsAConsistentRepeatedBindingBeforeAnotherResidual() {
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.var("A"),
                PatternExpr.var("A")),
            PatternExpr.num(0));

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            parser.parseTerm("(x + x) + 1"),
            RecognitionProfile.exact());

        assertEquals(
            PatternMatchAnalyzer.Status.RESIDUAL,
            analysis.status());
        assertEquals(
            "x",
            ExpressionFormatter.format(analysis.bindings().get("A")));
        assertEquals(1, analysis.residualObligations().size());
        assertEquals(
            PatternMatchAnalyzer.ResidualKind.LITERAL_MISMATCH,
            analysis.residualObligations().getFirst().kind());
        assertEquals(4, analysis.matchedPatternNodes());
        assertEquals(5, analysis.totalPatternNodes());
    }

    @Test
    void unrelatedRootShapesRemainAConclusiveNonMatch() {
        PatternExpr pattern = PatternExpr.fn(
            "sin",
            PatternExpr.var("A"));

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            parser.parseTerm("x + 1"),
            RecognitionProfile.exact());

        assertEquals(
            PatternMatchAnalyzer.Status.NOT_MATCHED,
            analysis.status());
        assertTrue(analysis.matches().isEmpty());
        assertTrue(analysis.bindings().isEmpty());
        assertTrue(analysis.residualObligations().isEmpty());
        assertEquals("CONCLUSIVE_PATTERN_NON_MATCH", analysis.detailCode());
    }

    @Test
    void structuralResidualWorkSharesTheMatcherStepBudget() {
        PatternExpr requiredTail = PatternExpr.variable("v1");
        Expr actualTail = new VariableExpr("v1");
        for (int index = 2; index <= 160; index++) {
            requiredTail = PatternExpr.op(
                BinaryOperator.ADD,
                requiredTail,
                PatternExpr.variable("v" + index));
            actualTail = new BinaryExpr(
                actualTail,
                BinaryOperator.ADD,
                new VariableExpr("v" + index));
        }
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.num(0),
            requiredTail);
        Expr expression = new BinaryExpr(
            new NumberExpr(1),
            BinaryOperator.ADD,
            actualTail);
        ExprMatcher.MatchOptions options = new ExprMatcher.MatchOptions(
            EquivalentExpressionProvider.identity(),
            64,
            64,
            10_000);

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            expression,
            RecognitionProfile.exact(),
            options);

        assertEquals(
            PatternMatchAnalyzer.Status.INCONCLUSIVE,
            analysis.status());
        assertEquals(
            "STRUCTURAL_COMPARISON_BUDGET_INCONCLUSIVE",
            analysis.detailCode());
        assertTrue(analysis.diagnostics().stream().anyMatch(diagnostic ->
            diagnostic.code().equals("STRUCTURAL_COMPARISON_LIMIT")));
        assertTrue(analysis.bindings().isEmpty());
        assertTrue(analysis.residualObligations().isEmpty());
        assertTrue(analysis.structuralComparisons() > 0);
        assertTrue(
            analysis.evaluatedSteps() + analysis.structuralComparisons()
                <= options.maxSteps());
    }

    @Test
    void reportsCommutativeOperandLimitAsInconclusive() {
        PatternExpr pattern = PatternExpr.variable("v1");
        for (int index = 2; index <= 9; index++) {
            pattern = PatternExpr.op(
                BinaryOperator.ADD,
                pattern,
                PatternExpr.variable("v" + index));
        }

        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            pattern,
            parser.parseTerm("v9 + v8 + v7 + v6 + v5 + v4 + v3 + v2 + v1"),
            RecognitionProfile.arithmeticAc());

        assertEquals(
            PatternMatchAnalyzer.Status.INCONCLUSIVE,
            analysis.status());
        assertTrue(analysis.inconclusive());
        assertTrue(analysis.diagnostics().stream().anyMatch(diagnostic ->
            diagnostic.code().equals("COMMUTATIVE_OPERAND_LIMIT")));
        assertTrue(analysis.residualObligations().isEmpty());
    }

    @Test
    void rejectsInvalidInputsAndInconsistentAnalysisRecords() {
        PatternExpr pattern = PatternExpr.var("A");
        assertThrows(
            NullPointerException.class,
            () -> analyzer.analyze(
                null,
                parser.parseTerm("x"),
                RecognitionProfile.exact()));
        assertThrows(
            NullPointerException.class,
            () -> analyzer.analyze(
                pattern,
                null,
                RecognitionProfile.exact()));
        assertThrows(
            IllegalArgumentException.class,
            () -> new PatternMatchAnalyzer.Analysis(
                PatternMatchAnalyzer.Status.INCONCLUSIVE,
                java.util.List.of(),
                java.util.Map.of(),
                java.util.List.of(),
                java.util.List.of(),
                0,
                0,
                0,
                0,
                1,
                "MISSING_DIAGNOSTIC"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new PatternMatchAnalyzer.Analysis(
                PatternMatchAnalyzer.Status.NOT_MATCHED,
                java.util.List.of(),
                java.util.Map.of("A", parser.parseTerm("x")),
                java.util.List.of(),
                java.util.List.of(),
                0,
                0,
                0,
                0,
                1,
                "INVALID_BINDING"));
    }
}
