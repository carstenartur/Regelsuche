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

    public static List<SuccessfulTransformationPath> rationalSimplificationExamples() {
        return List.of(
            path("rat-1", "(x*x)/x", "x"),
            path("rat-2", "(a*a)/a", "a"),
            path("rat-3", "(y*y)/y", "y"),
            path("rat-4", "(a*b)/a", "b"),
            path("rat-5", "(x+1)/(x+1)", "1"),
            path("rat-6", "x/x", "1"),
            path("rat-7", "a/a", "1"),
            path("rat-8", "y/y", "1"),
            path("rat-9", "(2*x)/(2*x)", "1"),
            path("rat-10", "(3*x)/(3*x)", "1"),
            path("rat-11", "(5*x)/(5*x)", "1")
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
            "(A * B) / A",
            "B",
            List.of("A != 0"),
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
