package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.docs.HistoricalPrecursorTestSupport.FrozenRule;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AdditivePairHypothesisOperator;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.ExactMonomialSquareExposureOperator;
import de.regelsuche.transform.OccurrenceAwareAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.SquareBaseSignSymmetryOperator;
import de.regelsuche.transform.SubtreeHypothesisOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Composes the Brahmagupta-Fibonacci identity with one independently learned
 * square-completion rule, generic associative pair selection and one exact
 * square-base sign symmetry.
 *
 * <p>The learned rule is frozen before the four-square source and historical
 * endpoint are introduced. Candidate generation then follows a bounded phase
 * schedule whose phases expose only one generic operator family at a time.
 * Every candidate in each phase is retained and deduplicated by exact AST;
 * the historical endpoint is used only after the complete final frontier has
 * been generated. This is goal-conditioned phase composition, not target-free
 * historical rediscovery and not an unrestricted all-at-once search claim.</p>
 */
class BrahmaguptaFibonacciSingleLearnedRuleIntegrationTest {
    private static final String SOURCE =
        "(a^2 + b^2) * (c^2 + d^2)";
    private static final String HISTORICAL_IDENTITY =
        "(a*c - b*d)^2 + (a*d + b*c)^2";
    private static final Set<String> DISTRIBUTION_RULE_IDS = Set.of(
        "ast_distribute_left_add",
        "ast_distribute_right_add");
    private static final Set<String> ADMITTED_RULE_IDS = Set.of(
        "ast_distribute_left_add",
        "ast_distribute_right_add",
        "ast_canonical_normalize");
    private static final int MAX_PHASE_FRONTIER = 8_192;

    private final ExpressionParser parser = new ExpressionParser();
    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();

    @Test
    @Timeout(300)
    void oneFrozenCompletionRuleComposesTheHistoricalIdentity() {
        RuntimeTask completionTask = support.completionTask();
        String observableTraining = completionTask.observableInput();
        assertFalse(observableTraining.contains(SOURCE));
        assertFalse(observableTraining.contains(HISTORICAL_IDENTITY));

        FrozenRule completion = support.freeze(completionTask);
        AdditivePairHypothesisOperator pairCompletion =
            new AdditivePairHypothesisOperator(
                completion.operator(),
                24);
        SubtreeHypothesisOperator signSymmetry =
            new SubtreeHypothesisOperator(
                new SquareBaseSignSymmetryOperator(),
                16);
        ExactMonomialSquareExposureOperator exposure =
            new ExactMonomialSquareExposureOperator();

        TransformationEngine distribution = distributionEngine();
        TransformationEngine exposurePhase = exposure::generateCandidates;
        TransformationEngine signPhase = signSymmetry::generateCandidates;
        TransformationEngine completionPhase =
            pairCompletion::generateCandidates;
        TransformationEngine canonicalization = canonicalOnlyEngine();

        List<FrontierState> distributed = terminalStates(
            advanceExactly(
                List.of(FrontierState.root(SOURCE)),
                distribution,
                3,
                "distribution"),
            distribution);
        assertFalse(distributed.isEmpty(),
            "three distribution steps must contain a fully expanded state");

        List<FrontierState> exposed = terminalStates(
            advanceExactly(
                distributed,
                exposurePhase,
                4,
                "exact monomial-square exposure"),
            exposurePhase);
        assertFalse(exposed.isEmpty(),
            "four exposure steps must make all product squares explicit");
        assertTrue(exposed.stream().allMatch(state ->
            explicitProductSquareCount(parser.parseTerm(state.expression()))
                == 4));

        List<FrontierState> baselineCompleted = advanceExactly(
            exposed,
            completionPhase,
            2,
            "baseline learned pair completion");
        List<FrontierState> baselineCanonical = advanceExactly(
            baselineCompleted,
            canonicalization,
            1,
            "baseline canonicalization");

        List<FrontierState> signed = advanceExactly(
            exposed,
            signPhase,
            1,
            "square-base sign symmetry");
        assertTrue(signed.stream().allMatch(state ->
            completion.operator().generateCandidates(state.expression())
                .isEmpty()),
            "the root-only learned rule must still require pair selection");
        List<FrontierState> completed = advanceExactly(
            signed,
            completionPhase,
            2,
            "learned pair completion");
        List<FrontierState> finalFrontier = advanceExactly(
            completed,
            canonicalization,
            1,
            "canonicalization");

        Expr historical = parser.parseTerm(HISTORICAL_IDENTITY);
        assertTrue(
            matchingStates(baselineCompleted, historical).isEmpty()
                && matchingStates(baselineCanonical, historical).isEmpty(),
            "the same phase schedule without sign symmetry must not contain "
                + "the historical representation");

        List<FrontierState> matches = matchingStates(
            finalFrontier,
            historical);
        assertEquals(1, matches.size(),
            "target-blind phase enumeration must retain one exact historical "
                + "representation after AST deduplication");
        FrontierState reached = matches.getFirst();

        assertTrue(support.exactVerifier().verify(
            SOURCE,
            reached.expression()).proved());
        assertEquals(11, reached.ruleIds().size(), reached.toString());
        assertEquals(12, reached.path().size(), reached.toString());
        assertEquals(
            parser.parseTerm(SOURCE),
            parser.parseTerm(reached.path().getFirst()),
            reached.toString());
        assertEquals(
            historical,
            parser.parseTerm(reached.expression()),
            reached.toString());
        assertEquals(2L,
            reached.ruleIds().stream()
                .filter(completion.candidate().dynamicRuleId()::equals)
                .count(),
            reached.toString());
        assertEquals(1L,
            reached.ruleIds().stream()
                .filter(SquareBaseSignSymmetryOperator.RULE_ID::equals)
                .count(),
            reached.toString());
        assertEquals(4L,
            reached.ruleIds().stream()
                .filter(ExactMonomialSquareExposureOperator.RULE_ID::equals)
                .count(),
            reached.toString());
        assertEquals(3L,
            reached.ruleIds().stream()
                .filter(rule -> rule.startsWith("ast_distribute_"))
                .count(),
            reached.toString());
        assertEquals(1L,
            reached.ruleIds().stream()
                .filter("ast_canonical_normalize"::equals)
                .count(),
            reached.toString());
        assertTrue(reached.ruleIds().stream().allMatch(rule ->
            ADMITTED_RULE_IDS.contains(rule)
                || ExactMonomialSquareExposureOperator.RULE_ID.equals(rule)
                || SquareBaseSignSymmetryOperator.RULE_ID.equals(rule)
                || completion.candidate().dynamicRuleId().equals(rule)),
            reached.toString());
        assertEquals(1L,
            reached.applicationKeys().stream()
                .filter(key -> key.startsWith("subtree-v1:"))
                .count(),
            reached.toString());
        assertEquals(2L,
            reached.applicationKeys().stream()
                .filter(key -> key.startsWith("additive-pair-v1:"))
                .count(),
            reached.toString());
    }

    private TransformationEngine distributionEngine() {
        List<RewriteRule> rules =
            AstRewriteTransformationEngine.allBuiltInRules().stream()
                .filter(rule -> DISTRIBUTION_RULE_IDS.contains(rule.id()))
                .toList();
        return new OccurrenceAwareAstRewriteTransformationEngine(
            rules,
            32,
            128);
    }

    private TransformationEngine canonicalOnlyEngine() {
        RewriteRule canonical =
            AstRewriteTransformationEngine.allBuiltInRules().stream()
                .filter(rule -> "ast_canonical_normalize".equals(rule.id()))
                .findFirst()
                .orElseThrow();
        return new OccurrenceAwareAstRewriteTransformationEngine(
            List.of(canonical),
            32,
            32);
    }

    /**
     * Enumerates one complete bounded phase without accepting a target,
     * distance function or preferred intermediate expression.
     */
    private List<FrontierState> advanceExactly(
        List<FrontierState> initial,
        TransformationEngine engine,
        int steps,
        String phase
    ) {
        List<FrontierState> current = List.copyOf(initial);
        for (int step = 1; step <= steps; step++) {
            Map<Expr, FrontierState> next = new LinkedHashMap<>();
            for (FrontierState state : current) {
                for (Transformation transformation
                        : engine.transform(state.expression())) {
                    Expr exactAst = parser.parseTerm(
                        transformation.transformedExpression());
                    next.putIfAbsent(
                        exactAst,
                        state.advance(transformation));
                    if (next.size() > MAX_PHASE_FRONTIER) {
                        throw new AssertionError(
                            phase + " exceeded the preregistered frontier "
                                + "bound of " + MAX_PHASE_FRONTIER
                                + " at step " + step);
                    }
                }
            }
            assertFalse(next.isEmpty(),
                phase + " produced no candidates at step " + step);
            current = List.copyOf(next.values());
        }
        return current;
    }

    private static List<FrontierState> terminalStates(
        List<FrontierState> states,
        TransformationEngine engine
    ) {
        return states.stream()
            .filter(state -> engine.transform(state.expression()).isEmpty())
            .toList();
    }

    private List<FrontierState> matchingStates(
        List<FrontierState> states,
        Expr expected
    ) {
        return states.stream()
            .filter(state -> parser.parseTerm(state.expression())
                .equals(expected))
            .toList();
    }

    private int explicitProductSquareCount(Expr expression) {
        int here = expression instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && power.left() instanceof BinaryExpr product
                && product.operator() == BinaryOperator.MUL
                && isTwo(power.right())
            ? 1
            : 0;
        if (expression instanceof BinaryExpr binary) {
            return here
                + explicitProductSquareCount(binary.left())
                + explicitProductSquareCount(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return here + function.arguments().stream()
                .mapToInt(this::explicitProductSquareCount)
                .sum();
        }
        return here;
    }

    private static boolean isTwo(Expr expression) {
        return expression instanceof NumberExpr number
            && Double.compare(number.value(), 2.0) == 0;
    }

    private record FrontierState(
        String expression,
        List<String> path,
        List<String> ruleIds,
        List<String> applicationKeys
    ) {
        private FrontierState {
            expression = java.util.Objects.requireNonNull(
                expression,
                "expression");
            path = List.copyOf(path);
            ruleIds = List.copyOf(ruleIds);
            applicationKeys = List.copyOf(applicationKeys);
            if (path.size() != ruleIds.size() + 1
                    || ruleIds.size() != applicationKeys.size()
                    || !path.getLast().equals(expression)) {
                throw new IllegalArgumentException(
                    "frontier path and lineage must remain balanced");
            }
        }

        private static FrontierState root(String expression) {
            return new FrontierState(
                expression,
                List.of(expression),
                List.of(),
                List.of());
        }

        private FrontierState advance(Transformation transformation) {
            String transformed = transformation.transformedExpression();
            return new FrontierState(
                transformed,
                append(path, transformed),
                append(ruleIds, transformation.rule()),
                append(applicationKeys, transformation.applicationKey()));
        }

        private static <T> List<T> append(List<T> values, T value) {
            List<T> result = new ArrayList<>(values.size() + 1);
            result.addAll(values);
            result.add(value);
            return List.copyOf(result);
        }
    }
}
