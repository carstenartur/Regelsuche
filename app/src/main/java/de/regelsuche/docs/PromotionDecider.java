package de.regelsuche.docs;

import java.util.ArrayList;
import java.util.List;

final class PromotionDecider {
    PromotionRecord decide(PromotionObservation observation) {
        List<String> blockers = promotionBlockers(observation);
        boolean promotionEligible = blockers.isEmpty();
        PromotionStage stage = stageFor(observation, promotionEligible);
        return new PromotionRecord(
            observation.candidateId(),
            observation.sourceCampaign(),
            observation.discoveryDate(),
            observation.family(),
            stage,
            observation.oracleStatus(),
            observation.ablationStatus(),
            observation.sourceOperator(),
            observation.sourcePack(),
            observation.assumptions(),
            observation.rationale(),
            observation.rulePath(),
            promotionEligible,
            blockers,
            observation.evidenceExists(),
            observation.curatedPathPresent(),
            observation.fallbackUsed(),
            observation.macroOpportunity(),
            "",
            List.of(),
            false,
            ""
        );
    }

    private List<String> promotionBlockers(PromotionObservation observation) {
        List<String> blockers = new ArrayList<>();
        if (!observation.success()) {
            blockers.add("success=false");
        }
        if ("DISAGREE".equals(observation.oracleStatus())) {
            blockers.add("oracle=DISAGREE");
        }
        if (!"DEGRADED".equals(observation.ablationStatus())) {
            blockers.add("ablation=" + observation.ablationStatus());
        }
        if (!observation.evidenceExists()) {
            blockers.add("evidence=missing");
        }
        if (observation.curatedPathPresent()) {
            blockers.add("curated-path=true");
        }
        if (observation.fallbackUsed()) {
            blockers.add("fallback=true");
        }
        return List.copyOf(blockers);
    }

    private PromotionStage stageFor(PromotionObservation observation, boolean promotionEligible) {
        if (!observation.success()) {
            return PromotionStage.OBSERVED;
        }
        if ("DISAGREE".equals(observation.oracleStatus()) || !observation.evidenceExists()) {
            return PromotionStage.CANDIDATE;
        }
        if (!"DEGRADED".equals(observation.ablationStatus())
            || observation.curatedPathPresent()
            || observation.fallbackUsed()) {
            return PromotionStage.VALIDATED;
        }
        return promotionEligible ? PromotionStage.PROMOTED : PromotionStage.VALIDATED;
    }
}
