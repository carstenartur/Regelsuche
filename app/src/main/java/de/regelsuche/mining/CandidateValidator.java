package de.regelsuche.mining;

import de.regelsuche.ast.Expr;
import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class CandidateValidator {
    private final EquivalenceService equivalenceService;
    private final RulePatternParser patternParser;
    private final RulePatternInstantiator patternInstantiator;
    private final FreshBindingGenerator freshBindingGenerator;
    private final ParameterRelationEvaluator parameterRelationEvaluator;

    public CandidateValidator(EquivalenceService equivalenceService) {
        this(
            equivalenceService,
            new RulePatternParser(),
            new RulePatternInstantiator(),
            new FreshBindingGenerator(),
            new ParameterRelationEvaluator()
        );
    }

    CandidateValidator(
        EquivalenceService equivalenceService,
        RulePatternParser patternParser,
        RulePatternInstantiator patternInstantiator,
        FreshBindingGenerator freshBindingGenerator,
        ParameterRelationEvaluator parameterRelationEvaluator
    ) {
        this.equivalenceService = equivalenceService;
        this.patternParser = patternParser;
        this.patternInstantiator = patternInstantiator;
        this.freshBindingGenerator = freshBindingGenerator;
        this.parameterRelationEvaluator = parameterRelationEvaluator;
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
        Set<String> independentPlaceholders = independentPlaceholders(placeholders, pattern.parameterRelations());
        for (Map<String, Integer> baseBindings : freshBindingGenerator.generate(independentPlaceholders)) {
            Map<String, Expr> bindings = parameterRelationEvaluator.completeBindings(
                placeholders,
                baseBindings,
                pattern.parameterRelations()
            );
            String left = ExpressionFormatter.format(patternInstantiator.instantiate(leftPattern, bindings));
            String right = ExpressionFormatter.format(patternInstantiator.instantiate(rightPattern, bindings));
            if (!equivalenceService.areEquivalent(left, right)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> independentPlaceholders(Set<String> placeholders, java.util.List<String> parameterRelations) {
        Set<String> independent = new LinkedHashSet<>(placeholders);
        independent.removeAll(parameterRelationEvaluator.relationTargets(parameterRelations));
        return independent;
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
