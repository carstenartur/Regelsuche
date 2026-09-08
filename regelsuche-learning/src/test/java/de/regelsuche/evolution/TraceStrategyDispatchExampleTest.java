package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.Profile;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkSearchReplay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class TraceStrategyDispatchExampleTest {
    @Test
    void fixedNewBatchIncludesEveryLossAndMatchesThePublishedReference(@TempDir Path output) throws Exception {
        Path repository = Path.of(System.getProperty("regelsuche.repositoryRoot", System.getProperty("user.dir"))).toAbsolutePath();
        while (repository != null && !Files.isRegularFile(repository.resolve("settings.gradle"))) repository = repository.getParent();
        assertNotNull(repository);
        assertEquals(Files.readString(repository.resolve("docs/generated/trace-strategy-dispatch-protocol.json")),
            TraceStrategyDispatchExample.protocol(), "protocol was fixed before application");
        var report = TraceStrategyDispatchExample.run();
        assertEquals(Files.readString(repository.resolve("docs/generated/trace-strategy-dispatch-reference.json")), report.summaryJson());
        assertEquals(Files.readString(repository.resolve("docs/generated/trace-strategy-dispatch-reference.md")), TraceStrategyDispatchExample.markdown(report));
        assertEquals(1152, report.rows().size());
        assertTrue(report.passes());
        assertTrue(report.work(Profile.LEARNED_DISPATCH) < report.work(Profile.UNGATED_CONTINUATIONS));
        assertTrue(report.policy().learningWork() + report.work(Profile.LEARNED_DISPATCH) > report.work(Profile.FLAT_GREEDY),
            "do not hide the still-unpaid learning cost on this batch");
        var exact = new ExactPolynomialAnalysis();
        var identities = new HashSet<String>();
        for (var example : TraceStrategyDispatchExample.evaluationInputs()) {
            String identity = exact.alphaIdentity(example.input().expression());
            assertTrue(identities.add(identity), "duplicate application polynomial");
            assertFalse(report.policy().exclusions().contains(identity));
        }
        for (var row : report.rows()) {
            var result = row.observation().search();
            assertEquals("", result.configuration().targetExpression());
            assertFalse(result.reached());
            assertEquals(report.policy().limits().budget(), result.configuration().budget());
            assertTrue(result.bestState().primitiveDepth() <= result.configuration().budget().maxPrimitiveSteps());
            exact.requireEquivalent(row.example().input().expression(), result.bestState().expression());
            // Replay each profile and structural family, including misleading hints.
            if (row.example().input().id().endsWith("-0")) assertEquals(result, WorkSearchReplay.verify(result.toCanonicalJson(),
                Problem.withoutTarget(row.example().input().expression(), new SearchExpansionSource.Measured(
                    TraceStrategyDispatchLearner.engine(report.policy(), row.profile())), new ExpressionScorer(),
                    new ExpressionCanonicalizer(), report.policy().limits().budget())));
        }
        var prepared = new TraceStrategyDispatchLearner().prepare(report.policy(), Profile.LEARNED_DISPATCH);
        var input = TraceStrategyDispatchExample.evaluationInputs().getFirst().input();
        var first = prepared.apply(input);
        prepared.apply(TraceStrategyDispatchExample.evaluationInputs().getLast().input());
        assertEquals(first, prepared.apply(input), "prepared application cannot learn from previous application outcomes");

        Path directory = TraceStrategyDispatchExample.write(report, output);
        assertEquals(directory, TraceStrategyDispatchExample.write(report, output));
        var mapper = new ObjectMapper();
        String manifestText = Files.readString(directory.resolve("manifest.json"));
        var manifest = mapper.readTree(manifestText);
        assertEquals(report.contentHash(), manifest.get("reportHash").asText());
        assertEquals(SchematicProofPlan.hash(manifestText).substring(7), directory.getFileName().toString());
        for (var artifact : manifest.get("artifacts")) assertEquals(artifact.get("sha256").asText(),
            SchematicProofPlan.hash(Files.readString(directory.resolve(artifact.get("name").asText()))));
        String html = Files.readString(directory.resolve("index.html"));
        assertEquals(288, html.split("<details data-case>", -1).length - 1);
        assertTrue(html.contains("same-hint-mismatch-31"));
        assertTrue(html.contains("Regel:"));
        assertTrue(html.contains("Lernen plus Anwendung auf diesem Bestand günstiger als Greedy: nein"));
        String retainedOutput = System.getProperty("regelsuche.strategyDispatch.output");
        if (retainedOutput != null) System.out.println(TraceStrategyDispatchExample.write(report, Path.of(retainedOutput)).resolve("index.html"));
    }
}
