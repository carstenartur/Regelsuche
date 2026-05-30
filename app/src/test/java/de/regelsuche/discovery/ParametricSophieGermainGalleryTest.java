package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.benchmark.DiscoveryReplayArtifactWriter;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.learning.MacroLearningPipeline;
import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParametricSophieGermainGalleryTest {
    @TempDir
    Path tempDir;

    private final ExpressionScorer scorer = new ExpressionScorer();
    private final SymPyEquivalenceService equivalence = new SymPyEquivalenceService();

    @Test
    void parametricSophieGermainGalleryComesFromReplayAndMacroReuseArtifacts() throws Exception {
        SuccessfulTransformationPath discoveryPath = hiddenStructureReplayPath(
            "x^4 + 4*y^4",
            "sophie-symbolic-real-hidden-structure-replay"
        );
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroLearningResult result = new MacroLearningPipeline(inventory).learn(List.of(discoveryPath));
        assertFalse(result.newlyActivated().isEmpty(), result.stageEvidence().toString());

        ReusableRule learned = result.newlyActivated().getFirst();
        String reuseInput = "(x+1)^4 + 4*z^4";
        MacroMoveTransformationEngine engine = new MacroMoveTransformationEngine(
            new AstRewriteTransformationEngine(List.of(), 0, 0),
            new GoalAwareMacroMoveSelector(inventory),
            null,
            Map.of(learned.id(), atomicSteps(discoveryPath)),
            learned.assumptions()
        );
        List<Transformation> reused = engine.transform(reuseInput).stream()
            .filter(transformation -> transformation.rule().equals(learned.id()))
            .toList();
        assertFalse(reused.isEmpty(), result.stageEvidence().toString());
        assertTrue(equivalence.areEquivalent(reuseInput, reused.getFirst().transformedExpression()),
            reused.toString());

        DeterministicDiscoveryExperimentRunner.DiscoveryReport report =
            new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
                List.of(discoveryRow(discoveryPath), macroReuseRow(reuseInput, reused.getFirst(), learned)),
                new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(2, 2, 2, 0, 0L, 0L),
                0L
            );
        DiscoveryReplayArtifactWriter.ArtifactBundle artifacts =
            new DiscoveryReplayArtifactWriter().write(report, tempDir.resolve("gallery"));
        if (Boolean.getBoolean("regelsuche.recordDocs")) {
            Path screenshots = locateRepoRoot().resolve("docs/assets/screenshots");
            Files.createDirectories(screenshots);
            Files.copy(artifacts.screenshotPng(), screenshots.resolve("parametric-sophie-germain-discovery.png"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(artifacts.mermaidGraph(), screenshots.resolve("parametric-sophie-germain-discovery.mmd"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(artifacts.gallerySnippet(), screenshots.resolve("parametric-sophie-germain-gallery-snippet.md"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        String snippet = Files.readString(artifacts.gallerySnippet());
        assertTrue(snippet.contains("A^4 + 4*B^4"), snippet);
        assertTrue(snippet.contains("(x+1)^4 + 4*z^4"), snippet);
        assertTrue(snippet.contains("replay source: generated search/replay path in this report"), snippet);
        assertTrue(snippet.contains("sophie-symbolic-real-hidden-structure-replay"), snippet);
        assertTrue(Files.exists(artifacts.mermaidGraph()));
        assertTrue(Files.size(artifacts.mermaidGraph()) > 0);
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunReport discoveryRow(SuccessfulTransformationPath path) {
        return new DeterministicDiscoveryExperimentRunner.SeedRunReport(
            new SeedExpression("hidden-sophie-germain-parametric", path.sourceExpression(), "test", "hidden-structure",
                List.of(), List.of()),
            true,
            "parametric Sophie-Germain path reached square-difference state and factored",
            List.of(path.targetExpression()),
            List.of(),
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            List.of(),
            List.of(),
            "",
            path.expressionPath(),
            DiscoveryResultKind.TRANSFORMED,
            path.rules(),
            0L,
            0L,
            Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED, DiscoveryEvidenceKind.FACTORED)
        );
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunReport macroReuseRow(
        String reuseInput,
        Transformation reused,
        ReusableRule learned
    ) {
        return new DeterministicDiscoveryExperimentRunner.SeedRunReport(
            new SeedExpression("sophie-germain-parametric-macro-reuse", reuseInput, "test", "hidden-structure",
                List.of(), List.of()),
            true,
            "parametric Sophie-Germain macro learned from replay evidence and reused",
            List.of(learned.leftPattern() + " -> " + learned.rightPattern()),
            List.of(),
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            List.of(),
            List.of(),
            "",
            List.of(reuseInput, reused.transformedExpression()),
            DiscoveryResultKind.HYPOTHESIS_ONLY,
            List.of(learned.id()),
            0L,
            0L,
            Set.of(
                DiscoveryEvidenceKind.MACRO_LEARNED,
                DiscoveryEvidenceKind.MACRO_REUSED,
                DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED
            )
        );
    }

    private SuccessfulTransformationPath hiddenStructureReplayPath(String source, String pathId) {
        HypothesisTransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );
        SearchProblem problem = new SearchProblem(
            source,
            engine,
            scorer,
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 160, 1, 10, 200, 200)
        );
        SearchState factoredState = new BestFirstSearchStrategy().search(problem).stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .filter(state -> state.appliedRuleIds().contains("ast_square_difference_factor"))
            .findFirst()
            .orElseThrow();
        return new SuccessfulTransformationPath(
            pathId,
            factoredState.path().getFirst(),
            factoredState.path().getLast(),
            factoredState.path(),
            factoredState.appliedRuleIds(),
            scorer.score(factoredState.path().getFirst()),
            factoredState.score(),
            true,
            "search problem replay with polynomial equivalence",
            Map.of("source", "SearchProblem")
        );
    }

    private List<TransformationStep> atomicSteps(SuccessfulTransformationPath path) {
        java.util.ArrayList<TransformationStep> steps = new java.util.ArrayList<>();
        for (int index = 0; index < path.rules().size(); index++) {
            String before = path.expressionPath().get(index);
            String after = path.expressionPath().get(index + 1);
            steps.add(new TransformationStep(
                index,
                before,
                after,
                path.rules().get(index),
                RewriteKind.NORMALIZE,
                scorer.score(before).weightedTotal(),
                scorer.score(after).weightedTotal(),
                true,
                path.rules().get(index),
                path.assumptions()
            ));
        }
        return steps;
    }

    private static Path locateRepoRoot() {
        Path candidate = Path.of(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(candidate.resolve("README.md"))
                && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent;
        }
        throw new IllegalStateException("Could not locate repository root");
    }
}
