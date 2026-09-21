package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.evolution.TraceStrategyTransferExample;
import de.regelsuche.evolution.TypedLearnedMoveInventory;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Wire-protocol negatives, not mathematical evidence supplied by a trusted worker. */
@Timeout(60)
class WorkReplacementChildAccountingTest {
    @Test void incompleteChildCannotBecomeACompleteParentAccount() {
        assertCompleteness("false", false);
    }

    @Test void absentCompletenessIsNotAssumedTrue() {
        assertCompleteness("missing", false);
    }

    @Test void malformedCompletenessIsNotCoercedToTrue() {
        assertCompleteness("string", false);
    }

    @Test void explicitCompleteChildPreservesItsPaidWork() {
        assertCompleteness("true", true);
    }

    private static void assertCompleteness(String marker, boolean expected) {
        var journal = new WorkReplacementExperiment.Journal();
        try (var child = new WorkReplacementLifecycleIntegrationTest.Child(AccountingWorker.class,
                Map.of("marker", marker), TimeUnit.SECONDS.toNanos(30), journal, "probe/setup")) {
            var evaluation = child.execute(query(), 10_000, quality(), journal, "probe/query");
            assertTrue(evaluation.validProof(), "the wire fixture deliberately claims a valid result");
            assertEquals(7, journal.account().work(LifecycleWorkAccount.Phase.QUERY),
                "known partial work must survive even when the full cost is unknown");
            assertEquals(expected, journal.complete(),
                "only an explicit boolean true can certify complete child accounting");
        }
    }

    private static WorkReplacementExperiment.Query query() {
        var inventory = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory());
        var problem = new TypedMoveSearch.Problem(new ExpressionParser().parseTerm("x"),
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION), List.of(),
            MovePriorityPolicy.INVENTORY_ORDER, inventory.newSearchSession(false, 0, 0).verifier(),
            state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED_INCREMENTAL,
            new MoveSearch.Budget(4, 4, 100, 16, 10_000));
        return new WorkReplacementExperiment.Query("wire-probe", WorkReplacementLearning.identity("x"),
            problem, WorkReplacementProcessFixture::paidScore);
    }

    private static WorkReplacementManifest.Quality quality() {
        return new WorkReplacementManifest.Quality("paid-node-traversal/v1",
            WorkReplacementManifest.QualityMode.SUFFICIENT_QUALITY_MIN_WORK, 3,
            SearchContinuationContract.PATH_SENSITIVE);
    }

    /** Controlled protocol peer. No transformation produced here is used as a mathematics test. */
    public static final class AccountingWorker {
        public static void main(String[] args) throws Exception {
            var json = new ObjectMapper();
            var input = new BufferedReader(new InputStreamReader(System.in));
            String marker = json.readTree(input.readLine()).path("marker").asText();
            System.out.println("[]");
            for (String line; (line = input.readLine()) != null;) {
                var request = json.readTree(line);
                var evaluation = new WorkReplacementExperiment.Evaluation(true, true, 1, 1,
                    request.path("source").asText(), "protocol-fixture", List.of());
                var receipt = new LifecycleWorkAccount.Receipt("known-part", LifecycleWorkAccount.Phase.QUERY,
                    7, 7, List.of(), "protocol-fixture/v1", "partial work", "");
                var response = json.createObjectNode();
                response.set("evaluation", json.valueToTree(evaluation));
                response.set("receipts", json.valueToTree(List.of(receipt)));
                switch (marker) {
                    case "true" -> response.put("accountingComplete", true);
                    case "false" -> response.put("accountingComplete", false);
                    case "string" -> response.put("accountingComplete", "true");
                    case "missing" -> { }
                    default -> throw new IllegalArgumentException("unknown protocol probe");
                }
                System.out.println(json.writeValueAsString(response));
            }
        }
    }
}
