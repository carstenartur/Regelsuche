package de.regelsuche.mining;

import java.util.ArrayList;
import java.util.List;

/** Heuristic assumption minimizer; it preserves an assumption when the caller reports instability after removal. */
public final class AssumptionMinimizer {
    private AssumptionMinimizer() {
    }

    public static HypothesisCandidate minimize(HypothesisCandidate candidate, StabilityOracle oracle) {
        List<String> minimized = new ArrayList<>(candidate.assumptions());
        for (String assumption : List.copyOf(minimized)) {
            List<String> trial = new ArrayList<>(minimized);
            trial.remove(assumption);
            if (oracle == null || oracle.isStable(candidate.withAssumptions(trial))) {
                minimized = trial;
            }
        }
        return candidate.withAssumptions(minimized);
    }

    @FunctionalInterface
    public interface StabilityOracle {
        boolean isStable(HypothesisCandidate candidateWithoutOneAssumption);
    }
}
