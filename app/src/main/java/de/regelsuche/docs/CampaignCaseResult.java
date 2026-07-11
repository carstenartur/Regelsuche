package de.regelsuche.docs;

import java.util.List;

/**
 * Common view of a discovery campaign case result used when building a
 * {@link PromotionObservation}.  All standard per-campaign {@code CaseResult}
 * records expose these fields, so callers can use a single factory method
 * ({@link PromotionObservation#fromCampaignResult}) instead of one per campaign.
 */
interface CampaignCaseResult {
    String id();
    String family();
    String inputExpression();
    String targetExpression();
    boolean success();
    String failureReason();
    String oracleStatus();
    String oracleEvidence();
    String ablationStatus();
    String shortcutSource();
    String shortcutPackId();
    String shortcutOperatorId();
    List<String> shortcutAssumptions();
    List<String> rulePath();
    String notes();
}
