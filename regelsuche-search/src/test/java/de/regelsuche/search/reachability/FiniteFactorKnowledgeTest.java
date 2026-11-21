package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;
import org.junit.jupiter.api.Test;

class FiniteFactorKnowledgeTest {
    private static final String SOURCE = "pair(a*x+a*y,b*x+b*y)";
    private static final String TARGET = "pair(a*(x+y),b*(x+y))";
    private static final AssumptionSignature CONTEXT = AssumptionSignature.ofExpressions(List.of("x != 0"));

    @Test void aPersistedFiniteTableIsRebuiltAndEveryQueryReplaysItsActualPrimitives() {
        var plan = new FiniteFactorKnowledge.Plan(List.of(SOURCE), CONTEXT, 3, 128, 10000);
        var table = FiniteFactorKnowledge.generate(plan);
        assertTrue(table.acquisition().calls() > 0);
        var result = table.lookup(SOURCE,TARGET,CONTEXT,new FiniteFactorKnowledge.QueryBudget(3,10000,1000));
        assertEquals(FiniteFactorKnowledge.Status.REACHED,result.status());
        assertEquals(2,result.witness().size());
        assertTrue(result.lookupWork() > 0);
        assertTrue(result.replayWork().calls() > 0);
        assertTrue(new ReversibleFactorSearch().replays(SOURCE,TARGET,CONTEXT,result.witness()));
        var restored = FiniteFactorKnowledge.restore(table.toCanonicalJson());
        assertEquals(table.toCanonicalJson(),restored.toCanonicalJson());
        assertEquals(table.acquisition(),restored.restoration());
        assertEquals(result,restored.lookup(SOURCE,TARGET,CONTEXT,new FiniteFactorKnowledge.QueryBudget(3,10000,1000)));
        assertThrows(IllegalArgumentException.class,()->FiniteFactorKnowledge.restore(table.toCanonicalJson().replace("ast_factor_common_left","forged_rule")));
    }

    @Test void missesNeverAuthorizeUnreachabilityAndContextOrReplayBudgetCannotBeBypassed() {
        var table = FiniteFactorKnowledge.generate(new FiniteFactorKnowledge.Plan(List.of(SOURCE),CONTEXT,1,128,10000));
        var budget = new FiniteFactorKnowledge.QueryBudget(3,10000,1000);
        assertEquals(FiniteFactorKnowledge.Status.NOT_IN_TABLE,table.lookup(SOURCE,"foreign",CONTEXT,budget).status());
        assertEquals(FiniteFactorKnowledge.Status.CONTEXT_MISMATCH,table.lookup(SOURCE,TARGET,AssumptionSignature.ofExpressions(List.of()),budget).status());
        assertEquals(FiniteFactorKnowledge.Status.OUTSIDE_LANGUAGE,table.lookup("foreign",TARGET,CONTEXT,budget).status());
        var single = table.lookup(SOURCE,"pair(a*(x+y),b*x+b*y)",CONTEXT,new FiniteFactorKnowledge.QueryBudget(1,10000,0));
        assertEquals(FiniteFactorKnowledge.Status.WORK_EXHAUSTED,single.status());
        assertTrue(single.witness().isEmpty());
        var shortLookup = table.lookup(SOURCE,TARGET,CONTEXT,new FiniteFactorKnowledge.QueryBudget(3,0,1000));
        assertEquals(FiniteFactorKnowledge.Status.WORK_EXHAUSTED,shortLookup.status());
        assertThrows(IllegalArgumentException.class,()->FiniteFactorKnowledge.generate(new FiniteFactorKnowledge.Plan(List.of(SOURCE),CONTEXT,3,128,0)));
    }

    @Test void deterministicParallelPortfolioRetainsEveryLaneAndAllPaidWork() {
        var lanes = List.of(new FactorSearchPortfolio.Lane("forward",ReversibleFactorSearch.Mode.ONE_SIDED,1000),
            new FactorSearchPortfolio.Lane("meeting",ReversibleFactorSearch.Mode.BIDIRECTIONAL,1000),
            new FactorSearchPortfolio.Lane("deepening",ReversibleFactorSearch.Mode.ITERATIVE_DEEPENING,1000));
        var serial = FactorSearchPortfolio.run(SOURCE,TARGET,CONTEXT,2,lanes,1);
        var parallel = FactorSearchPortfolio.run(SOURCE,TARGET,CONTEXT,2,lanes,3);
        assertEquals(serial,parallel);
        assertEquals(3,parallel.lanes().size());
        assertEquals(2,parallel.selected().orElseThrow().witness().size());
        assertEquals(parallel.lanes().stream().mapToInt(r->r.result().work().totalCalls()).sum(),parallel.totalCalls());
        assertTrue(parallel.totalCalls()>parallel.selected().orElseThrow().work().totalCalls());
        assertThrows(IllegalArgumentException.class,()->FactorSearchPortfolio.run(SOURCE,TARGET,CONTEXT,2,List.of(lanes.getFirst(),lanes.getFirst()),2));
    }

    @Test void aRetainedGoalBeyondTheQueryDepthIsDifferentFromAnAbsentGoal() {
        var table = FiniteFactorKnowledge.generate(new FiniteFactorKnowledge.Plan(List.of(SOURCE), CONTEXT, 2, 128, 1000));
        var shortBudget = new FiniteFactorKnowledge.QueryBudget(1, 1000, 100);
        var tooDeep = table.lookup(SOURCE, TARGET, CONTEXT, shortBudget);
        assertEquals(FiniteFactorKnowledge.Status.NO_PATH_IN_TABLE_WITHIN_QUERY_DEPTH, tooDeep.status());
        assertTrue(tooDeep.witness().isEmpty());
        assertEquals(0, tooDeep.replayWork().calls());
        assertEquals(FiniteFactorKnowledge.Status.NOT_IN_TABLE, table.lookup(SOURCE, "foreign", CONTEXT, shortBudget).status());
        assertEquals(FiniteFactorKnowledge.Status.REACHED,
            table.lookup(SOURCE, TARGET, CONTEXT, new FiniteFactorKnowledge.QueryBudget(2, 1000, 100)).status());
    }

    @Test void generationRespectsTheCanonicalByteBoundaryAndRetainsSpentWorkOnFailure() {
        var plan = new FiniteFactorKnowledge.Plan(List.of(SOURCE), CONTEXT, 2, 128, 1000);
        var full = FiniteFactorKnowledge.generate(plan);
        int bytes = full.toCanonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        var exact = FiniteFactorKnowledge.generate(plan, bytes);
        assertEquals(full.toCanonicalJson(), FiniteFactorKnowledge.restore(exact.toCanonicalJson()).toCanonicalJson());
        var refused = assertThrows(FiniteFactorKnowledge.GenerationFailure.class,
            () -> FiniteFactorKnowledge.generate(plan, bytes - 1));
        assertEquals(full.acquisition(), refused.work());
        assertTrue(refused.work().calls() > 0);
    }

    @Test void completedFinalPassDoesNotChargeForAnOrderingHintItCannotUse() {
        var exhausted = GuidedFactorSearch.search("a*x+a*y", "z", CONTEXT, 1, 100, 16, 4);
        assertEquals(ReversibleFactorSearch.Status.WORK_EXHAUSTED, exhausted.result().status());
        var exact = GuidedFactorSearch.search("a*x+a*y", "z", CONTEXT, 1, 100, 16, 5);
        assertEquals(ReversibleFactorSearch.Status.NO_PATH_WITHIN_DEPTH, exact.result().status());
        assertEquals(5, exact.lookupOperations());
        assertEquals(5, exact.passes().getFirst().lookupOperations());
        assertTrue(exact.result().witness().isEmpty());
    }

    @Test void iterativeGuidanceReusesPaidGenerationWithoutTreatingPriorPassesAsPruningProofs() {
        var guided=GuidedFactorSearch.search(SOURCE,TARGET,CONTEXT,3,1000,8);
        assertEquals(ReversibleFactorSearch.Status.REACHED,guided.result().status());
        assertEquals(2,guided.result().witness().size());
        assertEquals(List.of(1,2),guided.passes().stream().map(GuidedFactorSearch.Pass::depth).toList());
        assertTrue(guided.cacheHits()>0);
        assertTrue(guided.lookupOperations()>0);
        assertTrue(guided.result().work().replay().calls()>0);
        assertTrue(new ReversibleFactorSearch().replays(SOURCE,TARGET,CONTEXT,guided.result().witness()));
        var exhausted=GuidedFactorSearch.search(SOURCE,TARGET,CONTEXT,3,guided.result().work().totalCalls()-1,8);
        assertEquals(ReversibleFactorSearch.Status.WORK_EXHAUSTED,exhausted.result().status());
        assertTrue(exhausted.result().witness().isEmpty());
        var tiny=GuidedFactorSearch.search(SOURCE,TARGET,CONTEXT,3,1000,1);
        assertEquals(ReversibleFactorSearch.Status.REACHED,tiny.result().status());
        assertTrue(tiny.replacements()>0);
    }

    @Test void completeReferenceClosureAgreesAtEveryDepthAndForEveryEmittedEndpoint() {
        var search=new ReversibleFactorSearch();
        for(String source:List.of(SOURCE,"triple(a*x+a*y,b*x+b*y,c*x+c*y)")) {
            var complete=search.closure(source,CONTEXT,new BoundedReachabilityOracle.Budget(4,4,4096,100000),100000);
            assertEquals(BoundedReachabilityOracle.Status.UNREACHABLE_IN_COMPLETE_BOUNDED_CLOSURE,complete.oracle().status());
            var table=FiniteFactorKnowledge.generate(new FiniteFactorKnowledge.Plan(List.of(source),CONTEXT,4,4096,100000));
            var distances=complete.oracle().states().stream().collect(java.util.stream.Collectors.toMap(
                BoundedReachabilityOracle.RetainedState::expression,BoundedReachabilityOracle.RetainedState::depth,Math::min));
            assertEquals(distances,table.distanceFromSeeds());
            assertEquals(distances.keySet(),java.util.Set.copyOf(table.language()));
            for(var endpoint:distances.entrySet()) for(int depth=0;depth<=4;depth++) {
                var found=table.lookup(source,endpoint.getKey(),CONTEXT,new FiniteFactorKnowledge.QueryBudget(depth,100000,10000));
                boolean reachable=endpoint.getValue()<=depth;
                assertEquals(reachable,found.status()==FiniteFactorKnowledge.Status.REACHED,endpoint.getKey()+" depth="+depth);
                var guided=GuidedFactorSearch.search(source,endpoint.getKey(),CONTEXT,depth,100000,2);
                assertEquals(reachable,guided.result().status()==ReversibleFactorSearch.Status.REACHED);
                if(reachable) assertEquals(endpoint.getValue(),found.witness().size());
            }
        }
    }

    @Test void aCorrectDigestCannotAuthorizeForgedPersistedMathematics() throws Exception {
        var table=FiniteFactorKnowledge.generate(new FiniteFactorKnowledge.Plan(List.of(SOURCE),CONTEXT,2,128,10000));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var encoded=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(table.toCanonicalJson());
        var content=(com.fasterxml.jackson.databind.node.ObjectNode)encoded.get("content");
        var first=(com.fasterxml.jackson.databind.node.ObjectNode)content.get("edges").get(0);
        first.put("to","forged");
        String digest="sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(json.writeValueAsBytes(content)));
        encoded.put("contentHash",digest);
        assertThrows(IllegalArgumentException.class,()->FiniteFactorKnowledge.restore(json.writeValueAsString(encoded)));
        assertThrows(IllegalArgumentException.class,()->FiniteFactorKnowledge.restore("{\"content\":null,\"contentHash\":\"invalid\"}"));
    }

    @Test void paidAdjacencyReuseDoesNotAllowUnlimitedControllerWork() {
        var limited=GuidedFactorSearch.search(SOURCE,TARGET,CONTEXT,10,10000,128,2);
        assertEquals(ReversibleFactorSearch.Status.WORK_EXHAUSTED,limited.result().status());
        assertEquals("LOOKUP_LIMIT_REACHED",limited.terminalReason());
        assertTrue(limited.lookupOperations()<=2);
        assertTrue(limited.result().witness().isEmpty());
        assertFalse(limited.passes().isEmpty());
    }

    @Test void anUnreplayedCandidateIsNeverLabeledAsVerifiedReachability() {
        var run=GuidedFactorSearch.search("a*x+a*y","a*(x+y)",CONTEXT,1,2,16,10000);
        assertEquals(ReversibleFactorSearch.Status.WORK_EXHAUSTED,run.result().status());
        assertTrue(run.result().witness().isEmpty());
        assertTrue(run.passes().getFirst().candidateFound());
        assertEquals(0,run.result().work().replay().calls());
    }

    @Test void theIndependentReceiverRejectsALegitimatePrimitiveAttachedToAForgedEndpoint() throws Exception {
        String source=de.regelsuche.parse.ExpressionFormatter.format(new de.regelsuche.parse.ExpressionParser().parseTerm("a*x+a*y"));
        var primitive=new de.regelsuche.transform.AstRewriteTransformationEngine().transform(source).stream()
            .filter(t->t.rule().equals("ast_factor_common_left")).findFirst().orElseThrow();
        var forged=new ReversibleFactorSearch.Step(new ReversibleFactorSearch.State(source,CONTEXT),
            new ReversibleFactorSearch.State("z",CONTEXT),primitive);
        var constructor=GuidedFactorSearch.class.getDeclaredConstructor(String.class,String.class,AssumptionSignature.class,
            int.class,int.class,int.class,long.class);
        constructor.setAccessible(true);
        var receiver=constructor.newInstance(source,"z",CONTEXT,1,100,16,10000L);
        var check=GuidedFactorSearch.class.getDeclaredMethod("checked",List.class);check.setAccessible(true);
        var rejected=assertThrows(java.lang.reflect.InvocationTargetException.class,()->check.invoke(receiver,List.of(forged)));
        assertInstanceOf(IllegalStateException.class,rejected.getCause());
    }
}
