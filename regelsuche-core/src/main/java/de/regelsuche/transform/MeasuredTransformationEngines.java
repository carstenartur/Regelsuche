package de.regelsuche.transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Factories for deterministic, work-accounted transformation engines. */
public final class MeasuredTransformationEngines {
    private static final Comparator<Transformation> ORDER = Comparator
        .comparing(Transformation::rule)
        .thenComparing(Transformation::transformedExpression)
        .thenComparing(Transformation::applicationKey);

    private MeasuredTransformationEngines() {
    }

    /** Wrap an ordinary engine with one invocation plus candidate accounting. */
    public static MeasuredTransformationEngine counting(
        TransformationEngine engine
    ) {
        Objects.requireNonNull(engine, "engine");
        if (engine instanceof MeasuredTransformationEngine measured) {
            return measured;
        }
        return expression -> {
            List<Transformation> transformations = new ArrayList<>(
                Objects.requireNonNull(
                    engine.transform(expression),
                    "TransformationEngine.transform must not return null"));
            transformations.forEach(value ->
                Objects.requireNonNull(value, "transformation"));
            transformations.sort(ORDER);
            return new TransformationBatch(
                transformations,
                TransformationWorkMetrics.flatEngine(transformations.size()));
        };
    }

    /**
     * Invoke every child exactly once, sum all work and deterministically remove
     * duplicate output transformations.
     */
    public static MeasuredTransformationEngine union(
        TransformationEngine... engines
    ) {
        Objects.requireNonNull(engines, "engines");
        List<MeasuredTransformationEngine> measured = Arrays.stream(engines)
            .map(value -> counting(Objects.requireNonNull(value, "engine")))
            .toList();
        if (measured.isEmpty()) {
            throw new IllegalArgumentException("at least one engine is required");
        }
        return expression -> {
            List<Transformation> combined = new ArrayList<>();
            TransformationWorkMetrics work = TransformationWorkMetrics.ZERO;
            for (MeasuredTransformationEngine engine : measured) {
                TransformationBatch batch = engine.transformMeasured(expression);
                combined.addAll(batch.transformations());
                work = work.plus(batch.workMetrics());
            }
            combined.sort(ORDER);
            LinkedHashMap<String, Transformation> distinct =
                new LinkedHashMap<>();
            for (Transformation transformation : combined) {
                distinct.putIfAbsent(key(transformation), transformation);
            }
            long removed = combined.size() - distinct.size();
            return new TransformationBatch(
                List.copyOf(distinct.values()),
                work.withDuplicateCandidatesDropped(removed));
        };
    }

    private static String key(Transformation transformation) {
        return transformation.rule() + "\u0000"
            + transformation.transformedExpression() + "\u0000"
            + transformation.applicationKey() + "\u0000"
            + transformation.primitiveRuleIds();
    }
}
