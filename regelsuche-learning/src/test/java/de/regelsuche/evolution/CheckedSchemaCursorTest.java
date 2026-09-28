package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.search.program.AstTransportObservation;
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

    @Test void nativeSchemaSearchAccountsForTheActualProviderProofAndSuspendedCursorGraphs() {
        var selected=plan(model);Expr source=parse(PAIR);
        Expr goal=CODEC.decodeExpression(eager(PAIR).moves().getFirst().transformation().transformedExpression());
        for(var scheduling:List.of(MoveSearch.Scheduling.STAGED,MoveSearch.Scheduling.EAGER_CONTROL,MoveSearch.Scheduling.STAGED_INCREMENTAL)) {
            var providers=scheduling==MoveSearch.Scheduling.STAGED_INCREMENTAL?List.<NativeMoveProvider>of(selected.nativeProvider()):
                model.nativeProviders(1,Map.of(),Set.of(schemaId));
            var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(goal),providers,MoveSearch.Mode.FAST,scheduling,
                new MoveSearch.Budget(0,1,100000,10,100000000));
            try(var transport=AstTransportObservation.open()) {
                var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
                assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.outcome(),result.accounting().detail());
                assertTrue(result.accountingComplete());assertTrue(result.withinBudget());assertTrue(result.replayWork()>0);
                assertEquals(0,transport.total());assertTrue(result.accounting().peak().nodes()>0);
                assertTrue(result.accounting().resultRetained().nodes()>0);
                assertEquals(new de.regelsuche.retention.RetainedGraph.Usage(0,0,0),result.accounting().live());
                assertTrue(result.cursorReceipts().stream().allMatch(SearchExecution.Expansion::closed));
            }
        }
        var cursor=selected.nativeProvider().openSession(new TypedMoveSearch.State(source,0,0,"",List.of(),Set.of(),0),
            TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION));
        try {
            boolean suspended=false;
            for(int i=0;i<1000;i++) {
                cursor.next(2);
                var retained=assertDoesNotThrow(()->de.regelsuche.retention.RetainedGraph.measure(cursor));
                assertTrue(retained.retained().nodes()>0);
                if(cursor.snapshot().work().prepaidApplications().openApplications()>0){suspended=true;break;}
            }
            assertTrue(suspended);
        } finally {cursor.close();}
        assertEquals(0,cursor.snapshot().work().prepaidApplications().openApplications());
    }

    @Test void nativeCursorSuspendsTheSamePaidPhasesAndExportsTheSameApplications() {
        var provider=assertDoesNotThrow(()->plan(model).nativeProvider());
        var nativeState=new TypedMoveSearch.State(parse(PAIR),0,0,"",List.of(),Set.of(),0);
        var nativeContext=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        var cursor=provider.openSession(nativeState,nativeContext);
        var applications=new ArrayList<NativeMoveProof>();boolean suspended=false;
        for(int i=0;i<2000;i++) {
            cursor.next(2).ifPresent(applications::add);
            var receipt=cursor.snapshot();assertTrue(receipt.accountingComplete(),receipt.detailCode());
            var prepaid=receipt.work().prepaidApplications();
            if(prepaid!=null && prepaid.openApplications()>0) {
                suspended=true;assertFalse(prepaid.phaseCalls().isEmpty());
                assertEquals(applications.size(),receipt.work().mathematics().exactTheorySteps());
            }
            if(receipt.status()==IncrementalProviderContract.Status.EXHAUSTED || receipt.status()==IncrementalProviderContract.Status.INCONCLUSIVE)break;
        }
        assertTrue(suspended);assertEquals(2,applications.size());
        assertEquals(eager(PAIR).moves().stream().map(SearchMove::transformation).toList(),applications.stream().map(NativeMoveProof::exportLegacy).toList());
        assertEquals(2,cursor.snapshot().work().mathematics().exactTheorySteps());
        assertEquals(0,cursor.snapshot().work().metrics().candidateWork().exactTheoryWorkUnits());
        cursor.close();long paid=cursor.snapshot().work().metrics().totalWorkUnitsV2();cursor.close();
        assertEquals(paid,cursor.snapshot().work().metrics().totalWorkUnitsV2());
    }

    @Test void closingNativeSuspensionAbandonsItsTicketWithoutRefundOrIssuingProof() {
        var provider=plan(model).nativeProvider();
        var state=new TypedMoveSearch.State(parse(PAIR),0,0,"",List.of(),Set.of(),0);
        var cursor=provider.openSession(state,TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION));
        assertTrue(cursor.next(0).isEmpty());assertEquals(0,cursor.snapshot().work().metrics().totalWorkUnitsV2());
        for(int i=0;i<1000 && cursor.snapshot().work().prepaidApplications().openApplications()==0;i++)assertTrue(cursor.next(2).isEmpty());
        var before=cursor.snapshot();assertEquals(1,before.work().prepaidApplications().openApplications());
        assertTrue(before.work().prepaidApplications().chargedUnits()>0);
        cursor.close();var after=cursor.snapshot();
        assertTrue(after.closed());assertEquals(0,after.work().prepaidApplications().openApplications());
        assertEquals(1,after.work().prepaidApplications().abandonedApplications());
        assertEquals(before.work().prepaidApplications().chargedUnits(),after.work().prepaidApplications().chargedUnits());
        assertEquals(before.work().prepaidApplications().phaseCalls(),after.work().prepaidApplications().phaseCalls());
        assertEquals(before.work().metrics().totalWorkUnitsV2()+1,after.work().metrics().totalWorkUnitsV2());
        assertEquals(0,after.work().mathematics().exactTheorySteps());assertTrue(cursor.next(10000).isEmpty());
    }

    @Test void nativeStagedFrontierUsesTheSameCursorOrderingAndClosesItsReceipts() {
        var selected=plan(model);Expr source=parse(PAIR);
        Expr goal=CODEC.decodeExpression(eager(PAIR).moves().getFirst().transformation().transformedExpression());
        var context=TypedMoveSearch.Context.frozen(goal);var budget=new MoveSearch.Budget(0,1,100000,10,1000000);
        var legacy=new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,context,List.of(selected.provider()),
            MovePriorityPolicy.INVENTORY_ORDER,model.verifier(),s->0,MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED_INCREMENTAL,budget));
        var result=assertDoesNotThrow(()->new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,List.of(selected.nativeProvider()),
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED_INCREMENTAL,budget),SearchContinuationContract.PATH_SENSITIVE));
        var projection=result.exportLegacy();assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.outcome());
        assertEquals(legacy.encodedResult().witness(),projection.witness());assertEquals(legacy.encodedResult().events(),projection.events());
        assertEquals(legacy.metrics(),result.metrics());
        var receipts=projection.stagedIncrementalExecution();assertNotNull(receipts);assertTrue(receipts.accountingComplete());
        assertTrue(receipts.expansions().stream().allMatch(StagedIncrementalMoveExecution.Expansion::closed));
        var cursor=receipts.expansions().getFirst().lanes().getFirst().cursor();
        assertTrue(cursor.closed());assertEquals(1,cursor.work().mathematics().exactTheorySteps());
        assertEquals(0,cursor.work().prepaidApplications().openApplications());
    }

    @Test void nativeStagedSourceOnlyFinalReplayAndPartialBudgetRetainPrepaidWork() {
        var provider=plan(model).nativeProvider();Expr source=parse(PAIR);
        var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        var verifier=NativeVerifier.registered(List.of(provider));var checks=new java.util.concurrent.atomic.AtomicInteger();
        var problem=new NativeMoveSearch.Problem(source,context,List.of(provider),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED_INCREMENTAL,
            new MoveSearch.Budget(0,1,100000,10,1000000),NativeMovePriorityPolicy.INVENTORY_ORDER,s->0,NativeStateValue.NONE,
            (s,m,c)->{checks.incrementAndGet();return verifier.verify(s,m,c);});
        try(var transport=AstTransportObservation.open()) {
        var quality=new NativeMoveSearch().searchUntil(problem,s->new TypedSourceOnlySearch.Score(s.searchDepth()==0?1:0,1),0,SearchContinuationContract.PATH_SENSITIVE);
        assertTrue(quality.withinBudget());assertEquals(2,checks.get());assertTrue(quality.replayWork()>0);
        assertTrue(quality.search().cursorReceipts().stream().allMatch(SearchExecution.Expansion::closed));
        assertEquals(0,transport.total(),"checked schema generation, selection, admission and final replay must stay native");
        }
        boolean abandoned=false;
        for(long budget:List.of(8L,16L,32L,64L,128L,256L,512L,1024L)) {
            var limited=new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,List.of(provider),MoveSearch.Mode.FAST,
                MoveSearch.Scheduling.STAGED_INCREMENTAL,new MoveSearch.Budget(0,1,100000,10,budget)),SearchContinuationContract.PATH_SENSITIVE);
            for(var expansion:limited.cursorReceipts())for(var lane:expansion.lanes())if(lane.cursor()!=null) {
                assertTrue(lane.cursor().closed());var prepaid=lane.cursor().work().prepaidApplications();
                if(prepaid.abandonedApplications()>0) {
                    abandoned=true;assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED,limited.outcome());
                    assertTrue(prepaid.chargedUnits()>0);assertEquals(0,prepaid.openApplications());
                    assertTrue(limited.metrics().totalWork()>=prepaid.chargedUnits());
                }
            }
        }
        assertTrue(abandoned,"a bounded real search must expose the paid suspended phase at cleanup");
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
