package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.mining.RulePatternParser;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Applicability/evidence regressions, not claims that these synthetic paths are proofs. */
@Timeout(30)
class TraceBindingModelIntegrityTest {
    private static final List<String> GENES = List.of("first", "second");

    private static List<Expr> states(String... inputs) {
        var parser = new ExpressionParser();
        return java.util.Arrays.stream(inputs).map(parser::parseTerm).toList();
    }

    private static TraceBindingModel learn(List<TraceBindingModel.Trace> traces) {
        return TraceBindingModel.learn(traces, Set.of(GENES), 32, 100_000, 100_000);
    }

    private static TraceBindingModel literalModel(String literal) {
        return learn(List.of(
            new TraceBindingModel.Trace("one", GENES, states("x+" + literal, "x-" + literal, "x*" + literal)),
            new TraceBindingModel.Trace("two", GENES, states("a+" + literal, "a-" + literal, "a*" + literal))));
    }

    private static void assertFixedLiteral(String literal, String different) {
        var model = literalModel(literal);
        assertEquals(1, model.templates().size(), "retain supported whole-state learning, not just reject the path");
        var template = model.templates().getFirst();
        assertTrue(model.session().matches(template, states("u+" + literal, "u-" + literal, "u*" + literal)));
        assertFalse(model.session().matches(template, states("u+" + different, "u-" + different, "u*" + different)),
            "equal TRAIN literals must remain fixed even when the alternative is consistent across every state");
        assertFalse(model.session().matches(template, states("u+v", "u-v", "u*v")),
            "a fixed literal must not silently become a free symbolic parameter");
    }

    @Test
    void preservesSharedLargeIntegerLiteralsWithoutDiscardingTheTemplate() {
        assertFixedLiteral("2147483648", "2147483649");
        assertFixedLiteral("922337203685477580812345", "922337203685477580812346");
    }

    @Test
    void preservesSharedExactDecimalLiteralsWithoutDiscardingTheTemplate() {
        assertFixedLiteral("1.25", "1.5");
        assertFixedLiteral("0.000000000000000001", "0.000000000000000002");
    }

    @Test
    void smallFixedIntegersKeepTheirExistingBehavior() {
        assertFixedLiteral("7", "8");
    }

    @Test
    void differentFixedLiteralsHaveDifferentStructuralIdentities() {
        assertNotEquals(literalModel("2147483648").templates().getFirst().structuralJson(),
            literalModel("2147483649").templates().getFirst().structuralJson());
        assertNotEquals(literalModel("1.25").templates().getFirst().structuralJson(),
            literalModel("1.5").templates().getFirst().structuralJson());
    }

    @Test
    void patternIdentityPreservesAdditionAndMultiplicationGrouping() {
        for (String operator : List.of("+", "*")) {
            var parser = new RulePatternParser();
            var left = parser.parse("(P0" + operator + "P1)" + operator + "P2");
            var right = parser.parse("P0" + operator + "(P1" + operator + "P2)");
            var a = new TraceBindingModel.Template(GENES, List.of(left, left, left), List.of("one"), List.of("hash"));
            var b = new TraceBindingModel.Template(GENES, List.of(right, right, right), List.of("one"), List.of("hash"));
            assertNotEquals(a.structuralJson(), b.structuralJson(), "structural grouping must remain visible for " + operator);
        }
    }

    @Test
    void traceEvidencePreservesAdditionAndMultiplicationGrouping() {
        for (String operator : List.of("+", "*")) {
            String left = "(x" + operator + "y)" + operator + "z";
            String right = "x" + operator + "(y" + operator + "z)";
            var a = new TraceBindingModel.Trace("same", GENES, states(left, left, left));
            var b = new TraceBindingModel.Trace("same", GENES, states(right, right, right));
            assertNotEquals(a.evidenceHash(), b.evidenceHash(), "evidence must bind the real AST for " + operator);
        }
    }

    @Test
    void everyMergedSupportStillMatchesTheRetainedStructuralTemplate() {
        var traces = List.of(
            new TraceBindingModel.Trace("one", GENES, states("(a+b)+c", "(a+b)+c", "(a+b)+c")),
            new TraceBindingModel.Trace("two", GENES, states("(d+e)+f", "(d+e)+f", "(d+e)+f")),
            new TraceBindingModel.Trace("three", GENES, states("g+(h+i)", "g+(h+i)", "g+(h+i)")),
            new TraceBindingModel.Trace("four", GENES, states("j+(k+l)", "j+(k+l)", "j+(k+l)")));
        var model = learn(traces);
        assertFalse(model.templates().isEmpty());
        for (var template : model.templates()) {
            for (String id : template.trainingIds()) {
                var trace = traces.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
                assertTrue(model.session().matches(template, trace.states()),
                    "retained template claims support from a structurally incompatible trace " + id);
            }
        }
    }

    @Test
    void literalFormationIsDeterministicUnderTrainingOrder() {
        var traces = new ArrayList<>(List.of(
            new TraceBindingModel.Trace("one", GENES, states("x+1.25", "x-1.25", "x*1.25")),
            new TraceBindingModel.Trace("two", GENES, states("a+1.25", "a-1.25", "a*1.25"))));
        var first = learn(traces);
        Collections.reverse(traces);
        assertEquals(first.toCanonicalJson(), learn(traces).toCanonicalJson());
    }

    @Test
    void traceEvidenceRetainsScopedSymbolIdentity() {
        var one = SymbolicExpression.parse("x+y", new SymbolScope(new UUID(0, 1))).expression();
        var two = SymbolicExpression.parse("x+y", new SymbolScope(new UUID(0, 2))).expression();
        var a = new TraceBindingModel.Trace("same", GENES, List.of(one, one, one));
        var b = new TraceBindingModel.Trace("same", GENES, List.of(two, two, two));
        assertNotEquals(a.evidenceHash(), b.evidenceHash());
    }
}
