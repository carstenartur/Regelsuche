package de.regelsuche.mining;

import de.regelsuche.mining.InterestingnessCalibrationCase.Split;
import de.regelsuche.mining.InterestingnessCalibrationReport.CalibrationStatus;
import de.regelsuche.mining.InterestingnessCalibrationReport.CaseResult;
import de.regelsuche.mining.InterestingnessSensitivityReport.LeaveOneOutScenario;
import de.regelsuche.mining.InterestingnessSensitivityReport.SensitivityStatus;
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

/** Profile-order and leave-one-calibration-case-out sensitivity analysis. */
public final class InterestingnessSensitivityAnalyzer {
    private final InterestingnessProfileCalibrationEvaluator evaluator =
        new InterestingnessProfileCalibrationEvaluator();
    private final EvidenceAwareInterestingnessAssessor assessor =
        new EvidenceAwareInterestingnessAssessor();

    public InterestingnessSensitivityReport analyze(
        List<InterestingnessCalibrationCase> suppliedCases,
        InterestingnessCalibrationReport baseline
    ) {
        List<InterestingnessCalibrationCase> cases = orderedCases(suppliedCases);
        if (baseline == null || baseline.status() != CalibrationStatus.EVALUATED) {
            String predictiveHash = baseline == null
                ? hash("missing-baseline")
                : baseline.predictiveDatasetHash();
            List<String> blockers = List.of("baseline-calibration-not-evaluated");
            return rejected(predictiveHash, blockers);
        }

        InterestingnessCalibrationReport current = evaluator.evaluate(cases);
        List<String> blockers = baselineBlockers(baseline, current);
        if (!blockers.isEmpty()) {
            return rejected(baseline.predictiveDatasetHash(), blockers);
        }

        List<InterestingnessCalibrationCase> testCases = cases.stream()
            .filter(item -> item.split() == Split.TEST)
            .toList();
        Map<InterestingnessProfile, List<String>> orders = profileOrders(testCases);
        int crossProfileAgreement = crossProfileAgreement(orders);
        List<String> unstable = unstableCandidates(orders);
        String baselineTop = topCandidate(baseline.testResults());
        List<LeaveOneOutScenario> scenarios = leaveOneOutScenarios(
            cases, baseline.selectedProfile(), baselineTop);
        List<LeaveOneOutScenario> evaluated = scenarios.stream()
            .filter(scenario -> scenario.status() == CalibrationStatus.EVALUATED)
            .toList();
        int selectionStability = fractionPermille(
            evaluated.stream().filter(LeaveOneOutScenario::selectionMatchesBaseline).count(),
            evaluated.size());
        int topStability = fractionPermille(
            evaluated.stream().filter(LeaveOneOutScenario::topCandidateMatchesBaseline).count(),
            evaluated.size());
        String contentHash = hash(canonicalMaterial(
            baseline.predictiveDatasetHash(),
            baseline.selectedProfile(),
            crossProfileAgreement,
            evaluated.size(),
            selectionStability,
            topStability,
            unstable,
            scenarios));
        return new InterestingnessSensitivityReport(
            InterestingnessSensitivityReport.SCHEMA,
            SensitivityStatus.EVALUATED,
            baseline.predictiveDatasetHash(),
            baseline.selectedProfile(),
            crossProfileAgreement,
            evaluated.size(),
            selectionStability,
            topStability,
            unstable,
            scenarios,
            List.of(),
            contentHash);
    }

    private InterestingnessSensitivityReport rejected(
        String predictiveHash,
        List<String> blockers
    ) {
        String contentHash = hash(InterestingnessSensitivityReport.SCHEMA
            + "\nstatus=BASELINE_REJECTED"
            + "\npredictive=" + predictiveHash
            + "\nblockers=" + blockers);
        return new InterestingnessSensitivityReport(
            InterestingnessSensitivityReport.SCHEMA,
            SensitivityStatus.BASELINE_REJECTED,
            predictiveHash,
            "NOT_SELECTED",
            0,
            0,
            0,
            0,
            List.of(),
            List.of(),
            blockers,
            contentHash);
    }

    private static List<String> baselineBlockers(
        InterestingnessCalibrationReport baseline,
        InterestingnessCalibrationReport current
    ) {
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (current.status() != CalibrationStatus.EVALUATED) {
            blockers.add("current-calibration-not-evaluated");
            current.blockers().forEach(blocker -> blockers.add("current=" + blocker));
        }
        if (!baseline.predictiveDatasetHash().equals(current.predictiveDatasetHash())) {
            blockers.add("baseline-predictive-hash-mismatch");
        }
        if (!baseline.selectedProfile().equals(current.selectedProfile())) {
            blockers.add("baseline-selected-profile-mismatch");
        }
        return blockers.stream().sorted().toList();
    }

    private Map<InterestingnessProfile, List<String>> profileOrders(
        List<InterestingnessCalibrationCase> testCases
    ) {
        LinkedHashMap<InterestingnessProfile, List<String>> orders = new LinkedHashMap<>();
        for (InterestingnessProfile profile : InterestingnessProfile.values()) {
            List<String> order = testCases.stream()
                .map(item -> new RankedCase(
                    item.caseId(),
                    assessor.assess(
                        item.candidate(),
                        item.knownRuleSimilarity(),
                        item.domainTags(),
                        item.evidence(),
                        profile)))
                .sorted(Comparator.comparing(RankedCase::assessment))
                .map(RankedCase::caseId)
                .toList();
            orders.put(profile, order);
        }
        return java.util.Collections.unmodifiableMap(orders);
    }

    private static int crossProfileAgreement(
        Map<InterestingnessProfile, List<String>> orders
    ) {
        List<List<String>> profileOrders = new ArrayList<>(orders.values());
        if (profileOrders.size() < 2) {
            return 1000;
        }
        List<String> left = profileOrders.get(0);
        List<String> right = profileOrders.get(1);
        long total = 0L;
        long agreements = 0L;
        for (int first = 0; first < left.size(); first++) {
            for (int second = first + 1; second < left.size(); second++) {
                String firstId = left.get(first);
                String secondId = left.get(second);
                total++;
                if (right.indexOf(firstId) < right.indexOf(secondId)) {
                    agreements++;
                }
            }
        }
        return fractionPermille(agreements, total);
    }

    private static List<String> unstableCandidates(
        Map<InterestingnessProfile, List<String>> orders
    ) {
        List<List<String>> profileOrders = new ArrayList<>(orders.values());
        if (profileOrders.size() < 2) {
            return List.of();
        }
        List<String> left = profileOrders.get(0);
        List<String> right = profileOrders.get(1);
        return left.stream()
            .filter(caseId -> left.indexOf(caseId) != right.indexOf(caseId))
            .sorted()
            .toList();
    }

    private List<LeaveOneOutScenario> leaveOneOutScenarios(
        List<InterestingnessCalibrationCase> cases,
        String baselineProfile,
        String baselineTop
    ) {
        return cases.stream()
            .filter(item -> item.split() == Split.CALIBRATION)
            .map(omitted -> leaveOneOut(cases, omitted, baselineProfile, baselineTop))
            .sorted(Comparator.comparing(LeaveOneOutScenario::omittedCalibrationCaseId))
            .toList();
    }

    private LeaveOneOutScenario leaveOneOut(
        List<InterestingnessCalibrationCase> cases,
        InterestingnessCalibrationCase omitted,
        String baselineProfile,
        String baselineTop
    ) {
        List<InterestingnessCalibrationCase> reduced = cases.stream()
            .filter(item -> !item.caseId().equals(omitted.caseId()))
            .toList();
        InterestingnessCalibrationReport report = evaluator.evaluate(reduced);
        String top = report.status() == CalibrationStatus.EVALUATED
            ? topCandidate(report.testResults())
            : "NOT_AVAILABLE";
        return new LeaveOneOutScenario(
            omitted.caseId(),
            report.status(),
            report.selectedProfile(),
            top,
            baselineProfile.equals(report.selectedProfile()),
            baselineTop.equals(top),
            report.blockers());
    }

    private static String topCandidate(List<CaseResult> results) {
        return results.stream()
            .sorted(Comparator.comparing(CaseResult::assessment))
            .map(CaseResult::caseId)
            .findFirst()
            .orElse("NOT_AVAILABLE");
    }

    private static List<InterestingnessCalibrationCase> orderedCases(
        List<InterestingnessCalibrationCase> supplied
    ) {
        return supplied == null
            ? List.of()
            : supplied.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(InterestingnessCalibrationCase::caseId))
                .toList();
    }

    private static int fractionPermille(long numerator, long denominator) {
        return denominator == 0L ? 0 : (int) ((numerator * 1000L) / denominator);
    }

    private static String canonicalMaterial(
        String predictiveHash,
        String baselineProfile,
        int crossProfileAgreement,
        int evaluatedScenarios,
        int selectionStability,
        int topStability,
        List<String> unstable,
        List<LeaveOneOutScenario> scenarios
    ) {
        StringBuilder material = new StringBuilder(InterestingnessSensitivityReport.SCHEMA)
            .append("\nstatus=EVALUATED")
            .append("\npredictive=").append(predictiveHash)
            .append("\nbaselineProfile=").append(baselineProfile)
            .append("\ncrossProfileAgreement=").append(crossProfileAgreement)
            .append("\nevaluatedScenarios=").append(evaluatedScenarios)
            .append("\nselectionStability=").append(selectionStability)
            .append("\ntopStability=").append(topStability)
            .append("\nunstable=").append(unstable);
        scenarios.forEach(scenario -> material.append("\nscenario=")
            .append(scenario.omittedCalibrationCaseId()).append('|')
            .append(scenario.status()).append('|')
            .append(scenario.selectedProfile()).append('|')
            .append(scenario.topTestCandidateId()).append('|')
            .append(scenario.selectionMatchesBaseline()).append('|')
            .append(scenario.topCandidateMatchesBaseline()).append('|')
            .append(scenario.blockers()));
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

    private record RankedCase(String caseId, InterestingnessAssessment assessment) {
    }
}
