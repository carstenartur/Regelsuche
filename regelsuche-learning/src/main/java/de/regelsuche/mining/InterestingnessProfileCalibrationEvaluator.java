package de.regelsuche.mining;

import de.regelsuche.mining.InterestingnessAssessment.ComponentContribution;
import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessCalibrationCase.RelevanceLabel;
import de.regelsuche.mining.InterestingnessCalibrationCase.Split;
import de.regelsuche.mining.InterestingnessCalibrationReport.CalibrationStatus;
import de.regelsuche.mining.InterestingnessCalibrationReport.CaseResult;
import de.regelsuche.mining.InterestingnessCalibrationReport.ParetoPoint;
import de.regelsuche.mining.InterestingnessCalibrationReport.ProfileMetric;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selects an interestingness profile on CALIBRATION only and evaluates it unchanged
 * on structurally disjoint TEST families.
 */
public final class InterestingnessProfileCalibrationEvaluator {
    private final EvidenceAwareInterestingnessAssessor assessor =
        new EvidenceAwareInterestingnessAssessor();

    public InterestingnessCalibrationReport evaluate(
        List<InterestingnessCalibrationCase> suppliedCases
    ) {
        List<InterestingnessCalibrationCase> cases = orderedCases(suppliedCases);
        String predictiveHash = hash(predictiveMaterial(cases));
        String labeledHash = hash(labeledMaterial(cases));
        List<InterestingnessCalibrationCase> calibration = bySplit(cases, Split.CALIBRATION);
        List<InterestingnessCalibrationCase> test = bySplit(cases, Split.TEST);
        List<String> blockers = splitBlockers(cases, calibration, test);
        List<String> calibrationFamilies = families(calibration);
        List<String> testFamilies = families(test);

        if (!blockers.isEmpty()) {
            String contentHash = hash(rejectedMaterial(
                predictiveHash, labeledHash, calibrationFamilies, testFamilies, blockers));
            return new InterestingnessCalibrationReport(
                InterestingnessCalibrationReport.SCHEMA,
                CalibrationStatus.SPLIT_REJECTED,
                "NOT_SELECTED",
                predictiveHash,
                labeledHash,
                calibrationFamilies,
                testFamilies,
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                List.of(),
                blockers,
                contentHash);
        }

        List<ProfileEvaluation> evaluations = java.util.Arrays.stream(
                InterestingnessProfile.values())
            .map(profile -> evaluateProfile(profile, calibration))
            .sorted(Comparator.comparing(evaluation -> evaluation.profile().name()))
            .toList();
        ProfileEvaluation selected = evaluations.stream()
            .sorted(Comparator.comparingInt(ProfileEvaluation::agreementPermille)
                .reversed()
                .thenComparing(evaluation -> evaluation.profile().name()))
            .findFirst()
            .orElseThrow();
        List<CaseResult> calibrationResults = selected.results();
        List<CaseResult> testResults = assess(selected.profile(), test);
        int testAgreement = pairwiseAgreement(testResults);
        List<ParetoPoint> pareto = paretoFront(testResults);
        List<ProfileMetric> metrics = evaluations.stream()
            .map(ProfileEvaluation::metric)
            .toList();
        String contentHash = hash(evaluatedMaterial(
            selected.profile(),
            predictiveHash,
            labeledHash,
            calibrationFamilies,
            testFamilies,
            metrics,
            calibrationResults,
            testResults,
            selected.agreementPermille(),
            testAgreement,
            pareto));
        return new InterestingnessCalibrationReport(
            InterestingnessCalibrationReport.SCHEMA,
            CalibrationStatus.EVALUATED,
            selected.profile().name(),
            predictiveHash,
            labeledHash,
            calibrationFamilies,
            testFamilies,
            metrics,
            calibrationResults,
            testResults,
            selected.agreementPermille(),
            testAgreement,
            pareto,
            List.of(),
            contentHash);
    }

    private ProfileEvaluation evaluateProfile(
        InterestingnessProfile profile,
        List<InterestingnessCalibrationCase> calibration
    ) {
        List<CaseResult> results = assess(profile, calibration);
        int complete = count(results, Eligibility.RANKABLE_COMPLETE);
        int incomplete = count(results, Eligibility.RANKABLE_INCOMPLETE);
        int blocked = count(results, Eligibility.BLOCKED);
        int agreement = pairwiseAgreement(results);
        return new ProfileEvaluation(
            profile,
            results,
            agreement,
            new ProfileMetric(profile, agreement, complete, incomplete, blocked));
    }

    private List<CaseResult> assess(
        InterestingnessProfile profile,
        List<InterestingnessCalibrationCase> cases
    ) {
        return cases.stream()
            .map(item -> new CaseResult(
                item.caseId(),
                item.structuralFamily(),
                item.split(),
                item.relevanceLabel(),
                assessor.assess(
                    item.candidate(),
                    item.knownRuleSimilarity(),
                    item.domainTags(),
                    item.evidence(),
                    profile)))
            .sorted(Comparator.comparing(CaseResult::caseId))
            .toList();
    }

    private static int pairwiseAgreement(List<CaseResult> results) {
        long total = 0L;
        long earned = 0L;
        for (int leftIndex = 0; leftIndex < results.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < results.size(); rightIndex++) {
                CaseResult left = results.get(leftIndex);
                CaseResult right = results.get(rightIndex);
                if (left.relevanceLabel() == right.relevanceLabel()) {
                    continue;
                }
                total += 1000L;
                earned += pairAgreement(left, right);
            }
        }
        return total == 0L ? 0 : (int) ((earned * 1000L) / total);
    }

    private static int pairAgreement(CaseResult left, CaseResult right) {
        int desired = Integer.compare(
            right.relevanceLabel().priority(), left.relevanceLabel().priority());
        int predicted = compareWithoutIdTie(left.assessment(), right.assessment());
        if (predicted == 0) {
            return 500;
        }
        return Integer.signum(predicted) == Integer.signum(desired) ? 1000 : 0;
    }

    private static int compareWithoutIdTie(
        InterestingnessAssessment left,
        InterestingnessAssessment right
    ) {
        int eligibility = Integer.compare(
            eligibilityRank(left.eligibility()), eligibilityRank(right.eligibility()));
        return eligibility != 0
            ? eligibility
            : Integer.compare(right.totalPermille(), left.totalPermille());
    }

    private static int eligibilityRank(Eligibility eligibility) {
        return switch (eligibility) {
            case RANKABLE_COMPLETE -> 0;
            case RANKABLE_INCOMPLETE -> 1;
            case BLOCKED -> 2;
        };
    }

    private static List<ParetoPoint> paretoFront(List<CaseResult> results) {
        return results.stream()
            .map(target -> {
                List<String> dominators = results.stream()
                    .filter(other -> !other.caseId().equals(target.caseId()))
                    .filter(other -> dominates(other.assessment(), target.assessment()))
                    .map(CaseResult::caseId)
                    .sorted()
                    .toList();
                return new ParetoPoint(target.caseId(), dominators.isEmpty(), dominators);
            })
            .sorted(Comparator.comparing(ParetoPoint::caseId))
            .toList();
    }

    private static boolean dominates(
        InterestingnessAssessment left,
        InterestingnessAssessment right
    ) {
        int leftEligibility = eligibilityRank(left.eligibility());
        int rightEligibility = eligibilityRank(right.eligibility());
        if (leftEligibility > rightEligibility) {
            return false;
        }
        if (leftEligibility < rightEligibility) {
            return true;
        }
        Map<String, Integer> leftComponents = rawComponents(left);
        Map<String, Integer> rightComponents = rawComponents(right);
        boolean allAtLeast = rightComponents.entrySet().stream().allMatch(entry ->
            leftComponents.getOrDefault(entry.getKey(), 0) >= entry.getValue());
        boolean penaltiesNoWorse = left.unresolvedRiskPenaltyPermille()
                <= right.unresolvedRiskPenaltyPermille()
            && left.controlPenaltyPermille() <= right.controlPenaltyPermille();
        boolean strictComponent = rightComponents.entrySet().stream().anyMatch(entry ->
            leftComponents.getOrDefault(entry.getKey(), 0) > entry.getValue());
        boolean strictPenalty = left.unresolvedRiskPenaltyPermille()
                < right.unresolvedRiskPenaltyPermille()
            || left.controlPenaltyPermille() < right.controlPenaltyPermille();
        return allAtLeast && penaltiesNoWorse && (strictComponent || strictPenalty);
    }

    private static Map<String, Integer> rawComponents(InterestingnessAssessment assessment) {
        return assessment.contributions().stream().collect(Collectors.toMap(
            ComponentContribution::name,
            ComponentContribution::rawPermille,
            (left, right) -> left,
            LinkedHashMap::new));
    }

    private static List<String> splitBlockers(
        List<InterestingnessCalibrationCase> cases,
        List<InterestingnessCalibrationCase> calibration,
        List<InterestingnessCalibrationCase> test
    ) {
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (calibration.size() < 2) {
            blockers.add("calibration-cases<2");
        }
        if (test.size() < 2) {
            blockers.add("test-cases<2");
        }
        if (distinctLabels(calibration) < 2) {
            blockers.add("calibration-label-diversity<2");
        }
        if (distinctLabels(test) < 2) {
            blockers.add("test-label-diversity<2");
        }
        if (cases.stream().map(InterestingnessCalibrationCase::caseId).distinct().count()
                != cases.size()) {
            blockers.add("duplicate-case-id");
        }
        addOverlapBlocker(blockers, families(calibration), families(test), "family-split-leakage");
        addOverlapBlocker(
            blockers,
            signatures(calibration),
            signatures(test),
            "structural-signature-split-leakage");
        return blockers.stream().sorted().toList();
    }

    private static void addOverlapBlocker(
        Set<String> blockers,
        List<String> left,
        List<String> right,
        String prefix
    ) {
        TreeSet<String> overlap = new TreeSet<>(left);
        overlap.retainAll(right);
        if (!overlap.isEmpty()) {
            blockers.add(prefix + "=" + overlap);
        }
    }

    private static long distinctLabels(List<InterestingnessCalibrationCase> cases) {
        return cases.stream().map(InterestingnessCalibrationCase::relevanceLabel).distinct().count();
    }

    private static int count(List<CaseResult> results, Eligibility eligibility) {
        return (int) results.stream()
            .filter(result -> result.assessment().eligibility() == eligibility)
            .count();
    }

    private static List<InterestingnessCalibrationCase> orderedCases(
        List<InterestingnessCalibrationCase> supplied
    ) {
        if (supplied == null) {
            return List.of();
        }
        return supplied.stream()
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparing(InterestingnessCalibrationCase::caseId))
            .toList();
    }

    private static List<InterestingnessCalibrationCase> bySplit(
        List<InterestingnessCalibrationCase> cases,
        Split split
    ) {
        return cases.stream().filter(item -> item.split() == split).toList();
    }

    private static List<String> families(List<InterestingnessCalibrationCase> cases) {
        return cases.stream()
            .map(InterestingnessCalibrationCase::structuralFamily)
            .distinct()
            .sorted()
            .toList();
    }

    private static List<String> signatures(List<InterestingnessCalibrationCase> cases) {
        return cases.stream()
            .map(InterestingnessCalibrationCase::structuralSignatureHash)
            .distinct()
            .sorted()
            .toList();
    }

    private static String predictiveMaterial(List<InterestingnessCalibrationCase> cases) {
        StringBuilder material = new StringBuilder(InterestingnessCalibrationReport.SCHEMA)
            .append("\nassessment=").append(InterestingnessAssessment.SCHEMA)
            .append("\nprofiles=").append(java.util.Arrays.toString(
                InterestingnessProfile.values()));
        for (InterestingnessCalibrationCase item : cases) {
            material.append("\ncase=").append(item.caseId())
                .append('|').append(item.split())
                .append('|').append(item.structuralFamily())
                .append('|').append(item.structuralSignatureHash())
                .append('|').append(normalize(item.candidate().leftPattern()))
                .append("->").append(normalize(item.candidate().rightPattern()))
                .append('|').append(sorted(item.candidate().supportingPaths()))
                .append('|').append(sortedWitnesses(item.candidate()))
                .append('|').append(sorted(item.candidate().assumptions()))
                .append('|').append(item.candidate().proofStatus())
                .append('|').append(item.evidence())
                .append('|').append(item.knownRuleSimilarity())
                .append('|').append(item.domainTags());
        }
        return material.toString();
    }

    private static String labeledMaterial(List<InterestingnessCalibrationCase> cases) {
        StringBuilder material = new StringBuilder(predictiveMaterial(cases));
        cases.forEach(item -> material.append("\nlabel=")
            .append(item.caseId()).append('|').append(item.relevanceLabel()));
        return material.toString();
    }

    private static String rejectedMaterial(
        String predictiveHash,
        String labeledHash,
        List<String> calibrationFamilies,
        List<String> testFamilies,
        List<String> blockers
    ) {
        return InterestingnessCalibrationReport.SCHEMA
            + "\nstatus=SPLIT_REJECTED"
            + "\npredictive=" + predictiveHash
            + "\nlabeled=" + labeledHash
            + "\ncalibrationFamilies=" + calibrationFamilies
            + "\ntestFamilies=" + testFamilies
            + "\nblockers=" + blockers;
    }

    private static String evaluatedMaterial(
        InterestingnessProfile selected,
        String predictiveHash,
        String labeledHash,
        List<String> calibrationFamilies,
        List<String> testFamilies,
        List<ProfileMetric> metrics,
        List<CaseResult> calibrationResults,
        List<CaseResult> testResults,
        int calibrationAgreement,
        int testAgreement,
        List<ParetoPoint> pareto
    ) {
        StringBuilder material = new StringBuilder(InterestingnessCalibrationReport.SCHEMA)
            .append("\nstatus=EVALUATED")
            .append("\nselected=").append(selected)
            .append("\npredictive=").append(predictiveHash)
            .append("\nlabeled=").append(labeledHash)
            .append("\ncalibrationFamilies=").append(calibrationFamilies)
            .append("\ntestFamilies=").append(testFamilies)
            .append("\nmetrics=").append(metrics)
            .append("\ncalibrationAgreement=").append(calibrationAgreement)
            .append("\ntestAgreement=").append(testAgreement)
            .append("\npareto=").append(pareto);
        appendResults(material, "calibration", calibrationResults);
        appendResults(material, "test", testResults);
        return material.toString();
    }

    private static void appendResults(
        StringBuilder material,
        String prefix,
        List<CaseResult> results
    ) {
        results.forEach(result -> material.append('\n').append(prefix).append('=')
            .append(result.caseId()).append('|')
            .append(result.relevanceLabel()).append('|')
            .append(result.assessment().contentHash()).append('|')
            .append(result.assessment().totalPermille()));
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private static List<String> sortedWitnesses(HypothesisCandidate candidate) {
        return candidate.supportingExpressions().stream()
            .map(pair -> normalize(pair.left()) + "->" + normalize(pair.right()))
            .sorted()
            .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
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

    private record ProfileEvaluation(
        InterestingnessProfile profile,
        List<CaseResult> results,
        int agreementPermille,
        ProfileMetric metric
    ) {
    }
}
