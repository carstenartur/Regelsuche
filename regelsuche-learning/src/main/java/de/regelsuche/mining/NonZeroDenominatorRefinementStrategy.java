package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refinement strategy that adds a non-zero denominator constraint when a
 * counterexample reveals a division-by-zero case.
 *
 * <p>If the hypothesis contains a division operator ({@code /}) and the
 * counterexample assigns a zero value to a denominator variable, this
 * strategy adds a {@code "var != 0"} assumption for each such variable.</p>
 */
public class NonZeroDenominatorRefinementStrategy implements RefinementStrategy {
    private static final Pattern PARENTHESIZED_DENOMINATOR =
        Pattern.compile("/\\s*\\(\\s*([A-Za-z][A-Za-z0-9_]*)\\s*\\)");
    private static final Pattern BARE_DENOMINATOR =
        Pattern.compile("/\\s*([A-Za-z][A-Za-z0-9_]*)\\b");

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

        Set<String> denominatorVars = new LinkedHashSet<>();
        collectDenominatorVars(revision.leftPattern(), denominatorVars);
        collectDenominatorVars(revision.rightPattern(), denominatorVars);
        if (denominatorVars.isEmpty()) {
            return Optional.empty();
        }

        List<String> zeroVars = new ArrayList<>();
        if (counterexampleResult.counterexample().isPresent()) {
            CounterexampleSearchService.Counterexample cex = counterexampleResult.counterexample().get();
            for (String assignment : cex.assignments()) {
                String[] parts = assignment.split("=", 2);
                if (parts.length == 2 && isZeroValue(parts[1].strip())) {
                    String varName = parts[0].strip();
                    if (!varName.isEmpty() && denominatorVars.contains(varName)) {
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

    private static void collectDenominatorVars(String pattern, Set<String> denominatorVars) {
        Matcher parenthesized = PARENTHESIZED_DENOMINATOR.matcher(pattern);
        while (parenthesized.find()) {
            denominatorVars.add(parenthesized.group(1));
        }

        Matcher bare = BARE_DENOMINATOR.matcher(pattern);
        while (bare.find()) {
            denominatorVars.add(bare.group(1));
        }
    }

    private static boolean isZeroValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.strip();
        int slashIndex = normalized.indexOf('/');
        if (slashIndex > 0 && slashIndex == normalized.lastIndexOf('/')) {
            try {
                BigDecimal numerator = new BigDecimal(normalized.substring(0, slashIndex).strip());
                BigDecimal denominator = new BigDecimal(normalized.substring(slashIndex + 1).strip());
                return denominator.compareTo(BigDecimal.ZERO) != 0 && numerator.compareTo(BigDecimal.ZERO) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        try {
            return new BigDecimal(normalized).compareTo(BigDecimal.ZERO) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
