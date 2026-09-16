package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.mining.RulePatternParser;
import de.regelsuche.parse.ExpressionParser;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TraceBindingLiteralContractTest {
    private static final List<String> GENES = List.of("first", "second");

    private static List<Expr> states(String... inputs) {
        var parser = new ExpressionParser();
        return Arrays.stream(inputs).map(parser::parseTerm).toList();
    }

    private static TraceBindingModel model(List<Expr> one, List<Expr> two) {
        return TraceBindingModel.learn(List.of(new TraceBindingModel.Trace("one", GENES, one),
            new TraceBindingModel.Trace("two", GENES, two)), Set.of(GENES), 32, 100_000, 100_000);
    }

    @Test
    void fixedLiteralSeedsAreCopiedAndCannotBeReboundByTheCaller() {
        var model = model(states("x+1.25", "x-1.25", "x*1.25"), states("a+1.25", "a-1.25", "a*1.25"));
        var original = model.templates().getFirst();
        var seeds = new HashMap<>(original.fixedBindings());
        assertEquals(1, seeds.size());
        var copy = new TraceBindingModel.Template(original.sequence(), original.patterns(),
            original.trainingIds(), original.trainingHashes(), seeds);
        seeds.clear();
        assertEquals(1, copy.fixedBindings().size());
        assertThrows(UnsupportedOperationException.class, () -> copy.fixedBindings().clear());
        assertTrue(model.session().matches(copy, states("u+1.25", "u-1.25", "u*1.25")));
        assertFalse(model.session().matches(copy, states("u+v", "u-v", "u*v")));
        assertThrows(IllegalArgumentException.class, () -> new TraceBindingModel.Template(
            original.sequence(), original.patterns(), original.trainingIds(), original.trainingHashes(),
            Map.of("K0", new ExpressionParser().parseTerm("v"))));
    }

    @Test
    void aKnownLiteralMayFirstAppearAfterTheInitialStateWithoutBecomingFree() {
        var model = model(states("x+x", "x+1.25", "x-1.25"), states("a+a", "a+1.25", "a-1.25"));
        assertEquals(1, model.templates().size());
        var template = model.templates().getFirst();
        assertTrue(model.session().matches(template, states("u+u", "u+1.25", "u-1.25")));
        assertFalse(model.session().matches(template, states("u+u", "u+1.5", "u-1.5")));
    }

    @Test
    void distinctFixedLiteralsUseDistinctSeedSlots() {
        var model = model(states("x+1.25", "x-1.5", "x*1.25"), states("a+1.25", "a-1.5", "a*1.25"));
        var template = model.templates().getFirst();
        assertEquals(2, template.fixedBindings().size());
        assertTrue(model.session().matches(template, states("u+1.25", "u-1.5", "u*1.25")));
        assertFalse(model.session().matches(template, states("u+1.25", "u-1.25", "u*1.25")));
    }

    @Test
    void unequalTrainingNumbersCanStillFormAConsistentFreeParameter() {
        var model = model(states("x+1.25", "x-1.25", "x*1.25"), states("a+1.5", "a-1.5", "a*1.5"));
        var template = model.templates().getFirst();
        assertTrue(template.fixedBindings().isEmpty());
        assertTrue(model.session().matches(template, states("u+v", "u-v", "u*v")));
        assertFalse(model.session().matches(template, states("u+v", "u-w", "u*v")));
    }

    @Test
    void orderedFunctionIdentityIncludesArityAndArgumentBoundaries() {
        var expressions = new ExpressionParser();
        var patterns = new RulePatternParser();
        assertNotEquals(TraceBindingIdentity.expression(expressions.parseTerm("f(f(x),y)")),
            TraceBindingIdentity.expression(expressions.parseTerm("f(f(x,y))")));
        assertNotEquals(TraceBindingIdentity.pattern(patterns.parse("f(f(P0),P1)")),
            TraceBindingIdentity.pattern(patterns.parse("f(f(P0,P1))")));
        assertNotEquals(TraceBindingIdentity.expression(expressions.parseTerm("f(x,y)")),
            TraceBindingIdentity.expression(expressions.parseTerm("f(y,x)")));
        assertNotEquals(TraceBindingIdentity.pattern(patterns.parse("f(P0,1)")),
            TraceBindingIdentity.pattern(patterns.parse("g(P0,1)")));
    }

    @Test
    void equalExactValuesHaveTheSameLiteralIdentityWithoutRounding() {
        var parser = new ExpressionParser();
        assertEquals(TraceBindingIdentity.expression(parser.parseTerm("1.250")),
            TraceBindingIdentity.expression(parser.parseTerm("1.25")));
        assertNotEquals(TraceBindingIdentity.expression(parser.parseTerm("1.25")),
            TraceBindingIdentity.expression(parser.parseTerm("1.250000000000000001")));
    }
}
