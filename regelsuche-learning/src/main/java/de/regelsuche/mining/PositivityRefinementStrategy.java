package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refinement strategy that adds positivity or non-negativity constraints when
 * a counterexample reveals a violation caused by a negative argument to a
 * function that requires non-negative input (e.g. {@code sqrt}, {@code log}).
 *
 * <p>If the pattern contains {@code sqrt(x)} or {@code log(x)} and the
 * counterexample assigns a negative value to {@code x}, this strategy adds
 * an {@code "x >= 0"} or {@code "x > 0"} assumption for each such variable.</p>
 */
public class PositivityRefinementStrategy implements RefinementStrategy {

    /** Functions that require strictly positive arguments. */
    private static final Set<String> STRICTLY_POSITIVE_FUNCS = Set.of("log", "ln");

    /** Functions that require non-negative (>= 0) arguments. */
    private static final Set<String> NON_NEGATIVE_FUNCS = Set.of("sqrt");

    private static final Pattern FUNC_ARG_PATTERN =
        Pattern.compile("\\b(sqrt|log|ln)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");

    @Override
    public String name() {
        return "positivity-constraint";
    }

    @Override
    public Optional<RefinementProposal> refine(
        HypothesisRevision revision,
        CounterexampleSearchService.CounterexampleSearchResult counterexampleResult
    ) {
        // Find functions that impose positivity requirements in the pattern
        Set<String> nonNegVars = new LinkedHashSet<>();
        Set<String> strictlyPosVars = new LinkedHashSet<>();
        extractPositivityVars(revision.leftPattern(), nonNegVars, strictlyPosVars);
        extractPositivityVars(revision.rightPattern(), nonNegVars, strictlyPosVars);

        if (nonNegVars.isEmpty() && strictlyPosVars.isEmpty()) {
            return Optional.empty();
        }

        // Only apply if the counterexample assigns a negative value to one of those vars
        Set<String> negativeVars = new LinkedHashSet<>();
        if (counterexampleResult.counterexample().isPresent()) {
            CounterexampleSearchService.Counterexample cex = counterexampleResult.counterexample().get();
            for (String assignment : cex.assignments()) {
                String[] parts = assignment.split("=", 2);
                if (parts.length == 2) {
                    String varName = parts[0].strip();
                    String value = parts[1].strip();
                    if (isNegativeNumeric(value)) {
                        negativeVars.add(varName);
                    }
                }
            }
        }

        if (negativeVars.isEmpty()) {
            return Optional.empty();
        }

        List<String> newAssumptions = new ArrayList<>(revision.assumptions());
        boolean changed = false;
        for (String var : negativeVars) {
            if (strictlyPosVars.contains(var)) {
                String constraint = var + " > 0";
                if (!newAssumptions.contains(constraint)) {
                    newAssumptions.add(constraint);
                    changed = true;
                }
            } else if (nonNegVars.contains(var)) {
                String constraint = var + " >= 0";
                if (!newAssumptions.contains(constraint)) {
                    newAssumptions.add(constraint);
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

    private static void extractPositivityVars(
        String pattern,
        Set<String> nonNegVars,
        Set<String> strictlyPosVars
    ) {
        Matcher m = FUNC_ARG_PATTERN.matcher(pattern);
        while (m.find()) {
            String func = m.group(1);
            String arg = m.group(2);
            if (STRICTLY_POSITIVE_FUNCS.contains(func)) {
                strictlyPosVars.add(arg);
            } else if (NON_NEGATIVE_FUNCS.contains(func)) {
                nonNegVars.add(arg);
            }
        }
    }

    private static boolean isNegativeNumeric(String value) {
        try {
            return Double.parseDouble(value) < 0;
        } catch (NumberFormatException e) {
            return value.startsWith("-");
        }
    }
}
