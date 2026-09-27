package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckedSchemaCursorTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final String PAIR = "((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)";
    private static CheckedLearnedSchemaModel model;
    private static String schemaId;

    @BeforeAll static void learnAndRestore() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var learned = CheckedLearnedSchemaModel.learn(formation);
        model = CheckedLearnedSchemaModel.load(learned.toCanonicalJson(), learned.inventoryHash());
        assertTrue(model.loadWork() > 0);
        schemaId = model.providers().getFirst().candidates(state("(x+y)*(x-y)+y*y"), context())
            .moves().stream().filter(move -> CODEC.decodeExpression(move.transformation().transformedExpression())
                .equals(parse("x^2"))).findFirst().orElseThrow().transformation().rule();
    }

    @Test void firstPullDoesNotInstantiateTheSecondRealLearnedOccurrence() {
        var eager = eager(PAIR);
        assertEquals(2, eager.moves().size(), "the real learned schema has two distinct application sites");
        var provider = plan(model).provider();
        var cursor = provider.openSession(state(PAIR), context());
        var first = cursor.next(100_000).orElseThrow();
        assertEquals(eager.moves().getFirst().transformation(), first);
        assertEquals(1, cursor.snapshot().work().mathematics().exactTheorySteps(),
            "one consumed application must not pre-build the second proof");
        assertTrue(model.verifier().verify(new TypedMoveSearch.State(parse(PAIR), 0, 0, "",
            List.of(), Set.of(), 0), SearchMove.from(first, provider.descriptor(), 0),
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION)).accepted());
        cursor.close();
        assertEquals(1, cursor.snapshot().work().mathematics().exactTheorySteps());
    }

    @Test void zeroBudgetAndEarlyCloseDoNotCreateApplications() {
        var cursor = plan(model).provider().openSession(state(PAIR), context());
        assertTrue(cursor.next(0).isEmpty());
        assertEquals(0, cursor.snapshot().work().mathematics().exactTheorySteps());
        cursor.close();
        long paid = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        cursor.close();
        assertEquals(paid, cursor.snapshot().work().metrics().totalWorkUnitsV2());
        assertTrue(cursor.next(100_000).isEmpty());
        assertEquals(0, cursor.snapshot().work().mathematics().exactTheorySteps());
    }

    @Test void completeDrainPreservesEagerCandidateOrderAndEvidence() {
        for (String expression : List.of("(x+y)*(x-y)+y*y", PAIR,
                "7*((x+1+y)*(x+1-y)+y*y)", "(x+y)*(x-y)+z*z", "x+1")) {
            var expected = eager(expression);
            var cursor = plan(model).provider().openSession(state(expression), context());
            var actual = new ArrayList<Transformation>();
            for (int pulls = 0; pulls < 1_000; pulls++) {
                cursor.next(100_000).ifPresent(actual::add);
                var status = cursor.snapshot().status();
                assertNotEquals(IncrementalProviderContract.Status.FAILED, status, cursor.snapshot().detailCode());
                if (status == IncrementalProviderContract.Status.EXHAUSTED
                        || status == IncrementalProviderContract.Status.INCONCLUSIVE) break;
            }
            assertEquals(expected.moves().stream().map(SearchMove::transformation).toList(), actual, expression);
            assertEquals(expected.complete(), cursor.snapshot().complete(), expression);
            assertTrue(cursor.snapshot().accountingComplete());
            cursor.close();
        }
    }

    @Test void configurationIsBoundIntoTheRegisteredDefinition() {
        var first = plan(model).provider().contractDefinition();
        var differentLimit = CheckedSchemaMatcherPlan.prepare(model, 2, Map.of(), Set.of(schemaId))
            .provider().contractDefinition();
        var differentUtility = CheckedSchemaMatcherPlan.prepare(model, 1, Map.of(schemaId, 2.0), Set.of(schemaId))
            .provider().contractDefinition();
        assertNotEquals(first, differentLimit, "a different generation cap must not reuse the registration");
        assertNotEquals(first, differentUtility, "ordering configuration is part of provider identity");
    }

    @Test void prerequisitesAndUnsupportedSourcesNeverProduceAuthorizedMoves() {
        var gated = model.requiring(List.of("x > 0"));
        var missing = plan(gated).provider().openSession(state("(x+y)*(x-y)+y*y"), context());
        assertTrue(missing.next(100_000).isEmpty());
        assertEquals(0, missing.snapshot().work().mathematics().exactTheorySteps());
        missing.close();
        var unsupported = plan(model).provider().openSession(state("(1/x+y)*(1/x-y)+y*y"), context());
        assertTrue(unsupported.next(100_000).isEmpty());
        assertFalse(unsupported.snapshot().complete());
        assertTrue(unsupported.snapshot().accountingComplete());
        unsupported.close();
    }

    private static CheckedSchemaMatcherPlan plan(CheckedLearnedSchemaModel selected) {
        return CheckedSchemaMatcherPlan.prepare(selected, 1, Map.of(), Set.of(schemaId));
    }
    private static MoveProvider.Batch eager(String source) {
        return model.providers(1, Map.of(), Set.of(schemaId)).getFirst().candidates(state(source), context());
    }
    private static Expr parse(String source) { return new ExpressionParser().parseExactTerm(source).expression(); }
    private static MoveState state(String source) { return MoveState.root(CODEC.encodeExpression(parse(source))); }
    private static MoveContext context() { return MoveContext.frozen("unused"); }
}
