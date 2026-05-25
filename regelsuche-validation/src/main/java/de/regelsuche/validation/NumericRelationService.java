package de.regelsuche.validation;

import java.util.List;

public interface NumericRelationService {
    NumericRelationResult findIntegerRelation(List<Double> values);

    record NumericRelationResult(
        List<Integer> coefficients,
        double residual,
        MathematicalAlgorithmRegistry.AlgorithmExecutionResult result
    ) {
    }
}
