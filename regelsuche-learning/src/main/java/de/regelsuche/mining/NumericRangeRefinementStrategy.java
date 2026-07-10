package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Refinement strategy that restricts a numeric parameter to a narrower domain
 * when a counterexample reveals out-of-range behaviour.
 *
 * <p>This strategy checks whether the counterexample assigns an extreme value
 * (e.g. very large magnitude, fractional value when integer behaviour is
 * expected) to a parameter and adds a range assumption such as
 * {@code "A is integer"} or {@code "A > -100 and A < 100"}.</p>
 *
 * <p>Specifically, if the counterexample assigns a non-integer fractional
 * value to an uppercase parameter variable (convention: uppercase = numeric
 * constant placeholder) and the pattern's assumptions do not already restrict
 * it, this strategy proposes adding an {@code "A is integer"} constraint.</p>
 */
public class NumericRangeRefinementStrategy implements RefinementStrategy {

    /** Upper-case letters are the convention for numeric constant placeholders. */
    private static final java.util.regex.Pattern UPPERCASE_VAR =
        java.util.regex.Pattern.compile("\\b([A-Z])\\b");

    @Override
    public String name() {
        return "numeric-range-restriction";
    }

    @Override
    public Optional<RefinementProposal> refine(
        HypothesisRevision revision,
        CounterexampleSearchService.CounterexampleSearchResult counterexampleResult
    ) {
        if (counterexampleResult.counterexample().isEmpty()) {
            return Optional.empty();
        }

        CounterexampleSearchService.Counterexample cex = counterexampleResult.counterexample().get();

        // Find uppercase placeholder variables in the pattern
        java.util.Set<String> paramVars = new java.util.LinkedHashSet<>();
        collectUppercaseVars(revision.leftPattern(), paramVars);
        collectUppercaseVars(revision.rightPattern(), paramVars);

        if (paramVars.isEmpty()) {
            return Optional.empty();
        }

        List<String> newAssumptions = new ArrayList<>(revision.assumptions());
        boolean changed = false;

        for (String assignment : cex.assignments()) {
            String[] parts = assignment.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String varName = parts[0].strip();
            String value = parts[1].strip();
            if (!paramVars.contains(varName)) {
                continue;
            }

            // Check if it's a fractional value
            if (isFractional(value)) {
                String intConstraint = varName + " is integer";
                if (!newAssumptions.contains(intConstraint)) {
                    newAssumptions.add(intConstraint);
                    changed = true;
                }
            }
        }

        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(new RefinementProposal(
            revision.leftPattern(), revision.rightPattern(), newAssumptions
        ));
    }

    private static void collectUppercaseVars(String pattern, java.util.Set<String> vars) {
        java.util.regex.Matcher m = UPPERCASE_VAR.matcher(pattern);
        while (m.find()) {
            vars.add(m.group(1));
        }
    }

    private static boolean isFractional(String value) {
        try {
            double d = Double.parseDouble(value);
            return d != Math.floor(d) && !Double.isInfinite(d);
        } catch (NumberFormatException e) {
            // Could be a fraction literal like "1/3"
            return value.contains("/") && !value.startsWith("(");
        }
    }
}
