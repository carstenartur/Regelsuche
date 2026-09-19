package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Structural resource contracts; fixtures are not mathematical identities. */
class TypedOutputPatternLimitTest {
    @Test void exactMaximumInputIsAcceptedAndNextNodeRejected() {
        assertEquals(1024, TypedPatternGeneralizer.MAXIMUM_NODES);
        var pattern = learn(false, false);
        Expr z = v("z");
        Expr w = v("w");
        // 1 bundle + 2 f + 2 keep + (1 pad + 1018 leaves) = 1024.
        FunctionExpr exact = f("bundle", f("f", z), f("keep", w), wide("pad", 1018, z));
        var result = pattern.find(exact, 20);
        assertTrue(result.complete());
        assertEquals(1, result.applications().size());
        assertSame(exact.arguments().get(2), result.applications().getFirst().target().arguments().get(2));
        FunctionExpr excessive = f("bundle", f("f", z), f("keep", w), wide("pad", 1019, z));
        assertThrows(IllegalArgumentException.class, () -> pattern.find(excessive, 20));
    }

    @Test void preflightsOversizedSubstitutionWithoutClaimingCompleteAbsence() {
        var pattern = learn(true, false);
        Expr payload = wide("payload", 600, v("z"));
        // Input 605 nodes, selected substitution would exceed 1024.
        var result = pattern.find(f("bundle", f("f", payload), f("keep", v("w"))), 20);
        assertFalse(result.complete());
        assertTrue(result.applications().isEmpty());
        assertEquals(2, result.assignmentAttempts());
    }

    @Test void alsoBoundsTheCombinedTargetIncludingUnmatchedOutputs() {
        var pattern = learn(true, true);
        Expr payload = wide("payload", 400, v("z"));
        Expr unrelated = wide("pad", 600, v("w"));
        // Input 1004 nodes; selected replacement is valid alone, but combined output is 1405.
        var result = pattern.find(f("bundle", f("f", payload), unrelated), 20);
        assertFalse(result.complete());
        assertTrue(result.applications().isEmpty());
        assertEquals(2, result.assignmentAttempts());
    }

    private static TypedOutputPattern learn(boolean duplicate, boolean singleOutput) {
        var examples = List.of(List.of("x", "y"), List.of("u", "v")).stream().map(names -> {
            Expr x = v(names.get(0));
            Expr y = v(names.get(1));
            Expr target = duplicate ? f("duplicate", x, x) : f("g", x);
            return singleOutput
                ? new TypedPatternGeneralizer.Example(f("bundle", f("f", x)), f("bundle", target))
                : new TypedPatternGeneralizer.Example(f("bundle", f("f", x), f("keep", y)),
                    f("bundle", target, f("keep", y)));
        }).toList();
        return new TypedOutputPattern(new TypedPatternGeneralizer().generalize(examples).orElseThrow());
    }

    private static Expr v(String name) { return new VariableExpr(name); }
    private static FunctionExpr f(String name, Expr... args) { return new FunctionExpr(name, List.of(args)); }
    private static Expr wide(String name, int count, Expr repeated) {
        return new FunctionExpr(name, Collections.nCopies(count, repeated));
    }
}
