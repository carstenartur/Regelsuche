package de.regelsuche.docs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PromotionRegistry {
    Registry build(List<PromotionRecord> records) {
        Map<String, PromotionRecord> merged = new LinkedHashMap<>();
        for (PromotionRecord record : records.stream()
            .sorted(Comparator.comparing(PromotionRecord::candidateId)
                .thenComparing(PromotionRecord::sourceCampaign))
            .toList()) {
            merged.merge(record.candidateId(), record, this::merge);
        }
        List<PromotionRecord> registryRecords = merged.values().stream()
            .sorted(Comparator.comparing(PromotionRecord::candidateId))
            .toList();
        List<HistoryEntry> history = registryRecords.stream()
            .map(record -> new HistoryEntry(
                record.candidateId(),
                record.sourceCampaign(),
                record.stage(),
                record.promotionEligible(),
                record.generatedMacroId(),
                record.reuseCampaign(),
                record.promotionBlockers()))
            .toList();
        List<RegressionEntry> regressions = registryRecords.stream()
            .filter(record -> record.stage() == PromotionStage.PROMOTED && !record.reuseCampaign().isBlank())
            .filter(record -> !record.measuredImprovement())
            .map(record -> new RegressionEntry(
                record.candidateId(),
                record.reuseCampaign(),
                "macro reused without measurable improvement"))
            .toList();
        return new Registry(registryRecords, history, regressions);
    }

    String renderHistoryMarkdown(Registry registry) {
        StringBuilder out = new StringBuilder("# Promotion history\n\n");
        out.append("| Candidate | Campaign | Stage | Eligible | Generated macro | Reuse campaign | Blockers |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (HistoryEntry entry : registry.history()) {
            out.append("| ").append(escape(entry.candidateId()))
                .append(" | ").append(escape(entry.sourceCampaign()))
                .append(" | ").append(entry.stage().name().toLowerCase(Locale.ROOT))
                .append(" | ").append(entry.promotionEligible() ? "yes" : "no")
                .append(" | ").append(escape(orDash(entry.generatedMacroId())))
                .append(" | ").append(escape(orDash(entry.reuseCampaign())))
                .append(" | ").append(escape(entry.blockers().isEmpty() ? "—" : String.join(", ", entry.blockers())))
                .append(" |\n");
        }
        out.append("\n## Regression history\n\n");
        if (registry.regressions().isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (RegressionEntry regression : registry.regressions()) {
            out.append("- ").append(escape(regression.candidateId()))
                .append(" (").append(escape(regression.campaignId())).append("): ")
                .append(escape(regression.reason())).append('\n');
        }
        return out.toString();
    }

    private PromotionRecord merge(PromotionRecord left, PromotionRecord right) {
        PromotionRecord higherStage = left.stage().ordinal() >= right.stage().ordinal() ? left : right;
        List<String> blockers = new ArrayList<>(left.promotionBlockers());
        blockers.addAll(right.promotionBlockers());
        List<String> uniqueBlockers = blockers.stream().distinct().sorted().toList();
        List<String> reusedMacroIds = new ArrayList<>(left.reusedMacroIds());
        reusedMacroIds.addAll(right.reusedMacroIds());
        List<String> uniqueReusedMacroIds = reusedMacroIds.stream().distinct().sorted().toList();
        String generatedMacroId = !left.generatedMacroId().isBlank() ? left.generatedMacroId() : right.generatedMacroId();
        return new PromotionRecord(
            higherStage.candidateId(),
            higherStage.sourceCampaign(),
            higherStage.discoveryDate(),
            higherStage.family(),
            higherStage.stage(),
            !left.originalExpression().isBlank() ? left.originalExpression() : right.originalExpression(),
            !left.discoveredStructure().isBlank() ? left.discoveredStructure() : right.discoveredStructure(),
            higherStage.oracleStatus(),
            !left.oracleEvidence().isBlank() ? left.oracleEvidence() : right.oracleEvidence(),
            higherStage.ablationStatus(),
            choose(left.sourceOperator(), right.sourceOperator()),
            choose(left.sourcePack(), right.sourcePack()),
            !left.assumptions().isEmpty() ? left.assumptions() : right.assumptions(),
            !left.rationale().isBlank() ? left.rationale() : right.rationale(),
            !left.rulePath().isEmpty() ? left.rulePath() : right.rulePath(),
            left.promotionEligible() || right.promotionEligible(),
            uniqueBlockers,
            left.evidenceExists() || right.evidenceExists(),
            left.curatedPathPresent() || right.curatedPathPresent(),
            left.fallbackUsed() || right.fallbackUsed(),
            left.macroOpportunity() || right.macroOpportunity(),
            generatedMacroId,
            uniqueReusedMacroIds,
            left.measuredImprovement() || right.measuredImprovement(),
            choose(left.reuseCampaign(), right.reuseCampaign())
        );
    }

    private String choose(String left, String right) {
        return left != null && !left.isBlank() ? left : (right == null ? "" : right);
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    record Registry(List<PromotionRecord> records, List<HistoryEntry> history, List<RegressionEntry> regressions) {
        Registry {
            records = records == null ? List.of() : List.copyOf(records);
            history = history == null ? List.of() : List.copyOf(history);
            regressions = regressions == null ? List.of() : List.copyOf(regressions);
        }
    }

    record HistoryEntry(
        String candidateId,
        String sourceCampaign,
        PromotionStage stage,
        boolean promotionEligible,
        String generatedMacroId,
        String reuseCampaign,
        List<String> blockers
    ) {
        HistoryEntry {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    record RegressionEntry(String candidateId, String campaignId, String reason) {
    }
}
