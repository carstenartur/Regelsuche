package de.regelsuche.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.plugin.AstVisitorContext;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Characterizes the identity notions that coexist before ADR #242 is migrated.
 *
 * <p>These tests intentionally describe the current implementation and the
 * minimum occurrence/value distinction required by the accepted ADR.</p>
 */
class ExpressionIdentityCharacterizationTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void separatelyParsedEqualVariablesAreStructurallyEqualButNotInterned() {
        Expr first = parser.parseTerm("a");
        Expr second = parser.parseTerm("a");

        assertEquals(first, second, "VariableExpr record equality is structural");
        assertNotSame(first, second, "the current parser does not intern expression values");
    }

    @Test
    void repeatedVariableOccurrencesAreSeparateObjectsInTheSyntaxTree() {
        BinaryExpr sum = (BinaryExpr) parser.parseTerm("a + a");

        assertEquals(sum.left(), sum.right());
        assertNotSame(sum.left(), sum.right(),
            "the two written occurrences currently allocate distinct Expr objects");
    }

    @Test
    void pluginMetadataCurrentlyUsesExprReferenceAsOccurrenceIdentity() {
        BinaryExpr sum = (BinaryExpr) parser.parseTerm("a + a");
        AstVisitorContext context = new AstVisitorContext();

        context.putMetadata(sum.left(), "side", "left");
        context.putMetadata(sum.right(), "side", "right");

        assertEquals("left", context.metadata(sum.left()).get("side"));
        assertEquals("right", context.metadata(sum.right()).get("side"));
        assertNotSame(sum.left(), sum.right(),
            "interning the current Expr objects directly would collapse occurrence metadata");
    }

    @Test
    void aNormalSetPreservesDistinctOccurrencesOfOneEqualValue() {
        Expr value = new VariableExpr("a");
        Set<Use> uses = new LinkedHashSet<>();

        uses.add(new Use(1, value));
        uses.add(new Use(2, value));

        assertEquals(2, uses.size(),
            "Set is valid for unordered occurrences when occurrence identity is explicit");
        assertSame(uses.stream().toList().get(0).value(), uses.stream().toList().get(1).value(),
            "both occurrences may reference the same mathematical value object");
    }

    @Test
    void parserPreservesBinaryGroupingAsStructuralIdentity() {
        Expr leftGrouped = parser.parseTerm("(a + b) + c");
        Expr rightGrouped = parser.parseTerm("a + (b + c)");

        assertNotEquals(leftGrouped, rightGrouped,
            "binary grouping participates in current Expr equality");
        assertEquals(canonicalizer.stableHash("(a + b) + c"),
            canonicalizer.stableHash("a + (b + c)"),
            "the canonical identity layer removes associative grouping");
    }

    @Test
    void parserPreservesOperandOrderButCanonicalIdentityDoesNot() {
        Expr forward = parser.parseTerm("a + b");
        Expr reversed = parser.parseTerm("b + a");

        assertNotEquals(forward, reversed,
            "operand order participates in current Expr equality");
        assertEquals(canonicalizer.stableHash("a + b"), canonicalizer.stableHash("b + a"));
    }

    @Test
    void canonicalIdentityRetainsAdditiveMultiplicity() {
        assertNotEquals(canonicalizer.stableHash("a + a + b"),
            canonicalizer.stableHash("a + b"));
        assertEquals(canonicalizer.stableHash("a + a + b"),
            canonicalizer.stableHash("b + a + a"));
    }

    @Test
    void manuallySharedChildAlreadyTurnsTheTreeIntoADag() {
        Expr shared = new VariableExpr("a");
        BinaryExpr sum = new BinaryExpr(shared, BinaryOperator.ADD, shared);

        assertSame(sum.left(), sum.right(),
            "BinaryExpr permits shared child references even though the parser does not create them");
        assertEquals(parser.parseTerm("a + a"), sum,
            "record equality cannot distinguish a shared value from two equal occurrences");
    }

    private record Use(long id, Expr value) {
    }
}
