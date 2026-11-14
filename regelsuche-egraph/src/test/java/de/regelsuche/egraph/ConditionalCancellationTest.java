package de.regelsuche.egraph;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionEvaluatorPortfolio;
import de.regelsuche.assumption.AssumptionTruthValue;
import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalCancellationTest {
    private static final RewriteRule CANCEL = AstRewriteTransformationEngine.defaultRules().stream()
        .filter(rule -> rule.id().equals("ast_cancel_division_factor")).findFirst().orElseThrow();

    @Test
    void contextBoundCancellationAtANestedRootRetainsBothGuardAndConcreteWitness() {
        var input = parse("f((y*x)/x)");
        var context = List.of(Assumption.positive("x"));
        var outcome = new EqualitySaturation(List.of(CANCEL)).saturateUnderAssumptions(
            input, context, node -> 1);
        assertEquals(parse("f(y)"), outcome.result().expression());
        assertEquals(context, outcome.assumptions());
        assertTrue(outcome.decisions().stream().anyMatch(decision -> decision.applied()
            && decision.before().equals(parse("(y*x)/x")) && decision.after().equals(parse("y"))
            && decision.evaluation().requiredAssumption().equals(Assumption.nonZero("x"))
            && decision.evaluation().evaluatorProfileHash().equals(AssumptionEvaluatorPortfolio.localOnly().contentHash())
            && decision.evaluation().result() == AssumptionTruthValue.TRUE));
    }

    @Test
    void unknownAndFalseCannotFireOrLeakAcrossInvocations() {
        var runner = new EqualitySaturation(List.of(CANCEL));
        var input = parse("(x*y)/x");
        assertEquals(parse("y"), runner.saturateUnderAssumptions(input, List.of(Assumption.nonZero("x")), n -> 1)
            .result().expression());
        var missing = runner.saturateUnderAssumptions(input, List.of(Assumption.positive("z")), n -> 1);
        assertEquals(input, missing.result().expression());
        assertEquals(SaturationStats.Reason.UNDECIDED_GUARDS, missing.result().stats().stopReason());
        assertEquals(AssumptionTruthValue.UNKNOWN, missing.decisions().getFirst().evaluation().result());

        var zeroInput = parse("(y*0)/0");
        var zero = runner.saturateUnderAssumptions(zeroInput, List.of(), n -> 1);
        assertEquals(zeroInput, zero.result().expression());
        assertEquals(AssumptionTruthValue.FALSE, zero.decisions().getFirst().evaluation().result());
        assertFalse(zero.decisions().getFirst().applied());
        assertEquals(SaturationStats.Reason.FIX_POINT, zero.result().stats().stopReason());
    }

    @Test
    void exactRationalFactConflictingWithAssumedNonzeroFailsClosed() {
        var runner = new EqualitySaturation(List.of(CANCEL));
        var input = parse("(0*y)/0");
        var result = runner.saturateUnderAssumptions(input, List.of(Assumption.nonZero("0")), n -> 1);
        var evaluation = result.decisions().getFirst().evaluation();
        assertTrue(evaluation.conflicting());
        assertEquals(AssumptionTruthValue.UNKNOWN, evaluation.result());
        assertFalse(result.decisions().getFirst().applied());
        assertEquals(2, evaluation.evidence().size());
        assertEquals(input, result.result().expression());
        var positive = runner.saturateUnderAssumptions(parse("(2*y)/2"), List.of(), n -> 1);
        assertEquals(parse("y"), positive.result().expression());
    }

    @Test
    void formattedCompoundCannotBorrowTheAssumptionOfASymbolWithTheSameLabel() {
        var compound = parse("x+y");
        String label = de.regelsuche.parse.ExpressionFormatter.format(compound);
        var input = parse("((x+y)*z)/(x+y)");
        var result = new EqualitySaturation(List.of(CANCEL)).saturateUnderAssumptions(input,
            List.of(Assumption.nonZero(label)), n -> 1);
        assertEquals(input, result.result().expression());
        assertTrue(result.decisions().getFirst().evaluation().isInconclusive());
        assertEquals(de.regelsuche.assumption.AssumptionEvaluationDisposition.UNSUPPORTED,
            result.decisions().getFirst().evaluation().evidence().getFirst().disposition());
    }

    @Test
    void equalityExposedByAnUnconditionalRuleEnablesTheNativeRepeatedBinding() {
        var removeZero = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
        var input = parse("((x+0)*y)/x");
        var outcome = new EqualitySaturation(List.of(removeZero, CANCEL))
            .saturateUnderAssumptions(input, List.of(Assumption.nonZero("x")), n -> 1);
        assertEquals(parse("y"), outcome.result().expression());
        assertTrue(outcome.decisions().getFirst().applied());
        assertFalse(outcome.result().provenance().hasExtractionProof());
    }

    @Test
    void conditionsFromOtherRulesRemainUnresolved() {
        var otherRule = new de.regelsuche.rules.RationalRules.MultiplyFractionsRule();
        var input = parse("(a/b)*(c/d)");
        var outcome = new EqualitySaturation(List.of(otherRule)).saturateUnderAssumptions(input,
            List.of(Assumption.nonZero("b"), Assumption.nonZero("d")), n -> 1);
        assertEquals(input, outcome.result().expression());
        assertTrue(outcome.decisions().isEmpty());
        assertEquals(SaturationStats.Reason.UNDECIDED_GUARDS, outcome.result().stats().stopReason());
    }

    @Test
    void decisionsAndGraphSizeAreBounded() {
        var runner = new EqualitySaturation(List.of(CANCEL));
        var arguments = new java.util.ArrayList<Expr>();
        for (int i = 0; i <= RationalCancellation.MAX_DECISIONS; i++) arguments.add(parse("(x*y" + i + ")/x"));
        Expr input = new de.regelsuche.ast.FunctionExpr("many", arguments);
        var outcome = runner.saturateUnderAssumptions(input, List.of(), node -> 1);
        assertEquals(RationalCancellation.MAX_DECISIONS, outcome.decisions().size());
        assertEquals(SaturationStats.Reason.GUARD_BUDGET, outcome.result().stats().stopReason());
        assertEquals(input, outcome.result().expression());
        var small = new EqualitySaturation(List.of(CANCEL), new EqualitySaturation.Config(4, 2));
        assertThrows(IllegalArgumentException.class, () -> small.saturateUnderAssumptions(input, List.of(), n -> 1));
    }

    private static Expr parse(String text) {
        return new ExpressionParser().parse(new InputRequest(InputType.TERM, text)).terms().getFirst();
    }
}
