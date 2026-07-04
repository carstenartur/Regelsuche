package de.regelsuche.docs;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

record PromotionObservation(
    String candidateId,
    String sourceCampaign,
    String discoveryDate,
    String family,
    String originalExpression,
    String discoveredStructure,
    boolean success,
    String oracleStatus,
    String oracleEvidence,
    String ablationStatus,
    String sourceOperator,
    String sourcePack,
    List<String> assumptions,
    String rationale,
    List<String> rulePath,
    boolean evidenceExists,
    boolean curatedPathPresent,
    boolean fallbackUsed,
    boolean macroOpportunity
) {
    PromotionObservation {
        family = family == null ? "" : family;
        originalExpression = originalExpression == null ? "" : originalExpression;
        discoveredStructure = discoveredStructure == null ? "" : discoveredStructure;
        oracleStatus = oracleStatus == null || oracleStatus.isBlank() ? "UNAVAILABLE" : oracleStatus;
        oracleEvidence = oracleEvidence == null ? "" : oracleEvidence;
        ablationStatus = ablationStatus == null || ablationStatus.isBlank() ? "N/A" : ablationStatus;
        sourceOperator = sourceOperator == null ? "" : sourceOperator;
        sourcePack = sourcePack == null ? "" : sourcePack;
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        rationale = rationale == null ? "" : rationale;
        rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
    }

    static PromotionObservation fromCampaignOne(DiscoveryCampaignOneRunner.CaseResult result, String campaignId) {
        return new PromotionObservation(
            result.id(),
            campaignId,
            discoveryDateFor(campaignId),
            result.family(),
            result.inputExpression(),
            result.targetExpression(),
            result.success(),
            result.oracleStatus(),
            result.oracleEvidence(),
            result.ablationStatus(),
            result.shortcutOperatorId(),
            result.shortcutPackId(),
            result.shortcutAssumptions(),
            rationale(result.notes(), result.failureReason()),
            result.rulePath(),
            !result.rulePath().isEmpty(),
            curatedPathPresent(result.shortcutSource()),
            fallbackUsed(result.rulePath()),
            macroOpportunity(result.family(), result.rulePath())
        );
    }

    static PromotionObservation fromCampaignTwo(DiscoveryCampaignTwoRunner.CaseResult result, String campaignId) {
        return new PromotionObservation(
            result.id(),
            campaignId,
            discoveryDateFor(campaignId),
            result.family(),
            result.inputExpression(),
            result.targetExpression(),
            result.success(),
            result.oracleStatus(),
            result.oracleEvidence(),
            result.ablationStatus(),
            result.shortcutOperatorId(),
            result.shortcutPackId(),
            result.shortcutAssumptions(),
            rationale(result.notes(), result.failureReason()),
            result.rulePath(),
            !result.rulePath().isEmpty(),
            curatedPathPresent(result.shortcutSource()),
            fallbackUsed(result.rulePath()),
            macroOpportunity(result.family(), result.rulePath())
        );
    }

    static PromotionObservation fromCampaignThree(DiscoveryCampaignThreeRunner.CaseResult result, String campaignId) {
        return new PromotionObservation(
            result.id(),
            campaignId,
            discoveryDateFor(campaignId),
            result.family(),
            result.inputExpression(),
            result.targetExpression(),
            result.success(),
            result.oracleStatus(),
            result.oracleEvidence(),
            result.ablationStatus(),
            result.shortcutOperatorId(),
            result.shortcutPackId(),
            result.shortcutAssumptions(),
            rationale(result.notes(), result.failureReason()),
            result.rulePath(),
            !result.rulePath().isEmpty(),
            curatedPathPresent(result.shortcutSource()),
            fallbackUsed(result.rulePath()),
            macroOpportunity(result.family(), result.rulePath())
        );
    }

    static PromotionObservation fromCampaignFive(DiscoveryCampaignFiveRunner.CaseResult result, String campaignId) {
        return new PromotionObservation(
            result.id(),
            campaignId,
            discoveryDateFor(campaignId),
            result.family(),
            result.inputExpression(),
            result.targetExpression(),
            result.success(),
            result.oracleStatus(),
            result.oracleEvidence(),
            result.ablationStatus(),
            result.shortcutOperatorId(),
            result.shortcutPackId(),
            result.shortcutAssumptions(),
            rationale(result.notes(), result.failureReason()),
            result.rulePath(),
            !result.rulePath().isEmpty(),
            curatedPathPresent(result.shortcutSource()),
            fallbackUsed(result.rulePath()),
            macroOpportunity(result.family(), result.rulePath())
        );
    }

    static PromotionObservation fromCampaignSeven(DiscoveryCampaignSevenRunner.CaseResult result, String campaignId) {
        return new PromotionObservation(
            result.id(),
            campaignId,
            discoveryDateFor(campaignId),
            result.family(),
            result.inputExpression(),
            result.targetExpression(),
            result.success(),
            result.oracleStatus(),
            result.oracleEvidence(),
            result.ablationStatus(),
            result.shortcutOperatorId(),
            result.shortcutPackId(),
            result.shortcutAssumptions(),
            rationale(result.notes(), result.failureReason()),
            result.rulePath(),
            !result.rulePath().isEmpty(),
            curatedPathPresent(result.shortcutSource()),
            fallbackUsed(result.rulePath()),
            macroOpportunity(result.family(), result.rulePath())
        );
    }

    static PromotionObservation fromCampaignEight(DiscoveryCampaignEightRunner.CaseResult result, String campaignId) {
        return new PromotionObservation(
            result.id(),
            campaignId,
            discoveryDateFor(campaignId),
            result.family(),
            result.inputExpression(),
            result.targetExpression(),
            result.success(),
            result.oracleStatus(),
            result.oracleEvidence(),
            result.ablationStatus(),
            result.shortcutOperatorId(),
            result.shortcutPackId(),
            result.shortcutAssumptions(),
            rationale(result.notes(), result.failureReason()),
            result.rulePath(),
            !result.rulePath().isEmpty(),
            curatedPathPresent(result.shortcutSource()),
            fallbackUsed(result.rulePath()),
            macroOpportunity(result.family(), result.rulePath())
        );
    }

    static PromotionObservation fromCampaignNine(DiscoveryCampaignNineRunner.CaseResult result, String campaignId) {
        return new PromotionObservation(
            result.id(),
            campaignId,
            discoveryDateFor(campaignId),
            result.family(),
            result.inputExpression(),
            result.targetExpression(),
            result.success(),
            result.oracleStatus(),
            result.oracleEvidence(),
            result.ablationStatus(),
            result.shortcutOperatorId(),
            result.shortcutPackId(),
            result.shortcutAssumptions(),
            rationale(result.notes(), result.failureReason()),
            result.rulePath(),
            !result.rulePath().isEmpty(),
            curatedPathPresent(result.shortcutSource()),
            fallbackUsed(result.rulePath()),
            macroOpportunity(result.family(), result.rulePath())
        );
    }

    static String discoveryDateFor(String campaignId) {
        int month = switch (campaignId) {
            case "discovery-campaign-1" -> 1;
            case "discovery-campaign-2" -> 2;
            case "discovery-campaign-3" -> 3;
            case "discovery-campaign-4" -> 4;
            case "discovery-campaign-5" -> 5;
            case "discovery-campaign-7" -> 7;
            case "discovery-campaign-8" -> 8;
            case "discovery-campaign-9" -> 9;
            default -> 12;
        };
        return LocalDate.of(2026, month, 1).toString();
    }

    private static boolean curatedPathPresent(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        return normalized.equals("scenario")
            || normalized.equals("scenario-generic")
            || normalized.equals("hardcoded")
            || normalized.contains("scenario-exact-path");
    }

    private static boolean fallbackUsed(List<String> rulePath) {
        return rulePath != null && rulePath.stream()
            .filter(ruleId -> ruleId != null)
            .map(ruleId -> ruleId.toLowerCase(Locale.ROOT))
            .anyMatch(ruleId -> ruleId.contains("fallback"));
    }

    private static boolean macroOpportunity(String family, List<String> rulePath) {
        return "substitution".equals(family) || (rulePath != null && rulePath.size() >= 2);
    }

    private static String rationale(String notes, String failureReason) {
        if (notes != null && !notes.isBlank()) {
            return notes;
        }
        return failureReason == null ? "" : failureReason;
    }
}
