package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.evolution.CheckedSchemaTaskSolver;
import de.regelsuche.evolution.CumulativeTaskKnowledge;
import de.regelsuche.evolution.StructuralTaskObjective;
import de.regelsuche.evolution.TaskConstructionProfile;
import de.regelsuche.evolution.TraceStrategyTransferExample;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.web.WebSecurityConfig;
import de.regelsuche.web.WebWorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class HistoricalConstructionDemoTest {
    private static final String SOURCE = "x^4+4*y^4";
    private static final String PRODUCT = "(x^2+2*y^2-2*x*y)*(x^2+2*y^2+2*x*y)";
    private static final String ROUTE = "/api/demo/historical-sophie-germain";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void retainsTheVerifiedSourceOnlyWitnessInTheDemoAndReplay() throws Exception {
        var graph = new InMemoryExpressionGraphStore();
        var server = new WebWorkbenchServer("127.0.0.1", 0, graph,
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + server.boundPort();
            var request = HttpRequest.newBuilder(URI.create(base + ROUTE))
                .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.noBody()).build();
            var bundle = request(client, request);
            assertEquals(SOURCE, bundle.path("expression").asText());
            assertEquals("", bundle.path("expectedResultExpression").asText(""));
            assertEquals("", bundle.path("canonicalTargetExpression").asText());
            assertTrue(bundle.path("targetReached").asBoolean());
            assertFalse(bundle.has("proofOutcome"));

            var path = bundle.path("selectedPath");
            assertEquals(5, path.path("steps").asInt());
            assertEquals("SYMBOLICALLY_VERIFIED", path.path("proofStatus").asText());
            var parser = new ExpressionParser();
            String improved = path.path("improvedExpression").asText();
            assertEquals(parser.parseExactTerm(PRODUCT).expression(), parser.parseExactTerm(improved).expression());
            var exact = new ExactPolynomialAnalysis();
            exact.requireEquivalent(SOURCE, improved);

            var summary = bundle.path("constructionSummary");
            assertEquals("FACTORIZATION", summary.path("goal").asText());
            assertEquals("STRUCTURAL_EXPANSION_V1", summary.path("profile").asText());
            var budget = new MoveSearch.Budget(32, 8, 10_000_000, 512, 10_000_000);
            var actualBudget = summary.path("budget");
            assertEquals(budget.maxPrimitiveSteps(), actualBudget.path("maxPrimitiveSteps").asInt());
            assertEquals(budget.maxSearchDepth(), actualBudget.path("maxSearchDepth").asInt());
            assertEquals(budget.maxTheoryWork(), actualBudget.path("maxTheoryWork").asLong());
            assertEquals(budget.maxStates(), actualBudget.path("maxStates").asInt());
            assertEquals(budget.totalWork(), actualBudget.path("totalWork").asLong());
            assertEquals(budget.maxComplexityDebt(), actualBudget.path("maxComplexityDebt").asInt());
            var independent = sourceOnlyBaseline(parser, budget);
            assertTrue(independent.solved());
            assertEquals(independent.totalWork(), summary.path("totalWork").asLong());
            var witness = independent.solution().orElseThrow().evidence().witness();
            var replay = request(client, HttpRequest.newBuilder(URI.create(
                base + "/api/paths/" + path.path("id").asText() + "/replay")).GET().build());
            assertEquals(summary, replay.path("constructionSummary"));
            assertEquals(5, summary.path("steps").size());
            assertEquals(5, replay.path("steps").size());
            assertEquals(6, graph.snapshot().nodes().size());
            assertEquals(5, graph.snapshot().edges().size());

            String cursor = path.path("originalExpression").asText();
            for (int i = 0; i < witness.size(); i++) {
                var expected = witness.get(i);
                var evidenceStep = summary.path("steps").get(i);
                var replayStep = replay.path("steps").get(i);
                String target = ExpressionFormatter.format(expected.target().expression());
                assertEquals(ExpressionFormatter.format(expected.source().expression()), cursor);
                assertEquals(cursor, evidenceStep.path("sourceExpression").asText());
                assertEquals(target, evidenceStep.path("targetExpression").asText());
                assertEquals(expected.move().ruleId(), evidenceStep.path("ruleId").asText());
                assertEquals(expected.move().provenance().contentHash(), evidenceStep.path("provenanceHash").asText());
                var verification = evidenceStep.path("verification");
                assertTrue(verification.path("accepted").asBoolean());
                assertEquals(expected.verification().work(), verification.path("work").asLong());
                assertEquals(expected.verification().reason(), verification.path("reason").asText());
                assertEquals(expected.verification().receipts(), StreamSupport.stream(
                    verification.path("receipts").spliterator(), false).map(JsonNode::asText).toList());
                assertEquals(cursor, replayStep.path("fromExpression").asText());
                assertEquals(target, replayStep.path("toExpression").asText());
                assertEquals(expected.move().ruleId(), replayStep.path("ruleId").asText());
                assertFalse(replayStep.has("execution"));
                exact.requireEquivalent(cursor, target);
                cursor = target;
            }
            assertEquals(improved, cursor);
        } finally {
            server.stop();
        }
    }

    private static CumulativeTaskKnowledge.Execution sourceOnlyBaseline(ExpressionParser parser, MoveSearch.Budget budget) {
        var solver = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(),
            TaskConstructionProfile.STRUCTURAL_EXPANSION_V1);
        var task = new CheckedSchemaTaskSolver.Task(parser.parseExactTerm(SOURCE).expression(), List.of(),
            StructuralTaskObjective.FACTORIZATION.goal(), budget);
        return solver.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
    }

    private static JsonNode request(HttpClient client, HttpRequest request) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }
}
