package de.regelsuche.mining;

import de.regelsuche.equivalence.EquivalenceService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CandidateValidator {
    private static final List<Integer> CANDIDATE_VALUES = List.of(2, 4, 6, 7, 8, 9, 10);
    private final EquivalenceService equivalenceService;

    public CandidateValidator(EquivalenceService equivalenceService) {
        this.equivalenceService = equivalenceService;
    }

    public boolean validate(GeneralizedPattern pattern) {
        for (int value : freshValues(pattern)) {
            String left = instantiate(pattern.leftPattern(), value);
            String right = instantiate(pattern.rightPattern(), value);
            if (!equivalenceService.areEquivalent(left, right)) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> freshValues(GeneralizedPattern pattern) {
        Set<Integer> used = new HashSet<>();
        pattern.placeholderValues().values().forEach(values -> values.forEach(value -> used.add(Math.abs(value))));
        List<Integer> fresh = new ArrayList<>();
        for (int candidate : CANDIDATE_VALUES) {
            if (!used.contains(candidate)) {
                fresh.add(candidate);
            }
            if (fresh.size() == 3) {
                return fresh;
            }
        }
        return List.of(11, 12, 13);
    }

    private String instantiate(String pattern, int value) {
        String squared = Integer.toString(value * value);
        return pattern
            .replace("A^2", squared)
            .replace("2*A", Integer.toString(2 * value))
            .replace("-2*A", Integer.toString(-2 * value))
            .replace("A", Integer.toString(value));
    }
}
