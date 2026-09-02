package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.sha256;

import de.regelsuche.json.JsonWriter;
import java.util.Objects;

/** One case localized to the first stage that lost a relevant representation. */
public record RepresentationSalienceCaseAudit(
    String caseId,
    CaseRole role,
    ReferenceReachability referenceReachability,
    String oracleEvidenceHash,
    String searchTraceHash,
    String candidateFormationHash,
    String candidateRetentionHash,
    String recognitionEvidenceHash,
    String rankingEvidenceHash,
    String expertReviewEvidenceHash,
    RepresentationSalienceStageSet reached,
    RepresentationSalienceStageSet formed,
    RepresentationSalienceStageSet retained,
    RepresentationSalienceStageSet recognized,
    RepresentationSalienceStageSet ranked,
    int rankingCutoff,
    ExpertVerdict expertVerdict,
    Localization localization,
    String contentHash
) {
    public RepresentationSalienceCaseAudit {
        caseId = requireText(caseId, "caseId");
        role = Objects.requireNonNull(role, "role");
        referenceReachability = Objects.requireNonNull(
            referenceReachability,
            "referenceReachability"
        );
        oracleEvidenceHash = requireSha256(
            oracleEvidenceHash,
            "oracleEvidenceHash"
        );
        searchTraceHash = requireSha256(searchTraceHash, "searchTraceHash");
        candidateFormationHash = requireSha256(
            candidateFormationHash,
            "candidateFormationHash"
        );
        candidateRetentionHash = requireSha256(
            candidateRetentionHash,
            "candidateRetentionHash"
        );
        recognitionEvidenceHash = requireSha256(
            recognitionEvidenceHash,
            "recognitionEvidenceHash"
        );
        rankingEvidenceHash = requireSha256(
            rankingEvidenceHash,
            "rankingEvidenceHash"
        );
        expertReviewEvidenceHash = requireSha256(
            expertReviewEvidenceHash,
            "expertReviewEvidenceHash"
        );
        reached = Objects.requireNonNull(reached, "reached");
        formed = Objects.requireNonNull(formed, "formed");
        retained = Objects.requireNonNull(retained, "retained");
        recognized = Objects.requireNonNull(recognized, "recognized");
        ranked = Objects.requireNonNull(ranked, "ranked");
        if (rankingCutoff < 1) {
            throw new IllegalArgumentException("rankingCutoff must be positive");
        }
        if (ranked.representationIds().size() > rankingCutoff) {
            throw new IllegalArgumentException(
                "ranked stage exceeds rankingCutoff"
            );
        }
        expertVerdict = Objects.requireNonNull(
            expertVerdict,
            "expertVerdict"
        );
        requireStageNesting(reached, formed, retained, recognized, ranked);
        requireRoleAndReviewConsistency(
            role,
            referenceReachability,
            reached,
            ranked,
            expertVerdict
        );
        Localization expected = localize(
            role,
            referenceReachability,
            reached,
            formed,
            retained,
            recognized,
            ranked,
            expertVerdict
        );
        localization = Objects.requireNonNull(localization, "localization");
        if (localization != expected) {
            throw new IllegalArgumentException(
                "localization differs from stage evidence"
            );
        }
        contentHash = requireSha256(contentHash, "contentHash");
        String expectedHash = caseHash(
            caseId,
            role,
            referenceReachability,
            oracleEvidenceHash,
            searchTraceHash,
            candidateFormationHash,
            candidateRetentionHash,
            recognitionEvidenceHash,
            rankingEvidenceHash,
            expertReviewEvidenceHash,
            reached,
            formed,
            retained,
            recognized,
            ranked,
            rankingCutoff,
            expertVerdict,
            localization
        );
        if (!expectedHash.equals(contentHash)) {
            throw new IllegalArgumentException("case-audit content hash mismatch");
        }
    }

    public static RepresentationSalienceCaseAudit create(
        String caseId,
        CaseRole role,
        ReferenceReachability referenceReachability,
        String oracleEvidenceHash,
        String searchTraceHash,
        String candidateFormationHash,
        String candidateRetentionHash,
        String recognitionEvidenceHash,
        String rankingEvidenceHash,
        String expertReviewEvidenceHash,
        RepresentationSalienceStageSet reached,
        RepresentationSalienceStageSet formed,
        RepresentationSalienceStageSet retained,
        RepresentationSalienceStageSet recognized,
        RepresentationSalienceStageSet ranked,
        int rankingCutoff,
        ExpertVerdict expertVerdict
    ) {
        String retainedCaseId = requireText(caseId, "caseId");
        CaseRole retainedRole = Objects.requireNonNull(role, "role");
        ReferenceReachability reachability = Objects.requireNonNull(
            referenceReachability,
            "referenceReachability"
        );
        RepresentationSalienceStageSet reachedSet = Objects.requireNonNull(
            reached,
            "reached"
        );
        RepresentationSalienceStageSet formedSet = Objects.requireNonNull(
            formed,
            "formed"
        );
        RepresentationSalienceStageSet retainedSet = Objects.requireNonNull(
            retained,
            "retained"
        );
        RepresentationSalienceStageSet recognizedSet = Objects.requireNonNull(
            recognized,
            "recognized"
        );
        RepresentationSalienceStageSet rankedSet = Objects.requireNonNull(
            ranked,
            "ranked"
        );
        ExpertVerdict verdict = Objects.requireNonNull(
            expertVerdict,
            "expertVerdict"
        );
        Localization localization = localize(
            retainedRole,
            reachability,
            reachedSet,
            formedSet,
            retainedSet,
            recognizedSet,
            rankedSet,
            verdict
        );
        String hash = caseHash(
            retainedCaseId,
            retainedRole,
            reachability,
            oracleEvidenceHash,
            searchTraceHash,
            candidateFormationHash,
            candidateRetentionHash,
            recognitionEvidenceHash,
            rankingEvidenceHash,
            expertReviewEvidenceHash,
            reachedSet,
            formedSet,
            retainedSet,
            recognizedSet,
            rankedSet,
            rankingCutoff,
            verdict,
            localization
        );
        return new RepresentationSalienceCaseAudit(
            retainedCaseId,
            retainedRole,
            reachability,
            oracleEvidenceHash,
            searchTraceHash,
            candidateFormationHash,
            candidateRetentionHash,
            recognitionEvidenceHash,
            rankingEvidenceHash,
            expertReviewEvidenceHash,
            reachedSet,
            formedSet,
            retainedSet,
            recognizedSet,
            rankedSet,
            rankingCutoff,
            verdict,
            localization,
            hash
        );
    }

    public boolean reachedRelevantRepresentation() {
        return !reached.isEmpty();
    }

    public boolean formedRelevantCandidate() {
        return !formed.isEmpty();
    }

    public boolean retainedRelevantCandidate() {
        return !retained.isEmpty();
    }

    public boolean recognizedRelevantCandidate() {
        return !recognized.isEmpty();
    }

    public boolean rankedRelevantCandidate() {
        return !ranked.isEmpty();
    }

    void appendIdentity(StringBuilder descriptor) {
        append(descriptor, contentHash);
    }

    void writeJson(JsonWriter json) {
        json.property("caseId", caseId)
            .property("role", role.name())
            .property("referenceReachability", referenceReachability.name())
            .property("oracleEvidenceHash", oracleEvidenceHash)
            .property("searchTraceHash", searchTraceHash)
            .property("candidateFormationHash", candidateFormationHash)
            .property("candidateRetentionHash", candidateRetentionHash)
            .property("recognitionEvidenceHash", recognitionEvidenceHash)
            .property("rankingEvidenceHash", rankingEvidenceHash)
            .property("expertReviewEvidenceHash", expertReviewEvidenceHash);
        writeStageSet(json, "reached", reached);
        writeStageSet(json, "formed", formed);
        writeStageSet(json, "retained", retained);
        writeStageSet(json, "recognized", recognized);
        writeStageSet(json, "ranked", ranked);
        json.property("rankingCutoff", rankingCutoff)
            .property("expertVerdict", expertVerdict.name())
            .property("localization", localization.name())
            .property("contentHash", contentHash);
    }

    private static void writeStageSet(
        JsonWriter json,
        String name,
        RepresentationSalienceStageSet value
    ) {
        json.object(name, value::writeJson);
    }

    private static Localization localize(
        CaseRole role,
        ReferenceReachability referenceReachability,
        RepresentationSalienceStageSet reached,
        RepresentationSalienceStageSet formed,
        RepresentationSalienceStageSet retained,
        RepresentationSalienceStageSet recognized,
        RepresentationSalienceStageSet ranked,
        ExpertVerdict expertVerdict
    ) {
        requireStageNesting(reached, formed, retained, recognized, ranked);
        requireRoleAndReviewConsistency(
            role,
            referenceReachability,
            reached,
            ranked,
            expertVerdict
        );
        if (role == CaseRole.NEGATIVE_OR_ALIAS_CONTROL) {
            return recognized.isEmpty() && ranked.isEmpty()
                ? Localization.NEGATIVE_CONTROL_CORRECTLY_REJECTED
                : Localization.INVALID_OR_FALSE_POSITIVE;
        }
        if (referenceReachability
                == ReferenceReachability.NOT_REACHABLE_COMPLETE_CLOSURE) {
            return Localization.NOT_REACHABLE_IN_DECLARED_CLOSURE;
        }
        if (reached.isEmpty()) {
            if (referenceReachability == ReferenceReachability.INCONCLUSIVE) {
                return Localization.REACHABILITY_INCONCLUSIVE;
            }
            if (referenceReachability == ReferenceReachability.UNSUPPORTED) {
                return Localization.UNSUPPORTED;
            }
            return Localization.REACHABLE_NOT_REACHED_BY_POLICY;
        }
        if (formed.isEmpty()) {
            return Localization.REACHED_NOT_FORMED;
        }
        if (retained.isEmpty()) {
            return Localization.FORMED_NOT_RETAINED;
        }
        if (recognized.isEmpty()) {
            return Localization.RETAINED_NOT_RECOGNIZED;
        }
        if (ranked.isEmpty()) {
            return Localization.RECOGNIZED_NOT_RANKED;
        }
        return switch (expertVerdict) {
            case NOT_EVALUATED, PENDING ->
                Localization.RANKED_PENDING_EXPERT_REVIEW;
            case UNCERTAIN -> Localization.RANKED_EXPERT_REVIEW_UNCERTAIN;
            case CONSENSUS_NOT_INTERESTING ->
                Localization.RANKED_NO_EXPERT_CONSENSUS;
            case CONSENSUS_INTERESTING -> Localization.FULLY_DETECTED;
        };
    }

    private static void requireStageNesting(
        RepresentationSalienceStageSet reached,
        RepresentationSalienceStageSet formed,
        RepresentationSalienceStageSet retained,
        RepresentationSalienceStageSet recognized,
        RepresentationSalienceStageSet ranked
    ) {
        if (!Objects.requireNonNull(reached, "reached").containsAll(
                Objects.requireNonNull(formed, "formed"))
                || !formed.containsAll(
                    Objects.requireNonNull(retained, "retained"))
                || !retained.containsAll(
                    Objects.requireNonNull(recognized, "recognized"))
                || !recognized.containsAll(
                    Objects.requireNonNull(ranked, "ranked"))) {
            throw new IllegalArgumentException(
                "salience-audit stage sets are not nested"
            );
        }
    }

    private static void requireRoleAndReviewConsistency(
        CaseRole role,
        ReferenceReachability reachability,
        RepresentationSalienceStageSet reached,
        RepresentationSalienceStageSet ranked,
        ExpertVerdict verdict
    ) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(reachability, "reachability");
        Objects.requireNonNull(verdict, "verdict");
        if (role == CaseRole.POSITIVE_REFERENCE
                && reachability
                    == ReferenceReachability.NOT_REACHABLE_COMPLETE_CLOSURE
                && !reached.isEmpty()) {
            throw new IllegalArgumentException(
                "complete-closure-unreachable positive case cannot retain "
                    + "a matched state"
            );
        }
        if (ranked.isEmpty() && verdict != ExpertVerdict.NOT_EVALUATED) {
            throw new IllegalArgumentException(
                "unranked case cannot retain an expert verdict"
            );
        }
        if (role == CaseRole.NEGATIVE_OR_ALIAS_CONTROL
                && verdict != ExpertVerdict.NOT_EVALUATED) {
            throw new IllegalArgumentException(
                "negative controls do not receive relevance consensus"
            );
        }
    }

    private static String caseHash(
        String caseId,
        CaseRole role,
        ReferenceReachability reachability,
        String oracleEvidenceHash,
        String searchTraceHash,
        String candidateFormationHash,
        String candidateRetentionHash,
        String recognitionEvidenceHash,
        String rankingEvidenceHash,
        String expertReviewEvidenceHash,
        RepresentationSalienceStageSet reached,
        RepresentationSalienceStageSet formed,
        RepresentationSalienceStageSet retained,
        RepresentationSalienceStageSet recognized,
        RepresentationSalienceStageSet ranked,
        int rankingCutoff,
        ExpertVerdict expertVerdict,
        Localization localization
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, RepresentationSalienceAudit.SCHEMA + "/case");
        append(descriptor, requireText(caseId, "caseId"));
        append(descriptor, Objects.requireNonNull(role, "role").name());
        append(
            descriptor,
            Objects.requireNonNull(reachability, "reachability").name()
        );
        append(descriptor, requireSha256(
            oracleEvidenceHash,
            "oracleEvidenceHash"
        ));
        append(descriptor, requireSha256(
            searchTraceHash,
            "searchTraceHash"
        ));
        append(descriptor, requireSha256(
            candidateFormationHash,
            "candidateFormationHash"
        ));
        append(descriptor, requireSha256(
            candidateRetentionHash,
            "candidateRetentionHash"
        ));
        append(descriptor, requireSha256(
            recognitionEvidenceHash,
            "recognitionEvidenceHash"
        ));
        append(descriptor, requireSha256(
            rankingEvidenceHash,
            "rankingEvidenceHash"
        ));
        append(descriptor, requireSha256(
            expertReviewEvidenceHash,
            "expertReviewEvidenceHash"
        ));
        append(descriptor, reached.contentHash());
        append(descriptor, formed.contentHash());
        append(descriptor, retained.contentHash());
        append(descriptor, recognized.contentHash());
        append(descriptor, ranked.contentHash());
        append(descriptor, Integer.toString(rankingCutoff));
        append(descriptor, Objects.requireNonNull(
            expertVerdict,
            "expertVerdict"
        ).name());
        append(descriptor, Objects.requireNonNull(
            localization,
            "localization"
        ).name());
        return sha256(descriptor.toString());
    }

    public enum CaseRole {
        POSITIVE_REFERENCE,
        NEGATIVE_OR_ALIAS_CONTROL
    }

    public enum ReferenceReachability {
        REACHABLE,
        NOT_REACHABLE_COMPLETE_CLOSURE,
        INCONCLUSIVE,
        UNSUPPORTED
    }

    public enum ExpertVerdict {
        NOT_EVALUATED,
        PENDING,
        CONSENSUS_INTERESTING,
        CONSENSUS_NOT_INTERESTING,
        UNCERTAIN
    }

    public enum Localization {
        NOT_REACHABLE_IN_DECLARED_CLOSURE,
        REACHABILITY_INCONCLUSIVE,
        REACHABLE_NOT_REACHED_BY_POLICY,
        REACHED_NOT_FORMED,
        FORMED_NOT_RETAINED,
        RETAINED_NOT_RECOGNIZED,
        RECOGNIZED_NOT_RANKED,
        RANKED_PENDING_EXPERT_REVIEW,
        RANKED_EXPERT_REVIEW_UNCERTAIN,
        RANKED_NO_EXPERT_CONSENSUS,
        FULLY_DETECTED,
        NEGATIVE_CONTROL_CORRECTLY_REJECTED,
        INVALID_OR_FALSE_POSITIVE,
        UNSUPPORTED
    }
}
