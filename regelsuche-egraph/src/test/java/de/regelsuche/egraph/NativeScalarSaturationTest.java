package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueArena;
import org.junit.jupiter.api.Test;

class NativeScalarSaturationTest {
    final ExpressionParser parser = new ExpressionParser();

    @Test void nativeMixedSaturationReusesOneGraphAndIssuesOnlyIndependentlyCheckedOutputs() {
        var rules = de.regelsuche.transform.AstRewriteTransformationEngine.allBuiltInRules();
        var input = parser.parseTerm("f(((b+0)*a)/a)");
        var context = de.regelsuche.egraph.proof.ScalarExtractionProof.Context.create(rules,
            java.util.List.of(de.regelsuche.assumption.Assumption.nonZero("a")), "a".repeat(40));
        var search = new EqualitySaturation(rules);
        for (var scheduler : NativeScalarSaturation.Schedule.values()) {
            var result = search.saturateExactScalars(input, context, new NativeScalarSaturation.Budget(8,256,2500,scheduler));
            var chosen = result.extractions().getFirst();
            assertEquals(parser.parseTerm("f(b)"), chosen.expression());
            assertTrue(chosen.proof().isPresent());
            assertTrue(de.regelsuche.egraph.proof.ScalarExtractionProof.check(input,chosen.expression(),context,chosen.proof().get(),
                de.regelsuche.egraph.proof.ProofLimits.defaults()));
            assertTrue(result.guards().stream().anyMatch(g -> g.result().equals("TRUE") && g.applied()));
            assertEquals(2, result.extractions().size());
            assertTrue(result.ledger().values().stream().mapToInt(NativeScalarSaturation.RuleWork::unions).sum() >= 2);
            assertTrue(context.inventory().stream().anyMatch(e -> e.disposition().startsWith("EXCLUDED")));
        }
    }

    @Test void nativelyJoinsCommonFactorsInOppositeOperandRoles() {
        var rules=de.regelsuche.transform.AstRewriteTransformationEngine.allBuiltInRules();
        var ctx=de.regelsuche.egraph.proof.ScalarExtractionProof.Context.create(rules,java.util.List.of(),"a".repeat(40));
        var in=parser.parseTerm("x*a+a*y");
        var result=new EqualitySaturation(rules).saturateExactScalars(in,ctx,
            new NativeScalarSaturation.Budget(8,256,4000,NativeScalarSaturation.Schedule.ROUND_ROBIN));
        var out=result.extractions().getFirst();
        try(var arena=new ExprValueArena(100)){
            assertSame(arena.intern(parser.parseTerm("a*(x+y)")),arena.intern(out.expression()));
        }
        assertTrue(out.proof().isPresent());
    }

    @Test void reportsUnknownFalseAndConflictingGuardsWithoutMergingTheirQuotients() {
        var rules=de.regelsuche.transform.AstRewriteTransformationEngine.allBuiltInRules();
        var input=parser.parseTerm("(a*b)/a");
        for (var entry:java.util.Map.of("UNKNOWN",java.util.List.<de.regelsuche.assumption.Assumption>of(),
                "FALSE", java.util.List.of(de.regelsuche.assumption.Assumption.customPredicate("a = 0",java.util.List.of("a"))),
                "CONFLICT",java.util.List.of(de.regelsuche.assumption.Assumption.nonZero("a"),
                    de.regelsuche.assumption.Assumption.customPredicate("a = 0",java.util.List.of("a")))).entrySet()) {
            var ctx=de.regelsuche.egraph.proof.ScalarExtractionProof.Context.create(rules,entry.getValue(),"b".repeat(40));
            var result=new EqualitySaturation(rules).saturateExactScalars(input,ctx,
                new NativeScalarSaturation.Budget(4,128,300,NativeScalarSaturation.Schedule.ROUND_ROBIN));
            assertTrue(result.guards().stream().anyMatch(g -> entry.getKey().equals(g.result()) && !g.applied()));
            assertNotEquals(parser.parseTerm("b"),result.extractions().getFirst().expression());
        }
    }

    @Test void nativeWorkObservationPreservesTheResultAfterAnInsertionRefusal() {
        var rules = de.regelsuche.transform.AstRewriteTransformationEngine.allBuiltInRules();
        var context = de.regelsuche.egraph.proof.ScalarExtractionProof.Context.create(rules,
                java.util.List.of(), "a".repeat(40));
        var input = parser.parseTerm("a*x+a*y");
        var engine = new EqualitySaturation(rules);
        var budget = new NativeScalarSaturation.Budget(8, 7, 4000, NativeScalarSaturation.Schedule.ROUND_ROBIN);
        var result = engine.saturateExactScalars(input, context, budget);
        var observed = engine.observeExactScalars(input, context, budget);
        assertEquals("NODE_BUDGET", result.terminal());
        assertEquals(result, observed.result());
        for (String stage : java.util.List.of("graphStructure", "representativeForest", "extraction", "proofProduction")) {
            assertTrue(observed.producerStages().get(stage) > 0, stage);
        }
        assertEquals(observed.producerStages(), engine.observeExactScalars(input, context, budget).producerStages());
    }

    @Test void analysisMaintainsExactConstantConflictsAndDefinedness() {
        var graph=EGraph.exactScalarAcV1(); var two=graph.addExpression(parser.parseTerm("2"));
        var three=graph.addExpression(parser.parseTerm("3"));
        var facts=new ScalarClassAnalysis(graph,java.util.List.of());
        facts.update(graph.consumeDirtyClasses()); graph.union(two,three); graph.rebuild(); facts.update(graph.consumeDirtyClasses());
        assertEquals(de.regelsuche.egraph.proof.ScalarGuardFacts.Truth.CONFLICT, facts.get(two).nonzero());
        assertEquals(java.util.List.of("2","3"),facts.shape(two).exactConstants());
        var bad=parser.parseTerm("0*(1/0)+1");
        assertEquals(de.regelsuche.egraph.proof.ScalarGuardFacts.Truth.UNKNOWN,
            de.regelsuche.egraph.proof.ScalarGuardFacts.of(bad,java.util.List.of()).nonzero());
    }

    @Test void mapsArenaValuesToNativeNaryNodesWithNoExportOrDigestWork() {
        var graph = EGraph.exactScalarAcV1();
        try (var arena = new ExprValueArena(100); var foreign = new ExprValueArena(10)) {
            var source = parser.parseTerm("(b + a) + a");
            var input = arena.intern(source);
            var root = graph.addArenaValue(arena, input, 16);
            assertEquals(graph.addExpression(parser.parseTerm("a + (b + a)")), root);
            assertEquals(3, graph.nodeCount());
            assertEquals(0, arena.digestComputations());
            assertNotEquals(root, graph.addArenaValue(arena, arena.intern(parser.parseTerm("a+b")), 16));
            assertThrows(IllegalArgumentException.class, () -> graph.addArenaValue(foreign, input, 16));
            assertThrows(IllegalArgumentException.class, () -> new EGraph().addArenaValue(arena, input, 16));
            assertThrows(IllegalArgumentException.class, () -> graph.addArenaValue(arena, input, 2));
            assertNotEquals(graph.addArenaValue(arena, arena.intern(parser.parseTerm("a/b")), 16),
                graph.addArenaValue(arena, arena.intern(parser.parseTerm("b/a")), 16));
        }
    }
}
