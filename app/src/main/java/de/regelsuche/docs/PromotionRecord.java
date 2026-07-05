package de.regelsuche.docs;

import java.util.LinkedHashSet;
import java.util.List;

record PromotionRecord(
    String candidateId,
    String sourceCampaign,
    String discoveryDate,
    String family,
    PromotionStage stage,
    String originalExpression,
    String discoveredStructure,
    String oracleStatus,
    String oracleEvidence,
    String ablationStatus,
    String sourceOperator,
    String sourcePack,
    List<String> assumptions,
    String rationale,
    List<String> rulePath,
    boolean promotionEligible,
    List<String> promotionBlockers,
    boolean evidenceExists,
    boolean curatedPathPresent,
    boolean fallbackUsed,
    boolean macroOpportunity,
    String generatedMacroId,
    List<String> reusedMacroIds,
    boolean measuredImprovement,
    String reuseCampaign,
    AblationEvidence ablationEvidence
) {
    PromotionRecord(
        String candidateId,
        String sourceCampaign,
        String discoveryDate,
        String family,
        PromotionStage stage,
        String originalExpression,
        String discoveredStructure,
        String oracleStatus,
        String oracleEvidence,
        String ablationStatus,
        String sourceOperator,
        String sourcePack,
        List<String> assumptions,
        String rationale,
        List<String> rulePath,
        boolean promotionEligible,
        List<String> promotionBlockers,
        boolean evidenceExists,
        boolean curatedPathPresent,
        boolean fallbackUsed,
        boolean macroOpportunity,
        String generatedMacroId,
        List<String> reusedMacroIds,
        boolean measuredImprovement,
        String reuseCampaign
    ) {
        this(
            candidateId,
            sourceCampaign,
            discoveryDate,
            family,
            stage,
            originalExpression,
            discoveredStructure,
            oracleStatus,
            oracleEvidence,
            ablationStatus,
            sourceOperator,
            sourcePack,
            assumptions,
            rationale,
            rulePath,
            promotionEligible,
            promotionBlockers,
            evidenceExists,
            curatedPathPresent,
            fallbackUsed,
            macroOpportunity,
            generatedMacroId,
            reusedMacroIds,
            measuredImprovement,
            reuseCampaign,
            AblationEvidence.statusOnly(ablationStatus)
        );
    }

    PromotionRecord {
        family = family == null ? "" : family;
        stage = stage == null ? PromotionStage.OBSERVED : stage;
        originalExpression = originalExpression == null ? "" : originalExpression;
        discoveredStructure = discoveredStructure == null ? "" : discoveredStructure;
        oracleStatus = oracleStatus == null || oracleStatus.isBlank() ? "UNAVAILABLE" : oracleStatus;
        oracleEvidence = oracleEvidence == null ? "" : oracleEvidence;
        ablationEvidence = ablationEvidence == null ? AblationEvidence.statusOnly(ablationStatus) : ablationEvidence;
        ablationStatus = ablationEvidence.ablationStatus();
        sourceOperator = sourceOperator == null ? "" : sourceOperator;
        sourcePack = sourcePack == null ? "" : sourcePack;
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        rationale = rationale == null ? "" : rationale;
        rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        promotionBlockers = promotionBlockers == null ? List.of() : List.copyOf(promotionBlockers);
        generatedMacroId = generatedMacroId == null ? "" : generatedMacroId;
        reusedMacroIds = reusedMacroIds == null ? List.of() : List.copyOf(reusedMacroIds);
        reuseCampaign = reuseCampaign == null ? "" : reuseCampaign;
    }

    boolean unresolved() {
        return !stage.atLeast(PromotionStage.PROMOTED);
    }

    boolean galleryEligible(NoveltyStatus noveltyStatus) {
        return new PublicEvidenceGate().evaluate(this, noveltyStatus).accepted();
    }

    PromotionRecord withReuse(DiscoveryCampaignFourRunner.CaseResult reuse) {
        LinkedHashSet<String> reused = new LinkedHashSet<>(reusedMacroIds);
        reused.addAll(reuse.reusedMacroIds());
        PromotionStage nextStage = reuse.measuredImprovement() && !reuse.reusedMacroIds().isEmpty()
            ? PromotionStage.REUSED
            : stage;
        AblationEvidence reuseEvidence = AblationEvidence.compare(
            reuse.macroEnabled().success(),
            reuse.macroEnabled().pathLength(),
            reuse.macroEnabled().statesExplored(),
            reuse.macroDisabled().success(),
            reuse.macroDisabled().pathLength(),
            reuse.macroDisabled().statesExplored(),
            "macro reuse validation from " + reuse.campaignId()
        );
        return new PromotionRecord(
            candidateId,
            sourceCampaign,
            discoveryDate,
            family,
            nextStage,
            originalExpression,
            discoveredStructure,
            oracleStatus,
            oracleEvidence,
            reuseEvidence.ablationStatus(),
            sourceOperator,
            sourcePack,
            assumptions,
            rationale,
            rulePath,
            promotionEligible,
            promotionBlockers,
            evidenceExists,
            curatedPathPresent,
            fallbackUsed,
            macroOpportunity,
            reuse.generatedMacroId(),
            List.copyOf(reused),
            reuse.measuredImprovement(),
            reuse.campaignId(),
            reuseEvidence
        );
    }
}
