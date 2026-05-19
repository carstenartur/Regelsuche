package de.regelsuche.api;

import de.regelsuche.export.ExportBundle;
import java.util.List;

public record ExportBundleDto(
    String schemaVersion,
    List<TransformationPathDto> transformations,
    List<RuleCandidateDto> ruleCandidates,
    List<RuleInventoryDto> reusableRules
) {
    public static ExportBundleDto from(ExportBundle bundle) {
        return new ExportBundleDto(
            bundle.schemaVersion(),
            bundle.transformations().stream().map(TransformationPathDto::from).toList(),
            bundle.ruleCandidates().stream().map(RuleCandidateDto::from).toList(),
            bundle.reusableRules().stream().map(RuleInventoryDto::from).toList()
        );
    }
}
