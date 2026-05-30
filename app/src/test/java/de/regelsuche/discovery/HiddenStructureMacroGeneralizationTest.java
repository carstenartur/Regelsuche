package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.learning.MacroRuleLearningService;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.MacroMoveExpansion;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleCandidateMiner;
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
import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HiddenStructureMacroGeneralizationTest {
    private static final String SOURCE = "x^4 + 4";
    private static final String EXPECTED_SCHEMA_RIGHT =
        "(A^2 - 2*A + 2)*(A^2 + 2*A + 2)";
    private static final String SUBSTITUTED_SOURCE = "(x + 1)^4 + 4";
    private static final String SUBSTITUTED_FACTORED =
        "((x + 1)^2 - 2*(x + 1) + 2) * ((x + 1)^2 + 2*(x + 1) + 2)";

    private final SymPyEquivalenceService equivalence = new SymPyEquivalenceService();

    @Test
    void discoveredHiddenStructurePathProducesValidatedSingleExampleSchema() {
        SuccessfulTransformationPath path = discoveredHiddenStructurePath();

        RuleCandidate candidate = new RuleCandidateMiner(new KnownRuleRepository(), equivalence)
            .mineFromSinglePathForValidatedSchema(path)
            .orElseThrow();

        assertEquals("A^4 + 4", candidate.leftPattern());
        assertTrue(equivalence.areEquivalent(EXPECTED_SCHEMA_RIGHT, candidate.rightPattern()),
            () -> "Unexpected right schema: " + candidate.rightPattern());
        assertEquals(1, candidate.examplesCount());
        assertTrue(candidate.equivalenceVerified());
        assertTrue(candidate.containsFreeParameters());
        assertTrue(candidate.proofStatus().ordinal() >= CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal());
        assertEquals(List.of(path.id()), candidate.supportingTransformationIds());
    }

    @Test
    void learnedHiddenStructureMacroAppliesToSubstitutedTerm() {
        LearnedMacro learned = learnMacroFromHiddenStructurePath();
        MacroMoveTransformationEngine macroEngine = macroEngine(learned);

        Transformation macro = macroEngine.transform(SUBSTITUTED_SOURCE).stream()
            .filter(transformation -> transformation.rule().equals(learned.macroRuleId()))
            .filter(transformation -> equivalence.areEquivalent(
                SUBSTITUTED_FACTORED,
                transformation.transformedExpression()
            ))
            .findFirst()
            .orElseThrow();

        MacroMoveExpansion expansion = macroEngine.expansionFor(
            SUBSTITUTED_SOURCE,
            macro.transformedExpression(),
            macro.rule()
        ).orElseThrow();
        assertEquals(List.of(learned.path().id()), expansion.supportingPathIds());
        assertFalse(expansion.atomicSteps().isEmpty());
    }

    @Test
    void learnedHiddenStructureMacroAppliesAfterNormalization() {
        LearnedMacro learned = learnMacroFromHiddenStructurePath();
        MacroMoveTransformationEngine macroEngine = macroEngine(learned);
        SearchProblem problem = new SearchProblem(
            "(x^2)^2 + 4",
            macroEngine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 80, 1, 10, 200, 200)
        );

        SearchState macroState = new BestFirstSearchStrategy().search(problem).stream()
            .filter(state -> state.appliedRuleIds().contains(learned.macroRuleId()))
            .findFirst()
            .orElseThrow();

        assertTrue(macroState.appliedRuleIds().contains("ast_power_of_power"),
            "Current route normalizes (x^2)^2 + 4 to x^4 + 4 before applying the macro");
        assertTrue(macroState.path().stream().anyMatch(step -> step.replaceAll("\\s+", "").equals("x^4+4")));
        assertTrue(equivalence.areEquivalent(
            "(x^2 - 2*x + 2) * (x^2 + 2*x + 2)",
            macroState.expression()
        ));
    }

    @Test
    void learnedHiddenStructureMacroDoesNotMatchMerelyEquivalentHiddenForm() {
        LearnedMacro learned = learnMacroFromHiddenStructurePath();
        MacroMoveTransformationEngine macroEngine = macroEngine(learned);

        List<Transformation> transformations = macroEngine.transform("x^4 + 2*x^2 + 1 + 3 - 2*x^2");

        assertTrue(transformations.stream().noneMatch(t -> t.rule().equals(learned.macroRuleId())),
            "Current macro matching is structural; equivalence-class matching is future work");
    }

    private LearnedMacro learnMacroFromHiddenStructurePath() {
        SuccessfulTransformationPath path = discoveredHiddenStructurePath();
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroRuleLearningService service = new MacroRuleLearningService(
            inventory,
            new RuleCandidateMiner(new KnownRuleRepository(), equivalence),
            new KnownRuleRepository(),
            1,
            0.0
        );

        MacroLearningResult result = service.learn(List.of(path));

        assertFalse(result.newlyActivated().isEmpty());
        ReusableRule rule = inventory.findAll().stream()
            .filter(candidate -> candidate.leftPattern().equals("A^4 + 4"))
            .findFirst()
            .orElseThrow();
        assertTrue(inventory.isEnabled(rule.id()));
        String macroRuleId = rule.id().startsWith("macro_") ? rule.id() : "macro_" + rule.id();
        return new LearnedMacro(path, inventory, rule, macroRuleId);
    }

    private MacroMoveTransformationEngine macroEngine(LearnedMacro learned) {
        return new MacroMoveTransformationEngine(
            new AstRewriteTransformationEngine(),
            new GoalAwareMacroMoveSelector(learned.inventory(), 0.0, -1000.0, 1),
            null,
            Map.of(learned.rule().id(), atomicSteps(learned.path()))
        );
    }

    private SuccessfulTransformationPath discoveredHiddenStructurePath() {
        ExpressionScorer scorer = new ExpressionScorer();
        HypothesisTransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );
        SearchProblem problem = new SearchProblem(
            SOURCE,
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
            "hidden-structure-x4-plus-4",
            SOURCE,
            factoredState.expression(),
            factoredState.path(),
            factoredState.appliedRuleIds(),
            scorer.score(SOURCE),
            factoredState.score(),
            true,
            "polynomial equivalence",
            Map.of("variable", "x")
        );
    }

    private List<TransformationStep> atomicSteps(SuccessfulTransformationPath path) {
        List<TransformationStep> steps = new ArrayList<>();
        ExpressionScorer scorer = new ExpressionScorer();
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
                path.rules().get(index)
            ));
        }
        return steps;
    }

    private record LearnedMacro(
        SuccessfulTransformationPath path,
        InMemoryRuleInventoryRepository inventory,
        ReusableRule rule,
        String macroRuleId
    ) {
    }
}
