package de.regelsuche.transform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HypothesisTransformationEngine implements TransformationEngine {
    private final TransformationEngine baseEngine;
    private final List<HypothesisOperator> operators;
    private final int maxHypothesisCandidates;

    public HypothesisTransformationEngine(TransformationEngine baseEngine, List<HypothesisOperator> operators) {
        this(baseEngine, operators, 12);
    }

    public HypothesisTransformationEngine(
        TransformationEngine baseEngine,
        List<HypothesisOperator> operators,
        int maxHypothesisCandidates
    ) {
        if (baseEngine == null) {
            throw new IllegalArgumentException("baseEngine is required");
        }
        this.baseEngine = baseEngine;
        this.operators = operators == null ? List.of() : List.copyOf(operators);
        this.maxHypothesisCandidates = Math.max(0, maxHypothesisCandidates);
    }

    @Override
    public List<Transformation> transform(String expression) {
        List<Transformation> combined = new ArrayList<>(baseEngine.transform(expression));
        Set<String> applicationKeys = new HashSet<>();
        for (Transformation transformation : combined) {
            applicationKeys.add(transformation.applicationKey());
        }
        int generated = 0;
        for (HypothesisOperator operator : operators) {
            for (Transformation transformation : operator.generateCandidates(expression)) {
                if (generated >= maxHypothesisCandidates) {
                    return combined;
                }
                if (applicationKeys.add(transformation.applicationKey())) {
                    combined.add(transformation);
                    generated++;
                }
            }
        }
        return combined;
    }
}
