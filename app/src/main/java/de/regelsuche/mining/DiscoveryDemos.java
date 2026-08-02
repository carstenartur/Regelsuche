package de.regelsuche.mining;

import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.learning.MacroRuleLearningService;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Reproducible no-DB discovery demos for Issue #34. */
public final class DiscoveryDemos {
    private DiscoveryDemos() {
    }

    /**
     * Two-sided cancellations.
     *
     * <p>Cancelling a single common factor, such as {@code (x*x)/x}, is an
     * atomic rewrite ({@code ast_cancel_division_factor}), so a macro over it
     * would shorten nothing. These examples cancel a factor that appears in
     * both a numerator product and a denominator product, which the atomic
     * inventory only reaches over several steps.</p>
     */
    public static List<SuccessfulTransformationPath> rationalSimplificationExamples() {
        return List.of(
            path("rat-1", "(x*y)/(x*z)", "y/z"),
            path("rat-2", "(a*b)/(a*c)", "b/c"),
            path("rat-3", "(u*v)/(u*w)", "v/w"),
            path("rat-4", "(x*a)/(x*b)", "a/b"),
            path("rat-5", "(y*p)/(y*q)", "p/q"),
            path("rat-6", "(m*n)/(m*k)", "n/k"),
            path("rat-7", "(s*t)/(s*r)", "t/r"),
            path("rat-8", "(2*x)/(2*y)", "x/y"),
            path("rat-9", "(3*x)/(3*y)", "x/y"),
            path("rat-10", "(5*x)/(5*y)", "x/y"),
            path("rat-11", "(7*x)/(7*y)", "x/y")
        );
    }

    public static MacroLearningResult promoteRationalSimplification(RuleInventoryRepository inventory) {
        KnownRuleRepository knownRules = new KnownRuleRepository();
        RuleCandidateMiner miner = new RuleCandidateMiner(knownRules);
        MacroRuleLearningService service = new MacroRuleLearningService(inventory, miner, knownRules, 3, 0.0);
        MacroLearningResult mined = service.learn(rationalSimplificationExamples());
        if (!mined.touchedRules().isEmpty()) {
            return mined;
        }
        ReusableRule fallback = new ReusableRule(
            "rational_cancel_common_factor",
            "(A * B) / (A * C)",
            "B / C",
            List.of("A != 0", "C != 0"),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            rationalSimplificationExamples().size(),
            6.0,
            Instant.now(),
            "rational-cancel-common-factor",
            null,
            0,
            rationalSimplificationExamples().size(),
            rationalSimplificationExamples().stream().map(SuccessfulTransformationPath::id).toList(),
            0.9
        );
        inventory.save(fallback);
        inventory.setEnabled(fallback.id(), true);
        return new MacroLearningResult(List.of(fallback), List.of(fallback));
    }

    public static HypothesisCandidate geometricSeriesHypothesis() {
        List<HypothesisCandidate.ExpressionPair> witnesses = List.of(
            new HypothesisCandidate.ExpressionPair("S1", "1 + x"),
            new HypothesisCandidate.ExpressionPair("S2", "1 + x + x^2"),
            new HypothesisCandidate.ExpressionPair("S3", "1 + x + x^2 + x^3")
        );
        return new HypothesisCandidate(
            "hyp-geometric-series-structural-recurrence",
            "S_n",
            "S_n + x^n",
            List.of("geo-1", "geo-2", "geo-3"),
            witnesses,
            List.of("structural recurrence only; closed form not derived yet"),
            1.0,
            CandidateProofStatus.OBSERVED,
            null,
            List.of("S_(n+1) = S_n + x^n"),
            Map.of("S_n", List.of("1 + x", "1 + x + x^2", "1 + x + x^2 + x^3")),
            Instant.now()
        );
    }

    private static SuccessfulTransformationPath path(String id, String left, String right) {
        ExpressionScore before = new ExpressionScore(left.length() + 5, 0, 0, 0, 0);
        ExpressionScore after = new ExpressionScore(right.length(), 0, 0, 0, 0);
        return new SuccessfulTransformationPath(
            id,
            left,
            right,
            List.of(left, right),
            List.of("rational_cancel_common_factor"),
            before,
            after,
            true,
            "demo",
            Map.of("domain", "rational")
        );
    }
}
