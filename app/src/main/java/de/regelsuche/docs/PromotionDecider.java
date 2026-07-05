package de.regelsuche.docs;

import java.util.ArrayList;
import java.util.List;

final class PromotionDecider {
    PromotionRecord decide(PromotionObservation observation) {
        AblationEvidence ablationEvidence = observation.ablationEvidence();
        List<String> blockers = promotionBlockers(observation, ablationEvidence);
        boolean promotionEligible = blockers.isEmpty();
        PromotionStage stage = stageFor(observation, ablationEvidence, promotionEligible);
        return new PromotionRecord(
            observation.candidateId(),
            observation.sourceCampaign(),
            observation.discoveryDate(),
            observation.family(),
            stage,
            observation.originalExpression(),
            observation.discoveredStructure(),
            observation.oracleStatus(),
            observation.oracleEvidence(),
            ablationEvidence.ablationStatus(),
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
            "",
            ablationEvidence
        );
    }

    private List<String> promotionBlockers(PromotionObservation observation, AblationEvidence ablationEvidence) {
        List<String> blockers = new ArrayList<>();
        if (!observation.success()) {
            blockers.add("success=false");
        }
        if ("DISAGREE".equals(observation.oracleStatus())) {
            blockers.add("oracle=DISAGREE");
        }
        if (!ablationEvidence.promotionReady()) {
            blockers.add("ablation=" + ablationEvidence.ablationStatus());
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

    private PromotionStage stageFor(
        PromotionObservation observation,
        AblationEvidence ablationEvidence,
        boolean promotionEligible
    ) {
        if (!observation.success()) {
            return PromotionStage.OBSERVED;
        }
        if ("DISAGREE".equals(observation.oracleStatus()) || !observation.evidenceExists()) {
            return PromotionStage.CANDIDATE;
        }
        if (!ablationEvidence.promotionReady()
            || observation.curatedPathPresent()
            || observation.fallbackUsed()) {
            return PromotionStage.VALIDATED;
        }
        return promotionEligible ? PromotionStage.PROMOTED : PromotionStage.VALIDATED;
    }
}
