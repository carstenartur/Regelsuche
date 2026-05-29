package de.regelsuche.transform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, Transformation> combined = new LinkedHashMap<>();
        for (Transformation transformation : baseEngine.transform(expression)) {
            combined.putIfAbsent(transformation.applicationKey(), transformation);
        }
        int generated = 0;
        for (HypothesisOperator operator : operators) {
            for (Transformation transformation : operator.generateCandidates(expression)) {
                if (generated >= maxHypothesisCandidates) {
                    return new ArrayList<>(combined.values());
                }
                if (!combined.containsKey(transformation.applicationKey())) {
                    combined.put(transformation.applicationKey(), transformation);
                    generated++;
                }
            }
        }
        return new ArrayList<>(combined.values());
    }
}
