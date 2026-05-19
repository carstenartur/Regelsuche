package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.example.AlgebraicExampleGenerator;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleCandidateMinerTest {
    @Test
    void discoversFirstBinomialFormulaByGeneralizationOnly() {
        RuleCandidate candidate = requireCandidate(discoverFrom(List.of(
            pair("x^2 + 2*x + 1", "(x + 1)^2"),
            pair("x^2 + 6*x + 9", "(x + 3)^2"),
            pair("x^2 + 10*x + 25", "(x + 5)^2")
        )), "x^2 + 2*A*x + A^2", "(x + A)^2");

        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.equivalenceVerified());
        assertTrue(candidate.parameterRelations().containsAll(List.of("N1 = 2*A", "N2 = A^2")));
    }

    @Test
    void discoversSecondBinomialFormulaByGeneralizationOnly() {
        RuleCandidate candidate = requireCandidate(discoverFrom(List.of(
            pair("x^2 - 2*x + 1", "(x - 1)^2"),
            pair("x^2 - 6*x + 9", "(x - 3)^2"),
            pair("x^2 - 10*x + 25", "(x - 5)^2")
        )), "x^2 - 2*A*x + A^2", "(x - A)^2");

        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.parameterRelations().containsAll(List.of("N1 = 2*A", "N2 = A^2", "N3 = -A")));
    }

    @Test
    void discoversDifferenceOfSquaresByGeneralizationOnly() {
        RuleCandidate candidate = requireCandidate(discoverFrom(List.of(
            pair("(x + 1)*(x - 1)", "x^2 - 1"),
            pair("(x + 3)*(x - 3)", "x^2 - 9"),
            pair("(x + 5)*(x - 5)", "x^2 - 25")
        )), "(x + A)*(x - A)", "x^2 - A^2");

        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.parameterRelations().containsAll(List.of("N1 = A", "N2 = -A", "N3 = -A^2")));
    }

    @Test
    void discoversQuadraticCompletionByGeneralizationOnly() {
        RuleCandidate candidate = requireCandidate(discoverFrom(List.of(
            pair("x^2 + 2*x", "(x + 1)^2 - 1"),
            pair("x^2 + 6*x", "(x + 3)^2 - 9"),
            pair("x^2 + 10*x", "(x + 5)^2 - 25")
        )), "x^2 + 2*A*x", "(x + A)^2 - A^2");

        assertEquals(3, candidate.examplesCount());
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.parameterRelations().containsAll(List.of("N1 = 2*A", "N3 = -A^2")));
    }

    @Test
    void doesNotAcceptCoincidentalPatternWithOnlyTwoExamples() {
        List<RuleCandidate> candidates = discoverFrom(List.of(
            pair("x^2 + 2*x + 1", "(x + 1)^2"),
            pair("x^2 + 6*x + 9", "(x + 3)^2")
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
            pair("x^2 + 2*x + 1", "(x + 1)^2"),
            pair("x^2 + 6*x + 9", "(x + 3)^2"),
            pair("x^2 + 10*x + 25", "(x + 5)^2")
        )));

        assertTrue(candidates.isEmpty());
    }

    @Test
    void discoveryServiceRunsAsynchronouslyAndDeduplicatesEvents() {
        List<RuleCandidateDiscoveredEvent> events = new ArrayList<>();
        EquivalenceService testEquivalence = new EquivalenceService() {
            @Override
            public boolean areEquivalent(String leftExpression, String rightExpression) {
                return true;
            }

            @Override
            public String evidence(String leftExpression, String rightExpression) {
                return "matching normalized test equivalence";
            }
        };
        RuleDiscoveryService service = new RuleDiscoveryService(
            new AlgebraicExampleGenerator() {
                @Override
                public List<String> generateSmallIntegerExamples(int min, int max) {
                    return List.of("(x + 1)*(x + 1)", "(x + 2)*(x + 2)", "(x + 3)*(x + 3)");
                }
            },
            new AstRewriteTransformationEngine(),
            testEquivalence,
            new ExpressionScorer(),
            new InMemoryExpressionGraphStore(),
            new RuleCandidateMiner(new KnownRuleRepository(), testEquivalence),
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

    private List<RuleCandidate> discoverFrom(List<ExpressionPair> pairs) {
        return new RuleCandidateMiner(new KnownRuleRepository(), new SymPyEquivalenceService()).mine(pathsFrom(pairs));
    }

    private List<SuccessfulTransformationPath> pathsFrom(List<ExpressionPair> pairs) {
        ExpressionScorer scorer = new ExpressionScorer();
        List<SuccessfulTransformationPath> paths = new ArrayList<>();
        for (ExpressionPair pair : pairs) {
            paths.add(new SuccessfulTransformationPath(
                pair.source(),
                pair.target(),
                List.of(pair.source(), pair.target()),
                List.of("test_concrete_transformation"),
                scorer.score(pair.source()),
                scorer.score(pair.target()),
                "matching normalized quadratic coefficients",
                Map.of("variable", "x")
            ));
        }
        return paths;
    }

    private ExpressionPair pair(String source, String target) {
        return new ExpressionPair(source, target);
    }

    private record ExpressionPair(String source, String target) {
    }
}
