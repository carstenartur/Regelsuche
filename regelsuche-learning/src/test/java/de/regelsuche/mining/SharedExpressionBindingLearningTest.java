package de.regelsuche.mining;

import static de.regelsuche.evolution.ExactPolynomialPatternIdentityVerifier.Status.PROVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactPolynomialPatternVerificationService;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedExpressionBindingLearningTest {
    @Test
    void repeatedTrainingTuplesFormOneSharedExecutableParameter() {
        var pattern = squareContraction();
        assertEquals("B*B", pattern.leftPattern());
        assertEquals("B^2", pattern.rightPattern());
        assertEquals(Set.of("B"), pattern.expressionPlaceholderValues().keySet());
        assertEquals(List.of("x", "x + 1", "x + 2"),
            pattern.expressionPlaceholderValues().get("B"));
    }

    @Test
    void learnedContractionIsExactlyVerifiedAndTransferredByTheExistingOperator() {
        var pattern = squareContraction();
        assertEquals(PROVED, new ExactPolynomialPatternVerificationService()
            .verify(pattern.leftPattern(), pattern.rightPattern()).status());
        var compilation = new DynamicOperatorCompiler().compile(
            "shared-square", "development-v1", pattern.leftPattern(), pattern.rightPattern());
        assertTrue(compilation.isSuccess(), compilation.rejectionReason());
        var operator = compilation.operator().orElseThrow();
        var candidates = operator.generateCandidates("(u + v) * (u + v)");
        assertEquals(1, candidates.size());
        var parser = new ExpressionParser();
        assertEquals(parser.parseTerm("(u + v)^2"),
            parser.parseTerm(candidates.getFirst().transformedExpression()));
        assertTrue(operator.generateCandidates("(u + v) * (u - v)").isEmpty(),
            "A repeated placeholder must not match two different operands");
    }

    @Test
    void equalValueSetsInDifferentTrainingOrderDoNotAlias() {
        var pattern = new PatternGeneralizer().generalize(List.of(
            observed("first", "pair(x,x+1)", "x"),
            observed("second", "pair(x+1,x)", "x+1"),
            observed("third", "pair(x+2,x+2)", "x+2")
        )).orElseThrow();
        assertEquals("pair(B,C)", pattern.leftPattern());
        assertEquals("B", pattern.rightPattern());
        assertEquals(Set.of("B", "C"), pattern.expressionPlaceholderValues().keySet());
        assertEquals(Set.copyOf(pattern.expressionPlaceholderValues().get("B")),
            Set.copyOf(pattern.expressionPlaceholderValues().get("C")));
        assertNotEquals(pattern.expressionPlaceholderValues().get("B"),
            pattern.expressionPlaceholderValues().get("C"));
    }

    @Test
    void sharedContextAndTheSecondNumericParameterRemainDistinct() {
        // A shape-learning fixture, not a proof about the uninterpreted function f.
        var pattern = new PatternGeneralizer().generalize(List.of(
            observed("first", "f(15,x)", "f(3,5,x)"),
            observed("second", "f(28,x+1)", "f(4,7,x+1)"),
            observed("third", "f(66,x^2)", "f(6,11,x^2)")
        )).orElseThrow();
        assertEquals("f(A*C,B)", pattern.leftPattern());
        assertEquals("f(A,C,B)", pattern.rightPattern());
        assertEquals(Set.of("B"), pattern.expressionPlaceholderValues().keySet());
        assertTrue(pattern.parameterRelations().contains("N3 = C"));
        assertTrue(new DynamicOperatorCompiler().compile("shared-context", "development-v1",
            pattern.leftPattern(), pattern.rightPattern()).isSuccess(),
            "Every generated right-hand placeholder must remain bound on the left");
    }

    private static GeneralizedPattern squareContraction() {
        return new PatternGeneralizer().generalize(List.of(
            observed("plain", "x*x", "x^2"),
            observed("shift-one", "(x+1)*(x+1)", "(x+1)^2"),
            observed("shift-two", "(x+2)*(x+2)", "(x+2)^2")
        )).orElseThrow();
    }

    private static SuccessfulTransformationPath observed(String id, String source, String target) {
        return new SuccessfulTransformationPath(id, source, target, List.of(source, target),
            List.of("synthetic-shape-observation"), new ExpressionScore(100, 0, 0, 0, 0),
            new ExpressionScore(90, 0, 0, 0, 0), false,
            "synthetic fixture: independent verification required", Map.of(), List.of());
    }
}
