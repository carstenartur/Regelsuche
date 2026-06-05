package de.regelsuche.moves;

import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Builds deterministic classic-vs-move comparison reports. */
public final class MoveComparisonService {

    public MoveCandidateTransformationEngine.ComparisonReport compare(
        String expression,
        List<Transformation> classic,
        List<MoveCandidateTransformationEngine.MoveBackedTransformation> moveCandidates,
        Function<String, String> keyCanonicalizer
    ) {
        Map<String, MoveCandidateTransformationEngine.CandidateSummary> classicByExpression = new LinkedHashMap<>();
        for (Transformation transformation : classic) {
            classicByExpression.putIfAbsent(
                keyCanonicalizer.apply(transformation.transformedExpression()),
                MoveCandidateTransformationEngine.CandidateSummary.fromClassic(transformation)
            );
        }

        Map<String, MoveCandidateTransformationEngine.CandidateSummary> moveByExpression = new LinkedHashMap<>();
        for (MoveCandidateTransformationEngine.MoveBackedTransformation candidate : moveCandidates) {
            moveByExpression.putIfAbsent(
                keyCanonicalizer.apply(candidate.transformation().transformedExpression()),
                MoveCandidateTransformationEngine.CandidateSummary.fromMove(candidate)
            );
        }

        List<MoveCandidateTransformationEngine.CandidateSummary> overlaps = new ArrayList<>();
        List<MoveCandidateTransformationEngine.CandidateSummary> moveOnly = new ArrayList<>();
        for (Map.Entry<String, MoveCandidateTransformationEngine.CandidateSummary> entry : moveByExpression.entrySet()) {
            if (classicByExpression.containsKey(entry.getKey())) {
                overlaps.add(entry.getValue());
            } else {
                moveOnly.add(entry.getValue());
            }
        }

        List<MoveCandidateTransformationEngine.CandidateSummary> classicOnly = new ArrayList<>();
        for (Map.Entry<String, MoveCandidateTransformationEngine.CandidateSummary> entry : classicByExpression.entrySet()) {
            if (!moveByExpression.containsKey(entry.getKey())) {
                classicOnly.add(entry.getValue());
            }
        }

        return new MoveCandidateTransformationEngine.ComparisonReport(
            expression,
            new ArrayList<>(classicByExpression.values()),
            new ArrayList<>(moveByExpression.values()),
            overlaps,
            moveOnly,
            classicOnly
        );
    }
}
