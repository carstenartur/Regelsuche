package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.CrossFamilyBridgeAblationEvaluator.AblationReport;
import de.regelsuche.mining.CrossFamilyBridgeAblationEvaluator.AblationStatus;
import de.regelsuche.mining.CrossFamilyBridgeHypothesisBuilder.BridgeHypothesis;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyResult;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyStatus;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.TransferReport;
import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.EligibilityStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Binds transfer, lifecycle, novelty, proof, ablation and interestingness evidence
 * for one cross-family bridge without promoting or publishing it.
 */
public final class CrossFamilyBridgeQualificationGate {
    public static final String SCHEMA = "regelsuche.cross-family-bridge-qualification/v1";

    public QualificationReport evaluate(Input input) {
        Objects.requireNonNull(input, "input");
        List<String> blockers = new ArrayList<>();
        addIdentityBlockers(input, blockers);
        addTransferBlockers(input.transfer(), blockers);
        addLifecycleBlockers(input, blockers);
        addNoveltyBlockers(input.novelty(), blockers);
        addProofBlockers(input, blockers);
        addAblationBlockers(input, blockers);
        addInterestingnessBlockers(input, blockers);

        List<String> orderedBlockers = blockers.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
        AssumptionStatus assumptionStatus = assumptionsConsistent(input)
            ? AssumptionStatus.CONSISTENT
            : AssumptionStatus.MISMATCH;
        if (assumptionStatus == AssumptionStatus.MISMATCH
                && !orderedBlockers.contains("assumption evidence mismatch")) {
            orderedBlockers = appendSorted(orderedBlockers, "assumption evidence mismatch");
        }
        QualificationStatus status = orderedBlockers.isEmpty()
            ? QualificationStatus.QUALIFIED_FOR_PROMOTION_REVIEW
            : QualificationStatus.BLOCKED;
        String contentHash = hash(canonicalMaterial(
            input, status, assumptionStatus, orderedBlockers));
        return new QualificationReport(
            SCHEMA,
            input.hypothesis().hypothesisId(),
            input.hypothesis().sourceClusterId(),
            input.hypothesis().formationHash(),
            input.transfer().contentHash(),
            input.ablation().contentHash(),
            input.novelty().exactSignatureHash(),
            input.novelty().alphaSignatureHash(),
            input.proof().evidenceHash(),
            input.interestingness().contentHash(),
            status,
            input.transfer().status().name(),
            input.novelty().status().name(),
            input.novelty().externalNoveltyStatus(),
            input.proof().proofStatus().name(),
            input.proof().formalProofStatus(),
            assumptionStatus,
            input.ablation().status(),
            input.interestingness().eligibility(),
            input.interestingness().profile(),
            input.interestingness().totalPermille(),
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            orderedBlockers,
            contentHash);
    }

    private static void addIdentityBlockers(Input input, List<String> blockers) {
        String id = input.hypothesis().hypothesisId();
        if (!id.equals(input.transfer().hypothesisId())) {
            blockers.add("transfer identity mismatch");
        }
        if (!id.equals(input.lifecycle().id())) {
            blockers.add("lifecycle identity mismatch");
        }
        if (!id.equals(input.novelty().conjectureId())) {
            blockers.add("novelty identity mismatch");
        }
        if (!id.equals(input.proof().conjectureId())) {
            blockers.add("proof identity mismatch");
        }
        if (!id.equals(input.ablation().hypothesisId())) {
            blockers.add("ablation identity mismatch");
        }
        if (!id.equals(input.interestingness().candidateId())) {
            blockers.add("interestingness identity mismatch");
        }
        if (!input.hypothesis().sourceClusterId().equals(
                input.transfer().sourceClusterId())) {
            blockers.add("source cluster mismatch");
        }
        if (!input.hypothesis().formationHash().equals(
                input.transfer().formationHash())) {
            blockers.add("formation hash mismatch");
        }
        if (!input.transfer().contentHash().equals(
                input.ablation().transferContentHash())) {
            blockers.add("ablation/transfer hash mismatch");
        }
        if (!input.transfer().contentHash().equals(
                input.interestingness().evidence().evidenceId())) {
            blockers.add("interestingness evidence is not bound to transfer report");
        }
    }

    private static void addTransferBlockers(
        TransferReport transfer,
        List<String> blockers
    ) {
        if (transfer.targetProvided()) {
            blockers.add("targeted transfer evidence is not allowed");
        }
        if (!transfer.accepted() || !transfer.blockers().isEmpty()) {
            blockers.add("cross-family transfer is not accepted");
        }
        int expectedFamilies = transfer.trainingFamilies().size()
            + transfer.heldOutFamilies().size();
        if (transfer.heldOutFamilies().isEmpty()
                || transfer.familyResults().size() != expectedFamilies) {
            blockers.add("family transfer coverage is incomplete");
        }
        transfer.familyResults().stream()
            .filter(result -> !familyResultComplete(result))
            .map(result -> "family transfer incomplete: " + result.familyId())
            .forEach(blockers::add);
    }

    private static boolean familyResultComplete(FamilyResult result) {
        return result.status() == FamilyStatus.ACCEPTED
            && result.configuredPositiveHoldouts() == result.executedPositiveHoldouts()
            && result.skippedPositiveHoldouts() == 0
            && result.configuredNegativeHoldouts() == result.executedNegativeHoldouts()
            && result.skippedNegativeHoldouts() == 0
            && result.failedPositiveHoldouts().isEmpty()
            && result.failedNegativeHoldouts().isEmpty()
            && CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND.name().equals(
                result.counterexampleStatus())
            && !result.counterexampleAttemptedSources().isEmpty()
            && result.inferredAssumptions().isEmpty()
            && result.counterexampleAssignments().isEmpty()
            && result.blockers().isEmpty()
            && isSha256(result.provenanceHash());
    }

    private static void addLifecycleBlockers(Input input, List<String> blockers) {
        HypothesisCandidate lifecycle = input.lifecycle();
        BridgeHypothesis hypothesis = input.hypothesis();
        boolean patternsMatch = hypothesis.leftPattern().equals(lifecycle.leftPattern())
            && hypothesis.rightPattern().equals(lifecycle.rightPattern());
        boolean evidencePresent = !lifecycle.supportingPaths().isEmpty()
            && lifecycle.supportingExpressions().size()
                >= hypothesis.conjecture().supportCount();
        boolean validated = lifecycle.proofStatus().atLeast(
            CandidateProofStatus.VALIDATED_BY_EXAMPLES);
        boolean counterexampleCleared = lifecycle.counterexampleSearchStatus()
                == CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND
            && Boolean.FALSE.equals(lifecycle.counterexampleStatus());
        boolean generalizationPreserved = ordered(lifecycle.parameterRelations()).equals(
                ordered(hypothesis.conjecture().parameterRelations()))
            && orderedMap(lifecycle.expressionPlaceholders()).equals(
                orderedMap(hypothesis.conjecture().expressionPlaceholderValues()));
        if (!patternsMatch || !evidencePresent || !validated
                || !counterexampleCleared || !generalizationPreserved) {
            blockers.add("bridge lifecycle evidence is incomplete or drifted");
        }
    }

    private static void addNoveltyBlockers(
        NoveltyReport novelty,
        List<String> blockers
    ) {
        if (novelty.status() != NoveltyStatus.NOVEL_WITHIN_PROJECT) {
            blockers.add("project novelty is not established: " + novelty.status().name());
        }
        if (!novelty.matches().isEmpty()
                || !isSha256(novelty.exactSignatureHash())
                || !isSha256(novelty.alphaSignatureHash())) {
            blockers.add("project novelty evidence is inconsistent");
        }
    }

    private static void addProofBlockers(Input input, List<String> blockers) {
        ProofReport proof = input.proof();
        BridgeHypothesis hypothesis = input.hypothesis();
        if (proof.eligibility() != EligibilityStatus.ELIGIBLE
                || proof.proofStatus() != ProofStatus.SYMBOLICALLY_VERIFIED
                || !proof.blockers().isEmpty()
                || !proof.proofObligationEmitted()
                || proof.obligation() == null) {
            blockers.add("symbolic proof evidence is not accepted");
            return;
        }
        boolean obligationMatches = hypothesis.hypothesisId().equals(
                proof.obligation().conjectureId())
            && !proof.obligation().targetProvided()
            && hypothesis.leftPattern().equals(proof.obligation().leftExpression())
            && hypothesis.rightPattern().equals(proof.obligation().rightExpression())
            && ordered(hypothesis.assumptions()).equals(
                ordered(proof.obligation().assumptions()));
        if (!obligationMatches
                || !isSha256(proof.obligation().obligationHash())
                || !isSha256(proof.evidenceHash())) {
            blockers.add("proof obligation provenance mismatch");
        }
    }

    private static void addAblationBlockers(Input input, List<String> blockers) {
        AblationReport ablation = input.ablation();
        if (ablation.status() != AblationStatus.BENEFICIAL_HELD_OUT
                || !ablation.beneficial()
                || !ablation.blockers().isEmpty()) {
            blockers.add("held-out paired ablation is not beneficial");
        }
        if (!input.transfer().trainingFamilies().equals(ablation.trainingFamilies())
                || !input.transfer().heldOutFamilies().equals(ablation.heldOutFamilies())) {
            blockers.add("ablation family provenance mismatch");
        }
    }

    private static void addInterestingnessBlockers(
        Input input,
        List<String> blockers
    ) {
        InterestingnessAssessment assessment = input.interestingness();
        if (assessment.eligibility() != Eligibility.RANKABLE_COMPLETE
                || !assessment.hardBlockers().isEmpty()
                || !assessment.warnings().isEmpty()) {
            blockers.add("interestingness evidence is not complete and rankable");
        }
        InterestingnessEvidence evidence = assessment.evidence();
        boolean transferComplete = evidence.heldOutTransferRequired()
            && evidence.heldOutFamiliesConfigured() > 0
            && evidence.heldOutFamiliesConfigured() == evidence.heldOutFamiliesPassed();
        boolean utilityComplete = evidence.pairedUtilityEvaluated()
            && evidence.pairedUtilityPermille() > 0;
        boolean noveltyConsistent = evidence.projectNoveltyStatus()
            == ProjectNoveltyStatus.NOVEL_WITHIN_PROJECT;
        if (!transferComplete || !utilityComplete || !noveltyConsistent
                || evidence.controlClassification() != ControlClassification.NONE) {
            blockers.add("interestingness evidence axes are inconsistent with bridge qualification");
        }
    }

    private static boolean assumptionsConsistent(Input input) {
        List<String> expected = ordered(input.hypothesis().assumptions());
        List<String> proofAssumptions = input.proof().obligation() == null
            ? List.of()
            : ordered(input.proof().obligation().assumptions());
        boolean familyAssumptionsClear = input.transfer().familyResults().stream()
            .allMatch(result -> result.inferredAssumptions().isEmpty());
        return expected.equals(ordered(input.lifecycle().assumptions()))
            && expected.equals(proofAssumptions)
            && familyAssumptionsClear;
    }

    private static String canonicalMaterial(
        Input input,
        QualificationStatus status,
        AssumptionStatus assumptionStatus,
        List<String> blockers
    ) {
        return SCHEMA
            + "\nhypothesis=" + input.hypothesis().hypothesisId()
            + "\ncluster=" + input.hypothesis().sourceClusterId()
            + "\nformation=" + input.hypothesis().formationHash()
            + "\ntransfer=" + input.transfer().contentHash()
            + "\nnoveltyExact=" + input.novelty().exactSignatureHash()
            + "\nnoveltyAlpha=" + input.novelty().alphaSignatureHash()
            + "\nproof=" + input.proof().evidenceHash()
            + "\nablation=" + input.ablation().contentHash()
            + "\ninterestingness=" + input.interestingness().contentHash()
            + "\nstatus=" + status.name()
            + "\nassumptions=" + assumptionStatus.name()
            + "\nblockers=" + blockers;
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static List<String> appendSorted(List<String> values, String extra) {
        List<String> combined = new ArrayList<>(values);
        combined.add(extra);
        return combined.stream().distinct().sorted().toList();
    }

    private static List<String> ordered(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().replaceAll("\\s+", " "))
                .distinct()
                .sorted()
                .toList();
    }

    private static Map<String, List<String>> orderedMap(
        Map<String, List<String>> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> ordered = new TreeMap<>();
        values.forEach((key, entries) -> ordered.put(key, ordered(entries)));
        return Map.copyOf(ordered);
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    public enum QualificationStatus {
        QUALIFIED_FOR_PROMOTION_REVIEW,
        BLOCKED
    }

    public enum AssumptionStatus {
        CONSISTENT,
        MISMATCH
    }

    public record Input(
        BridgeHypothesis hypothesis,
        TransferReport transfer,
        HypothesisCandidate lifecycle,
        NoveltyReport novelty,
        ProofReport proof,
        AblationReport ablation,
        InterestingnessAssessment interestingness
    ) {
        public Input {
            Objects.requireNonNull(hypothesis, "hypothesis");
            Objects.requireNonNull(transfer, "transfer");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(novelty, "novelty");
            Objects.requireNonNull(proof, "proof");
            Objects.requireNonNull(ablation, "ablation");
            Objects.requireNonNull(interestingness, "interestingness");
        }
    }

    public record QualificationReport(
        String schema,
        String hypothesisId,
        String sourceClusterId,
        String formationHash,
        String transferContentHash,
        String ablationContentHash,
        String exactSignatureHash,
        String alphaSignatureHash,
        String proofEvidenceHash,
        String interestingnessContentHash,
        QualificationStatus status,
        String transferStatus,
        String projectNoveltyStatus,
        String externalNoveltyStatus,
        String symbolicProofStatus,
        String formalProofStatus,
        AssumptionStatus assumptionStatus,
        AblationStatus ablationStatus,
        Eligibility interestingnessEligibility,
        InterestingnessProfile interestingnessProfile,
        int interestingnessTotalPermille,
        String promotionStatus,
        String publicEvidenceStatus,
        List<String> blockers,
        String contentHash
    ) {
        public QualificationReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported bridge-qualification schema");
            }
            requireText(hypothesisId, "hypothesisId");
            requireText(sourceClusterId, "sourceClusterId");
            requireSha256(formationHash, "formationHash");
            requireSha256(transferContentHash, "transferContentHash");
            requireSha256(ablationContentHash, "ablationContentHash");
            requireSha256(exactSignatureHash, "exactSignatureHash");
            requireSha256(alphaSignatureHash, "alphaSignatureHash");
            requireSha256(proofEvidenceHash, "proofEvidenceHash");
            requireSha256(interestingnessContentHash, "interestingnessContentHash");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(assumptionStatus, "assumptionStatus");
            Objects.requireNonNull(ablationStatus, "ablationStatus");
            Objects.requireNonNull(interestingnessEligibility, "interestingnessEligibility");
            Objects.requireNonNull(interestingnessProfile, "interestingnessProfile");
            blockers = ordered(blockers);
            requireSha256(contentHash, "contentHash");
            externalNoveltyStatus = normalizeStatus(externalNoveltyStatus);
            formalProofStatus = normalizeStatus(formalProofStatus);
            promotionStatus = requireNotEvaluated(promotionStatus, "promotionStatus");
            publicEvidenceStatus = requireNotEvaluated(
                publicEvidenceStatus, "publicEvidenceStatus");
        }

        public boolean qualified() {
            return status == QualificationStatus.QUALIFIED_FOR_PROMOTION_REVIEW;
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("hypothesisId", hypothesisId)
                .property("sourceClusterId", sourceClusterId)
                .property("formationHash", formationHash)
                .property("transferContentHash", transferContentHash)
                .property("ablationContentHash", ablationContentHash)
                .property("exactSignatureHash", exactSignatureHash)
                .property("alphaSignatureHash", alphaSignatureHash)
                .property("proofEvidenceHash", proofEvidenceHash)
                .property("interestingnessContentHash", interestingnessContentHash)
                .property("status", status.name())
                .property("transferStatus", transferStatus)
                .property("projectNoveltyStatus", projectNoveltyStatus)
                .property("externalNoveltyStatus", externalNoveltyStatus)
                .property("symbolicProofStatus", symbolicProofStatus)
                .property("formalProofStatus", formalProofStatus)
                .property("assumptionStatus", assumptionStatus.name())
                .property("ablationStatus", ablationStatus.name())
                .property("interestingnessEligibility", interestingnessEligibility.name())
                .property("interestingnessProfile", interestingnessProfile.name())
                .property("interestingnessTotalPermille", interestingnessTotalPermille)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .stringArray("blockers", blockers)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }

        public void write(Path output) {
            try {
                Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(output, toCanonicalJson(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static String normalizeStatus(String value) {
        return value == null || value.isBlank() ? "NOT_EVALUATED" : value;
    }

    private static String requireNotEvaluated(String value, String name) {
        if (!"NOT_EVALUATED".equals(value)) {
            throw new IllegalArgumentException(name + " must remain NOT_EVALUATED");
        }
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha256(String value, String name) {
        if (!isSha256(value)) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hash");
        }
    }
}
