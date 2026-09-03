package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.docs.HistoricalPrecursorTestSupport.FrozenRule;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.Composition;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.Effect;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.SourceComponent;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.ExactMonomialSquareExposureOperator;
import de.regelsuche.transform.SquareBaseSignSymmetryOperator;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Composes Brahmagupta-Fibonacci representations by exact residual balance.
 *
 * <p>The square-completion rule is learned and frozen before the four-square
 * source is exposed. Exact normal form and monomial-square exposure prepare
 * the source. Every unordered pair receives a plus-centred and a deterministic
 * sign-reflected effect. The composer receives no target expression or
 * historical name. Historical correspondence is checked only after the
 * complete candidate set has been frozen.</p>
 */
class BrahmaguptaResidualCompositionIntegrationTest {
    private static final String SOURCE =
        "(a^2 + b^2) * (c^2 + d^2)";
    private static final String HISTORICAL_IDENTITY =
        "(a*c - b*d)^2 + (a*d + b*c)^2";
    private static final int MAX_EXPOSURE_STEPS = 8;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();
    private final ExactPolynomialResidualComposer composer =
        new ExactPolynomialResidualComposer();
    private final PolynomialNormalFormEquivalenceService normalForm =
        new PolynomialNormalFormEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry());

    @Test
    @Timeout(300)
    void residualBalanceChoosesTheHistoricalPairingAndSigns() {
        RuntimeTask completionTask = support.completionTask();
        assertFalse(completionTask.observableInput().contains(SOURCE));
        FrozenRule completion = support.freeze(completionTask);

        PreparedSource prepared = prepareSource(SOURCE);
        List<SourceComponent> components =
            sourceComponents(prepared.expression());
        assertEquals(4, components.size());
        assertEquals(4, prepared.exposureRuleIds().size());
        assertTrue(support.exactVerifier().verify(
            SOURCE,
            prepared.expression()).proved());

        List<Effect> plusOnly = new ArrayList<>();
        List<Effect> allEffects = new ArrayList<>();
        for (int left = 0; left < components.size(); left++) {
            for (int right = left + 1;
                    right < components.size();
                    right++) {
                List<SourceComponent> pair = List.of(
                    components.get(left),
                    components.get(right));
                Effect plus = plusEffect(
                    completion,
                    pair,
                    left,
                    right);
                plusOnly.add(plus);
                allEffects.add(plus);
                allEffects.add(reflectedEffect(
                    completion,
                    pair,
                    left,
                    right));
            }
        }

        assertTrue(composer.compose(
            prepared.expression(),
            components,
            plusOnly,
            2,
            16).isEmpty(),
            "plus-centred completions alone cannot cancel residuals");

        List<Composition> frozenCandidates = List.copyOf(
            composer.compose(
                prepared.expression(),
                components,
                allEffects,
                2,
                16));
        assertEquals(2, frozenCandidates.size());
        assertTrue(frozenCandidates.stream().allMatch(candidate ->
            candidate.combinedResidualNormalForm().equals("0")));
        assertTrue(frozenCandidates.stream().allMatch(candidate ->
            candidate.effects().size() == 2));
        assertTrue(frozenCandidates.stream().allMatch(candidate ->
            candidate.primitiveRuleIds().stream()
                .filter(completion.candidate().dynamicRuleId()::equals)
                .count() == 2));
        assertTrue(frozenCandidates.stream().allMatch(candidate ->
            candidate.primitiveRuleIds().stream()
                .filter(SquareBaseSignSymmetryOperator.RULE_ID::equals)
                .count() == 1));
        assertTrue(frozenCandidates.stream().allMatch(candidate ->
            support.exactVerifier().verify(
                SOURCE,
                candidate.candidateExpression()).proved()));

        String historical = canonicalizer.canonicalize(
            HISTORICAL_IDENTITY);
        List<Composition> historicalMatches =
            frozenCandidates.stream()
                .filter(candidate -> canonicalizer.canonicalize(
                    candidate.candidateExpression()).equals(historical))
                .toList();
        assertEquals(1, historicalMatches.size());
    }

    private PreparedSource prepareSource(String source) {
        String current = normalForm.normalForm(source).orElseThrow();
        ExactMonomialSquareExposureOperator exposure =
            new ExactMonomialSquareExposureOperator();
        List<String> ruleIds = new ArrayList<>();
        for (int step = 0;
                step < MAX_EXPOSURE_STEPS;
                step++) {
            List<Transformation> candidates =
                exposure.generateCandidates(current);
            if (candidates.isEmpty()) {
                return new PreparedSource(
                    current,
                    List.copyOf(ruleIds));
            }
            Transformation selected = candidates.getFirst();
            current = selected.transformedExpression();
            ruleIds.addAll(selected.primitiveRuleIds());
        }
        throw new AssertionError(
            "square exposure did not reach a fixed point");
    }

    private List<SourceComponent> sourceComponents(
        String expression
    ) {
        List<Expr> terms = new ArrayList<>();
        collectAddition(parser.parseTerm(expression), terms);
        List<SourceComponent> components = new ArrayList<>();
        for (int index = 0; index < terms.size(); index++) {
            Expr term = terms.get(index);
            assertTrue(isExplicitSquare(term), () ->
                "prepared term is not an explicit square: " + term);
            components.add(composer.component(
                "term-" + index,
                "additive-term-v1:" + index,
                ExpressionFormatter.format(term)));
        }
        return List.copyOf(components);
    }

    private Effect plusEffect(
        FrozenRule completion,
        List<SourceComponent> pair,
        int left,
        int right
    ) {
        Transformation move = only(
            completion.operator().generateCandidates(
                pairExpression(pair)));
        return composer.effect(
            effectId(left, right, "plus"),
            pair,
            move.transformedExpression(),
            structuredSquare(move.transformedExpression()),
            move.assumptions(),
            move.primitiveRuleIds(),
            List.of(move.applicationKey()));
    }

    private Effect reflectedEffect(
        FrozenRule completion,
        List<SourceComponent> pair,
        int left,
        int right
    ) {
        SquareBaseSignSymmetryOperator symmetry =
            new SquareBaseSignSymmetryOperator();
        Transformation reflection = only(
            symmetry.generateCandidates(
                pair.get(1).expression()));
        String signedPair = "(" + pair.get(0).expression()
            + ") + (" + reflection.transformedExpression() + ")";
        Transformation completionMove = only(
            completion.operator().generateCandidates(signedPair));
        return composer.effect(
            effectId(left, right, "reflected"),
            pair,
            completionMove.transformedExpression(),
            structuredSquare(
                completionMove.transformedExpression()),
            append(
                reflection.assumptions(),
                completionMove.assumptions()),
            append(
                reflection.primitiveRuleIds(),
                completionMove.primitiveRuleIds()),
            List.of(
                reflection.applicationKey(),
                completionMove.applicationKey()));
    }

    private String structuredSquare(String expression) {
        List<Expr> candidates = new ArrayList<>();
        collectStructuredSquares(
            parser.parseTerm(expression),
            candidates);
        assertEquals(1, candidates.size(), expression);
        return ExpressionFormatter.format(candidates.getFirst());
    }

    private static void collectAddition(
        Expr expression,
        List<Expr> terms
    ) {
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.ADD) {
            collectAddition(binary.left(), terms);
            collectAddition(binary.right(), terms);
        } else {
            terms.add(expression);
        }
    }

    private static void collectStructuredSquares(
        Expr expression,
        List<Expr> candidates
    ) {
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.POW
                    && isTwo(binary.right())
                    && binary.left() instanceof BinaryExpr base
                    && (base.operator() == BinaryOperator.ADD
                        || base.operator() == BinaryOperator.SUB)) {
                candidates.add(binary);
            }
            collectStructuredSquares(
                binary.left(),
                candidates);
            collectStructuredSquares(
                binary.right(),
                candidates);
        } else if (expression instanceof FunctionExpr function) {
            function.arguments().forEach(argument ->
                collectStructuredSquares(argument, candidates));
        }
    }

    private static boolean isExplicitSquare(Expr expression) {
        return expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.POW
            && isTwo(binary.right());
    }

    private static boolean isTwo(Expr expression) {
        return expression instanceof NumberExpr number
            && Double.compare(number.value(), 2.0) == 0;
    }

    private static Transformation only(
        List<Transformation> candidates
    ) {
        assertEquals(1, candidates.size(), candidates.toString());
        return candidates.getFirst();
    }

    private static String pairExpression(
        List<SourceComponent> pair
    ) {
        return "(" + pair.get(0).expression()
            + ") + (" + pair.get(1).expression() + ")";
    }

    private static String effectId(
        int left,
        int right,
        String mode
    ) {
        return "pair-" + left + "-" + right + "-" + mode;
    }

    private static <T> List<T> append(
        List<T> first,
        List<T> second
    ) {
        List<T> result = new ArrayList<>(
            first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private record PreparedSource(
        String expression,
        List<String> exposureRuleIds
    ) {
        private PreparedSource {
            expression = java.util.Objects.requireNonNull(
                expression,
                "expression");
            exposureRuleIds = List.copyOf(exposureRuleIds);
        }
    }
}
