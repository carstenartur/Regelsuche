package de.regelsuche.docs;

import de.regelsuche.json.JsonWriter;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Canonical provenance needed to reproduce one open-target promotion decision. */
record OpenTargetPromotionProvenance(
    String sourceCampaign,
    String discoveryDate,
    String dynamicRuleId,
    String evaluationProvenanceHash,
    String exactSignatureHash,
    String alphaSignatureHash,
    String proofEvidenceHash,
    String proofObligationHash,
    List<String> assumptions,
    List<String> rulePath,
    boolean evidenceExists,
    boolean curatedPathPresent,
    boolean fallbackUsed
) {
    OpenTargetPromotionProvenance {
        requireText(sourceCampaign, "sourceCampaign");
        requireText(discoveryDate, "discoveryDate");
        requireText(dynamicRuleId, "dynamicRuleId");
        try {
            LocalDate.parse(discoveryDate);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                "discoveryDate must use ISO-8601 YYYY-MM-DD", exception);
        }
        requireSha256(evaluationProvenanceHash, "evaluationProvenanceHash");
        requireSha256(exactSignatureHash, "exactSignatureHash");
        requireSha256(alphaSignatureHash, "alphaSignatureHash");
        requireSha256(proofEvidenceHash, "proofEvidenceHash");
        requireSha256(proofObligationHash, "proofObligationHash");
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
    }

    void writeJson(JsonWriter json) {
        json.property("sourceCampaign", sourceCampaign)
            .property("discoveryDate", discoveryDate)
            .property("dynamicRuleId", dynamicRuleId)
            .property("evaluationProvenanceHash", evaluationProvenanceHash)
            .property("exactSignatureHash", exactSignatureHash)
            .property("alphaSignatureHash", alphaSignatureHash)
            .property("proofEvidenceHash", proofEvidenceHash)
            .property("proofObligationHash", proofObligationHash)
            .stringArray("assumptions", assumptions)
            .stringArray("rulePath", rulePath)
            .property("evidenceExists", evidenceExists)
            .property("curatedPathPresent", curatedPathPresent)
            .property("fallbackUsed", fallbackUsed);
    }

    String canonicalMaterial() {
        return "campaign=" + sourceCampaign
            + "\ndiscoveryDate=" + discoveryDate
            + "\ndynamicRuleId=" + dynamicRuleId
            + "\nevaluationProvenance=" + evaluationProvenanceHash
            + "\nexactSignature=" + exactSignatureHash
            + "\nalphaSignature=" + alphaSignatureHash
            + "\nproofEvidence=" + proofEvidenceHash
            + "\nproofObligation=" + proofObligationHash
            + "\nassumptions=" + String.join("\u0001", assumptions)
            + "\nrulePath=" + String.join("\u0001", rulePath)
            + "\nevidenceExists=" + evidenceExists
            + "\ncuratedPathPresent=" + curatedPathPresent
            + "\nfallbackUsed=" + fallbackUsed;
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hash");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
