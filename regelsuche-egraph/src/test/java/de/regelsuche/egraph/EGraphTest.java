package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import java.util.List;
import org.junit.jupiter.api.Test;

class EGraphTest {

    @Test
    void unionFindIsTransitive() {
        UnionFind unionFind = new UnionFind();
        EClassId a = unionFind.makeSet();
        EClassId b = unionFind.makeSet();
        EClassId c = unionFind.makeSet();

        unionFind.union(a, b);
        unionFind.union(b, c);

        assertTrue(unionFind.inSameSet(a, c));
        assertEquals(unionFind.find(a), unionFind.find(c));
    }

    @Test
    void addingTheSameExpressionTwiceReusesTheEClass() {
        EGraph eGraph = new EGraph();
        EClassId first = eGraph.addExpression(parse("a + b"));
        EClassId second = eGraph.addExpression(parse("a + b"));

        assertEquals(eGraph.find(first), eGraph.find(second));
        // 3 distinct e-nodes: var:a, var:b, op:ADD
        assertEquals(3, eGraph.nodeCount());
    }

    @Test
    void distinctSubExpressionsLiveInDistinctClassesUntilUnioned() {
        EGraph eGraph = new EGraph();
        EClassId left = eGraph.addExpression(parse("a + b"));
        EClassId right = eGraph.addExpression(parse("b + a"));

        assertFalse(eGraph.areEquivalent(left, right),
            "without an explicit commutativity rule, a+b and b+a must be distinct");
    }

    @Test
    void unionMergesClassesAndRebuildPropagatesCongruence() {
        // Insert two expressions that *share* their top-level operator
        // structure but differ in one leaf — proving that an externally
        // declared equivalence between the leaves propagates to the parent
        // via congruence closure (egg's defining feature).
        EGraph eGraph = new EGraph();
        EClassId aPlusOne = eGraph.addExpression(parse("a + 1"));
        EClassId bPlusOne = eGraph.addExpression(parse("b + 1"));

        // Before: completely separate.
        assertFalse(eGraph.areEquivalent(aPlusOne, bPlusOne));

        // Declare a ≡ b.
        EClassId aClass = eGraph.addExpression(new VariableExpr("a"));
        EClassId bClass = eGraph.addExpression(new VariableExpr("b"));
        eGraph.union(aClass, bClass);
        eGraph.rebuild();

        // After rebuild: the parent classes are equivalent too.
        assertTrue(eGraph.areEquivalent(aPlusOne, bPlusOne),
            "rebuild() must propagate a≡b up to (a+1)≡(b+1)");
    }

    @Test
    void extractionPicksTheCheapestRepresentative() {
        // Two algebraically equivalent forms — the e-graph won't discover
        // the equivalence on its own (that's PR 2b, saturation), but once
        // we declare them equivalent, extract(...) must return the cheaper
        // representative, which is the whole point of the cost-function
        // hook PR 3 already introduced.
        EGraph eGraph = new EGraph();
        EClassId distributed = eGraph.addExpression(parse("(a + b) * c"));
        EClassId expanded = eGraph.addExpression(parse("a * c + b * c"));
        eGraph.union(distributed, expanded);
        eGraph.rebuild();

        // node count cost: leaves = 1, operators = 1 + sum of children.
        Expr extracted = eGraph.extract(distributed, node -> 1);
        // The factored form has fewer operators (3 vs 5), so extraction
        // must pick it.
        assertEquals(parse("(a + b) * c"), extracted);
    }

    @Test
    void extractionUsesADifferentCostFunctionConsistently() {
        EGraph eGraph = new EGraph();
        EClassId withZero = eGraph.addExpression(parse("x + 0"));
        EClassId bare = eGraph.addExpression(parse("x"));
        eGraph.union(withZero, bare);
        eGraph.rebuild();

        // Trivial uniform cost ⇒ extract picks the smallest node count form.
        Expr extracted = eGraph.extract(withZero, node -> 1);
        assertEquals(new VariableExpr("x"), extracted,
            "extract must prefer the bare variable over x + 0");
    }

    @Test
    void rebuildIsIdempotent() {
        EGraph eGraph = new EGraph();
        EClassId one = eGraph.addExpression(parse("a + b"));
        EClassId two = eGraph.addExpression(parse("c + d"));
        eGraph.union(one, two);
        eGraph.rebuild();
        int classCountAfterFirst = eGraph.classCount();
        int nodeCountAfterFirst = eGraph.nodeCount();
        eGraph.rebuild();

        assertEquals(classCountAfterFirst, eGraph.classCount());
        assertEquals(nodeCountAfterFirst, eGraph.nodeCount());
    }

    @Test
    void leafENodeUtilities() {
        ENode leaf = ENode.leaf("var:x");
        assertTrue(leaf.isLeaf());
        assertEquals("var:x", leaf.symbol());
        assertEquals(0, leaf.children().size());
    }

    @Test
    void eClassIdsAreStableAcrossMerges() {
        EGraph eGraph = new EGraph();
        EClassId a = eGraph.addExpression(new VariableExpr("a"));
        EClassId b = eGraph.addExpression(new VariableExpr("b"));
        // Before merge: distinct canonical ids.
        assertNotEquals(eGraph.find(a), eGraph.find(b));
        eGraph.union(a, b);
        eGraph.rebuild();
        // After merge: both id-handles still resolve to the same class.
        assertEquals(eGraph.find(a), eGraph.find(b));
    }

    @Test
    void extractedExpressionRoundtripsThroughTheFormatter() {
        EGraph eGraph = new EGraph();
        EClassId id = eGraph.addExpression(parse("(x + 1) * (x - 1)"));
        Expr extracted = eGraph.extract(id, node -> 1);
        String formatted = ExpressionFormatter.format(extracted);
        // Re-parsing the extracted form must yield the same AST.
        assertEquals(extracted, parse(formatted));
    }

    @Test
    void numbersAndBinaryOpsRoundtrip() {
        EGraph eGraph = new EGraph();
        EClassId id = eGraph.addExpression(new BinaryExpr(
            new NumberExpr(3), BinaryOperator.MUL, new VariableExpr("x")));
        Expr extracted = eGraph.extract(id, node -> 1);
        assertEquals(new BinaryExpr(new NumberExpr(3), BinaryOperator.MUL, new VariableExpr("x")),
            extracted);
    }

    @Test
    void eGraphKeepsAssumptionContextsDistinct() {
        EGraph eGraph = new EGraph();
        EClassId xDivXWithoutAssumption = eGraph.addExpression(parse("x / x"));
        EClassId oneWithAssumption = eGraph.addExpression(parse("1"), List.of("x != 0"));

        try {
            eGraph.union(xDivXWithoutAssumption, oneWithAssumption);
            org.junit.jupiter.api.Assertions.fail("incompatible assumptions must prevent unsafe merge");
        } catch (IllegalArgumentException expected) {
            assertFalse(eGraph.areEquivalent(xDivXWithoutAssumption, oneWithAssumption));
        }
    }

    @Test
    void eGraphAllowsConditionalIdentityUnderSameAssumptions() {
        EGraph eGraph = new EGraph();
        EClassId xDivX = eGraph.addExpression(parse("x / x"), List.of("0 != x"));
        EClassId one = eGraph.addExpression(parse("1"), List.of("x != 0"));

        eGraph.union(xDivX, one);
        eGraph.rebuild();

        assertTrue(eGraph.areEquivalent(xDivX, one));
        assertEquals("x != 0", eGraph.assumptionsFor(xDivX).fingerprint());
    }

    @Test
    void addingExpressionWithDifferentAssumptionContextThrows() {
        EGraph eGraph = new EGraph();
        eGraph.addExpression(parse("x + 1"), List.of("x != 0"));
        try {
            eGraph.addExpression(parse("x + 1"), List.of("x > 0"));
            org.junit.jupiter.api.Assertions.fail("re-adding with a different assumption must throw");
        } catch (IllegalArgumentException expected) {
            // x + 1 still carries its original assumption context
            EClassId id = eGraph.addExpression(parse("x + 1"), List.of("x != 0"));
            assertEquals("x != 0", eGraph.assumptionsFor(id).fingerprint());
        }
    }

    @Test
    void congruenceClosureThrowsWhenMergingIncompatibleAssumptions() {
        // f(a) carries assumption "A", f(b) carries assumption "B"; declaring a≡b
        // must not silently fuse the two distinct assumption contexts via rebuild().
        EGraph eGraph = new EGraph();
        EClassId a = eGraph.addExpression(new VariableExpr("a"));
        EClassId b = eGraph.addExpression(new VariableExpr("b"));
        eGraph.addExpression(parse("a + 1"), List.of("a != 0"));
        eGraph.addExpression(parse("b + 1"), List.of("b != 0"));
        // a and b themselves have no assumptions, so union(a,b) is fine.
        eGraph.union(a, b);
        try {
            eGraph.rebuild();
            org.junit.jupiter.api.Assertions.fail(
                "rebuild() must throw when congruence would merge classes with incompatible assumptions");
        } catch (IllegalStateException expected) {
            // The e-classes for (a+1) and (b+1) have different assumptions and
            // must not be silently merged.
        }
    }

    private static Expr parse(String input) {
        return new ExpressionParser()
            .parse(new InputRequest(InputType.TERM, input))
            .terms()
            .getFirst();
    }
}
