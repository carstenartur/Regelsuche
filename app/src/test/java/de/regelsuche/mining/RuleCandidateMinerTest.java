package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.example.AlgebraicExampleGenerator;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.scoring.ExpressionScore;
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
        assertTrue(candidate.proofStatus().ordinal() >= CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal());
        assertTrue(candidate.equivalenceVerified());
        assertTrue(candidate.parameterRelations().containsAll(List.of("N1 = 2*A", "N2 = A^2")));
    }

    @Test
    void ruleMinerStillDiscoversBinomialCandidateFromGeneratedPaths() {
        RuleCandidate candidate = requireCandidate(new RuleCandidateMiner(new KnownRuleRepository(), new SymPyEquivalenceService()).mine(
            pathsFrom(List.of(
                pair("x^2 + 2*x + 1", "(x + 1)^2"),
                pair("x^2 + 4*x + 4", "(x + 2)^2"),
                pair("x^2 + 6*x + 9", "(x + 3)^2")
            ), List.of(
                "ast_power_two_to_product",
                "ast_distribute_right_add",
                "ast_distribute_left_add",
                "ast_canonical_normalize"
            ))
        ), "x^2 + 2*A*x + A^2", "(x + A)^2");

        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.proofStatus().ordinal() >= CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal());
        assertTrue(candidate.generalizationPlausible());
    }

    @Test
    void e2eDiscoveryDoesNotUseQuadraticFallback() {
        AtomicDiscoveryResult result = discoverKnownBinomialFromAtomicSteps();

        assertTrue(result.store().snapshot().edges().stream()
            .map(GraphEdge::pathId)
            .map(pathId -> pathId.substring(0, pathId.lastIndexOf('#')))
            .distinct()
            .count() >= 3);
        assertTrue(result.store().snapshot().edges().stream()
            .map(GraphEdge::transformationRule)
            .noneMatch(this::isForbiddenSpecialRule));
    }

    @Test
    void knownBinomialRuleEmergesFromAtomicStepsOnly() {
        AtomicDiscoveryResult result = discoverKnownBinomialFromAtomicSteps();

        RuleCandidate candidate = requireCandidate(
            result.candidates(),
            "(x + A)^2",
            "x^2 + 2*A*x + A^2"
        );
        assertEquals(RuleStatus.MATCHES_KNOWN_RULE, candidate.status());
        assertTrue(candidate.proofStatus().ordinal() >= CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal());
        assertTrue(candidate.examplesCount() >= 3);
        assertTrue(result.store().snapshot().edges().stream()
            .map(GraphEdge::transformationRule)
            .anyMatch("ast_power_two_to_product"::equals));
        assertTrue(result.store().snapshot().edges().stream()
            .map(GraphEdge::transformationRule)
            .anyMatch(rule -> rule.startsWith("ast_distribute")));
        assertTrue(result.store().snapshot().edges().stream()
            .map(GraphEdge::transformationRule)
            .anyMatch("ast_canonical_normalize"::equals));
        assertTrue(result.store().snapshot().edges().stream()
            .map(GraphEdge::transformationRule)
            .anyMatch("ast_product_to_power_two"::equals));
        assertTrue(result.store().snapshot().edges().stream()
            .map(GraphEdge::transformationRule)
            .anyMatch("ast_double_term"::equals));
        assertTrue(result.store().snapshot().edges().stream()
            .map(GraphEdge::transformationRule)
            .anyMatch(rule -> rule.startsWith("ast_factor_common")));
        assertTrue(result.store().snapshot().edges().stream()
            .map(GraphEdge::transformationRule)
            .noneMatch(this::isForbiddenSpecialRule));
    }

    @Test
    void discoversKnownBinomialRuleFromSearchPaths() {
        AtomicDiscoveryResult result = discoverKnownBinomialFromAtomicSteps();

        assertTrue(result.candidates().stream().anyMatch(candidate ->
            candidate.leftPattern().equals("(x + A)^2")
                && candidate.rightPattern().equals("x^2 + 2*A*x + A^2")
                && candidate.status() == RuleStatus.MATCHES_KNOWN_RULE
                && candidate.proofStatus().ordinal() >= CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal()
        ), () -> "Expected first binomial rule candidate in " + result.candidates());
    }

    private AtomicDiscoveryResult discoverKnownBinomialFromAtomicSteps() {
        InMemoryExpressionGraphStore store = new InMemoryExpressionGraphStore();
        RuleDiscoveryService service = new RuleDiscoveryService(
            new AlgebraicExampleGenerator() {
                @Override
                public List<String> generateSmallIntegerExamples(int min, int max) {
                    return List.of("(x + 1)^2", "(x + 2)^2", "(x + 3)^2");
                }
            },
            new AstRewriteTransformationEngine(),
            new SymPyEquivalenceService(),
            new ExpansionRewardingScorer(),
            store,
            new RuleCandidateMiner(new KnownRuleRepository()),
            event -> {}
        );

        try {
            List<RuleCandidate> candidates = service.discover(1, 5);
            return new AtomicDiscoveryResult(candidates, store);
        } finally {
            service.shutdown();
        }
    }

    private boolean isForbiddenSpecialRule(String ruleId) {
        String normalized = ruleId.toLowerCase();
        return normalized.contains("quadratic")
            || normalized.contains("binomial")
            || normalized.contains("perfect_square")
            || normalized.contains("difference_of_squares");
    }

    private static final class ExpansionRewardingScorer extends ExpressionScorer {
        @Override
        public ExpressionScore score(String expression) {
            ExpressionScore score = super.score(expression);
            int weightedTotal = score.weightedTotal();
            if (expression.contains(") ^ 2")) {
                weightedTotal += 30;
            }
            return new ExpressionScore(weightedTotal, 0, 0, 0, 0);
        }
    }

    private record AtomicDiscoveryResult(List<RuleCandidate> candidates, InMemoryExpressionGraphStore store) {
    }

    @Test
    void exampleGeneratorCoversMultipleVariablesAndHigherDegreePolynomials() {
        List<String> examples = new AlgebraicExampleGenerator().generateSmallIntegerExamples(1, 2);

        assertTrue(examples.stream().anyMatch(example -> example.contains("y")));
        assertTrue(examples.stream().anyMatch(example -> example.contains("^3") || example.contains("^4")));
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
    void candidateValidatorInstantiatesPatternsThroughAst() {
        CandidateValidator validator = new CandidateValidator(new SymPyEquivalenceService());

        assertTrue(validator.validate(new GeneralizedPattern(
            "A*A + 2 * A + A^3 + A*B - A",
            "A^2 + A + A^3 + B*A",
            Map.of("A", List.of(1, 3, 5), "B", List.of(2, 4, 6)),
            List.of("synthetic test relation")
        )));
        assertTrue(validator.validate(new GeneralizedPattern(
            "(A)^2",
            "A*A",
            Map.of("A", List.of(1, 3, 5)),
            List.of("synthetic test relation")
        )));
        assertTrue(validator.validate(new GeneralizedPattern(
            "-A",
            "0 - A",
            Map.of("A", List.of(1, 3, 5)),
            List.of("synthetic test relation")
        )));
    }

    @Test
    void equivalenceVerifiedUsesPathBooleanInsteadOfEvidenceText() {
        ExpressionScorer scorer = new ExpressionScorer();
        List<SuccessfulTransformationPath> paths = List.of(
            verifiedPath("x + 1", "1 + x", scorer),
            verifiedPath("x + 3", "3 + x", scorer),
            verifiedPath("x + 5", "5 + x", scorer)
        );

        List<RuleCandidate> candidates = new RuleCandidateMiner(
            new KnownRuleRepository(),
            (leftExpression, rightExpression) -> true
        ).mine(paths);

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().allMatch(RuleCandidate::equivalenceVerified));
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
                    return List.of("x^2 + 2*x + 1", "x^2 + 4*x + 4", "x^2 + 6*x + 9");
                }
            },
            expression -> switch (expression) {
                case "1 + 2 * x + x ^ 2" -> List.of(new de.regelsuche.transform.Transformation("test_atomic_path", "(x + 1)^2"));
                case "4 + 4 * x + x ^ 2" -> List.of(new de.regelsuche.transform.Transformation("test_atomic_path", "(x + 2)^2"));
                case "9 + 6 * x + x ^ 2" -> List.of(new de.regelsuche.transform.Transformation("test_atomic_path", "(x + 3)^2"));
                default -> List.of();
            },
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
        return pathsFrom(pairs, List.of("test_concrete_transformation"));
    }

    private List<SuccessfulTransformationPath> pathsFrom(List<ExpressionPair> pairs, List<String> rules) {
        ExpressionScorer scorer = new ExpressionScorer();
        List<SuccessfulTransformationPath> paths = new ArrayList<>();
        for (ExpressionPair pair : pairs) {
            paths.add(new SuccessfulTransformationPath(
                pair.source(),
                pair.target(),
                List.of(pair.source(), pair.target()),
                rules,
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

    private SuccessfulTransformationPath verifiedPath(String source, String target, ExpressionScorer scorer) {
        return new SuccessfulTransformationPath(
            source,
            target,
            List.of(source, target),
            List.of("test_commute"),
            scorer.score(source),
            scorer.score(target),
            true,
            "equivalent",
            Map.of("variable", "x")
        );
    }

    private record ExpressionPair(String source, String target) {
    }
}
