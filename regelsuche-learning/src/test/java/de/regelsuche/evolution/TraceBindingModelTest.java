package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceBindingModelTest {
    private static final List<String> GENES = List.of("first", "second");
    private static List<Expr> states(String... expressions) {
        var parser = new ExpressionParser();
        return java.util.Arrays.stream(expressions).map(parser::parseTerm).toList();
    }
    private static List<TraceBindingModel.Trace> traces() {
        return List.of(
            new TraceBindingModel.Trace("one", GENES, states("x+y", "x-y", "x*x")),
            new TraceBindingModel.Trace("two", GENES, states("a+b", "a-b", "a*a")));
    }
    private static TraceBindingModel model(long allowance) {
        return TraceBindingModel.learn(traces(), Set.of(GENES), 32, 100_000, allowance);
    }

    @Test void learnsSharedSubstitutionsFromTwoPathsRatherThanIndependentStates() {
        var model = model(10_000);
        assertEquals(1, model.templates().size());
        var template = model.templates().getFirst();
        assertEquals(List.of("one", "two"), template.trainingIds());
        assertTrue(model.session().matches(template, states("u+v", "u-v", "u*u")));
        assertFalse(model.session().matches(template, states("u+v", "u-v", "v*v")));
        assertTrue(model.formationWork() > 0);
        assertThrows(UnsupportedOperationException.class, () -> template.patterns().clear());
        assertThrows(UnsupportedOperationException.class, () -> model.templates().clear());
    }

    @Test void prefixDoesNotLockAChoiceThatTheNextStateMustReconsider() {
        var model = model(10_000);
        var session = model.session();
        assertEquals(1, session.matching(GENES, states("v+u", "u-v")).size());
        assertTrue(session.matches(model.templates().getFirst(), states("v+u", "u-v", "u*u")));
        assertFalse(session.exhausted());
    }

    @Test void compositeBindingsAndAliasesRetainActualScopedIdentity() {
        var model = model(10_000);
        var scope = new SymbolScope(new UUID(0, 1));
        scope.alias("alias", scope.resolve("y"));
        var source = SymbolicExpression.parse("(x+1)+y", scope);
        var middle = SymbolicExpression.parse("(x+1)-alias", scope).withDisplayName(scope.resolve("y"), "shownY");
        var end = SymbolicExpression.parse("(x+1)*(x+1)", scope);
        assertTrue(model.session().matches(model.templates().getFirst(),
            List.of(source.expression(), middle.expression(), end.expression())));
        var foreign = SymbolicExpression.parse("(x+1)*(x+1)", new SymbolScope(new UUID(0, 2)));
        assertFalse(model.session().matches(model.templates().getFirst(),
            List.of(source.expression(), middle.expression(), foreign.expression())));
    }

    @Test void allAttemptsShareOneWorkAllowanceAndCutoffsAreInconclusive() {
        var completed = model(10_000);
        var full = completed.session();
        assertTrue(full.matches(completed.templates().getFirst(), states("u+v", "u-v", "u*u")));
        long exactWork = full.workUnits();
        var exact = model(exactWork);
        var exactSession = exact.session();
        assertTrue(exactSession.matches(exact.templates().getFirst(), states("u+v", "u-v", "u*u")));
        assertEquals(exactWork, exactSession.workUnits());
        var cut = model(exactWork - 1);
        var cutSession = cut.session();
        assertFalse(cutSession.matches(cut.templates().getFirst(), states("u+v", "u-v", "u*u")));
        assertTrue(cutSession.exhausted());
        assertEquals(exactWork - 1, cutSession.workUnits());
        assertFalse(cutSession.matches(cut.templates().getFirst(), states("u+v", "u-v", "u*u")));
        assertEquals(exactWork - 1, cutSession.workUnits());
        // An earlier successful prefix still consumes the expansion's allowance.
        var shared = exact.session();
        assertFalse(shared.matching(GENES, states("u+v", "u-v")).isEmpty());
        assertFalse(shared.matches(exact.templates().getFirst(), states("u+v", "u-v", "u*u")));
        assertTrue(shared.exhausted());
    }

    @Test void deterministicIdentityBindsEvidenceAndLimits() {
        var reverse = new ArrayList<>(traces());
        Collections.reverse(reverse);
        var first = model(10_000);
        var second = TraceBindingModel.learn(reverse, Set.of(GENES), 32, 100_000, 10_000);
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertNotEquals(first.contentHash(), model(10_001).contentHash());
        assertTrue(first.toCanonicalJson().contains("regelsuche.rule-pattern-sequence/v1"));
    }

    @Test void aSingleTraceUnrelatedGenesAndUnstructuredStartsDoNotFormTemplates() {
        assertTrue(TraceBindingModel.learn(List.of(traces().getFirst()), Set.of(GENES),
            32, 100_000, 10_000).templates().isEmpty());
        assertTrue(TraceBindingModel.learn(traces(), Set.of(List.of("different", "second")),
            32, 100_000, 10_000).templates().isEmpty());
        assertTrue(model(10_000).session().matching(List.of("second", "first"), states("u+v", "u-v")).isEmpty());
        var different = List.of(traces().getFirst(),
            new TraceBindingModel.Trace("other", GENES, states("a*b", "a-b", "a*a")));
        assertTrue(TraceBindingModel.learn(different, Set.of(GENES), 32, 100_000, 10_000).templates().isEmpty());
    }

    @Test void formationAndTraceBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> TraceBindingModel.learn(traces(), Set.of(GENES), 32, 1, 10_000));
        assertThrows(IllegalArgumentException.class, () -> TraceBindingModel.learn(traces(), Set.of(GENES), 0, 1000, 1000));
        assertThrows(IllegalArgumentException.class, () -> TraceBindingModel.learn(traces(), Set.of(GENES), 32, 1000, 0));
        assertThrows(IllegalArgumentException.class, () -> new TraceBindingModel.Trace("one", GENES, states("x")));
        assertThrows(IllegalArgumentException.class, () -> TraceBindingModel.learn(
            List.of(traces().getFirst(), traces().getFirst()), Set.of(GENES), 32, 1000, 1000));
    }

    @Test void noNewFreePlaceholderMayAppearOnlyAfterTheInitialState() {
        var unrelated = List.of(
            new TraceBindingModel.Trace("one", GENES, states("x+y", "x-y", "z*z")),
            new TraceBindingModel.Trace("two", GENES, states("a+b", "a-b", "c*c")));
        assertTrue(TraceBindingModel.learn(unrelated, Set.of(GENES), 32, 100_000, 10_000).templates().isEmpty(),
            "later free placeholders would accept a different mathematical object without a shared input binding");
    }
}
