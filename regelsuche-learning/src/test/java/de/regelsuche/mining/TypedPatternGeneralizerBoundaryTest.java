package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Resource limits count tree occurrences, including the root, on BOTH sides. */
class TypedPatternGeneralizerBoundaryTest {
    private final TypedPatternGeneralizer generalizer = new TypedPatternGeneralizer();

    @Test void acceptsExactlyMaximumNodesInWideSourceAndTarget() {
        var examples = List.of("x", "y").stream().map(name -> {
            Expr leaf = new VariableExpr(name);
            var arguments = Collections.nCopies(TypedPatternGeneralizer.MAXIMUM_NODES - 1, leaf);
            return new TypedPatternGeneralizer.Example(
                new FunctionExpr("input", arguments), new FunctionExpr("output", arguments));
        }).toList();
        assertReconstructs(generalizer.generalize(examples).orElseThrow());
    }

    @Test void rejectsWideSourceOneNodeOverTheLimit() {
        Expr leaf = new VariableExpr("x");
        var pair = new TypedPatternGeneralizer.Example(wide(leaf), leaf);
        var error = assertThrows(IllegalArgumentException.class,
            () -> generalizer.generalize(List.of(pair, pair)));
        assertTrue(error.getMessage().contains("argument limit"));
    }

    @Test void rejectsWideTargetOneNodeOverTheLimit() {
        Expr leaf = new VariableExpr("x");
        var pair = new TypedPatternGeneralizer.Example(leaf, wide(leaf));
        var error = assertThrows(IllegalArgumentException.class,
            () -> generalizer.generalize(List.of(pair, pair)));
        assertTrue(error.getMessage().contains("argument limit"));
    }

    @Test void acceptsExactlyMaximumOccurrencesInNestedSharedTrees() {
        var examples = List.of("x", "y").stream().map(name -> {
            Expr tree = sharedBinaryTree(new VariableExpr(name));
            return new TypedPatternGeneralizer.Example(
                new FunctionExpr("input", tree), new FunctionExpr("output", tree));
        }).toList();
        assertReconstructs(generalizer.generalize(examples).orElseThrow());
    }

    @Test void rejectsNestedSourceOneOccurrenceOverTheLimit() {
        Expr leaf = new VariableExpr("x");
        var pair = new TypedPatternGeneralizer.Example(overNested(leaf), leaf);
        var error = assertThrows(IllegalArgumentException.class,
            () -> generalizer.generalize(List.of(pair, pair)));
        assertTrue(error.getMessage().contains("structural limit"));
    }

    @Test void rejectsNestedTargetOneOccurrenceOverTheLimit() {
        Expr leaf = new VariableExpr("x");
        var pair = new TypedPatternGeneralizer.Example(leaf, overNested(leaf));
        var error = assertThrows(IllegalArgumentException.class,
            () -> generalizer.generalize(List.of(pair, pair)));
        assertTrue(error.getMessage().contains("structural limit"));
    }

    private static Expr wide(Expr leaf) {
        // Root plus MAXIMUM_NODES arguments: exactly one occurrence too many.
        return new FunctionExpr("wide", Collections.nCopies(TypedPatternGeneralizer.MAXIMUM_NODES, leaf));
    }

    private static Expr sharedBinaryTree(Expr leaf) {
        // Nine levels: 512 leaf occurrences + 511 binary occurrences = 1023.
        // Only ten different Java objects: a visited-identity set would be wrong.
        assertEquals(1024, TypedPatternGeneralizer.MAXIMUM_NODES, "update the exact-boundary fixture if the contract changes");
        Expr tree = leaf;
        for (int level = 0; level < 9; level++) {
            tree = new BinaryExpr(tree, BinaryOperator.ADD, tree);
        }
        return tree;
    }

    private static Expr overNested(Expr leaf) {
        // Two unary wrappers: 1025 occurrences, depth 11, no wide-arity guard.
        return new FunctionExpr("outer", new FunctionExpr("inner", sharedBinaryTree(leaf)));
    }

    private static void assertReconstructs(TypedPatternGeneralizer.Candidate candidate) {
        assertEquals(2, candidate.examples().size());
        for (int i = 0; i < candidate.examples().size(); i++) {
            var example = candidate.examples().get(i);
            var bindings = candidate.bindings().get(i);
            assertEquals(1, bindings.size(), "repeated object occurrences must share their expression binding");
            assertEquals(example.source(), candidate.source().instantiate(bindings));
            assertEquals(example.target(), candidate.target().instantiate(bindings));
        }
    }
}
