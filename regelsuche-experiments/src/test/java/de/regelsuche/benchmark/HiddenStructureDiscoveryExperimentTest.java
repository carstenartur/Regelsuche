package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.example.ScientificSeedCorpora;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.SquareDifferenceAstPredicate;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HiddenStructureDiscoveryExperimentTest {
    @TempDir
    Path outputDirectory;

    @Test
    void hiddenStructureExperimentUsesExistingReplayGraphAndExportInfrastructure() throws IOException {
        List<SeedExpression> seeds = ScientificSeedCorpora.curated().stream()
            .filter(seed -> seed.category().equals("hidden-structure"))
            .toList();
        DeterministicDiscoveryExperimentRunner runner = new DeterministicDiscoveryExperimentRunner(
            1,
            1,
            this::evaluateHiddenStructureSeed
        );

        DeterministicDiscoveryExperimentRunner.DiscoveryReport first = runner.runDetailed(seeds);
        DeterministicDiscoveryExperimentRunner.DiscoveryReport second = runner.runDetailed(seeds);
        DiscoveryReplayArtifactWriter.ArtifactBundle artifacts =
            new DiscoveryReplayArtifactWriter().write(first, outputDirectory);

        assertEquals(second.renderDeterministicJson(), first.renderDeterministicJson());
        assertEquals(1, first.rows().size());
        DeterministicDiscoveryExperimentRunner.SeedRunReport row = first.rows().getFirst();
        assertEquals("x^4 + 4", row.seed().expression());
        assertTrue(row.success(), row.summary());
        assertTrue(row.summary().contains("factored via ast_square_difference_factor"), row.summary());
        assertFalse(row.hypotheses().isEmpty());
        assertTrue(row.replayPath().stream().anyMatch(this::isSquareDifferenceState), row.replayPath().toString());
        assertTrue(row.replayPath().getLast().contains("*"), row.replayPath().toString());
        assertTrue(row.replayPath().getLast().contains("x ^ 2"), row.replayPath().toString());
        assertTrue(Files.exists(artifacts.jsonReport()));
        assertTrue(Files.exists(artifacts.replayJson()));
        assertTrue(Files.exists(artifacts.markdownReport()));
        assertTrue(Files.exists(artifacts.provenanceGraphJson()));
        assertTrue(Files.readString(artifacts.replayJson()).contains("\"semanticGraph\""));
        assertTrue(Files.readString(artifacts.markdownReport()).contains("```mermaid"));
        assertTrue(Files.readString(artifacts.provenanceGraphJson()).contains("\"SupportingPath\""));
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunOutcome evaluateHiddenStructureSeed(SeedExpression seed) {
        TransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );
        List<Transformation> hypothesisCandidates = engine.transform(seed.expression()).stream()
            .filter(transformation -> transformation.rule().equals(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .toList();
        SearchProblem problem = new SearchProblem(
            seed.expression(),
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 40, 1)
        );
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        SearchState hypothesisState = states.stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .findFirst()
            .orElse(null);
        SearchState squareDifferenceState = states.stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .filter(state -> isSquareDifferenceState(state.expression()))
            .findFirst()
            .orElse(null);
        SearchState factoredState = states.stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .filter(state -> state.appliedRuleIds().contains("ast_square_difference_factor"))
            .findFirst()
            .orElse(null);
        SearchState reportedState = factoredState != null
            ? factoredState
            : squareDifferenceState != null ? squareDifferenceState : hypothesisState;
        return new DeterministicDiscoveryExperimentRunner.SeedRunOutcome(
            !hypothesisCandidates.isEmpty() && factoredState != null,
            summary(hypothesisState, squareDifferenceState, factoredState),
            hypothesisCandidates.stream().map(Transformation::transformedExpression).toList(),
            List.of(),
            reportedState == null ? List.of() : reportedState.path(),
            0L,
            0L
        );
    }

    private boolean isSquareDifferenceState(String expression) {
        return SquareDifferenceAstPredicate.containsSquareDifference(expression);
    }

    private String summary(SearchState hypothesisState, SearchState squareDifferenceState, SearchState factoredState) {
        if (factoredState != null) {
            return "hypothesis path reached square-difference state and factored via ast_square_difference_factor";
        }
        if (squareDifferenceState != null) {
            return "hypothesis path reached square-difference state but did not reach ast_square_difference_factor";
        }
        if (hypothesisState != null) {
            return "hypothesis candidate participated but did not reach a square-difference state";
        }
        return "no hypothesis path found";
    }
}
