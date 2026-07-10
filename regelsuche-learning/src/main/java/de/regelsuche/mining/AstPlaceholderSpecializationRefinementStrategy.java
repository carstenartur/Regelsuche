package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refinement strategy that specializes an over-broad AST placeholder.
 *
 * <p>When a counterexample shows that an expression-level placeholder (a
 * single-letter lowercase variable that can match any sub-expression) is
 * being assigned a value that makes the rule invalid, this strategy attempts
 * to restrict the placeholder to numeric-only bindings by adding a
 * {@code "var is numeric"} constraint.</p>
 *
 * <p>The strategy activates when:
 * <ul>
 *   <li>The left or right pattern contains a placeholder variable (a bare
 *       lowercase letter not inside a function call)</li>
 *   <li>The counterexample assigns a symbolic or complex expression to that
 *       placeholder (i.e. a non-numeric string value)</li>
 * </ul>
 * </p>
 */
public class AstPlaceholderSpecializationRefinementStrategy implements RefinementStrategy {

    /** Bare lowercase single-letter variable not followed by '(' (not a function call). */
    private static final Pattern BARE_PLACEHOLDER =
        Pattern.compile("\\b([a-z])\\b(?!\\s*\\()");

    @Override
    public String name() {
        return "ast-placeholder-specialization";
    }

    @Override
    public Optional<RefinementProposal> refine(
        HypothesisRevision revision,
        CounterexampleSearchService.CounterexampleSearchResult counterexampleResult
    ) {
        if (counterexampleResult.counterexample().isEmpty()) {
            return Optional.empty();
        }

        // Collect bare placeholder variables from the pattern
        java.util.Set<String> placeholders = new java.util.LinkedHashSet<>();
        extractPlaceholders(revision.leftPattern(), placeholders);
        extractPlaceholders(revision.rightPattern(), placeholders);

        if (placeholders.isEmpty()) {
            return Optional.empty();
        }

        CounterexampleSearchService.Counterexample cex = counterexampleResult.counterexample().get();
        List<String> newAssumptions = new ArrayList<>(revision.assumptions());
        boolean changed = false;

        for (String assignment : cex.assignments()) {
            String[] parts = assignment.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String varName = parts[0].strip();
            String value = parts[1].strip();

            if (!placeholders.contains(varName)) {
                continue;
            }

            // If the assigned value is symbolic (non-numeric), restrict to numeric
            if (!isNumericValue(value)) {
                String numericConstraint = varName + " is numeric";
                if (!newAssumptions.contains(numericConstraint)) {
                    newAssumptions.add(numericConstraint);
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

    private static void extractPlaceholders(String pattern, java.util.Set<String> placeholders) {
        Matcher m = BARE_PLACEHOLDER.matcher(pattern);
        while (m.find()) {
            placeholders.add(m.group(1));
        }
    }

    private static boolean isNumericValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.strip();
        int slashIndex = normalized.indexOf('/');
        if (slashIndex > 0 && slashIndex == normalized.lastIndexOf('/')) {
            try {
                new BigDecimal(normalized.substring(0, slashIndex).strip());
                BigDecimal denominator = new BigDecimal(normalized.substring(slashIndex + 1).strip());
                return denominator.compareTo(BigDecimal.ZERO) != 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        try {
            new BigDecimal(normalized);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
