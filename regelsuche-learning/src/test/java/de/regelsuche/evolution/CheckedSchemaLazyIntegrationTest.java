package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.Transformation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckedSchemaLazyIntegrationTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final String PAIR = "((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)";
    private static CheckedLearnedSchemaModel model;
    private static String schema;
    @BeforeAll static void restoreActualLearnedModel() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var learned = CheckedLearnedSchemaModel.learn(formation);
        model = CheckedLearnedSchemaModel.load(learned.toCanonicalJson(), learned.inventoryHash());
        schema = model.providers().getFirst().candidates(state("(x+y)*(x-y)+y*y"), MoveContext.frozen("unused"))
            .moves().stream().filter(move -> CODEC.decodeExpression(move.transformation().transformedExpression())
                .equals(parse("x^2"))).findFirst().orElseThrow().transformation().rule();
    }

    @Test void sameSearchKernelStopsBeforeTheSecondInstantiationAndIndependentlyReplays() {
        Expr source = parse(PAIR);
        var prepared = plan();
        var lazy = search(source, List.of(prepared.provider()), MoveSearch.Scheduling.STAGED_INCREMENTAL);
        var eager = search(source, model.providers(1, Map.of(), Set.of(schema)), MoveSearch.Scheduling.STAGED);
        assertEquals(MoveSearch.Outcome.QUALITY_REACHED, lazy.search().outcome());
        assertTrue(lazy.withinBudget());
        assertEquals(eager.incumbent().expression(), lazy.incumbent().expression());
        assertEquals(1, lazy.witness().size());
        assertEquals(eager.witness().getFirst().move().transformation(), lazy.witness().getFirst().move().transformation());
        long applications = lazy.search().encodedResult().stagedIncrementalExecution().expansions().stream()
            .flatMap(expansion -> expansion.lanes().stream()).filter(lane -> lane.cursor() != null)
            .mapToLong(lane -> lane.cursor().work().mathematics().exactTheorySteps()).sum();
        assertEquals(1, applications, "the unconsumed second occurrence has no application/proof");
        assertEquals(2, eager.search().metrics().generatedSuccessors());
        assertTrue(lazy.replayWork() > 0);
        assertTrue(prepared.compilationWork() > 0, "preparation remains an explicit additional lifecycle charge");
        // Report both directions; reduced generation alone is not a lifecycle speedup claim.
        System.out.printf("P03_MECHANISM eagerQuery=%d lazyQuery=%d eagerReplay=%d lazyReplay=%d compile=%d%n",
            eager.queryWork(), lazy.queryWork(), eager.replayWork(), lazy.replayWork(), prepared.compilationWork());
    }

    @Test void smallAllowancesResumeWithoutRecreatingAlreadyPaidApplications() {
        var prepared = plan();
        List<Transformation> expected = eager(PAIR).moves().stream().map(SearchMove::transformation).toList();
        for (long allowance : new long[] {2, 4, 16, 64, 100_000}) {
            var cursor = prepared.provider().openSession(state(PAIR), MoveContext.frozen("unused"));
            var actual = new ArrayList<Transformation>();
            long previous = 0;
            for (int pull = 0; pull < 10_000; pull++) {
                cursor.next(allowance).ifPresent(actual::add);
                var snapshot = cursor.snapshot();
                assertTrue(snapshot.work().metrics().totalWorkUnitsV2() >= previous);
                previous = snapshot.work().metrics().totalWorkUnitsV2();
                assertTrue(snapshot.accountingComplete(), snapshot.detailCode());
                if (snapshot.status() == IncrementalProviderContract.Status.EXHAUSTED
                        || snapshot.status() == IncrementalProviderContract.Status.INCONCLUSIVE) break;
            }
            assertEquals(expected, actual, "allowance=" + allowance);
            assertEquals(expected.size(), cursor.snapshot().work().mathematics().exactTheorySteps());
            assertFalse(cursor.snapshot().resumable(), "the complete drain must terminate");
            cursor.close();
        }
    }

    @Test void closingAPaidPendingApplicationDoesNotCreateOrLoseAnotherOne() {
        var cursor = plan().provider().openSession(state(PAIR), MoveContext.frozen("unused"));
        int emitted = 0;
        for (int pull = 0; pull < 10_000 && cursor.snapshot().work().mathematics().exactTheorySteps() == 0; pull++) {
            if (cursor.next(2).isPresent()) emitted++;
        }
        assertEquals(1, cursor.snapshot().work().mathematics().exactTheorySteps());
        assertEquals(0, emitted, "atomic application exhausted the small allowance before emission");
        long mathematics = cursor.snapshot().work().mathematics().exactTheoryWorkUnits();
        cursor.close();
        assertEquals(mathematics, cursor.snapshot().work().mathematics().exactTheoryWorkUnits());
        assertEquals(1, cursor.snapshot().work().mathematics().exactTheorySteps());
    }

    @Test void preparationCopiesConfigurationAndEmptySelectionDoesNotInventKnowledge() {
        var utilities = new HashMap<String, Double>();
        var included = new HashSet<>(Set.of(schema));
        var prepared = CheckedSchemaMatcherPlan.prepare(model, 1, utilities, included);
        var definition = prepared.provider().contractDefinition();
        utilities.put(schema, 9.0); included.clear();
        assertEquals(definition, prepared.provider().contractDefinition());
        var receipt = prepared.compilationReceipt();
        assertEquals(1, receipt.includedSchemas());
        assertTrue(receipt.patternNodeVisits() > 0);
        assertEquals(receipt.workUnits(), prepared.compilationWork());
        var cursor = CheckedSchemaMatcherPlan.prepare(model, 1, Map.of(), Set.of())
            .provider().openSession(state(PAIR), MoveContext.frozen("unused"));
        assertTrue(cursor.next(100_000).isEmpty());
        assertEquals(0, cursor.snapshot().work().mathematics().exactTheorySteps());
        assertFalse(cursor.snapshot().complete(), "an empty subset is not complete over a nonempty learned library");
        cursor.close();
    }

    private static TypedSourceOnlySearch.Result search(Expr source, List<MoveProvider> providers, MoveSearch.Scheduling scheduling) {
        var problem = new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION), providers,
            MovePriorityPolicy.INVENTORY_ORDER, model.verifier(), state -> 0, MoveSearch.Mode.FAST,
            scheduling, new MoveSearch.Budget(6, 1, 1_000, 32, 100_000));
        return new TypedSourceOnlySearch().searchUntil(problem, state -> {
            long size = nodes(state.expression());
            return new TypedSourceOnlySearch.Score(size, size);
        }, nodes(source) - 1, SearchContinuationContract.PATH_SENSITIVE);
    }
    private static long nodes(Expr source) {
        var pending = new ArrayDeque<Expr>(); pending.push(source);
        long count = 0;
        while (!pending.isEmpty()) {
            Expr value = pending.pop(); count++;
            if (value instanceof BinaryExpr binary) { pending.push(binary.right()); pending.push(binary.left()); }
        }
        return count;
    }
    private static CheckedSchemaMatcherPlan plan() { return CheckedSchemaMatcherPlan.prepare(model, 1, Map.of(), Set.of(schema)); }
    private static MoveProvider.Batch eager(String source) {
        return model.providers(1, Map.of(), Set.of(schema)).getFirst().candidates(state(source), MoveContext.frozen("unused"));
    }
    private static Expr parse(String source) { return new ExpressionParser().parseExactTerm(source).expression(); }
    private static MoveState state(String source) { return MoveState.root(CODEC.encodeExpression(parse(source))); }
}
