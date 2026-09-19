package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PatternGeneralizerSharedStructureTest {
    private final PatternGeneralizer generalizer = new PatternGeneralizer();

    @Test
    void decomposesACommonPowerAndReusesTheBaseAcrossBothSides() {
        var pattern = generalizer.generalize(List.of(
            observed("x^2", "x*x"),
            observed("(x+1)^2", "(x+1)*(x+1)"),
            observed("(x+2)^2", "(x+2)*(x+2)"))).orElseThrow();
        assertEquals("B^2", pattern.leftPattern());
        assertEquals("B*B", pattern.rightPattern());
        assertEquals(1, pattern.expressionPlaceholderValues().size());
        assertTrue(new DynamicOperatorCompiler().compile(
            "shared-base", "test", pattern.leftPattern(), pattern.rightPattern()).isSuccess());
    }

    @Test
    void learnedRepeatedOperandPatternTransfersAndRejectsDifferentOperands() {
        var pattern = generalizer.generalize(List.of(
            observed("x*x", "x^2"),
            observed("(x+1)*(x+1)", "(x+1)^2"),
            observed("(x+2)*(x+2)", "(x+2)^2"))).orElseThrow();
        var verifier = new de.regelsuche.evolution.ExactPolynomialPatternVerificationService();
        assertTrue(verifier.verify(pattern.leftPattern(), pattern.rightPattern()).proved());
        var operator = new DynamicOperatorCompiler().compile(
            "learned-shared-operand", "development", pattern.leftPattern(), pattern.rightPattern())
            .operator().orElseThrow();
        String source = "(u+3*v)*(u+3*v)";
        var candidates = operator.generateCandidates(source);
        assertEquals(1, candidates.size());
        assertTrue(verifier.verify(source, candidates.getFirst().transformedExpression()).proved());
        assertTrue(candidates.getFirst().transformedExpression().contains("^ 2"));
        assertTrue(operator.generateCandidates("(u+3*v)*(u+4*v)").isEmpty());
    }

    @Test
    void retainsACommonFunctionAndAllocatesNumericParametersAroundSharedExpressions() {
        var pattern = generalizer.generalize(List.of(
            observed("f(15,x)", "f(3,5,x)"),
            observed("f(28,x+1)", "f(4,7,x+1)"),
            observed("f(66,x^2)", "f(6,11,x^2)"))).orElseThrow();
        assertEquals("f(A*C,B)", pattern.leftPattern());
        assertEquals("f(A,C,B)", pattern.rightPattern());
        assertEquals(java.util.Set.of("B"), pattern.expressionPlaceholderValues().keySet());
        assertTrue(pattern.parameterRelations().contains("N3 = C"));
    }

    @Test
    void decomposesCommonAncestorsUntilOnlyTheDifferingSubtreeIsAbstracted() {
        var pattern = generalizer.generalize(List.of(
            observed("outer(inner(x))", "outer(x)"),
            observed("outer(inner(x+1))", "outer(x+1)"))).orElseThrow();
        assertEquals("outer(inner(B))", pattern.leftPattern());
        assertEquals("outer(B)", pattern.rightPattern());
    }

    @Test
    void differentFunctionNamesRemainDifferentConstructors() {
        var pattern = generalizer.generalize(List.of(
            observed("f(x)", "f(x)^2"),
            observed("g(x)", "g(x)^2"))).orElseThrow();
        assertEquals("B", pattern.leftPattern());
        assertEquals("B^2", pattern.rightPattern());
    }

    @Test
    void differentAritiesAreAbstractedWithoutDroppingArguments() {
        var pattern = generalizer.generalize(List.of(
            observed("f(x)", "f(x)^2"),
            observed("f(x,x+1)", "f(x,x+1)^2"))).orElseThrow();
        assertEquals("B", pattern.leftPattern());
        assertEquals("B^2", pattern.rightPattern());
    }

    @Test
    void distinctVectorsWithTheSameValueSetMustNotShareBindings() {
        var pattern = generalizer.generalize(List.of(
            observed("f(x,x)", "g(x,x)"),
            observed("f(x+1,x+1)", "g(x+1,x+1)"),
            observed("f(x,x+1)", "g(x,x+1)"))).orElseThrow();
        // Both columns display {x, x+1}, but the third observation differs.
        assertEquals("f(B,C)", pattern.leftPattern());
        assertEquals("g(B,C)", pattern.rightPattern());
        assertEquals(2, pattern.expressionPlaceholderValues().size());
    }

    @Test
    void orderingTrainingRowsDoesNotChangeSharedBindingConstraints() {
        var paths = List.of(observed("x^2", "x*x"),
            observed("(x+1)^2", "(x+1)*(x+1)"),
            observed("(x+2)^2", "(x+2)*(x+2)"));
        var forward = generalizer.generalize(paths).orElseThrow();
        var backward = generalizer.generalize(paths.reversed()).orElseThrow();
        assertEquals(forward.leftPattern(), backward.leftPattern());
        assertEquals(forward.rightPattern(), backward.rightPattern());
    }

    @Test
    void repeatedObservationsDoNotConsumeTheLimitedPlaceholderNamespace() {
        String first = IntStream.range(0, 30).mapToObj(i -> "x").collect(Collectors.joining(","));
        String second = IntStream.range(0, 30).mapToObj(i -> "x+1").collect(Collectors.joining(","));
        var pattern = generalizer.generalize(List.of(
            observed("f(" + first + ")", "g(" + first + ")"),
            observed("f(" + second + ")", "g(" + second + ")"))).orElseThrow();
        assertEquals("f(" + IntStream.range(0, 30).mapToObj(i -> "B").collect(Collectors.joining(",")) + ")",
            pattern.leftPattern());
        assertEquals(1, pattern.expressionPlaceholderValues().size());
    }

    @Test
    void namespaceExhaustionDoesNotEmitPunctuationAsAPlaceholder() {
        String first = IntStream.range(0, 26).mapToObj(i -> "x").collect(Collectors.joining(","));
        String second = IntStream.rangeClosed(1, 26).mapToObj(i -> "x+" + i).collect(Collectors.joining(","));
        assertTrue(generalizer.generalize(List.of(
            observed("f(" + first + ")", "g(" + first + ")"),
            observed("f(" + second + ")", "g(" + second + ")"))).isEmpty());
    }

    @Test
    void bindingStateIsLocalToOneGeneralization() {
        var paths = List.of(observed("x^2", "x*x"),
            observed("(x+1)^2", "(x+1)*(x+1)"));
        var before = generalizer.generalize(paths).orElseThrow();
        generalizer.generalize(List.of(observed("f(x)", "g(x)"), observed("f(x+2)", "g(x+2)")));
        assertEquals(before, generalizer.generalize(paths).orElseThrow());
    }

    private static SuccessfulTransformationPath observed(String source, String target) {
        return new SuccessfulTransformationPath(source + "->" + target, source, target,
            List.of(source, target), List.of("synthetic-observation"),
            new ExpressionScore(100, 0, 0, 0, 0), new ExpressionScore(90, 0, 0, 0, 0),
            false, "hypothesis-formation fixture, not a proof", Map.of(), List.of());
    }
}
