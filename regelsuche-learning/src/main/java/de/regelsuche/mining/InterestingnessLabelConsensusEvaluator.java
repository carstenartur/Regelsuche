package de.regelsuche.mining;

import de.regelsuche.mining.InterestingnessCalibrationCase.RelevanceLabel;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessLabelConsensusReport.CaseConsensus;
import de.regelsuche.mining.InterestingnessLabelConsensusReport.ConsensusStatus;
import de.regelsuche.mining.InterestingnessLabelConsensusReport.LabelCount;
import de.regelsuche.mining.InterestingnessLabelConsensusReport.ReportStatus;
import de.regelsuche.mining.InterestingnessReviewLabel.Source;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Aggregates privacy-preserving post-hoc labels without exposing labels to scoring. */
public final class InterestingnessLabelConsensusEvaluator {
    public InterestingnessLabelConsensusReport evaluate(
        List<InterestingnessCalibrationCase> expectedCases,
        List<InterestingnessReviewLabel> suppliedLabels,
        InterestingnessLabelConsensusThresholds thresholds
    ) {
        Objects.requireNonNull(thresholds, "thresholds");
        List<InterestingnessCalibrationCase> cases = orderedCases(expectedCases);
        List<InterestingnessReviewLabel> labels = orderedLabels(suppliedLabels);
        String reviewRoundId = reviewRoundId(labels);
        Map<String, InterestingnessCalibrationCase> caseById = cases.stream()
            .collect(Collectors.toMap(
                InterestingnessCalibrationCase::caseId,
                Function.identity(),
                (left, right) -> left,
                TreeMap::new));
        List<String> reportBlockers = reportBlockers(cases, labels, caseById);
        Map<String, List<InterestingnessReviewLabel>> byCase = labels.stream()
            .filter(label -> caseById.containsKey(label.caseId()))
            .collect(Collectors.groupingBy(
                InterestingnessReviewLabel::caseId,
                TreeMap::new,
                Collectors.toList()));
        List<CaseConsensus> consensus = cases.stream()
            .map(item -> evaluateCase(
                item,
                byCase.getOrDefault(item.caseId(), List.of()),
                thresholds))
            .toList();
        ReportStatus status = reportStatus(consensus, labels, reportBlockers);
        int reviewed = (int) consensus.stream().filter(item -> item.reviewCount() > 0).count();
        int accepted = count(consensus, ConsensusStatus.CONSENSUS);
        int uncertain = count(consensus, ConsensusStatus.UNCERTAIN);
        int insufficient = count(consensus, ConsensusStatus.INSUFFICIENT_REVIEWS);
        int development = count(consensus, ConsensusStatus.DEVELOPMENT_ONLY);
        String datasetHash = hash(reviewDatasetMaterial(cases, labels, thresholds));
        String contentHash = hash(contentMaterial(
            status,
            reviewRoundId,
            thresholds,
            cases.size(),
            reviewed,
            accepted,
            uncertain,
            insufficient,
            development,
            consensus,
            reportBlockers,
            datasetHash));
        return new InterestingnessLabelConsensusReport(
            InterestingnessLabelConsensusReport.SCHEMA,
            status,
            reviewRoundId,
            thresholds.profileId(),
            cases.size(),
            reviewed,
            accepted,
            uncertain,
            insufficient,
            development,
            consensus,
            reportBlockers,
            datasetHash,
            contentHash);
    }

    private static CaseConsensus evaluateCase(
        InterestingnessCalibrationCase item,
        List<InterestingnessReviewLabel> supplied,
        InterestingnessLabelConsensusThresholds thresholds
    ) {
        List<InterestingnessReviewLabel> labels = supplied.stream()
            .sorted(Comparator.comparing(InterestingnessReviewLabel::reviewId))
            .toList();
        boolean control = item.evidence().controlClassification()
            != ControlClassification.NONE;
        List<InterestingnessReviewLabel> relevant = control
            ? labels.stream()
                .filter(label -> label.source() != Source.TEST_FIXTURE)
                .toList()
            : labels.stream()
                .filter(label -> label.source() == Source.EXPERT_REVIEW)
                .toList();
        List<String> blockers = caseBlockers(item, labels, relevant, thresholds, control);
        boolean hasFixture = labels.stream().anyMatch(label -> label.source() == Source.TEST_FIXTURE);
        ConsensusStatus status = consensusStatus(
            relevant,
            blockers,
            hasFixture,
            control,
            thresholds);
        RelevanceLabel consensusLabel = weightedMode(relevant.isEmpty() ? labels : relevant);
        int reviewCount = labels.size();
        int independent = (int) labels.stream()
            .map(InterestingnessReviewLabel::reviewerIdHash)
            .distinct()
            .count();
        int expertCount = countSource(labels, Source.EXPERT_REVIEW);
        int controlCount = countSource(labels, Source.CONTROL_ASSIGNMENT);
        int fixtureCount = countSource(labels, Source.TEST_FIXTURE);
        int blindCount = (int) relevant.stream()
            .filter(InterestingnessReviewLabel::blindToAssessment)
            .count();
        int blindPermille = fractionPermille(blindCount, relevant.size());
        int agreement = agreementPermille(relevant);
        int spread = labelSpread(relevant);
        int averageConfidence = relevant.isEmpty()
            ? 0
            : relevant.stream()
                .mapToInt(InterestingnessReviewLabel::confidencePermille)
                .sum() / relevant.size();
        List<LabelCount> counts = Arrays.stream(RelevanceLabel.values())
            .map(label -> new LabelCount(
                label,
                (int) labels.stream()
                    .filter(itemLabel -> itemLabel.relevanceLabel() == label)
                    .count()))
            .toList();
        return new CaseConsensus(
            item.caseId(),
            status,
            consensusLabel,
            reviewCount,
            independent,
            expertCount,
            controlCount,
            fixtureCount,
            blindCount,
            blindPermille,
            agreement,
            spread,
            averageConfidence,
            counts,
            labels.stream().map(InterestingnessReviewLabel::reviewId).toList(),
            blockers);
    }

    private static List<String> caseBlockers(
        InterestingnessCalibrationCase item,
        List<InterestingnessReviewLabel> labels,
        List<InterestingnessReviewLabel> relevant,
        InterestingnessLabelConsensusThresholds thresholds,
        boolean control
    ) {
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (labels.isEmpty()) {
            blockers.add("reviews-missing");
        }
        long uniqueReviewIds = labels.stream()
            .map(InterestingnessReviewLabel::reviewId)
            .distinct()
            .count();
        if (uniqueReviewIds != labels.size()) {
            blockers.add("duplicate-review-id");
        }
        long uniqueReviewers = relevant.stream()
            .map(InterestingnessReviewLabel::reviewerIdHash)
            .distinct()
            .count();
        if (uniqueReviewers != relevant.size()) {
            blockers.add("duplicate-reviewer-for-case");
        }
        if (control) {
            if (labels.stream().noneMatch(label ->
                    label.source() == Source.CONTROL_ASSIGNMENT
                        || label.source() == Source.EXPERT_REVIEW)) {
                blockers.add("control-assignment-missing");
            }
            if (labels.stream().anyMatch(label ->
                    label.source() == Source.CONTROL_ASSIGNMENT
                        && label.relevanceLabel() != RelevanceLabel.CONTROL)) {
                blockers.add("control-assignment-not-control");
            }
        } else {
            if (uniqueReviewers < thresholds.minimumIndependentExpertReviews()) {
                blockers.add("independent-expert-reviews<"
                    + thresholds.minimumIndependentExpertReviews());
            }
            if (agreementPermille(relevant) < thresholds.minimumAgreementPermille()) {
                blockers.add("agreement<" + thresholds.minimumAgreementPermille());
            }
            if (labelSpread(relevant) > thresholds.maximumLabelSpread()) {
                blockers.add("label-spread>" + thresholds.maximumLabelSpread());
            }
            if (fractionPermille(
                    relevant.stream().filter(InterestingnessReviewLabel::blindToAssessment).count(),
                    relevant.size()) < thresholds.minimumBlindReviewPermille()) {
                blockers.add("blind-review-permille<"
                    + thresholds.minimumBlindReviewPermille());
            }
        }
        return blockers.stream().sorted().toList();
    }

    private static ConsensusStatus consensusStatus(
        List<InterestingnessReviewLabel> relevant,
        List<String> blockers,
        boolean hasFixture,
        boolean control,
        InterestingnessLabelConsensusThresholds thresholds
    ) {
        if (hasFixture) {
            return ConsensusStatus.DEVELOPMENT_ONLY;
        }
        if (control && blockers.isEmpty()) {
            return ConsensusStatus.CONSENSUS;
        }
        if (relevant.size() < thresholds.minimumIndependentExpertReviews()) {
            return ConsensusStatus.INSUFFICIENT_REVIEWS;
        }
        return blockers.isEmpty()
            ? ConsensusStatus.CONSENSUS
            : ConsensusStatus.UNCERTAIN;
    }

    private static ReportStatus reportStatus(
        List<CaseConsensus> cases,
        List<InterestingnessReviewLabel> labels,
        List<String> blockers
    ) {
        if (labels.stream().anyMatch(label -> label.source() == Source.TEST_FIXTURE)
                || cases.stream().anyMatch(item ->
                    item.status() == ConsensusStatus.DEVELOPMENT_ONLY)) {
            return ReportStatus.DEVELOPMENT_ONLY;
        }
        if (!blockers.isEmpty()
                || cases.stream().anyMatch(item ->
                    item.status() == ConsensusStatus.UNCERTAIN
                        || item.status() == ConsensusStatus.INSUFFICIENT_REVIEWS)) {
            return ReportStatus.INCOMPLETE;
        }
        return ReportStatus.EXPERT_EVIDENCE;
    }

    private static List<String> reportBlockers(
        List<InterestingnessCalibrationCase> cases,
        List<InterestingnessReviewLabel> labels,
        Map<String, InterestingnessCalibrationCase> caseById
    ) {
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (cases.isEmpty()) {
            blockers.add("expected-cases-empty");
        }
        if (cases.stream().map(InterestingnessCalibrationCase::caseId).distinct().count()
                != cases.size()) {
            blockers.add("duplicate-expected-case-id");
        }
        TreeSet<String> unknown = labels.stream()
            .map(InterestingnessReviewLabel::caseId)
            .filter(caseId -> !caseById.containsKey(caseId))
            .collect(Collectors.toCollection(TreeSet::new));
        if (!unknown.isEmpty()) {
            blockers.add("unknown-reviewed-cases=" + unknown);
        }
        TreeSet<String> rounds = labels.stream()
            .map(InterestingnessReviewLabel::reviewRoundId)
            .collect(Collectors.toCollection(TreeSet::new));
        if (rounds.size() > 1) {
            blockers.add("mixed-review-rounds=" + rounds);
        }
        return blockers.stream().sorted().toList();
    }

    private static RelevanceLabel weightedMode(List<InterestingnessReviewLabel> labels) {
        if (labels.isEmpty()) {
            return RelevanceLabel.CONTROL;
        }
        Map<RelevanceLabel, Integer> weights = new LinkedHashMap<>();
        for (RelevanceLabel label : RelevanceLabel.values()) {
            weights.put(label, 0);
        }
        labels.forEach(item -> weights.merge(
            item.relevanceLabel(),
            Math.max(1, item.confidencePermille()),
            Integer::sum));
        return weights.entrySet().stream()
            .sorted(Map.Entry.<RelevanceLabel, Integer>comparingByValue().reversed()
                .thenComparing(entry -> entry.getKey().priority()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(RelevanceLabel.CONTROL);
    }

    private static int agreementPermille(List<InterestingnessReviewLabel> labels) {
        if (labels.isEmpty()) {
            return 0;
        }
        if (labels.size() == 1) {
            return 1000;
        }
        long distance = 0L;
        long pairs = 0L;
        for (int left = 0; left < labels.size(); left++) {
            for (int right = left + 1; right < labels.size(); right++) {
                distance += Math.abs(
                    labels.get(left).relevanceLabel().priority()
                        - labels.get(right).relevanceLabel().priority());
                pairs++;
            }
        }
        long maximum = pairs * 3L;
        return maximum == 0L ? 0 : (int) (((maximum - distance) * 1000L) / maximum);
    }

    private static int labelSpread(List<InterestingnessReviewLabel> labels) {
        if (labels.isEmpty()) {
            return 0;
        }
        int minimum = labels.stream()
            .mapToInt(label -> label.relevanceLabel().priority())
            .min()
            .orElse(0);
        int maximum = labels.stream()
            .mapToInt(label -> label.relevanceLabel().priority())
            .max()
            .orElse(0);
        return maximum - minimum;
    }

    private static int countSource(List<InterestingnessReviewLabel> labels, Source source) {
        return (int) labels.stream().filter(label -> label.source() == source).count();
    }

    private static int count(List<CaseConsensus> cases, ConsensusStatus status) {
        return (int) cases.stream().filter(item -> item.status() == status).count();
    }

    private static int fractionPermille(long numerator, long denominator) {
        return denominator <= 0L ? 0 : (int) ((numerator * 1000L) / denominator);
    }

    private static List<InterestingnessCalibrationCase> orderedCases(
        List<InterestingnessCalibrationCase> supplied
    ) {
        return supplied == null
            ? List.of()
            : supplied.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(InterestingnessCalibrationCase::caseId))
                .toList();
    }

    private static List<InterestingnessReviewLabel> orderedLabels(
        List<InterestingnessReviewLabel> supplied
    ) {
        return supplied == null
            ? List.of()
            : supplied.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(InterestingnessReviewLabel::caseId)
                    .thenComparing(InterestingnessReviewLabel::reviewId))
                .toList();
    }

    private static String reviewRoundId(List<InterestingnessReviewLabel> labels) {
        return labels.stream()
            .map(InterestingnessReviewLabel::reviewRoundId)
            .distinct()
            .sorted()
            .findFirst()
            .orElse("no-review-round");
    }

    private static String reviewDatasetMaterial(
        List<InterestingnessCalibrationCase> cases,
        List<InterestingnessReviewLabel> labels,
        InterestingnessLabelConsensusThresholds thresholds
    ) {
        StringBuilder material = new StringBuilder(InterestingnessLabelConsensusReport.SCHEMA)
            .append("\nthresholdProfile=").append(thresholds.profileId())
            .append("\nminimumReviews=").append(thresholds.minimumIndependentExpertReviews())
            .append("\nminimumAgreement=").append(thresholds.minimumAgreementPermille())
            .append("\nmaximumSpread=").append(thresholds.maximumLabelSpread())
            .append("\nminimumBlind=").append(thresholds.minimumBlindReviewPermille());
        cases.forEach(item -> material.append("\ncase=")
            .append(item.caseId()).append('|')
            .append(item.structuralFamily()).append('|')
            .append(item.structuralSignatureHash()).append('|')
            .append(item.evidence().controlClassification()));
        labels.forEach(label -> material.append("\nreview=")
            .append(label.reviewId()).append('|')
            .append(label.reviewRoundId()).append('|')
            .append(label.caseId()).append('|')
            .append(label.reviewerIdHash()).append('|')
            .append(label.source()).append('|')
            .append(label.relevanceLabel()).append('|')
            .append(label.confidencePermille()).append('|')
            .append(label.rationaleCode()).append('|')
            .append(label.blindToAssessment()));
        return material.toString();
    }

    private static String contentMaterial(
        ReportStatus status,
        String round,
        InterestingnessLabelConsensusThresholds thresholds,
        int expected,
        int reviewed,
        int consensusCount,
        int uncertain,
        int insufficient,
        int development,
        List<CaseConsensus> cases,
        List<String> blockers,
        String datasetHash
    ) {
        StringBuilder material = new StringBuilder(InterestingnessLabelConsensusReport.SCHEMA)
            .append("\nstatus=").append(status)
            .append("\nround=").append(round)
            .append("\nthresholds=").append(thresholds)
            .append("\nexpected=").append(expected)
            .append("\nreviewed=").append(reviewed)
            .append("\nconsensus=").append(consensusCount)
            .append("\nuncertain=").append(uncertain)
            .append("\ninsufficient=").append(insufficient)
            .append("\ndevelopment=").append(development)
            .append("\nblockers=").append(blockers)
            .append("\ndatasetHash=").append(datasetHash);
        cases.forEach(item -> material.append("\ncaseConsensus=").append(item));
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
}
