package de.regelsuche.learning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.ScientificDiscoveryWorkflow;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.GeneralizedPattern;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.PatternGeneralizer;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.persistence.PersistenceConfig;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacroLearningPipelineReplayIntegrationTest {
    private static final CounterexampleSearchService NO_COUNTEREXAMPLES =
        (hypothesis, budget) -> CounterexampleSearchService.CounterexampleSearchResult.noCounterexample();

    @TempDir
    Path tempDir;

    private final SymPyEquivalenceService equivalence = new SymPyEquivalenceService();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void sophieGermainMacroLearnsFromRealHiddenStructureReplayAndReuses() {
        SuccessfulTransformationPath path = hiddenStructureReplayPath();

        assertDiscoveryReplayLearnsAndReuses(new ReplayCase(
            "sophie",
            path,
            "z^4 + 4",
            List.of(),
            ""
        ));
    }

    @Test
    void telescopingMacroLearnsFromRealOperatorCorpusWorkflowAndReuses() {
        SuccessfulTransformationPath path = operatorCorpusReplayPath(
            "telescoping-fraction",
            "1 / (n * (n + 1))"
        );

        assertDiscoveryReplayLearnsAndReuses(new ReplayCase(
            "telescoping",
            path,
            "1 / ((x + 2) * (x + 3))",
            List.of(),
            "1 / (n * (n + 2))"
        ));
    }

    @Test
    void rationalizationMacroLearnsFromRealOperatorCorpusWorkflowAndReuses() {
        SuccessfulTransformationPath path = operatorCorpusReplayPath(
            "rationalization",
            "1 / (sqrt(x) + 1)"
        );

        assertDiscoveryReplayLearnsAndReuses(new ReplayCase(
            "rationalization",
            path,
            "1 / (sqrt(y + 2) + 1)",
            List.of("A != 1"),
            ""
        ));
    }

    @Test
    void multiPlaceholderSchemasAreValidatedAndPromoted() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        PatternGeneralizer generalizer = new PatternGeneralizer() {
            @Override
            public Optional<GeneralizedPattern> generalizeSingleExampleSchema(SuccessfulTransformationPath path) {
                return Optional.of(new GeneralizedPattern(
                    "A + B",
                    "B + A",
                    Map.of(),
                    List.of("A ∈ {x}", "B ∈ {y}"),
                    Map.of("A", List.of("x"), "B", List.of("y"))
                ));
            }
        };
        MacroLearningResult result = new MacroLearningPipeline(
            inventory,
            generalizer,
            equivalence,
            NO_COUNTEREXAMPLES,
            new KnownRuleRepository(),
            0.0
        ).learn(List.of(new SuccessfulTransformationPath(
            "multi-placeholder-replay",
            "x + y",
            "y + x",
            List.of("x + y", "y + x"),
            List.of("commute"),
            scorer.score("x + y"),
            scorer.score("y + x"),
            true,
            "unit-test",
            Map.of()
        )));

        assertFalse(result.newlyActivated().isEmpty(), result.stageEvidence().toString());
        assertTrue(result.validationExamples().size() > 1, result.validationExamples().toString());
        assertTrue(result.validationExamples().stream().allMatch(MacroValidationExample::equivalent));
        assertTrue(result.stageEvidence().stream()
        .anyMatch(stage -> stage.contains("generate placeholder substitutions")),
        result.stageEvidence().toString());
        assertFalse(result.stageEvidence().stream()
        .anyMatch(stage -> stage.contains("multi-placeholder validation not supported yet")));
    }

    @Test
    void unsupportedPlaceholderRelationsAreRejectedWithStageEvidence() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        PatternGeneralizer generalizer = new PatternGeneralizer() {
        @Override
        public Optional<GeneralizedPattern> generalizeSingleExampleSchema(SuccessfulTransformationPath path) {
            return Optional.of(new GeneralizedPattern(
                "A + B",
                "B + A",
                Map.of(),
                List.of("A + B = C"),
                Map.of("A", List.of("x"), "B", List.of("y"))
            ));
        }
        };
        MacroLearningResult result = new MacroLearningPipeline(
        inventory,
        generalizer,
        equivalence,
        NO_COUNTEREXAMPLES,
        new KnownRuleRepository(),
        0.0
        ).learn(List.of(new SuccessfulTransformationPath(
        "unsupported-relation-replay",
        "x + y",
        "y + x",
        List.of("x + y", "y + x"),
        List.of("commute"),
        scorer.score("x + y"),
        scorer.score("y + x"),
        true,
        "unit-test",
        Map.of()
        )));

        assertTrue(result.newlyActivated().isEmpty());
        assertTrue(result.stageEvidence().stream()
        .anyMatch(stage -> stage.contains("reject: unsupported placeholder relation")),
        result.stageEvidence().toString());
    }

    private void assertDiscoveryReplayLearnsAndReuses(ReplayCase replayCase) {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroLearningResult result = new MacroLearningPipeline(inventory).learn(List.of(replayCase.path()));
        assertFalse(result.newlyActivated().isEmpty(), replayCase.id() + " promoted? " + result.stageEvidence());
        assertTrue(result.validationExamples().stream().allMatch(MacroValidationExample::equivalent),
            replayCase.id() + " validation examples");
        ReusableRule learned = result.newlyActivated().getFirst();

        MacroMoveTransformationEngine engine = new MacroMoveTransformationEngine(
            new AstRewriteTransformationEngine(List.of(), 0, 0),
            new GoalAwareMacroMoveSelector(inventory),
            null,
            Map.of(learned.id(), atomicSteps(replayCase.path())),
            learned.assumptions()
        );
        List<Transformation> reused = engine.transform(replayCase.reuseInput()).stream()
            .filter(transformation -> transformation.rule().equals(learned.id()))
            .toList();

        assertFalse(reused.isEmpty(), replayCase.id() + " reused?");
        assertTrue(equivalence.areEquivalent(replayCase.reuseInput(), reused.getFirst().transformedExpression()),
            replayCase.id() + " equivalent?");
        assertTrue(learned.assumptions().containsAll(replayCase.expectedAssumptions()),
            replayCase.id() + " assumptions: " + learned.assumptions());
        assertTrue(engine.expansionFor(
            replayCase.reuseInput(),
            reused.getFirst().transformedExpression(),
            learned.id()
        ).orElseThrow().supportingPathIds().contains(replayCase.path().id()));
        if (!replayCase.falsePositiveInput().isBlank()) {
            assertTrue(engine.transform(replayCase.falsePositiveInput()).stream()
                .noneMatch(transformation -> transformation.rule().equals(learned.id())),
                replayCase.id() + " false positive guard");
        }
    }

    private SuccessfulTransformationPath hiddenStructureReplayPath() {
        HypothesisTransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );
        String source = "x^4 + 4";
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
            "sophie-real-hidden-structure-replay",
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

    private SuccessfulTransformationPath operatorCorpusReplayPath(String operatorId, String expression) {
        SeedExpression seed = new SeedExpression(
            operatorId + "-macro-replay",
            expression,
            "operator-corpus",
            "operator-corpus",
            List.of("operator:" + operatorId),
            List.of()
        );
        DeterministicDiscoveryExperimentRunner.SeedRunReport row;
        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            row = workflow.run(
                operatorId + "-macro-replay",
                List.of(seed),
                1,
                1,
                tempDir.resolve(operatorId)
            ).report().rows().getFirst();
        }
        assertTrue(row.success(), row.toString());
        assertFalse(row.replayPath().isEmpty(), row.toString());
        return new SuccessfulTransformationPath(
            operatorId + "-real-operator-corpus-replay",
            row.replayPath().getFirst(),
            row.replayPath().getLast(),
            row.replayPath(),
            row.rulePath(),
            scorer.score(row.replayPath().getFirst()),
            scorer.score(row.replayPath().getLast()),
            true,
            row.counterexampleExplanation().isBlank() ? row.summary() : row.counterexampleExplanation(),
            Map.of("source", "ScientificDiscoveryWorkflow")
        ).withAssumptions(row.inferredAssumptions());
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

    private record ReplayCase(
        String id,
        SuccessfulTransformationPath path,
        String reuseInput,
        List<String> expectedAssumptions,
        String falsePositiveInput
    ) {
    }
}
