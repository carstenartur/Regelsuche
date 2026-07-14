package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.InterestingnessProfileCalibration.CalibrationReport;
import de.regelsuche.mining.InterestingnessProfileCalibration.ProfileMetric;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies predeclared quality thresholds to a held-out interestingness report.
 *
 * <p>The gate consumes an already completed calibration/TEST report. It cannot
 * change profile selection, candidate scores, relevance labels or the TEST
 * ranking. A rejected result remains useful diagnostic evidence but cannot be
 * presented as an accepted empirical ranking-quality result.</p>
 */
public final class InterestingnessAcceptanceGate {
    public static final String SCHEMA = "regelsuche.interestingness-acceptance/v1";

    public Decision evaluate(CalibrationReport report, Thresholds thresholds) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(thresholds, "thresholds");

        ProfileMetric selectedMetric = report.profileMetrics().stream()
            .filter(metric -> metric.profile() == report.selectedProfile())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "selected profile metric is missing"));

        List<String> blockers = new ArrayList<>();
        requireAtLeast(
            report.testRanking().size(), thresholds.minimumTestCases(),
            "test-case-count", blockers);
        requireAtLeast(
            selectedMetric.calibrationAgreementPermille(),
            thresholds.minimumCalibrationAgreementPermille(),
            "calibration-agreement", blockers);
        requireAtLeast(
            selectedMetric.testAgreementPermille(),
            thresholds.minimumTestAgreementPermille(),
            "test-agreement", blockers);
        requireAtLeast(
            report.sensitivity().profileOrderAgreementPermille(),
            thresholds.minimumProfileOrderAgreementPermille(),
            "profile-order-agreement", blockers);
        requireAtLeast(
            report.sensitivity().leaveOneOutSelectionStabilityPermille(),
            thresholds.minimumLeaveOneOutStabilityPermille(),
            "leave-one-out-selection-stability", blockers);
        if (thresholds.requireStableTopCandidate()
                && !report.sensitivity().topCandidateStableAcrossProfiles()) {
            blockers.add("top-candidate-is-not-stable-across-profiles");
        }
        if (thresholds.requireNonEmptyParetoFront()
                && report.paretoCandidateIds().isEmpty()) {
            blockers.add("pareto-front-is-empty");
        }

        List<String> orderedBlockers = blockers.stream().distinct().sorted().toList();
        AcceptanceStatus status = orderedBlockers.isEmpty()
            ? AcceptanceStatus.ACCEPTED
            : AcceptanceStatus.REJECTED;
        String contentHash = hash(canonicalMaterial(
            report, thresholds, selectedMetric, status, orderedBlockers));
        return new Decision(
            SCHEMA,
            report.predictiveCorpusHash(),
            report.labeledEvaluationHash(),
            report.selectionHash(),
            report.contentHash(),
            report.selectedProfile(),
            thresholds,
            selectedMetric.calibrationAgreementPermille(),
            selectedMetric.testAgreementPermille(),
            report.sensitivity().profileOrderAgreementPermille(),
            report.sensitivity().leaveOneOutSelectionStabilityPermille(),
            report.sensitivity().topCandidateStableAcrossProfiles(),
            report.testRanking().size(),
            report.paretoCandidateIds().size(),
            status,
            orderedBlockers,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    private static void requireAtLeast(
        int actual,
        int minimum,
        String metric,
        List<String> blockers
    ) {
        if (actual < minimum) {
            blockers.add(metric + "=" + actual + "<" + minimum);
        }
    }

    private static String canonicalMaterial(
        CalibrationReport report,
        Thresholds thresholds,
        ProfileMetric selectedMetric,
        AcceptanceStatus status,
        List<String> blockers
    ) {
        return SCHEMA
            + "\npredictiveCorpus=" + report.predictiveCorpusHash()
            + "\nlabeledEvaluation=" + report.labeledEvaluationHash()
            + "\nselection=" + report.selectionHash()
            + "\nreport=" + report.contentHash()
            + "\nprofile=" + report.selectedProfile().name()
            + "\nthresholds=" + thresholds.canonicalMaterial()
            + "\ncalibrationAgreement="
            + selectedMetric.calibrationAgreementPermille()
            + "\ntestAgreement=" + selectedMetric.testAgreementPermille()
            + "\nprofileOrderAgreement="
            + report.sensitivity().profileOrderAgreementPermille()
            + "\nleaveOneOut="
            + report.sensitivity().leaveOneOutSelectionStabilityPermille()
            + "\ntopStable="
            + report.sensitivity().topCandidateStableAcrossProfiles()
            + "\ntestCases=" + report.testRanking().size()
            + "\nparetoSize=" + report.paretoCandidateIds().size()
            + "\nstatus=" + status.name()
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

    public enum AcceptanceStatus {
        ACCEPTED,
        REJECTED
    }

    /** Immutable thresholds fixed before TEST evaluation is interpreted. */
    public record Thresholds(
        int minimumTestCases,
        int minimumCalibrationAgreementPermille,
        int minimumTestAgreementPermille,
        int minimumProfileOrderAgreementPermille,
        int minimumLeaveOneOutStabilityPermille,
        boolean requireStableTopCandidate,
        boolean requireNonEmptyParetoFront
    ) {
        public Thresholds {
            if (minimumTestCases < 2) {
                throw new IllegalArgumentException("minimumTestCases must be at least 2");
            }
            requirePermille(minimumCalibrationAgreementPermille,
                "minimumCalibrationAgreementPermille");
            requirePermille(minimumTestAgreementPermille,
                "minimumTestAgreementPermille");
            requirePermille(minimumProfileOrderAgreementPermille,
                "minimumProfileOrderAgreementPermille");
            requirePermille(minimumLeaveOneOutStabilityPermille,
                "minimumLeaveOneOutStabilityPermille");
        }

        public static Thresholds conservativeDefault() {
            return new Thresholds(4, 650, 600, 700, 600, false, true);
        }

        String canonicalMaterial() {
            return minimumTestCases + "|"
                + minimumCalibrationAgreementPermille + "|"
                + minimumTestAgreementPermille + "|"
                + minimumProfileOrderAgreementPermille + "|"
                + minimumLeaveOneOutStabilityPermille + "|"
                + requireStableTopCandidate + "|"
                + requireNonEmptyParetoFront;
        }
    }

    public record Decision(
        String schema,
        String predictiveCorpusHash,
        String labeledEvaluationHash,
        String selectionHash,
        String calibrationReportHash,
        InterestingnessProfile selectedProfile,
        Thresholds thresholds,
        int calibrationAgreementPermille,
        int testAgreementPermille,
        int profileOrderAgreementPermille,
        int leaveOneOutSelectionStabilityPermille,
        boolean topCandidateStableAcrossProfiles,
        int testCaseCount,
        int paretoCandidateCount,
        AcceptanceStatus status,
        List<String> blockers,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public Decision {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported acceptance schema");
            }
            requireSha256(predictiveCorpusHash, "predictiveCorpusHash");
            requireSha256(labeledEvaluationHash, "labeledEvaluationHash");
            requireSha256(selectionHash, "selectionHash");
            requireSha256(calibrationReportHash, "calibrationReportHash");
            Objects.requireNonNull(selectedProfile, "selectedProfile");
            Objects.requireNonNull(thresholds, "thresholds");
            requirePermille(calibrationAgreementPermille,
                "calibrationAgreementPermille");
            requirePermille(testAgreementPermille, "testAgreementPermille");
            requirePermille(profileOrderAgreementPermille,
                "profileOrderAgreementPermille");
            requirePermille(leaveOneOutSelectionStabilityPermille,
                "leaveOneOutSelectionStabilityPermille");
            if (testCaseCount < 0 || paretoCandidateCount < 0) {
                throw new IllegalArgumentException("counts must be non-negative");
            }
            Objects.requireNonNull(status, "status");
            blockers = blockers == null
                ? List.of()
                : blockers.stream().distinct().sorted().toList();
            if (!"NOT_EVALUATED".equals(promotionStatus)
                    || !"NOT_EVALUATED".equals(publicEvidenceStatus)) {
                throw new IllegalArgumentException(
                    "acceptance cannot perform promotion or public evidence");
            }
            requireSha256(contentHash, "contentHash");
        }

        public boolean accepted() {
            return status == AcceptanceStatus.ACCEPTED;
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("predictiveCorpusHash", predictiveCorpusHash)
                .property("labeledEvaluationHash", labeledEvaluationHash)
                .property("selectionHash", selectionHash)
                .property("calibrationReportHash", calibrationReportHash)
                .property("selectedProfile", selectedProfile.name())
                .object("thresholds", object -> object
                    .property("minimumTestCases", thresholds.minimumTestCases())
                    .property("minimumCalibrationAgreementPermille",
                        thresholds.minimumCalibrationAgreementPermille())
                    .property("minimumTestAgreementPermille",
                        thresholds.minimumTestAgreementPermille())
                    .property("minimumProfileOrderAgreementPermille",
                        thresholds.minimumProfileOrderAgreementPermille())
                    .property("minimumLeaveOneOutStabilityPermille",
                        thresholds.minimumLeaveOneOutStabilityPermille())
                    .property("requireStableTopCandidate",
                        thresholds.requireStableTopCandidate())
                    .property("requireNonEmptyParetoFront",
                        thresholds.requireNonEmptyParetoFront()))
                .property("calibrationAgreementPermille",
                    calibrationAgreementPermille)
                .property("testAgreementPermille", testAgreementPermille)
                .property("profileOrderAgreementPermille",
                    profileOrderAgreementPermille)
                .property("leaveOneOutSelectionStabilityPermille",
                    leaveOneOutSelectionStabilityPermille)
                .property("topCandidateStableAcrossProfiles",
                    topCandidateStableAcrossProfiles)
                .property("testCaseCount", testCaseCount)
                .property("paretoCandidateCount", paretoCandidateCount)
                .property("status", status.name())
                .stringArray("blockers", blockers)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static void requirePermille(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " must be in [0,1000]");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
