package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.example.AlgebraicExampleGenerator;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.SymPyTransformationEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleCandidateMinerTest {
    @Test
    void discoversFirstBinomialFormulaByGeneralizationOnly() {
        RuleCandidate candidate = requireCandidate(discoverFrom(List.of(
            "x^2 + 2*x + 1",
            "x^2 + 6*x + 9",
            "x^2 + 10*x + 25"
        )), "x^2 + 2*A*x + A^2", "(x + A)^2");

        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.equivalenceVerified());
        assertTrue(candidate.parameterRelations().containsAll(List.of("N1 = 2*A", "N2 = A^2")));
    }

    @Test
    void discoversSecondBinomialFormulaByGeneralizationOnly() {
        RuleCandidate candidate = requireCandidate(discoverFrom(List.of(
            "x^2 - 2*x + 1",
            "x^2 - 6*x + 9",
            "x^2 - 10*x + 25"
        )), "x^2 - 2*A*x + A^2", "(x - A)^2");

        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.parameterRelations().containsAll(List.of("N1 = 2*A", "N2 = A^2", "N3 = -A")));
    }

    @Test
    void discoversDifferenceOfSquaresByGeneralizationOnly() {
        RuleCandidate candidate = requireCandidate(discoverFrom(List.of(
            "(x + 1)*(x - 1)",
            "(x + 3)*(x - 3)",
            "(x + 5)*(x - 5)"
        )), "(x + A)*(x - A)", "x^2 - A^2");

        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.parameterRelations().containsAll(List.of("N1 = A", "N2 = -A", "N3 = -A^2")));
    }

    @Test
    void discoversQuadraticCompletionByGeneralizationOnly() {
        RuleCandidate candidate = requireCandidate(discoverFrom(List.of(
            "x^2 + 2*x",
            "x^2 + 6*x",
            "x^2 + 10*x"
        )), "x^2 + 2*A*x", "(x + A)^2 - A^2");

        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.parameterRelations().containsAll(List.of("N1 = 2*A", "N3 = -A^2")));
    }

    @Test
    void doesNotAcceptCoincidentalPatternWithOnlyTwoExamples() {
        List<RuleCandidate> candidates = discoverFrom(List.of(
            "x^2 + 2*x + 1",
            "x^2 + 6*x + 9"
        ));

        assertTrue(candidates.isEmpty());
    }

    @Test
    void rejectsCandidateThatFailsFreshValidationExamples() {
        EquivalenceService rejectingValidation = new EquivalenceService() {
            @Override
            public boolean areEquivalent(String leftExpression, String rightExpression) {
                return false;
            }
        };

        List<RuleCandidate> candidates = new RuleCandidateMiner(new KnownRuleRepository(), rejectingValidation).mine(pathsFrom(List.of(
            "x^2 + 2*x + 1",
            "x^2 + 6*x + 9",
            "x^2 + 10*x + 25"
        )));

        assertTrue(candidates.isEmpty());
    }

    @Test
    void discoveryServiceRunsAsynchronouslyAndDeduplicatesEvents() {
        List<RuleCandidateDiscoveredEvent> events = new ArrayList<>();
        RuleDiscoveryService service = new RuleDiscoveryService(
            new AlgebraicExampleGenerator(),
            new SymPyTransformationEngine(),
            new SymPyEquivalenceService(),
            new ExpressionScorer(),
            new InMemoryExpressionGraphStore(),
            new RuleCandidateMiner(new KnownRuleRepository()),
            events::add
        );

        List<RuleCandidate> candidates = service.discoverAsync(1, 3).join();
        List<RuleCandidate> repeated = service.discoverAsync(1, 3).join();

        assertFalse(candidates.isEmpty());
        assertFalse(repeated.isEmpty());
        assertEquals(candidates.size(), events.size());
        service.shutdown();
    }

    private RuleCandidate requireCandidate(List<RuleCandidate> candidates, String leftPattern, String rightPattern) {
        return candidates.stream()
            .filter(candidate -> candidate.leftPattern().equals(leftPattern) && candidate.rightPattern().equals(rightPattern))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing candidate " + leftPattern + " -> " + rightPattern));
    }

    private List<RuleCandidate> discoverFrom(List<String> expressions) {
        return new RuleCandidateMiner(new KnownRuleRepository()).mine(pathsFrom(expressions));
    }

    private List<SuccessfulTransformationPath> pathsFrom(List<String> expressions) {
        SymPyTransformationEngine engine = new SymPyTransformationEngine();
        SymPyEquivalenceService equivalence = new SymPyEquivalenceService();
        ExpressionScorer scorer = new ExpressionScorer();
        List<SuccessfulTransformationPath> paths = new ArrayList<>();
        for (String expression : expressions) {
            engine.transform(expression).stream()
                .filter(transformation -> equivalence.areEquivalent(expression, transformation.transformedExpression()))
                .map(transformation -> new SuccessfulTransformationPath(
                    expression,
                    transformation.transformedExpression(),
                    List.of(expression, transformation.transformedExpression()),
                    List.of(transformation.rule()),
                    scorer.score(expression),
                    scorer.score(transformation.transformedExpression()),
                    equivalence.evidence(expression, transformation.transformedExpression()),
                    Map.of("variable", "x")
                ))
                .filter(path -> path.scoreImprovement() > 0)
                .forEach(paths::add);
        }
        return paths;
    }
}
