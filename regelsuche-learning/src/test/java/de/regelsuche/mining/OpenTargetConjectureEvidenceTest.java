package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.mining.OpenTargetConjectureEvidence.CampaignContext;
import de.regelsuche.mining.OpenTargetConjectureEvidence.SeedProvenance;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetConjectureEvidenceTest {
    private static final SearchHeuristic BUDGET = new SearchHeuristic(3, 40, 1, 2, 8, 8);

    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final OpenTargetConjectureMiner miner = new OpenTargetConjectureMiner();

    @Test
    void writesStableTargetFreeArtifactWithCompleteSeedProvenance() throws Exception {
        var first = factoringObservation("obs-2-3", "x", "2", "3");
        var second = factoringObservation("obs-4-5", "a", "4", "5");
        var report = miner.mine(List.of(first, second));
        var reversedReport = miner.mine(List.of(second, first));

        CampaignContext context = context(List.of(
            seed("obs-4-5", "seed-4-5", Map.of("right", "5", "left", "4", "common", "a")),
            seed("obs-2-3", "seed-2-3", Map.of("left", "2", "common", "x", "right", "3"))));
        CampaignContext reorderedContext = context(List.of(
            seed("obs-2-3", "seed-2-3", Map.of("right", "3", "common", "x", "left", "2")),
            seed("obs-4-5", "seed-4-5", Map.of("left", "4", "right", "5", "common", "a"))));

        OpenTargetConjectureEvidence evidence =
            new OpenTargetConjectureEvidence(context, report);
        OpenTargetConjectureEvidence reordered =
            new OpenTargetConjectureEvidence(reorderedContext, reversedReport);
        String json = evidence.toJson();
        Path output = Path.of(
            "build", "reports", "open-target-conjecture-mining", "report.json");
        evidence.write(output);

        assertEquals(evidence.contentHash(), reordered.contentHash());
        assertEquals(json, reordered.toJson());
        assertEquals(json, Files.readString(output));
        assertTrue(Files.isRegularFile(output));
        assertTrue(json.contains(
            "\"schema\":\"regelsuche.open-target-conjecture-mining/v1\""));
        assertTrue(json.contains("\"targetProvided\":false"));
        assertTrue(json.contains("\"contentHash\":\"sha256:"));
        assertTrue(json.contains("\"searchStatus\":\"UNTARGETED\""));
        assertTrue(json.contains("\"proofStatus\":\"NOT_EVALUATED\""));
        assertTrue(json.contains("\"noveltyStatus\":\"NOT_EVALUATED\""));
        assertFalse(json.contains("targetExpression"));
        assertFalse(json.contains("targetDistance"));
        assertFalse(json.contains("hiddenReference"));
    }

    @Test
    void rejectsAnArtifactWithMissingObservationProvenance() {
        var first = factoringObservation("obs-2-3", "x", "2", "3");
        var second = factoringObservation("obs-4-5", "a", "4", "5");
        var report = miner.mine(List.of(first, second));
        CampaignContext incomplete = context(List.of(
            seed("obs-2-3", "seed-2-3", Map.of("common", "x", "left", "2", "right", "3"))));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new OpenTargetConjectureEvidence(incomplete, report));

        assertTrue(exception.getMessage().contains("seed provenance"));
    }

    private CampaignContext context(List<SeedProvenance> seeds) {
        return new CampaignContext(
            "open-target-factoring-characterization",
            "open-target-evidence-test/v1",
            "test-revision",
            "sha256:test-open-target-inventory",
            BUDGET,
            seeds);
    }

    private static SeedProvenance seed(
        String observationId,
        String seedId,
        Map<String, String> parameters
    ) {
        return new SeedProvenance(
            observationId,
            seedId,
            "factoring-seed-generator/v1",
            parameters);
    }

    private OpenTargetConjectureMiner.OpenTargetObservation factoringObservation(
        String observationId,
        String commonFactor,
        String leftTerm,
        String rightTerm
    ) {
        String factor = commonFactor.contains(" ") ? "(" + commonFactor + ")" : commonFactor;
        String root = factor + " * " + leftTerm + " + " + factor + " * " + rightTerm;
        String output = factor + " * (" + leftTerm + " + " + rightTerm + ")";
        String padded = "(" + output + ") + 0";
        TransformationEngine engine = expression -> {
            if (expression.equals(root)) {
                return List.of(
                    step("open_target_factor_direct", output, expression),
                    step("open_target_factor_padded", padded, expression));
            }
            if (expression.equals(padded)) {
                return List.of(step("open_target_remove_padding", output, expression));
            }
            return List.of();
        };
        SearchProblem problem = new SearchProblem(
            root,
            engine,
            scorer,
            canonicalizer,
            BUDGET);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertEquals(GoalStatus.UNTARGETED, result.status());
        assertTrue(result.states().stream().anyMatch(state -> state.expression().equals(output)));
        return OpenTargetConjectureMiner.OpenTargetObservation.from(
            observationId,
            "factor-common",
            root,
            result);
    }

    private static Transformation step(String rule, String output, String parent) {
        return new Transformation(
            rule,
            output,
            RewriteKind.SIMPLIFY,
            false,
            -2,
            true,
            rule + ":" + parent + "->" + output);
    }
}
