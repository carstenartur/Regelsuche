package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductCandidateValidationTest {
    @Test
    void freshValidationBindsEveryParameterEmittedByTheActualGeneralizer() {
        // Keep both factors in separate structural positions. A literal 3*5
        // normalizes to 15 before mining and would test only one parameter.
        var pattern = new PatternGeneralizer().generalize(List.of(
            observed("first", "15*x+15", "3*(5*x+5)"),
            observed("second", "28*x+28", "4*(7*x+7)"),
            observed("third", "66*x+66", "6*(11*x+11)"))).orElseThrow();
        assertEquals("A*B*x + A*B", pattern.leftPattern());
        assertEquals("A*(B*x + B)", pattern.rightPattern());
        assertTrue(pattern.parameterRelations().contains("N1 = A*B"));
        var freshExpressions = new ArrayList<String>();
        var delegate = new SymPyEquivalenceService();
        EquivalenceService observing = (left, right) -> {
            // Observe real instantiated inputs; the delegate still determines validity.
            if (!left.equals(pattern.leftPattern()) || !right.equals(pattern.rightPattern())) {
                freshExpressions.add(left);
                freshExpressions.add(right);
            }
            return delegate.areEquivalent(left, right);
        };
        assertEquals(CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            new CandidateValidator(observing).proofStatus(pattern));
        assertEquals(12, freshExpressions.size());
        var parser = new ExpressionParser();
        for (String expression : freshExpressions) {
            assertTrue(containsOnlyOrdinaryVariable(parser.parseTerm(expression)), expression);
        }
    }

    @Test
    void aWrongNonzeroSecondFactorFailsTheExistingValidator() {
        var validator = new CandidateValidator(new SymPyEquivalenceService());
        assertFalse(validator.validate(new GeneralizedPattern("A*B", "A*B+B", Map.of(), List.of())));
        assertEquals("right-placeholder-unbound:C does not appear in left pattern",
            new DynamicOperatorCompiler().compile("unbound-product", "v1", "A*B", "A*C").rejectionReason());
    }

    @Test
    void mixedExpressionAbstractionCannotAliasTheSecondNumericParameter() {
        // Synthetic shape observations, not a claim that these functions are equal.
        // Separate argument slots prevent the numeric factors being folded away.
        var pattern = new PatternGeneralizer().generalize(List.of(
            observed("first", "f(15,x)", "f(3,5,x)"),
            observed("second", "f(28,x+1)", "f(4,7,x+1)"),
            observed("third", "f(66,x^2)", "f(6,11,x^2)"))).orElseThrow();
        assertEquals("f(A*D,B)", pattern.leftPattern());
        assertEquals("f(A,D,C)", pattern.rightPattern());
        assertEquals(java.util.Set.of("B", "C"), pattern.expressionPlaceholderValues().keySet());
        assertTrue(pattern.parameterRelations().contains("N3 = D"));
    }

    private static boolean containsOnlyOrdinaryVariable(Expr expression) {
        if (expression instanceof NumberExpr) return true;
        if (expression instanceof VariableExpr variable) return variable.name().equals("x");
        return expression instanceof BinaryExpr binary
            && containsOnlyOrdinaryVariable(binary.left()) && containsOnlyOrdinaryVariable(binary.right());
    }

    private static SuccessfulTransformationPath observed(String id, String source, String target) {
        return new SuccessfulTransformationPath(id, source, target, List.of(source, target),
            List.of("synthetic-product-observation"), new ExpressionScore(100, 0, 0, 0, 0),
            new ExpressionScore(90, 0, 0, 0, 0), false,
            "synthetic fixture: independent verification required", Map.of(), List.of());
    }
}
