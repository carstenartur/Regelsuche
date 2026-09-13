package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.calculus.CalculusDerivativeRules;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecognitionTheoryDomainTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final List<RewriteRule> rules = CalculusDerivativeRules.rules();
    private final RecognitionTheory theory = new RecognitionTheory(rules);
    private final RecognitionProfile profile = RecognitionProfile.exact().withRecognitionRules(
        rules.stream().map(RewriteRule::id).collect(Collectors.toSet()), 3);

    @ParameterizedTest
    @ValueSource(strings = {"ln(x)", "log(x)", "x^0", "x^0.5", "x^-2", "abs(x) - abs(x)"})
    void expressionOnlyRepresentativesCannotDropDerivativeDomains(String body) {
        Expr derivative = CalculusDerivativeRules.derivative(parser.parseTerm(body), "x");

        assertEquals(List.of(derivative), theory.representatives(derivative, profile));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "5", "x^1", "x^2", "sin(x)", "exp(x)", "x + 1"})
    void unconditionalApplicationsOfGuardCapableRulesRemainAvailable(String body) {
        Expr derivative = CalculusDerivativeRules.derivative(parser.parseTerm(body), "x");

        assertTrue(theory.representatives(derivative, profile).size() > 1);
    }

    @Test
    void patternAuthorityCannotTreatAGuardedDerivativeAsAnUnconditionalReciprocal() {
        Expr derivative = parser.parseTerm("diff(ln(x), x)");
        PatternExpr reciprocal = PatternExpr.op(BinaryOperator.DIV,
            PatternExpr.num(1), PatternExpr.variable("x"));
        var analysis = new PatternMatchAnalyzer().analyze(reciprocal, derivative, profile,
            ExprMatcher.MatchOptions.defaults().withRepresentativeProvider(theory));

        assertTrue(analysis.matches().isEmpty());
        assertNotEquals(PatternMatchAnalyzer.Status.MATCH_MODULO_THEORY, analysis.status());
    }
}
