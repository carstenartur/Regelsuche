package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.example.AlgebraicExampleGenerator;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.SymPyTransformationEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleCandidateMinerTest {
    @Test
    void reconstructsFirstBinomialFormulaFromConcreteExamples() {
        List<RuleCandidate> candidates = discoverFrom(List.of(
            "x^2 + 2*x + 1",
            "x^2 + 6*x + 9",
            "x^2 + 10*x + 25"
        ));

        RuleCandidate candidate = requireCandidate(candidates, "x^2 + 2*a*x + a^2", "(x + a)^2");
        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.equivalenceVerified());
    }

    @Test
    void reconstructsSecondBinomialFormulaFromConcreteExamples() {
        List<RuleCandidate> candidates = discoverFrom(List.of(
            "x^2 - 2*x + 1",
            "x^2 - 6*x + 9",
            "x^2 - 10*x + 25"
        ));

        RuleCandidate candidate = requireCandidate(candidates, "x^2 - 2*a*x + a^2", "(x - a)^2");
        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.containsFreeParameters());
    }

    @Test
    void reconstructsThirdBinomialFormulaFromConcreteExamples() {
        List<RuleCandidate> candidates = discoverFrom(List.of(
            "(x + 1)*(x - 1)",
            "(x + 3)*(x - 3)",
            "(x + 5)*(x - 5)"
        ));

        RuleCandidate candidate = requireCandidate(candidates, "(a + b)*(a - b)", "a^2 - b^2");
        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
    }

    @Test
    void reconstructsQuadraticCompletionFromConcreteExamples() {
        List<RuleCandidate> candidates = discoverFrom(List.of(
            "x^2 + 2*x",
            "x^2 + 6*x",
            "x^2 + 10*x"
        ));

        RuleCandidate candidate = requireCandidate(candidates, "x^2 + 2*a*x", "(x + a)^2 - a^2");
        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
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
                    java.util.Map.of("variable", "x")
                ))
                .filter(path -> path.scoreImprovement() > 0)
                .forEach(paths::add);
        }
        return new RuleCandidateMiner(new KnownRuleRepository()).mine(paths);
    }
}
