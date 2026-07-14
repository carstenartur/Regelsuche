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
        try {
            LocalDate.parse(discoveryDate);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                "discoveryDate must use ISO-8601 YYYY-MM-DD", exception);
        }
        dynamicRuleId = normalize(dynamicRuleId);
        evaluationProvenanceHash = optionalSha256(
            evaluationProvenanceHash, "evaluationProvenanceHash");
        exactSignatureHash = optionalSha256(exactSignatureHash, "exactSignatureHash");
        alphaSignatureHash = optionalSha256(alphaSignatureHash, "alphaSignatureHash");
        proofEvidenceHash = optionalSha256(proofEvidenceHash, "proofEvidenceHash");
        proofObligationHash = optionalSha256(
            proofObligationHash, "proofObligationHash");
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

    private static String optionalSha256(String value, String name) {
        String normalized = normalize(value);
        if (!normalized.isEmpty() && !normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be empty or a SHA-256 hash");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
