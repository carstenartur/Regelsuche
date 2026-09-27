package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.Transformation;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckedSchemaApplicationPhasesTest {
    private static final String SOURCE = "((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)";
    private static CheckedSchemaMatcherPlan plan;
    private static CheckedLearnedSchemaModel model;
    private static List<Transformation> expected;
    private static MoveState state;

    @BeforeAll static void learnAndRestore() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var learned = CheckedLearnedSchemaModel.learn(formation);
        model = CheckedLearnedSchemaModel.load(learned.toCanonicalJson(), learned.inventoryHash());
        var codec = new CompiledAstReplayCodec();
        var single = MoveState.root(codec.encodeExpression(new ExpressionParser().parseExactTerm("(x+y)*(x-y)+y*y").expression()));
        String schema = model.providers().getFirst().candidates(single, MoveContext.frozen("unused")).moves().stream()
            .filter(move -> codec.decodeExpression(move.transformation().transformedExpression())
                .equals(new ExpressionParser().parseExactTerm("x^2").expression()))
            .findFirst().orElseThrow().transformation().rule();
        state = MoveState.root(codec.encodeExpression(new ExpressionParser().parseExactTerm(SOURCE).expression()));
        plan = CheckedSchemaMatcherPlan.prepare(model, 1, Map.of(), Set.of(schema));
        expected = model.providers(1, Map.of(), Set.of(schema)).getFirst().candidates(state, MoveContext.frozen("unused"))
            .moves().stream().map(SearchMove::transformation).toList();
        assertEquals(2, expected.size());
    }

    @Test void closeAfterEachReturningPhaseKeepsPaidWorkWithoutBuildingEvidenceOrIssuingMathematics() throws Exception {
        for (String phase : List.of("SUBSTITUTION_DOMAIN", "INSTANTIATION", "TARGET_DOMAIN")) {
            var cursor = plan.provider().openSession(state, MoveContext.frozen("unused"));
            JsonNode prepaid = untilPhase(cursor, phase);
            assertEquals(0, cursor.snapshot().work().mathematics().exactTheorySteps(), phase);
            assertEquals(0, prepaid.path("phaseCalls").path("EVIDENCE").asLong(), phase);
            assertTrue(prepaid.path("chargedUnits").asLong() > 0, phase);
            assertEquals(0, cursor.snapshot().emittedCandidates());
            long before = cursor.snapshot().work().metrics().totalWorkUnitsV2();
            cursor.close();
            assertEquals(before + 1, cursor.snapshot().work().metrics().totalWorkUnitsV2(), "only wrapper close is new work");
            assertEquals(0, cursor.snapshot().work().mathematics().exactTheorySteps());
            assertEquals(prepaid.path("chargedUnits"), prepaid(cursor).path("chargedUnits"));
            assertEquals(1, prepaid(cursor).path("abandonedApplications").asLong());
            var closed = cursor.snapshot(); cursor.close();
            assertEquals(closed, cursor.snapshot()); assertTrue(cursor.next(100000).isEmpty());
        }
    }

    @Test void resumeAfterInstantiationOnlyFinishesRemainingPhasesAndChargesTheCandidateExactlyOnce() throws Exception {
        var cursor = plan.provider().openSession(state, MoveContext.frozen("unused"));
        var paused = untilPhase(cursor, "INSTANTIATION");
        assertTrue(paused.path("phaseWork").path("INSTANTIATION").asLong() > 0, "each actual phase exposes its work separately");
        long matches = cursor.snapshot().work().units(IncrementalProviderContract.Operation.MATCH);
        long paid = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        assertTrue(cursor.next(0).isEmpty());
        assertEquals(paid, cursor.snapshot().work().metrics().totalWorkUnitsV2());
        Transformation first = null;
        for (int pull = 0; pull < 1000 && first == null; pull++) {
            var next = cursor.next(2);
            long now = cursor.snapshot().work().metrics().totalWorkUnitsV2();
            assertTrue(now >= paid); paid = now;
            first = next.orElse(null);
        }
        assertEquals(expected.getFirst(), first);
        var completed = prepaid(cursor);
        assertEquals(paused.path("phaseCalls").path("SUBSTITUTION_DOMAIN"), completed.path("phaseCalls").path("SUBSTITUTION_DOMAIN"));
        assertEquals(1, completed.path("phaseCalls").path("INSTANTIATION").asLong());
        assertEquals(1, completed.path("phaseCalls").path("TARGET_DOMAIN").asLong());
        assertEquals(1, completed.path("phaseCalls").path("EVIDENCE").asLong());
        assertEquals(matches, cursor.snapshot().work().units(IncrementalProviderContract.Operation.MATCH), "no rematch or later occurrence");
        assertEquals(first.executionWork(), cursor.snapshot().work().mathematics());
        assertEquals(first.executionWork().exactTheoryWorkUnits(), completed.path("chargedUnits").asLong());
        long operations = cursor.snapshot().work().operations().values().stream().mapToLong(Long::longValue).sum();
        long phaseCalls = 0; for (JsonNode n : completed.path("phaseCalls")) phaseCalls += n.asLong();
        assertEquals(operations + completed.path("chargedUnits").asLong() + phaseCalls, paid,
            "prepaid work is not charged again as candidate mathematics");
        cursor.close();
    }

    @Test void unchangedApplicationRetainsPrepaidWorkWithoutConstructingEvidence() throws Exception {
        var a = de.regelsuche.transform.PatternExpr.var("P0");
        var b = de.regelsuche.transform.PatternExpr.var("P1");
        var checked = fixture(de.regelsuche.transform.PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, a, b),
            de.regelsuche.transform.PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, b, a));
        var p = CheckedSchemaMatcherPlan.prepare(checked, 1, Map.of(), Set.of(checked.schemas().getFirst().id()));
        var source = MoveState.root(new CompiledAstReplayCodec().encodeExpression(new ExpressionParser().parseTerm("x+x")));
        try (var cursor = p.provider().openSession(source, MoveContext.frozen("unused"))) {
            assertTrue(cursor.next(100000).isEmpty());
            assertTrue(cursor.snapshot().complete());
            assertEquals(0, cursor.snapshot().work().mathematics().exactTheorySteps());
            assertTrue(prepaid(cursor).path("chargedUnits").asLong() > 0);
            assertEquals(1, prepaid(cursor).path("abandonedApplications").asLong());
            assertEquals(0, prepaid(cursor).path("phaseCalls").path("EVIDENCE").asLong());
        }
    }

    @Test void rejectedTargetDomainRetainsAllVisitedWorkAndNeverConstructsEvidence() throws Exception {
        var a = de.regelsuche.transform.PatternExpr.var("P0");
        var source = de.regelsuche.transform.PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, a,
            de.regelsuche.transform.PatternExpr.num(0));
        var checked = fixture(source, de.regelsuche.transform.PatternExpr.op(de.regelsuche.ast.BinaryOperator.ADD, source,
            de.regelsuche.transform.PatternExpr.num(0)));
        var p = CheckedSchemaMatcherPlan.prepare(checked, 1, Map.of(), Set.of(checked.schemas().getFirst().id()));
        var expression = new de.regelsuche.ast.BinaryExpr(new ExpressionParser().parseTerm("x+0"),
            de.regelsuche.ast.BinaryOperator.ADD, balanced(507));
        try (var cursor = p.provider().openSession(MoveState.root(new CompiledAstReplayCodec().encodeExpression(expression)), MoveContext.frozen("unused"))) {
            assertTrue(cursor.next(100000).isEmpty());
            assertFalse(cursor.snapshot().complete()); assertTrue(cursor.snapshot().accountingComplete());
            assertEquals(0, cursor.snapshot().work().mathematics().exactTheorySteps());
            assertTrue(prepaid(cursor).path("chargedUnits").asLong() >= 512);
            assertTrue(prepaid(cursor).path("phaseWork").path("TARGET_DOMAIN").asLong() >= 512);
            assertEquals(0, prepaid(cursor).path("phaseCalls").path("EVIDENCE").asLong());
        }
    }

    private static de.regelsuche.ast.Expr balanced(int nodes) {
        if (nodes == 1) return new de.regelsuche.ast.VariableExpr("q");
        int left = (nodes - 1) / 2; if (left % 2 == 0) left--;
        return new de.regelsuche.ast.BinaryExpr(balanced(left), de.regelsuche.ast.BinaryOperator.ADD, balanced(nodes - 1 - left));
    }
    private static CheckedLearnedSchemaModel fixture(de.regelsuche.transform.PatternExpr source, de.regelsuche.transform.PatternExpr target) {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) CheckedSchemaSupport.read(model.toCanonicalJson());
        String proof = CheckedSchemaSupport.prove(source, target, model.bounds(), new CheckedSchemaSupport.Work());
        var schema = ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("schemas").get(0)).deepCopy();
        schema.put("id", "checked-schema:" + SchematicProofPlan.hash(model.inventorySemanticsHash() + "\n" + proof));
        schema.put("proofHash", SchematicProofPlan.hash(proof));
        schema.set("source", CheckedSchemaSupport.pattern(source)); schema.set("target", CheckedSchemaSupport.pattern(target));
        root.putArray("schemas").add(schema);
        return CheckedLearnedSchemaModel.load(CheckedSchemaSupport.write(root), model.inventoryHash());
    }

    private static JsonNode untilPhase(IncrementalProviderContract.Cursor cursor, String phase) throws Exception {
        for (int i = 0; i < 10000; i++) {
            assertTrue(cursor.next(2).isEmpty(), "no candidate may escape before the requested phase boundary");
            var prepaid = prepaid(cursor);
            if (prepaid.path("phaseCalls").path(phase).asLong() > 0) return prepaid;
            assertEquals(0, cursor.snapshot().work().mathematics().exactTheorySteps(),
                "partial work must be visible before a complete application exists");
        }
        throw new AssertionError("phase boundary was never exposed: " + phase);
    }
    private static JsonNode prepaid(IncrementalProviderContract.Cursor cursor) throws Exception {
        return new ObjectMapper().readTree(LearnedSchedulingArtifacts.json(cursor.snapshot())).path("work").path("prepaidApplications");
    }
}
