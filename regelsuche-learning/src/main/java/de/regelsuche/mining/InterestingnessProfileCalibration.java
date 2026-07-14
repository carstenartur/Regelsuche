package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.InterestingnessAssessment.ComponentContribution;
import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusReport;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.FrozenCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Selects an interestingness profile on CALIBRATION only and evaluates it once
 * on structurally and family-held-out TEST cases.
 */
public final class InterestingnessProfileCalibration {
    public static final String SCHEMA = "regelsuche.interestingness-profile-calibration/v1";
    private static final List<String> PARETO_AXES = List.of(
        "structuralSurprise",
        "crossFamilyTransfer",
        "pairedUtility",
        "reusability");

    public CalibrationReport calibrate(
        CorpusReport corpus,
        List<CaseProfiles> suppliedProfiles
    ) {
        Objects.requireNonNull(corpus, "corpus");
        List<CaseProfiles> profiles = orderedProfiles(suppliedProfiles);
        Map<String, CaseProfiles> byCaseId = validateAndIndex(corpus, profiles);
        List<ScoredCase> calibration = scoredCases(
            corpus.split(CorpusSplit.CALIBRATION), byCaseId);
        List<ScoredCase> test = scoredCases(corpus.split(CorpusSplit.TEST), byCaseId);

        List<ProfileMetric> metrics = profileMetrics(calibration, test);
        InterestingnessProfile selected = select(metrics);
        List<RankedCase> testRanking = ranking(test, selected);
        List<String> pareto = paretoFront(test, selected);
        Sensitivity sensitivity = sensitivity(calibration, test, selected);
        String selectionHash = hash(selectionMaterial(
            corpus, calibration, metrics, selected));
        String contentHash = hash(reportMaterial(
            corpus,
            selected,
            metrics,
            testRanking,
            pareto,
            sensitivity,
            selectionHash));
        return new CalibrationReport(
            SCHEMA,
            corpus.predictiveCorpusHash(),
            corpus.labeledEvaluationHash(),
            selected,
            metrics,
            testRanking,
            pareto,
            sensitivity,
            selectionHash,
            contentHash);
    }

    private static List<CaseProfiles> orderedProfiles(
        List<CaseProfiles> suppliedProfiles
    ) {
        Objects.requireNonNull(suppliedProfiles, "suppliedProfiles");
        if (suppliedProfiles.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("case profiles must not contain null");
        }
        return suppliedProfiles.stream()
            .sorted(Comparator.comparing(CaseProfiles::caseId))
            .toList();
    }

    private static Map<String, CaseProfiles> validateAndIndex(
        CorpusReport corpus,
        List<CaseProfiles> profiles
    ) {
        Map<String, CaseProfiles> byCaseId = new LinkedHashMap<>();
        for (CaseProfiles item : profiles) {
            if (byCaseId.putIfAbsent(item.caseId(), item) != null) {
                throw new IllegalArgumentException(
                    "duplicate profile case: " + item.caseId());
            }
            validateProfilePair(item);
        }
        Set<String> expected = corpus.cases().stream()
            .map(FrozenCase::caseId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!expected.equals(new TreeSet<>(byCaseId.keySet()))) {
            throw new IllegalArgumentException(
                "profile cases must match the frozen corpus exactly");
        }
        for (FrozenCase item : corpus.cases()) {
            CaseProfiles caseProfiles = byCaseId.get(item.caseId());
            if (!item.candidateId().equals(caseProfiles.candidateId())) {
                throw new IllegalArgumentException(
                    "candidate/profile mismatch for case: " + item.caseId());
            }
            if (!item.assessmentContentHash().equals(
                    caseProfiles.theoryDiscovery().contentHash())) {
                throw new IllegalArgumentException(
                    "frozen baseline assessment mismatch for case: " + item.caseId());
            }
        }
        return Map.copyOf(byCaseId);
    }

    private static void validateProfilePair(CaseProfiles item) {
        InterestingnessAssessment theory = item.theoryDiscovery();
        InterestingnessAssessment reuse = item.searchReuse();
        if (!item.candidateId().equals(theory.candidateId())
                || !item.candidateId().equals(reuse.candidateId())) {
            throw new IllegalArgumentException(
                "assessment candidate mismatch: " + item.caseId());
        }
        if (theory.profile() != InterestingnessProfile.THEORY_DISCOVERY
                || reuse.profile() != InterestingnessProfile.SEARCH_REUSE) {
            throw new IllegalArgumentException(
                "both interestingness profiles are required: " + item.caseId());
        }
        if (!theory.evidence().equals(reuse.evidence())
                || theory.eligibility() != reuse.eligibility()
                || theory.proofStatus() != reuse.proofStatus()
                || theory.counterexampleStatus() != reuse.counterexampleStatus()
                || theory.knownRuleSimilarityPermille()
                    != reuse.knownRuleSimilarityPermille()
                || theory.evidenceCompletenessPermille()
                    != reuse.evidenceCompletenessPermille()
                || theory.unresolvedRiskPenaltyPermille()
                    != reuse.unresolvedRiskPenaltyPermille()
                || theory.controlPenaltyPermille() != reuse.controlPenaltyPermille()
                || !rawComponents(theory).equals(rawComponents(reuse))) {
            throw new IllegalArgumentException(
                "profile comparison must share identical raw evidence: " + item.caseId());
        }
    }

    private static Map<String, Integer> rawComponents(
        InterestingnessAssessment assessment
    ) {
        Map<String, Integer> components = new TreeMap<>();
        assessment.contributions().forEach(contribution ->
            components.put(contribution.name(), contribution.rawPermille()));
        return Map.copyOf(components);
    }

    private static List<ScoredCase> scoredCases(
        List<FrozenCase> cases,
        Map<String, CaseProfiles> profiles
    ) {
        return cases.stream()
            .map(item -> new ScoredCase(item, profiles.get(item.caseId())))
            .sorted(Comparator.comparing(item -> item.frozenCase().caseId()))
            .toList();
    }

    private static List<ProfileMetric> profileMetrics(
        List<ScoredCase> calibration,
        List<ScoredCase> test
    ) {
        List<ProfileMetric> metrics = new ArrayList<>();
        for (InterestingnessProfile profile : InterestingnessProfile.values()) {
            metrics.add(new ProfileMetric(
                profile,
                pairwiseLabelAgreement(calibration, profile),
                pairwiseLabelAgreement(test, profile)));
        }
        return List.copyOf(metrics);
    }

    private static InterestingnessProfile select(List<ProfileMetric> metrics) {
        InterestingnessProfile selected = InterestingnessProfile.values()[0];
        int best = -1;
        for (ProfileMetric metric : metrics) {
            if (metric.calibrationAgreementPermille() > best) {
                selected = metric.profile();
                best = metric.calibrationAgreementPermille();
            }
        }
        return selected;
    }

    private static int pairwiseLabelAgreement(
        List<ScoredCase> cases,
        InterestingnessProfile profile
    ) {
        int points = 0;
        int pairs = 0;
        for (int left = 0; left < cases.size(); left++) {
            for (int right = left + 1; right < cases.size(); right++) {
                ScoredCase first = cases.get(left);
                ScoredCase second = cases.get(right);
                int desired = Integer.compare(
                    first.frozenCase().consensusRelevancePermille(),
                    second.frozenCase().consensusRelevancePermille());
                if (desired == 0) {
                    continue;
                }
                int actual = Integer.compare(
                    first.assessment(profile).totalPermille(),
                    second.assessment(profile).totalPermille());
                points += actual == desired ? 1000 : actual == 0 ? 500 : 0;
                pairs++;
            }
        }
        return pairs == 0 ? 0 : points / pairs;
    }

    private static List<RankedCase> ranking(
        List<ScoredCase> cases,
        InterestingnessProfile profile
    ) {
        List<ScoredCase> ordered = cases.stream()
            .sorted(Comparator
                .comparing((ScoredCase item) -> item.assessment(profile))
                .thenComparing(item -> item.frozenCase().caseId()))
            .toList();
        List<RankedCase> ranking = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            ScoredCase item = ordered.get(index);
            InterestingnessAssessment assessment = item.assessment(profile);
            ranking.add(new RankedCase(
                index + 1,
                item.frozenCase().caseId(),
                item.frozenCase().candidateId(),
                item.frozenCase().consensusRelevancePermille(),
                assessment.eligibility(),
                assessment.totalPermille(),
                assessment.contentHash(),
                rawComponents(assessment)));
        }
        return List.copyOf(ranking);
    }

    private static List<String> paretoFront(
        List<ScoredCase> cases,
        InterestingnessProfile profile
    ) {
        List<ScoredCase> eligible = cases.stream()
            .filter(item -> item.assessment(profile).eligibility()
                == Eligibility.RANKABLE_COMPLETE)
            .toList();
        return eligible.stream()
            .filter(candidate -> eligible.stream().noneMatch(other ->
                other != candidate && dominates(other, candidate, profile)))
            .map(item -> item.frozenCase().candidateId())
            .distinct()
            .sorted()
            .toList();
    }

    private static boolean dominates(
        ScoredCase left,
        ScoredCase right,
        InterestingnessProfile profile
    ) {
        Map<String, Integer> leftComponents = rawComponents(left.assessment(profile));
        Map<String, Integer> rightComponents = rawComponents(right.assessment(profile));
        boolean strictlyBetter = false;
        for (String axis : PARETO_AXES) {
            int leftValue = leftComponents.getOrDefault(axis, 0);
            int rightValue = rightComponents.getOrDefault(axis, 0);
            if (leftValue < rightValue) {
                return false;
            }
            strictlyBetter |= leftValue > rightValue;
        }
        return strictlyBetter;
    }

    private static Sensitivity sensitivity(
        List<ScoredCase> calibration,
        List<ScoredCase> test,
        InterestingnessProfile selected
    ) {
        int profileOrderAgreement = pairwiseProfileAgreement(test);
        int leaveOneOut = leaveOneOutStability(calibration, selected);
        boolean topStable = topCandidate(test, InterestingnessProfile.THEORY_DISCOVERY)
            .equals(topCandidate(test, InterestingnessProfile.SEARCH_REUSE));
        return new Sensitivity(profileOrderAgreement, leaveOneOut, topStable);
    }

    private static int pairwiseProfileAgreement(List<ScoredCase> cases) {
        int points = 0;
        int pairs = 0;
        for (int left = 0; left < cases.size(); left++) {
            for (int right = left + 1; right < cases.size(); right++) {
                int theory = compare(cases.get(left), cases.get(right),
                    InterestingnessProfile.THEORY_DISCOVERY);
                int reuse = compare(cases.get(left), cases.get(right),
                    InterestingnessProfile.SEARCH_REUSE);
                points += theory == reuse ? 1000 : theory == 0 || reuse == 0 ? 500 : 0;
                pairs++;
            }
        }
        return pairs == 0 ? 0 : points / pairs;
    }

    private static int compare(
        ScoredCase left,
        ScoredCase right,
        InterestingnessProfile profile
    ) {
        return Integer.compare(
            left.assessment(profile).totalPermille(),
            right.assessment(profile).totalPermille());
    }

    private static int leaveOneOutStability(
        List<ScoredCase> calibration,
        InterestingnessProfile selected
    ) {
        if (calibration.size() < 3) {
            return 0;
        }
        int stable = 0;
        for (int omitted = 0; omitted < calibration.size(); omitted++) {
            List<ScoredCase> subset = new ArrayList<>(calibration);
            subset.remove(omitted);
            InterestingnessProfile subsetSelection = select(profileMetrics(subset, List.of()));
            if (subsetSelection == selected) {
                stable++;
            }
        }
        return stable * 1000 / calibration.size();
    }

    private static String topCandidate(
        List<ScoredCase> cases,
        InterestingnessProfile profile
    ) {
        return cases.stream()
            .min(Comparator
                .comparing((ScoredCase item) -> item.assessment(profile))
                .thenComparing(item -> item.frozenCase().candidateId()))
            .map(item -> item.frozenCase().candidateId())
            .orElse("");
    }

    private static String selectionMaterial(
        CorpusReport corpus,
        List<ScoredCase> calibration,
        List<ProfileMetric> metrics,
        InterestingnessProfile selected
    ) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\npredictiveCorpus=").append(corpus.predictiveCorpusHash())
            .append("\nselected=").append(selected.name());
        calibration.forEach(item -> material.append("\ncalibration=")
            .append(item.frozenCase().caseId()).append('|')
            .append(item.frozenCase().consensusRelevancePermille()).append('|')
            .append(item.profiles().theoryDiscovery().contentHash()).append('|')
            .append(item.profiles().searchReuse().contentHash()));
        metrics.forEach(metric -> material.append("\nmetric=")
            .append(metric.profile().name()).append('|')
            .append(metric.calibrationAgreementPermille()));
        return material.toString();
    }

    private static String reportMaterial(
        CorpusReport corpus,
        InterestingnessProfile selected,
        List<ProfileMetric> metrics,
        List<RankedCase> testRanking,
        List<String> pareto,
        Sensitivity sensitivity,
        String selectionHash
    ) {
        StringBuilder material = new StringBuilder(selectionHash)
            .append("\nlabeledCorpus=").append(corpus.labeledEvaluationHash())
            .append("\nselected=").append(selected.name())
            .append("\npareto=").append(pareto)
            .append("\nsensitivity=").append(sensitivity.canonicalMaterial());
        metrics.forEach(metric -> material.append("\nprofile=")
            .append(metric.canonicalMaterial()));
        testRanking.forEach(item -> material.append("\ntest=")
            .append(item.canonicalMaterial()));
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

    public record CaseProfiles(
        String caseId,
        String candidateId,
        InterestingnessAssessment theoryDiscovery,
        InterestingnessAssessment searchReuse
    ) {
        public CaseProfiles {
            requireText(caseId, "caseId");
            requireText(candidateId, "candidateId");
            Objects.requireNonNull(theoryDiscovery, "theoryDiscovery");
            Objects.requireNonNull(searchReuse, "searchReuse");
        }
    }

    public record ProfileMetric(
        InterestingnessProfile profile,
        int calibrationAgreementPermille,
        int testAgreementPermille
    ) {
        public ProfileMetric {
            Objects.requireNonNull(profile, "profile");
            requirePermille(calibrationAgreementPermille,
                "calibrationAgreementPermille");
            requirePermille(testAgreementPermille, "testAgreementPermille");
        }

        String canonicalMaterial() {
            return profile.name() + '|'
                + calibrationAgreementPermille + '|'
                + testAgreementPermille;
        }
    }

    public record RankedCase(
        int rank,
        String caseId,
        String candidateId,
        int consensusRelevancePermille,
        Eligibility eligibility,
        int totalPermille,
        String assessmentContentHash,
        Map<String, Integer> rawComponents
    ) {
        public RankedCase {
            if (rank < 1) {
                throw new IllegalArgumentException("rank must be positive");
            }
            requireText(caseId, "caseId");
            requireText(candidateId, "candidateId");
            requirePermille(consensusRelevancePermille,
                "consensusRelevancePermille");
            Objects.requireNonNull(eligibility, "eligibility");
            if (totalPermille < -1000 || totalPermille > 1000) {
                throw new IllegalArgumentException("totalPermille must be in [-1000,1000]");
            }
            requireSha256(assessmentContentHash, "assessmentContentHash");
            rawComponents = rawComponents == null
                ? Map.of()
                : Map.copyOf(new TreeMap<>(rawComponents));
        }

        String canonicalMaterial() {
            return rank + "|" + caseId + "|" + candidateId + "|"
                + consensusRelevancePermille + "|" + eligibility.name() + "|"
                + totalPermille + "|" + assessmentContentHash + "|" + rawComponents;
        }
    }

    public record Sensitivity(
        int profileOrderAgreementPermille,
        int leaveOneOutSelectionStabilityPermille,
        boolean topCandidateStableAcrossProfiles
    ) {
        public Sensitivity {
            requirePermille(profileOrderAgreementPermille,
                "profileOrderAgreementPermille");
            requirePermille(leaveOneOutSelectionStabilityPermille,
                "leaveOneOutSelectionStabilityPermille");
        }

        String canonicalMaterial() {
            return profileOrderAgreementPermille + "|"
                + leaveOneOutSelectionStabilityPermille + "|"
                + topCandidateStableAcrossProfiles;
        }
    }

    public record CalibrationReport(
        String schema,
        String predictiveCorpusHash,
        String labeledEvaluationHash,
        InterestingnessProfile selectedProfile,
        List<ProfileMetric> profileMetrics,
        List<RankedCase> testRanking,
        List<String> paretoCandidateIds,
        Sensitivity sensitivity,
        String selectionHash,
        String contentHash
    ) {
        public CalibrationReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported profile-calibration schema");
            }
            requireSha256(predictiveCorpusHash, "predictiveCorpusHash");
            requireSha256(labeledEvaluationHash, "labeledEvaluationHash");
            Objects.requireNonNull(selectedProfile, "selectedProfile");
            profileMetrics = profileMetrics == null
                ? List.of()
                : profileMetrics.stream()
                    .sorted(Comparator.comparing(metric -> metric.profile().name()))
                    .toList();
            testRanking = testRanking == null
                ? List.of()
                : testRanking.stream().sorted(Comparator.comparingInt(RankedCase::rank)).toList();
            paretoCandidateIds = paretoCandidateIds == null
                ? List.of()
                : paretoCandidateIds.stream().distinct().sorted().toList();
            Objects.requireNonNull(sensitivity, "sensitivity");
            requireSha256(selectionHash, "selectionHash");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("predictiveCorpusHash", predictiveCorpusHash)
                .property("labeledEvaluationHash", labeledEvaluationHash)
                .property("selectedProfile", selectedProfile.name())
                .array("profileMetrics", array -> profileMetrics.forEach(metric ->
                    array.objectValue(object -> object
                        .property("profile", metric.profile().name())
                        .property("calibrationAgreementPermille",
                            metric.calibrationAgreementPermille())
                        .property("testAgreementPermille",
                            metric.testAgreementPermille()))))
                .array("testRanking", array -> testRanking.forEach(item ->
                    array.objectValue(object -> object
                        .property("rank", item.rank())
                        .property("caseId", item.caseId())
                        .property("candidateId", item.candidateId())
                        .property("consensusRelevancePermille",
                            item.consensusRelevancePermille())
                        .property("eligibility", item.eligibility().name())
                        .property("totalPermille", item.totalPermille())
                        .property("assessmentContentHash",
                            item.assessmentContentHash())
                        .object("rawComponents", components ->
                            item.rawComponents().forEach(components::property)))))
                .stringArray("paretoCandidateIds", paretoCandidateIds)
                .object("sensitivity", object -> object
                    .property("profileOrderAgreementPermille",
                        sensitivity.profileOrderAgreementPermille())
                    .property("leaveOneOutSelectionStabilityPermille",
                        sensitivity.leaveOneOutSelectionStabilityPermille())
                    .property("topCandidateStableAcrossProfiles",
                        sensitivity.topCandidateStableAcrossProfiles()))
                .property("selectionHash", selectionHash)
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

    private record ScoredCase(FrozenCase frozenCase, CaseProfiles profiles) {
        InterestingnessAssessment assessment(InterestingnessProfile profile) {
            return profile == InterestingnessProfile.THEORY_DISCOVERY
                ? profiles.theoryDiscovery()
                : profiles.searchReuse();
        }
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
}
