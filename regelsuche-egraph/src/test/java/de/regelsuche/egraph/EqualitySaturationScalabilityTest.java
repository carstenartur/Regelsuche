package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import org.junit.jupiter.api.Test;

class EqualitySaturationScalabilityTest {

    @Test
    void directEClassLookupSurvivesUnionAndRebuild() {
        EGraph eGraph = new EGraph();
        EClassId left = eGraph.addExpression(parse("a + b"));
        EClassId right = eGraph.addExpression(parse("b + a"));

        eGraph.union(left, right);
        eGraph.rebuild();

        EClass lhsClass = eGraph.classOrThrow(left);
        EClass rhsClass = eGraph.classOrThrow(right);
        assertEquals(eGraph.find(left), lhsClass.id());
        assertEquals(eGraph.find(right), rhsClass.id());
        assertEquals(lhsClass.id(), rhsClass.id());
    }

    @Test
    void symbolArityIndexFindsOnlyCompatibleClasses() {
        EGraph eGraph = new EGraph();
        EClassId add = eGraph.addExpression(parse("a + b"));
        EClassId mul = eGraph.addExpression(parse("a * b"));
        EClassId sin = eGraph.addExpression(parse("sin(x)"));
        eGraph.addExpression(parse("x"));

        var addHits = eGraph.classesWith(new ENodeSignature("op:ADD", 2));
        var mulHits = eGraph.classesWith(new ENodeSignature("op:MUL", 2));
        var sinHits = eGraph.classesWith(new ENodeSignature("fn:sin", 1));

        assertTrue(addHits.contains(eGraph.find(add)));
        assertTrue(!addHits.contains(eGraph.find(mul)));
        assertTrue(mulHits.contains(eGraph.find(mul)));
        assertTrue(!mulHits.contains(eGraph.find(add)));
        assertTrue(sinHits.contains(eGraph.find(sin)));
    }

    @Test
    void patternMatcherDoesNotScanUnrelatedSymbols() {
        EGraph eGraph = new EGraph();
        eGraph.addExpression(parse("a + b"));
        eGraph.addExpression(parse("a * b"));
        eGraph.addExpression(parse("sin(x)"));
        eGraph.addExpression(parse("cos(x)"));
        eGraph.addExpression(parse("x + 0"));
        EGraphPatternMatcher matcher = new EGraphPatternMatcher(eGraph);

        PatternExpr addPattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.var("L"),
            PatternExpr.var("R")
        );
        matcher.matchAll("add-root", addPattern, null);
        EGraphPatternMatcher.MatcherStats stats = matcher.stats();

        assertTrue(stats.candidateClassesSkipped() > 0);
        assertTrue(stats.classesScanned() < eGraph.classCount());
    }

    @Test
    void matchMemoizationInvalidatesAfterEGraphChange() {
        EGraph eGraph = new EGraph();
        eGraph.addExpression(parse("a + b"));
        EGraphPatternMatcher matcher = new EGraphPatternMatcher(eGraph);
        PatternExpr addPattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.var("L"),
            PatternExpr.var("R")
        );

        matcher.matchAll("memo-test", addPattern, null);
        long missesAfterFirst = matcher.stats().matcherCacheMisses();
        matcher.matchAll("memo-test", addPattern, null);
        long hitsAfterSecond = matcher.stats().matcherCacheHits();
        long missesAfterSecond = matcher.stats().matcherCacheMisses();

        eGraph.addExpression(parse("c + d"));
        matcher.matchAll("memo-test", addPattern, null);
        long missesAfterMutation = matcher.stats().matcherCacheMisses();

        assertTrue(missesAfterFirst > 0);
        assertTrue(hitsAfterSecond > 0);
        assertEquals(missesAfterFirst, missesAfterSecond);
        assertTrue(missesAfterMutation > missesAfterSecond);
    }

    @Test
    void worklistSaturationKeepsSameResultAsFullScan() {
        Expr root = parse("( x + 3 ) ^ 2");
        EGraph worklistGraph = new EGraph();
        EClassId worklistRoot = worklistGraph.addExpression(root);
        EqualitySaturation worklist = new EqualitySaturation(
            AstRewriteTransformationEngine.defaultRules(),
            new EqualitySaturation.Config(12, 10_000, true)
        );
        EqualitySaturation.Result worklistResult = worklist.saturate(
            worklistGraph,
            worklistRoot,
            node -> node.isLeaf() ? 0 : 1
        );

        EGraph fullScanGraph = new EGraph();
        EClassId fullScanRoot = fullScanGraph.addExpression(root);
        EqualitySaturation fullScan = new EqualitySaturation(
            AstRewriteTransformationEngine.defaultRules(),
            new EqualitySaturation.Config(12, 10_000, false)
        );
        EqualitySaturation.Result fullScanResult = fullScan.saturate(
            fullScanGraph,
            fullScanRoot,
            node -> node.isLeaf() ? 0 : 1
        );

        assertEquals(
            ExpressionFormatter.format(fullScanResult.expression()),
            ExpressionFormatter.format(worklistResult.expression())
        );
        assertEquals(fullScanResult.stats().appliedRules(), worklistResult.stats().appliedRules());
        assertTrue(worklistResult.stats().classesScanned() <= fullScanResult.stats().classesScanned());
    }

    private static Expr parse(String input) {
        return new ExpressionParser()
            .parse(new InputRequest(InputType.TERM, input))
            .terms()
            .getFirst();
    }
}
