package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessReviewConsensus.CandidateConsensus;
import de.regelsuche.mining.InterestingnessReviewConsensus.ConsensusReport;
import de.regelsuche.mining.InterestingnessReviewConsensus.ConsensusStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Freezes a reviewed calibration/test corpus while keeping labels out of the
 * predictive corpus identity.
 */
public final class InterestingnessCalibrationCorpus {
    public static final String SCHEMA = "regelsuche.interestingness-calibration-corpus/v1";
    public static final int MIN_CASES_PER_SPLIT = 2;

    public CorpusReport freeze(
        List<CorpusCase> cases,
        ConsensusReport consensusReport
    ) {
        Objects.requireNonNull(consensusReport, "consensusReport");
        List<CorpusCase> ordered = orderedCases(cases);
        validateUniqueCases(ordered);
        validateSplitSizes(ordered);
        validateSplitIsolation(ordered);

        List<FrozenCase> frozen = ordered.stream()
            .map(item -> freezeCase(item, consensusReport))
            .toList();
        validateLabelDiversity(frozen);
        String predictiveHash = hash(predictiveMaterial(frozen));
        String labeledHash = hash(labeledMaterial(frozen, consensusReport.contentHash()));
        return new CorpusReport(
            SCHEMA,
            CorpusStatus.FROZEN,
            MIN_CASES_PER_SPLIT,
            frozen,
            predictiveHash,
            labeledHash,
            consensusReport.contentHash());
    }

    private static List<CorpusCase> orderedCases(List<CorpusCase> cases) {
        Objects.requireNonNull(cases, "cases");
        if (cases.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("corpus cases must not contain null");
        }
        return cases.stream()
            .sorted(Comparator.comparing((CorpusCase item) -> item.split().name())
                .thenComparing(CorpusCase::caseId))
            .toList();
    }

    private static void validateUniqueCases(List<CorpusCase> cases) {
        Set<String> caseIds = new LinkedHashSet<>();
        Set<String> candidateIds = new LinkedHashSet<>();
        for (CorpusCase item : cases) {
            if (!caseIds.add(item.caseId())) {
                throw new IllegalArgumentException(
                    "duplicate calibration case ID: " + item.caseId());
            }
            if (!candidateIds.add(item.candidateId())) {
                throw new IllegalArgumentException(
                    "candidate appears in more than one corpus case: "
                        + item.candidateId());
            }
        }
    }

    private static void validateSplitSizes(List<CorpusCase> cases) {
        for (CorpusSplit split : CorpusSplit.values()) {
            long count = cases.stream().filter(item -> item.split() == split).count();
            if (count < MIN_CASES_PER_SPLIT) {
                throw new IllegalArgumentException(
                    split.name() + " requires at least " + MIN_CASES_PER_SPLIT + " cases");
            }
        }
    }

    private static void validateSplitIsolation(List<CorpusCase> cases) {
        Set<String> calibrationFamilies = values(cases, CorpusSplit.CALIBRATION, true);
        Set<String> testFamilies = values(cases, CorpusSplit.TEST, true);
        Set<String> familyOverlap = new TreeSet<>(calibrationFamilies);
        familyOverlap.retainAll(testFamilies);
        if (!familyOverlap.isEmpty()) {
            throw new IllegalArgumentException(
                "candidate families cross calibration/test: " + familyOverlap);
        }

        Set<String> calibrationSignatures = values(cases, CorpusSplit.CALIBRATION, false);
        Set<String> testSignatures = values(cases, CorpusSplit.TEST, false);
        Set<String> signatureOverlap = new TreeSet<>(calibrationSignatures);
        signatureOverlap.retainAll(testSignatures);
        if (!signatureOverlap.isEmpty()) {
            throw new IllegalArgumentException(
                "structural signatures cross calibration/test: " + signatureOverlap);
        }
    }

    private static Set<String> values(
        List<CorpusCase> cases,
        CorpusSplit split,
        boolean families
    ) {
        return cases.stream()
            .filter(item -> item.split() == split)
            .map(item -> families ? item.candidateFamily() : item.structuralSignatureHash())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static FrozenCase freezeCase(
        CorpusCase item,
        ConsensusReport consensusReport
    ) {
        CandidateConsensus consensus = consensusReport.requireCandidate(item.candidateId());
        if (consensus.status() != ConsensusStatus.CONSENSUS) {
            throw new IllegalArgumentException(
                "candidate lacks expert consensus: " + item.candidateId()
                    + " status=" + consensus.status().name());
        }
        return new FrozenCase(
            item.caseId(),
            item.candidateId(),
            item.split(),
            item.candidateFamily(),
            item.structuralSignatureHash(),
            item.assessmentContentHash(),
            item.controlClassification(),
            consensus.consensusRelevancePermille(),
            consensus.countedExpertReviews(),
            consensus.blindExpertReviews(),
            consensus.spreadPermille(),
            consensus.meanConfidencePermille(),
            consensus.rationaleCodes());
    }

    private static void validateLabelDiversity(List<FrozenCase> cases) {
        for (CorpusSplit split : CorpusSplit.values()) {
            long distinctLabels = cases.stream()
                .filter(item -> item.split() == split)
                .map(FrozenCase::consensusRelevancePermille)
                .distinct()
                .count();
            if (distinctLabels < 2) {
                throw new IllegalArgumentException(
                    split.name() + " requires relevance-label diversity");
            }
        }
    }

    private static String predictiveMaterial(List<FrozenCase> cases) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\nminimumCasesPerSplit=").append(MIN_CASES_PER_SPLIT);
        cases.forEach(item -> material.append("\ncase=")
            .append(item.predictiveMaterial()));
        return material.toString();
    }

    private static String labeledMaterial(
        List<FrozenCase> cases,
        String consensusContentHash
    ) {
        StringBuilder material = new StringBuilder(predictiveMaterial(cases))
            .append("\nconsensusReport=").append(consensusContentHash);
        cases.forEach(item -> material.append("\nlabel=")
            .append(item.labelMaterial()));
        return material.toString();
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

    public enum CorpusSplit {
        CALIBRATION,
        TEST
    }

    public enum CorpusStatus {
        FROZEN
    }

    public record CorpusCase(
        String caseId,
        String candidateId,
        CorpusSplit split,
        String candidateFamily,
        String structuralSignatureHash,
        String assessmentContentHash,
        ControlClassification controlClassification
    ) {
        public CorpusCase {
            requireText(caseId, "caseId");
            requireText(candidateId, "candidateId");
            Objects.requireNonNull(split, "split");
            requireText(candidateFamily, "candidateFamily");
            requireSha256(structuralSignatureHash, "structuralSignatureHash");
            requireSha256(assessmentContentHash, "assessmentContentHash");
            Objects.requireNonNull(controlClassification, "controlClassification");
        }
    }

    public record FrozenCase(
        String caseId,
        String candidateId,
        CorpusSplit split,
        String candidateFamily,
        String structuralSignatureHash,
        String assessmentContentHash,
        ControlClassification controlClassification,
        int consensusRelevancePermille,
        int countedExpertReviews,
        int blindExpertReviews,
        int spreadPermille,
        int meanConfidencePermille,
        List<String> rationaleCodes
    ) {
        public FrozenCase {
            requireText(caseId, "caseId");
            requireText(candidateId, "candidateId");
            Objects.requireNonNull(split, "split");
            requireText(candidateFamily, "candidateFamily");
            requireSha256(structuralSignatureHash, "structuralSignatureHash");
            requireSha256(assessmentContentHash, "assessmentContentHash");
            Objects.requireNonNull(controlClassification, "controlClassification");
            requirePermille(consensusRelevancePermille, "consensusRelevancePermille");
            requireNonNegative(countedExpertReviews, "countedExpertReviews");
            requireNonNegative(blindExpertReviews, "blindExpertReviews");
            requirePermille(spreadPermille, "spreadPermille");
            requirePermille(meanConfidencePermille, "meanConfidencePermille");
            rationaleCodes = orderedStrings(rationaleCodes);
        }

        String predictiveMaterial() {
            return caseId + '|'
                + candidateId + '|'
                + split.name() + '|'
                + candidateFamily + '|'
                + structuralSignatureHash + '|'
                + assessmentContentHash + '|'
                + controlClassification.name();
        }

        String labelMaterial() {
            return caseId + '|'
                + consensusRelevancePermille + '|'
                + countedExpertReviews + '|'
                + blindExpertReviews + '|'
                + spreadPermille + '|'
                + meanConfidencePermille + '|'
                + rationaleCodes;
        }
    }

    public record CorpusReport(
        String schema,
        CorpusStatus status,
        int minimumCasesPerSplit,
        List<FrozenCase> cases,
        String predictiveCorpusHash,
        String labeledEvaluationHash,
        String consensusReportHash
    ) {
        public CorpusReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported calibration-corpus schema");
            }
            Objects.requireNonNull(status, "status");
            if (minimumCasesPerSplit != MIN_CASES_PER_SPLIT) {
                throw new IllegalArgumentException("unexpected minimumCasesPerSplit");
            }
            cases = cases == null
                ? List.of()
                : cases.stream()
                    .sorted(Comparator.comparing((FrozenCase item) -> item.split().name())
                        .thenComparing(FrozenCase::caseId))
                    .toList();
            requireSha256(predictiveCorpusHash, "predictiveCorpusHash");
            requireSha256(labeledEvaluationHash, "labeledEvaluationHash");
            requireSha256(consensusReportHash, "consensusReportHash");
        }

        public List<FrozenCase> split(CorpusSplit split) {
            return cases.stream().filter(item -> item.split() == split).toList();
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("status", status.name())
                .property("minimumCasesPerSplit", minimumCasesPerSplit)
                .property("predictiveCorpusHash", predictiveCorpusHash)
                .property("labeledEvaluationHash", labeledEvaluationHash)
                .property("consensusReportHash", consensusReportHash)
                .array("cases", array -> cases.forEach(item ->
                    array.objectValue(object -> object
                        .property("caseId", item.caseId())
                        .property("candidateId", item.candidateId())
                        .property("split", item.split().name())
                        .property("candidateFamily", item.candidateFamily())
                        .property("structuralSignatureHash", item.structuralSignatureHash())
                        .property("assessmentContentHash", item.assessmentContentHash())
                        .property("controlClassification",
                            item.controlClassification().name())
                        .property("consensusRelevancePermille",
                            item.consensusRelevancePermille())
                        .property("countedExpertReviews", item.countedExpertReviews())
                        .property("blindExpertReviews", item.blindExpertReviews())
                        .property("spreadPermille", item.spreadPermille())
                        .property("meanConfidencePermille",
                            item.meanConfidencePermille())
                        .stringArray("rationaleCodes", item.rationaleCodes()))))
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

    private static List<String> orderedStrings(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hash");
        }
    }

    private static void requirePermille(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " must be in [0,1000]");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
