package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Refinement strategy that adds a non-zero denominator constraint when a
 * counterexample reveals a division-by-zero case.
 *
 * <p>If the hypothesis contains a division operator ({@code /}) and the
 * counterexample assigns a zero value to a denominator variable, this
 * strategy adds a {@code "var != 0"} assumption for each such variable.</p>
 */
public class NonZeroDenominatorRefinementStrategy implements RefinementStrategy {

    @Override
    public String name() {
        return "non-zero-denominator";
    }

    @Override
    public Optional<RefinementProposal> refine(
        HypothesisRevision revision,
        CounterexampleSearchService.CounterexampleSearchResult counterexampleResult
    ) {
        // Only apply if the pattern contains division
        if (!revision.leftPattern().contains("/") && !revision.rightPattern().contains("/")) {
            return Optional.empty();
        }

        // Collect variables that are assigned 0 in the counterexample
        List<String> zeroVars = new ArrayList<>();
        if (counterexampleResult.counterexample().isPresent()) {
            CounterexampleSearchService.Counterexample cex = counterexampleResult.counterexample().get();
            for (String assignment : cex.assignments()) {
                // assignments are of the form "x=0" or "x = 0"
                String[] parts = assignment.split("=", 2);
                if (parts.length == 2 && parts[1].strip().equals("0")) {
                    String varName = parts[0].strip();
                    if (!varName.isEmpty()) {
                        zeroVars.add(varName);
                    }
                }
            }
        }

        if (zeroVars.isEmpty()) {
            // No zero-assignments found; also check inferred assumptions for denominator hints
            boolean hasNonZeroDenominatorHint = counterexampleResult.inferredAssumptions().stream()
                .anyMatch(a -> a.contains("!= 0") || a.contains("≠ 0") || a.contains("≠0"));
            if (!hasNonZeroDenominatorHint) {
                return Optional.empty();
            }
            // Use inferred assumptions directly
            List<String> newAssumptions = new ArrayList<>(revision.assumptions());
            counterexampleResult.inferredAssumptions().stream()
                .filter(a -> !newAssumptions.contains(a))
                .forEach(newAssumptions::add);
            if (newAssumptions.equals(revision.assumptions())) {
                return Optional.empty();
            }
            return Optional.of(new RefinementProposal(
                revision.leftPattern(), revision.rightPattern(), newAssumptions
            ));
        }

        List<String> newAssumptions = new ArrayList<>(revision.assumptions());
        boolean changed = false;
        for (String var : zeroVars) {
            String constraint = var + " != 0";
            if (!newAssumptions.contains(constraint)) {
                newAssumptions.add(constraint);
                changed = true;
            }
        }
        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(new RefinementProposal(
            revision.leftPattern(), revision.rightPattern(), newAssumptions
        ));
    }
}
