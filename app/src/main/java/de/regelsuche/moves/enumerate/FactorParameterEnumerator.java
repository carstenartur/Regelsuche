package de.regelsuche.moves.enumerate;

import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import de.regelsuche.transform.QuadraticFactorizationHypothesisOperator;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Enumerates factorization candidates that the classic transformation engine can
 * realize for the current expression.
 */
public final class FactorParameterEnumerator implements ParameterEnumerator {

    private final QuadraticFactorizationHypothesisOperator factorCandidateOperator;

    public FactorParameterEnumerator() {
        this(new QuadraticFactorizationHypothesisOperator());
    }

    FactorParameterEnumerator(QuadraticFactorizationHypothesisOperator factorCandidateOperator) {
        this.factorCandidateOperator = factorCandidateOperator;
    }

    @Override
    public String id() {
        return "factor-candidate";
    }

    @Override
    public List<MoveParameter> enumerate(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        List<Transformation> ordered = new ArrayList<>(factorCandidateOperator.generateCandidates(expression));
        ordered.sort(Comparator.comparing(Transformation::rule)
                .thenComparing(Transformation::transformedExpression)
                .thenComparing(Transformation::applicationKey));

        LinkedHashMap<String, MoveParameter> distinct = new LinkedHashMap<>();
        int index = 0;
        for (Transformation transformation : ordered) {
            String transformed = transformation.transformedExpression();
            distinct.putIfAbsent(
                    transformed,
                    new MoveParameter(
                            "target",
                            MoveParameterKind.REPLACEMENT,
                            transformed,
                            transformed,
                            index++,
                            id()));
        }
        return List.copyOf(distinct.values());
    }
}
