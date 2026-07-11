package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Leave-one-out assumption minimizer with explicit classification of each challenged assumption. */
public final class AssumptionMinimizer {
    private AssumptionMinimizer() {
    }

    public static HypothesisCandidate minimize(HypothesisCandidate candidate, StabilityOracle oracle) {
        return analyze(candidate, oracle == null ? null : challengedCandidate -> oracle.isStable(challengedCandidate)
            ? CounterexampleSearchService.CounterexampleSearchResult.noCounterexample()
            : CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(List.of(), "challenge failed", "challenge failed"),
                List.of(),
                List.of("assumption-minimizer")
            )).minimizedCandidate();
    }

    public static MinimizationResult analyze(HypothesisCandidate candidate, ChallengeOracle oracle) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        List<String> minimized = new ArrayList<>(candidate.assumptions());
        Map<String, AssumptionJudgement> judgements = new LinkedHashMap<>();
        for (String assumption : List.copyOf(minimized)) {
            List<String> trial = new ArrayList<>(minimized);
            trial.remove(assumption);
            if (oracle == null) {
                judgements.put(assumption, new AssumptionJudgement(
                    assumption,
                    CounterexampleSearchService.AssumptionClassification.UNKNOWN,
                    CounterexampleSearchService.CounterexampleSearchResult.inconclusive("no challenge oracle configured")
                ));
                continue;
            }
            CounterexampleSearchService.CounterexampleSearchResult challenge = oracle.challenge(candidate.withAssumptions(trial));
            CounterexampleSearchService.AssumptionClassification classification = classify(challenge);
            judgements.put(assumption, new AssumptionJudgement(assumption, classification, challenge));
            if (classification == CounterexampleSearchService.AssumptionClassification.REDUNDANT_WITHIN_TESTED_EVIDENCE) {
                minimized = trial;
            }
        }
        return new MinimizationResult(candidate.withAssumptions(minimized), List.copyOf(judgements.values()));
    }

    private static CounterexampleSearchService.AssumptionClassification classify(
        CounterexampleSearchService.CounterexampleSearchResult challenge
    ) {
        if (challenge == null) {
            return CounterexampleSearchService.AssumptionClassification.UNKNOWN;
        }
        return switch (challenge.status()) {
            case COUNTEREXAMPLE_FOUND -> CounterexampleSearchService.AssumptionClassification.REQUIRED;
            case NO_COUNTEREXAMPLE_FOUND -> CounterexampleSearchService.AssumptionClassification.REDUNDANT_WITHIN_TESTED_EVIDENCE;
            case INCONCLUSIVE -> challenge.attemptedSources().isEmpty()
                || challenge.explanation().toLowerCase(java.util.Locale.ROOT).contains("unsupported")
                ? CounterexampleSearchService.AssumptionClassification.UNSUPPORTED
                : CounterexampleSearchService.AssumptionClassification.UNKNOWN;
        };
    }

    public record AssumptionJudgement(
        String assumption,
        CounterexampleSearchService.AssumptionClassification classification,
        CounterexampleSearchService.CounterexampleSearchResult challengeResult
    ) {
        public AssumptionJudgement {
            if (assumption == null || assumption.isBlank()) {
                throw new IllegalArgumentException("assumption must not be blank");
            }
            classification = classification == null
                ? CounterexampleSearchService.AssumptionClassification.UNKNOWN
                : classification;
            challengeResult = challengeResult == null
                ? CounterexampleSearchService.CounterexampleSearchResult.inconclusive("missing challenge result")
                : challengeResult;
        }
    }

    public record MinimizationResult(HypothesisCandidate minimizedCandidate, List<AssumptionJudgement> judgements) {
        public MinimizationResult {
            if (minimizedCandidate == null) {
                throw new IllegalArgumentException("minimizedCandidate must not be null");
            }
            judgements = judgements == null ? List.of() : List.copyOf(judgements);
        }
    }

    @FunctionalInterface
    public interface StabilityOracle {
        boolean isStable(HypothesisCandidate candidateWithoutOneAssumption);
    }

    @FunctionalInterface
    public interface ChallengeOracle {
        CounterexampleSearchService.CounterexampleSearchResult challenge(HypothesisCandidate candidateWithoutOneAssumption);
    }
}
