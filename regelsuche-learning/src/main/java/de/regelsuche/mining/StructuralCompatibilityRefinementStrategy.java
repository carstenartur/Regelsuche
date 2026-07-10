package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;

import java.util.List;
import java.util.Optional;

/**
 * Refinement strategy that rejects structurally incompatible substitutions.
 *
 * <p>When the counterexample assigns a complex expression (e.g. a list, a
 * matrix, or a symbolic expression with multiple operators) to a placeholder
 * that was intended to represent a simple scalar, this strategy adds an
 * assumption that restricts the placeholder to scalar numeric values.</p>
 *
 * <p>A substitution is considered structurally incompatible if a lowercase
 * placeholder variable (convention: simple expression placeholder) is
 * assigned a value containing nested structure indicators (brackets, commas)
 * or matrix notation.</p>
 */
public class StructuralCompatibilityRefinementStrategy implements RefinementStrategy {

    @Override
    public String name() {
        return "structural-compatibility";
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
        List<String> newAssumptions = new java.util.ArrayList<>(revision.assumptions());
        boolean changed = false;

        for (String assignment : cex.assignments()) {
            String[] parts = assignment.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String varName = parts[0].strip();
            String value = parts[1].strip();

            // Only apply to single-letter lowercase variables (expression placeholders)
            if (varName.length() != 1 || !Character.isLowerCase(varName.charAt(0))) {
                continue;
            }

            // Check if the assigned value has incompatible structure
            if (isStructurallyComplex(value)) {
                String scalarConstraint = varName + " is scalar";
                if (!newAssumptions.contains(scalarConstraint)) {
                    newAssumptions.add(scalarConstraint);
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

    private static boolean isStructurallyComplex(String value) {
        // Matrix brackets or comma-separated list
        return value.contains("[") || value.contains(",") || value.contains("\\begin{");
    }
}
