package de.regelsuche.api;

import de.regelsuche.discovery.DiscoveredTransformation;
import java.time.Instant;
import java.util.List;

public record TransformationPathDto(
    String id,
    String originalExpression,
    String improvedExpression,
    List<TransformationStepDto> steps,
    int originalScore,
    int improvedScore,
    int totalImprovement,
    String validationStatus,
    Instant discoveredAt,
    String canonicalHash
) {
    public static TransformationPathDto from(DiscoveredTransformation transformation) {
        return new TransformationPathDto(
            transformation.id(),
            transformation.originalExpression(),
            transformation.improvedExpression(),
            transformation.steps().stream().map(TransformationStepDto::from).toList(),
            transformation.originalScore().weightedTotal(),
            transformation.improvedScore().weightedTotal(),
            transformation.totalImprovement(),
            transformation.validationStatus().name(),
            transformation.discoveredAt(),
            transformation.canonicalHash()
        );
    }
}
