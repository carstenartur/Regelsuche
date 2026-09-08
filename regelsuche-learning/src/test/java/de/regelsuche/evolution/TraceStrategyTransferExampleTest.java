package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.Profile;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkSearchReplay;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class TraceStrategyTransferExampleTest {
    @Test
    void allProfilesRetainTheirLossesControlsAndReplayWithoutTargets(@TempDir Path output) throws Exception {
        var report = TraceStrategyTransferExample.run();
        var repeated = TraceStrategyTransferExample.run();
        assertEquals(report.toCanonicalJson(), repeated.toCanonicalJson());
        assertEquals(report.strategy().toCanonicalJson(), repeated.strategy().toCanonicalJson());
        Path repository = Path.of(System.getProperty("regelsuche.repositoryRoot", System.getProperty("user.dir"))).toAbsolutePath();
        while (repository != null && !Files.isRegularFile(repository.resolve("settings.gradle"))) repository = repository.getParent();
        assertNotNull(repository, "repository root for the published development reference");
        assertEquals(Files.readString(repository.resolve("docs/generated/trace-strategy-transfer-reference.json")),
            report.toCanonicalJson(), "public development reference must match the actual run");
        assertEquals(Files.readString(repository.resolve("docs/generated/trace-strategy-transfer-reference.md")),
            TraceStrategyTransferExample.markdown(report));
        assertEquals(24, report.rows().size());
        var exact = new ExactPolynomialAnalysis();
        var inventory = report.strategy().inventory();
        var primitive = MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine(
            new EvolutionGenomeCompiler().compile(inventory).rules(), inventory.budget().maxAstGrowthPerStep(),
            inventory.budget().maxCandidatesPerState()));
        for (var row : report.rows()) {
            if (row.example().id().equals("unsupported")) {
                assertNull(row.application());
                assertFalse(row.unsupportedReason().isBlank());
                continue;
            }
            assertNotNull(row.application());
            var result = row.application().search();
            assertFalse(result.reached());
            assertEquals(report.strategy().limits().trainingBudget(), result.configuration().budget());
            assertEquals("", result.configuration().targetExpression());
            exact.requireEquivalent(row.example().expression(), result.bestState().expression());
            var program = switch (row.profile()) {
                case FLAT_RULES -> java.util.Optional.<EvolutionRewriteProgramPlan>empty();
                case LEARNED_PROGRAM -> report.strategy().plan();
                case SHUFFLED_PROGRAM -> report.strategy().shuffledPlan();
            };
            var engine = program.isEmpty() ? primitive : MeasuredTransformationEngines.union(primitive,
                new EvolutionRewriteProgramCompiler().compile(inventory, program.orElseThrow()).engine());
            assertEquals(result, WorkSearchReplay.verify(result.toCanonicalJson(), Problem.withoutTarget(
                row.example().expression(), new SearchExpansionSource.Measured(engine), new ExpressionScorer(),
                new ExpressionCanonicalizer(), report.strategy().limits().trainingBudget())));
        }
        var simple = report.rows().stream().filter(row -> row.example().id().equals("already-simple")).toList();
        assertEquals(3, simple.size());
        assertTrue(simple.stream().allMatch(row -> row.application().search().bestState().primitiveDepth() == 0));
        long flatWork = simple.stream().filter(row -> row.profile() == Profile.FLAT_RULES).findFirst().orElseThrow()
            .application().search().metrics().chargedSearchWorkUnits();
        assertTrue(simple.stream().filter(row -> row.profile() != Profile.FLAT_RULES).allMatch(row ->
            row.application().search().metrics().chargedSearchWorkUnits() > flatWork));

        Path directory = TraceStrategyTransferExample.write(report, output);
        assertEquals(directory, TraceStrategyTransferExample.write(repeated, output));
        var manifest = new ObjectMapper().readTree(Files.readString(directory.resolve("manifest.json")));
        assertEquals(report.contentHash(), manifest.get("reportHash").asText());
        assertEquals(33, manifest.get("artifacts").size());
        for (var observation : report.strategy().observations()) {
            if (observation.minimality().isPresent()) {
                var proof = observation.minimality().orElseThrow();
                assertTrue(proof.reusableMultistepTrace());
                new PrimitiveTraceMinimalityVerifier(inventory).verify(proof);
                assertEquals(proof.toCanonicalJson(), Files.readString(directory.resolve(observation.input().id() + ".minimality.json")));
            }
        }
        for (var artifact : manifest.get("artifacts")) {
            assertEquals(artifact.get("sha256").asText(), SchematicProofPlan.hash(
                Files.readString(directory.resolve(artifact.get("name").asText()))));
        }
        String html = Files.readString(directory.resolve("index.html"));
        // Inner primitive steps of the learned cancellation must be visible, not just its macro edge.
        assertTrue(html.contains("Regel:"));
        assertTrue(html.contains("difference-product"));
        assertTrue(html.contains("square-product"));
        assertTrue(html.contains("cancel-addend"));
        assertTrue(html.contains("SHUFFLED_PROGRAM"));
        int learnedExample = html.indexOf("<summary>product-composition · LEARNED_PROGRAM");
        assertTrue(learnedExample > 0);
        String learnedReplay = html.substring(learnedExample, html.indexOf("</details>", learnedExample));
        assertEquals(4, learnedReplay.split("<li>", -1).length - 1, "source plus three inner primitive steps");
        String retainedOutput = System.getProperty("regelsuche.strategyTransfer.output");
        if (retainedOutput != null) {
            System.out.println(TraceStrategyTransferExample.write(report, Path.of(retainedOutput)).resolve("index.html").toAbsolutePath());
        }
        Files.writeString(directory.resolve("strategy.json"), "tampered");
        assertThrows(IOException.class, () -> TraceStrategyTransferExample.write(report, output));
    }
}
