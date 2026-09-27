package de.regelsuche.evolution;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Small independently reproved test inventory exercises order and limits, not a learning-success claim. */
class CheckedSchemaOrderingLimitsTest {
    private static final String PAIR = "((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static CheckedLearnedSchemaModel original;
    private record Fixture(CheckedLearnedSchemaModel model, Set<String> included, Map<String, Double> utility,
            String high, String low, String wildcard) {}

    @BeforeAll static void learn() {
        original = CheckedLearnedSchemaModel.learn(new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits()));
    }
    @Test void nondefaultUtilityOrdersBothMatchingRulesBeforeWildcardAndDrainMatchesEager() {
        var fixture = fixture(null);
        var expected = eager(fixture, 3);
        assertTrue(expected.complete());
        assertEquals(List.of(fixture.wildcard(), fixture.high(), fixture.low(), fixture.wildcard()),
            expected.moves().subList(0, 4).stream().map(move -> move.transformation().rule()).toList());
        for (long allowance : List.of(2L, 7L, 100000L)) parity(fixture, 3, allowance, expected);
    }
    @Test void perOccurrenceSchemaLimitWithRemainingEntriesIsInconclusive() {
        var fixture = fixture(null);
        var expected = eager(fixture, 1);
        assertFalse(expected.complete()); assertFalse(expected.moves().isEmpty());
        parity(fixture, 1, 2, expected);
    }
    @Test void matchAttemptLimitWithRemainingRulesIsInconclusiveEvenWithoutCandidates() {
        var fixture = fixture("maximumMatchAttempts");
        var expected = eager(fixture, 3);
        assertFalse(expected.complete()); assertTrue(expected.moves().isEmpty());
        parity(fixture, 3, 2, expected);
    }
    @Test void candidateLimitWithRemainingOccurrencesIsInconclusive() {
        var fixture = fixture("maximumCandidates");
        var expected = eager(fixture, 3);
        assertFalse(expected.complete()); assertEquals(1, expected.moves().size());
        parity(fixture, 3, 2, expected);
    }
    private static void parity(Fixture fixture, int maximum, long allowance, MoveProvider.Batch expected) {
        var provider = CheckedSchemaMatcherPlan.prepare(fixture.model(), maximum, fixture.utility(), fixture.included()).provider();
        try (var cursor = provider.openSession(state(), MoveContext.frozen("unused"))) {
            var actual = new ArrayList<Transformation>();
            for (int i = 0; i < 20000; i++) {
                cursor.next(allowance).ifPresent(actual::add);
                assertTrue(cursor.snapshot().accountingComplete(), cursor.snapshot().detailCode());
                if (cursor.snapshot().status() == IncrementalProviderContract.Status.EXHAUSTED
                        || cursor.snapshot().status() == IncrementalProviderContract.Status.INCONCLUSIVE) break;
            }
            assertEquals(expected.moves().stream().map(SearchMove::transformation).toList(), actual);
            assertEquals(expected.complete(), cursor.snapshot().complete());
            assertFalse(cursor.snapshot().resumable());
        }
    }
    private static MoveProvider.Batch eager(Fixture f, int maximum) {
        return f.model().providers(maximum, f.utility(), f.included()).getFirst().candidates(state(), MoveContext.frozen("unused"));
    }
    private static MoveState state() { return MoveState.root(CODEC.encodeExpression(new ExpressionParser().parseExactTerm(PAIR).expression())); }
    private static Fixture fixture(String limit) {
        var root = (ObjectNode) CheckedSchemaSupport.read(original.toCanonicalJson());
        if (limit != null) ((ObjectNode) root.get("bounds")).put(limit, 1);
        var a = PatternExpr.var("P0"); var b = PatternExpr.var("P1");
        var source = PatternExpr.op(ADD, PatternExpr.op(MUL, PatternExpr.op(ADD, a, b), PatternExpr.op(SUB, a, b)), PatternExpr.op(MUL, b, b));
        var low = schema(source, PatternExpr.op(POW, a, PatternExpr.num(2)));
        var high = schema(source, PatternExpr.op(MUL, a, a));
        var wildcard = schema(a, PatternExpr.op(ADD, a, PatternExpr.num(0)));
        root.putArray("schemas").add(low).add(high).add(wildcard);
        var model = CheckedLearnedSchemaModel.load(CheckedSchemaSupport.write(root), original.inventoryHash());
        String h = high.get("id").asText(), l = low.get("id").asText(), w = wildcard.get("id").asText();
        return new Fixture(model, Set.of(h, l, w), Map.of(h, 7.0, l, -3.0, w, 100.0), h, l, w);
    }
    private static ObjectNode schema(PatternExpr source, PatternExpr target) {
        String proof = CheckedSchemaSupport.prove(source, target, original.bounds(), new CheckedSchemaSupport.Work());
        var result = CheckedSchemaSupport.JSON.createObjectNode()
            .put("id", "checked-schema:" + SchematicProofPlan.hash(original.inventorySemanticsHash() + "\n" + proof));
        result.set("source", CheckedSchemaSupport.pattern(source)); result.set("target", CheckedSchemaSupport.pattern(target));
        result.putArray("assumptions"); result.put("proofHash", SchematicProofPlan.hash(proof));
        result.putArray("supportingObservationIds").add("public-ordering-control");
        return result;
    }
}
