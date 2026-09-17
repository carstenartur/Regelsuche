package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.DIV;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guard against dropping side conditions in typed generation or structural replay. */
class AstRewriteTransportAssumptionsTest {
    private final ExpressionParser parser = new ExpressionParser();

    private static RewriteRule conditionalRule() {
        var a = PatternExpr.var("A");
        return new PatternRewriteRule("conditional-self-division", PatternExpr.op(DIV, a, a), PatternExpr.num(1)) {
            @Override public List<Assumption> assumptions(Expr source) {
                return List.of(Assumption.nonZero(ExpressionFormatter.format(((BinaryExpr) source).right())));
            }
        };
    }

    private static AstRewriteTransport transport(RewriteRule rule) {
        return new AstRewriteTransport(List.of(rule), 64, 128);
    }

    @Test void generationRetainsTheConditionForTheActualRewrittenSubtree() {
        var engine = transport(conditionalRule());
        Expr source = parser.parseTerm("f(x/x,y/y)");
        var steps = engine.generate(source);
        assertEquals(2, steps.size());
        assertEquals(parser.parseTerm("f(1,y/y)"), steps.get(0).target());
        assertEquals(List.of("x != 0"), steps.get(0).assumptions());
        assertEquals(parser.parseTerm("f(x/x,1)"), steps.get(1).target());
        assertEquals(List.of("y != 0"), steps.get(1).assumptions());
        assertEquals(steps.getFirst().target(), engine.replay(source, List.of(steps.getFirst())));
    }

    @Test void replayRejectsRemovedChangedAndAddedConditions() {
        var engine = transport(conditionalRule());
        Expr source = parser.parseTerm("x/x");
        var step = engine.generate(source).getFirst();
        assertEquals(List.of("x != 0"), step.assumptions());
        for (var altered : List.<List<String>>of(List.of(), List.of("y != 0"), List.of("x > 0"),
                List.of("x != 0", "y != 0"))) {
            var forged = withAssumptions(step, altered);
            assertThrows(IllegalArgumentException.class, () -> engine.replay(source, List.of(forged)), altered.toString());
        }
    }

    @Test void replayRejectsARuleWhoseConditionsChangedWithoutChangingItsIdOrTarget() {
        Expr source = parser.parseTerm("x/x");
        var recorded = transport(conditionalRule()).generate(source).getFirst();
        var a = PatternExpr.var("A");
        var unguarded = new PatternRewriteRule(recorded.rule(), PatternExpr.op(DIV, a, a), PatternExpr.num(1));
        assertEquals(recorded.target(), transport(unguarded).generate(source).getFirst().target());
        assertThrows(IllegalArgumentException.class, () -> transport(unguarded).replay(source, List.of(recorded)));
    }

    @Test void replayPreservesEachStepsConditionsAcrossASequence() {
        var engine = transport(conditionalRule());
        Expr source = parser.parseTerm("f(x/x,y/y)");
        var first = engine.generate(source).getFirst();
        var second = engine.generate(first.target()).getFirst();
        assertEquals(List.of("x != 0"), first.assumptions());
        assertEquals(List.of("y != 0"), second.assumptions());
        assertEquals(parser.parseTerm("f(1,1)"), engine.replay(source, List.of(first, second)));
        assertThrows(IllegalArgumentException.class,
            () -> engine.replay(source, List.of(first, withAssumptions(second, first.assumptions()))));
    }

    @Test void aNonemptyConditionListIsDefensivelyCopiedAndNormalized() {
        var engine = transport(conditionalRule());
        var step = engine.generate(parser.parseTerm("x/x")).getFirst();
        var input = new ArrayList<>(List.of(" x != 0 ", "x != 0"));
        var copied = withAssumptions(step, input);
        input.clear();
        assertEquals(List.of("x != 0"), copied.assumptions());
        assertThrows(UnsupportedOperationException.class, () -> copied.assumptions().clear());
        assertEquals(step.target(), engine.replay(step.source(), List.of(copied)));
    }

    private static AstRewriteTransport.Step withAssumptions(AstRewriteTransport.Step step, List<String> assumptions) {
        return new AstRewriteTransport.Step(step.source(), step.target(), step.rule(), step.kind(),
            step.mayIncreaseComplexity(), step.estimatedCostDelta(), step.equivalencePreservingByConstruction(),
            assumptions, step.packId(), step.license());
    }
}
