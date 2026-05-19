package de.regelsuche.mining;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CandidateValidator {
    private static final List<Integer> CANDIDATE_VALUES = List.of(2, 4, 6, 7, 8, 9, 10);
    private final EquivalenceService equivalenceService;
    private final RulePatternParser patternParser;
    private final RulePatternInstantiator patternInstantiator;

    public CandidateValidator(EquivalenceService equivalenceService) {
        this(equivalenceService, new RulePatternParser(), new RulePatternInstantiator());
    }

    CandidateValidator(
        EquivalenceService equivalenceService,
        RulePatternParser patternParser,
        RulePatternInstantiator patternInstantiator
    ) {
        this.equivalenceService = equivalenceService;
        this.patternParser = patternParser;
        this.patternInstantiator = patternInstantiator;
    }

    public boolean validate(GeneralizedPattern pattern) {
        return proofStatus(pattern) != CandidateProofStatus.OBSERVED;
    }

    public CandidateProofStatus proofStatus(GeneralizedPattern pattern) {
        boolean validatedByFreshExamples = validateFreshExamples(pattern);
        if (!validatedByFreshExamples) {
            return CandidateProofStatus.OBSERVED;
        }
        if (equivalenceService.areEquivalent(pattern.leftPattern(), pattern.rightPattern())
            && equivalenceService.evidence(pattern.leftPattern(), pattern.rightPattern()).contains("SymPy")) {
            return CandidateProofStatus.SYMBOLICALLY_VERIFIED;
        }
        return CandidateProofStatus.VALIDATED_BY_EXAMPLES;
    }

    private boolean validateFreshExamples(GeneralizedPattern pattern) {
        RulePatternNode leftPattern = patternParser.parse(pattern.leftPattern());
        RulePatternNode rightPattern = patternParser.parse(pattern.rightPattern());
        Set<String> placeholders = new LinkedHashSet<>();
        collectPlaceholders(leftPattern, placeholders);
        collectPlaceholders(rightPattern, placeholders);
        for (int value : freshValues(pattern)) {
            Map<String, Expr> bindings = bindings(placeholders, value);
            String left = ExpressionFormatter.format(patternInstantiator.instantiate(leftPattern, bindings));
            String right = ExpressionFormatter.format(patternInstantiator.instantiate(rightPattern, bindings));
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

    private Map<String, Expr> bindings(Set<String> placeholders, int value) {
        Map<String, Expr> bindings = new HashMap<>();
        int offset = 0;
        for (String placeholder : placeholders) {
            bindings.put(placeholder, new NumberExpr(value + offset));
            offset++;
        }
        return bindings;
    }

    private void collectPlaceholders(RulePatternNode node, Set<String> placeholders) {
        if (node instanceof PatternVariable variable && isPlaceholder(variable.name())) {
            placeholders.add(variable.name());
        } else if (node instanceof PatternBinary binary) {
            collectPlaceholders(binary.left(), placeholders);
            collectPlaceholders(binary.right(), placeholders);
        }
    }

    private boolean isPlaceholder(String name) {
        return name.matches("[A-Z]") || name.matches("N\\d+");
    }
}
