package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.CrossFamilyBridgeHypothesisBuilder.BridgeHypothesis;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.CounterexampleEvidence;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationPlan;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldout;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldout;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleBudget;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates an already formed bridge hypothesis on fresh per-family suites.
 *
 * <p>Formation and validation are separate APIs. This evaluator cannot alter the
 * bridge relation and reports each family independently, including incomplete
 * suites, counterexamples and inferred assumptions.</p>
 */
public final class CrossFamilyBridgeTransferEvaluator {
    public static final String SCHEMA = "regelsuche.cross-family-bridge-transfer/v1";

    private final FamilyEvaluator familyEvaluator;

    public CrossFamilyBridgeTransferEvaluator() {
        OpenTargetConjectureEvaluator evaluator = new OpenTargetConjectureEvaluator();
        this.familyEvaluator = evaluator::evaluate;
    }

    CrossFamilyBridgeTransferEvaluator(FamilyEvaluator familyEvaluator) {
        this.familyEvaluator = Objects.requireNonNull(familyEvaluator, "familyEvaluator");
    }

    public TransferReport evaluate(
        BridgeHypothesis hypothesis,
        List<FamilySuite> suites
    ) {
        Objects.requireNonNull(hypothesis, "hypothesis");
        List<FamilySuite> orderedSuites = orderedSuites(suites);
        ensureUniqueFamilies(orderedSuites);

        TreeSet<String> trainingFamilies = new TreeSet<>(hypothesis.trainingFamilies());
        List<FamilyResult> results = new ArrayList<>();
        for (FamilySuite suite : orderedSuites) {
            results.add(evaluateSuite(hypothesis, trainingFamilies, suite));
        }
        addMissingFormationFamilies(trainingFamilies, orderedSuites, results);
        results.sort(Comparator.comparing(FamilyResult::familyId)
            .thenComparing(result -> result.role().name()));

        List<String> heldOutFamilies = orderedSuites.stream()
            .filter(suite -> suite.role() == FamilyRole.HELD_OUT)
            .map(FamilySuite::familyId)
            .filter(family -> !trainingFamilies.contains(family))
            .distinct()
            .sorted()
            .toList();
        List<String> blockers = aggregateBlockers(
            trainingFamilies, heldOutFamilies, results);
        TransferStatus status = transferStatus(
            trainingFamilies, heldOutFamilies, results, blockers);
        String contentHash = hash(canonicalMaterial(
            hypothesis,
            heldOutFamilies,
            status,
            results,
            blockers));
        return new TransferReport(
            SCHEMA,
            hypothesis.hypothesisId(),
            hypothesis.sourceClusterId(),
            hypothesis.formationHash(),
            false,
            hypothesis.trainingFamilies(),
            heldOutFamilies,
            status,
            results,
            blockers,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    private FamilyResult evaluateSuite(
        BridgeHypothesis hypothesis,
        Set<String> trainingFamilies,
        FamilySuite suite
    ) {
        List<String> planBlockers = planBlockers(trainingFamilies, suite);
        if (!planBlockers.isEmpty()) {
            return FamilyResult.incomplete(suite, planBlockers);
        }
        EvaluationPlan plan = new EvaluationPlan(
            suite.revision(),
            suite.positiveHoldouts(),
            suite.negativeHoldouts(),
            suite.counterexampleBudget());
        EvaluationReport report = familyEvaluator.evaluate(
            hypothesis.conjecture(), plan);
        return FamilyResult.from(suite, report);
    }

    private static List<String> planBlockers(
        Set<String> trainingFamilies,
        FamilySuite suite
    ) {
        List<String> blockers = new ArrayList<>();
        if (suite.role() == FamilyRole.FORMATION
                && !trainingFamilies.contains(suite.familyId())) {
            blockers.add("formation suite is not part of the hypothesis training families");
        }
        if (suite.role() == FamilyRole.HELD_OUT
                && trainingFamilies.contains(suite.familyId())) {
            blockers.add("held-out suite reuses a hypothesis training family");
        }
        if (suite.positiveHoldouts().isEmpty()) {
            blockers.add("positive holdouts missing");
        }
        if (suite.negativeHoldouts().isEmpty()) {
            blockers.add("negative holdouts missing");
        }
        return List.copyOf(blockers);
    }

    private static void addMissingFormationFamilies(
        Set<String> trainingFamilies,
        List<FamilySuite> suites,
        List<FamilyResult> results
    ) {
        Set<String> suppliedFormation = suites.stream()
            .filter(suite -> suite.role() == FamilyRole.FORMATION)
            .map(FamilySuite::familyId)
            .collect(java.util.stream.Collectors.toSet());
        trainingFamilies.stream()
            .filter(family -> !suppliedFormation.contains(family))
            .forEach(family -> results.add(FamilyResult.missingFormation(family)));
    }

    private static List<String> aggregateBlockers(
        Set<String> trainingFamilies,
        List<String> heldOutFamilies,
        List<FamilyResult> results
    ) {
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (heldOutFamilies.isEmpty()) {
            blockers.add("held-out family suite missing");
        }
        for (String family : trainingFamilies) {
            boolean present = results.stream().anyMatch(result ->
                result.familyId().equals(family)
                    && result.role() == FamilyRole.FORMATION);
            if (!present) {
                blockers.add("formation family suite missing: " + family);
            }
        }
        results.stream()
            .filter(result -> !result.accepted())
            .forEach(result -> {
                if (result.blockers().isEmpty()) {
                    blockers.add(result.familyId() + ": status=" + result.status().name());
                } else {
                    result.blockers().forEach(blocker ->
                        blockers.add(result.familyId() + ": " + blocker));
                }
            });
        return blockers.stream().sorted().toList();
    }

    private static TransferStatus transferStatus(
        Set<String> trainingFamilies,
        List<String> heldOutFamilies,
        List<FamilyResult> results,
        List<String> blockers
    ) {
        boolean incomplete = heldOutFamilies.isEmpty()
            || results.stream().anyMatch(result ->
                result.status() == FamilyStatus.INCOMPLETE_SUITE);
        if (incomplete) {
            return TransferStatus.INCOMPLETE_EVIDENCE;
        }
        boolean formationAccepted = trainingFamilies.stream().allMatch(family ->
            results.stream().anyMatch(result ->
                result.familyId().equals(family)
                    && result.role() == FamilyRole.FORMATION
                    && result.accepted()));
        if (!formationAccepted) {
            return TransferStatus.REJECTED_FORMATION_FAMILY;
        }
        boolean heldOutAccepted = heldOutFamilies.stream().allMatch(family ->
            results.stream().anyMatch(result ->
                result.familyId().equals(family)
                    && result.role() == FamilyRole.HELD_OUT
                    && result.accepted()));
        if (!heldOutAccepted) {
            return TransferStatus.REJECTED_HELD_OUT_TRANSFER;
        }
        return blockers.isEmpty()
            ? TransferStatus.ACCEPTED_CROSS_FAMILY_TRANSFER
            : TransferStatus.INCOMPLETE_EVIDENCE;
    }

    private static List<FamilySuite> orderedSuites(List<FamilySuite> suites) {
        return suites == null
            ? List.of()
            : suites.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(FamilySuite::familyId)
                    .thenComparing(suite -> suite.role().name()))
                .toList();
    }

    private static void ensureUniqueFamilies(List<FamilySuite> suites) {
        Set<String> families = new LinkedHashSet<>();
        for (FamilySuite suite : suites) {
            if (!families.add(suite.familyId())) {
                throw new IllegalArgumentException(
                    "duplicate family suite: " + suite.familyId());
            }
        }
    }

    private static String canonicalMaterial(
        BridgeHypothesis hypothesis,
        List<String> heldOutFamilies,
        TransferStatus status,
        List<FamilyResult> results,
        List<String> blockers
    ) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\nhypothesis=").append(hypothesis.hypothesisId())
            .append("\ncluster=").append(hypothesis.sourceClusterId())
            .append("\nformationHash=").append(hypothesis.formationHash())
            .append("\ntargetProvided=false")
            .append("\ntrainingFamilies=").append(hypothesis.trainingFamilies())
            .append("\nheldOutFamilies=").append(heldOutFamilies)
            .append("\nstatus=").append(status.name())
            .append("\nblockers=").append(blockers);
        results.forEach(result -> material.append("\nfamily=")
            .append(result.canonicalMaterial()));
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

    @FunctionalInterface
    interface FamilyEvaluator {
        EvaluationReport evaluate(OpenTargetConjecture conjecture, EvaluationPlan plan);
    }

    public enum FamilyRole {
        FORMATION,
        HELD_OUT
    }

    public enum FamilyStatus {
        ACCEPTED,
        REJECTED,
        INCONCLUSIVE,
        COMPILATION_REJECTED,
        INCOMPLETE_SUITE
    }

    public enum TransferStatus {
        ACCEPTED_CROSS_FAMILY_TRANSFER,
        REJECTED_FORMATION_FAMILY,
        REJECTED_HELD_OUT_TRANSFER,
        INCOMPLETE_EVIDENCE
    }

    public record FamilySuite(
        String familyId,
        FamilyRole role,
        String revision,
        List<PositiveHoldout> positiveHoldouts,
        List<NegativeHoldout> negativeHoldouts,
        CounterexampleBudget counterexampleBudget
    ) {
        public FamilySuite {
            requireText(familyId, "familyId");
            Objects.requireNonNull(role, "role");
            requireText(revision, "revision");
            positiveHoldouts = positiveHoldouts == null
                ? List.of()
                : positiveHoldouts.stream()
                    .sorted(Comparator.comparing(PositiveHoldout::id))
                    .toList();
            negativeHoldouts = negativeHoldouts == null
                ? List.of()
                : negativeHoldouts.stream()
                    .sorted(Comparator.comparing(NegativeHoldout::id))
                    .toList();
            Objects.requireNonNull(counterexampleBudget, "counterexampleBudget");
            Set<String> ids = new LinkedHashSet<>();
            positiveHoldouts.forEach(holdout -> addHoldoutId(ids, holdout.id()));
            negativeHoldouts.forEach(holdout -> addHoldoutId(ids, holdout.id()));
        }

        private static void addHoldoutId(Set<String> ids, String id) {
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate holdout ID: " + id);
            }
        }
    }

    public record FamilyResult(
        String familyId,
        FamilyRole role,
        FamilyStatus status,
        int configuredPositiveHoldouts,
        int executedPositiveHoldouts,
        int skippedPositiveHoldouts,
        int configuredNegativeHoldouts,
        int executedNegativeHoldouts,
        int skippedNegativeHoldouts,
        List<String> failedPositiveHoldouts,
        List<String> failedNegativeHoldouts,
        String dynamicRuleId,
        String provenanceHash,
        String counterexampleStatus,
        List<String> counterexampleAttemptedSources,
        List<String> inferredAssumptions,
        List<String> counterexampleAssignments,
        String counterexampleLeftValue,
        String counterexampleRightValue,
        String counterexampleExplanation,
        List<String> blockers
    ) {
        public FamilyResult {
            requireText(familyId, "familyId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(status, "status");
            failedPositiveHoldouts = sortedDistinct(failedPositiveHoldouts);
            failedNegativeHoldouts = sortedDistinct(failedNegativeHoldouts);
            dynamicRuleId = dynamicRuleId == null ? "" : dynamicRuleId;
            provenanceHash = provenanceHash == null ? "" : provenanceHash;
            counterexampleStatus = counterexampleStatus == null
                ? "NOT_RUN"
                : counterexampleStatus;
            counterexampleAttemptedSources = sortedDistinct(
                counterexampleAttemptedSources);
            inferredAssumptions = sortedDistinct(inferredAssumptions);
            counterexampleAssignments = sortedDistinct(counterexampleAssignments);
            counterexampleLeftValue = counterexampleLeftValue == null
                ? ""
                : counterexampleLeftValue;
            counterexampleRightValue = counterexampleRightValue == null
                ? ""
                : counterexampleRightValue;
            counterexampleExplanation = counterexampleExplanation == null
                ? ""
                : counterexampleExplanation;
            blockers = sortedDistinct(blockers);
        }

        static FamilyResult from(FamilySuite suite, EvaluationReport report) {
            List<String> accountingBlockers = accountingBlockers(suite, report);
            FamilyStatus status = accountingBlockers.isEmpty()
                ? mapStatus(report.status())
                : FamilyStatus.INCOMPLETE_SUITE;
            List<String> blockers = new ArrayList<>(report.blockers());
            blockers.addAll(accountingBlockers);
            if (status != FamilyStatus.ACCEPTED && blockers.isEmpty()) {
                blockers.add("evaluation-status=" + report.status().name());
            }
            CounterexampleEvidence counterexample = report.counterexample();
            return new FamilyResult(
                suite.familyId(),
                suite.role(),
                status,
                report.configuredPositiveHoldouts(),
                report.executedPositiveHoldouts(),
                report.skippedPositiveHoldouts(),
                report.configuredNegativeHoldouts(),
                report.executedNegativeHoldouts(),
                report.skippedNegativeHoldouts(),
                report.positiveResults().stream()
                    .filter(result -> !result.passed())
                    .map(PositiveHoldoutResult::id)
                    .toList(),
                report.negativeResults().stream()
                    .filter(result -> !result.passed())
                    .map(NegativeHoldoutResult::id)
                    .toList(),
                report.dynamicRuleId(),
                report.provenanceHash(),
                counterexample.status(),
                counterexample.attemptedSources(),
                counterexample.inferredAssumptions(),
                counterexample.assignments(),
                counterexample.leftValue(),
                counterexample.rightValue(),
                counterexample.explanation(),
                blockers);
        }

        static FamilyResult incomplete(FamilySuite suite, List<String> blockers) {
            return new FamilyResult(
                suite.familyId(),
                suite.role(),
                FamilyStatus.INCOMPLETE_SUITE,
                suite.positiveHoldouts().size(),
                0,
                suite.positiveHoldouts().size(),
                suite.negativeHoldouts().size(),
                0,
                suite.negativeHoldouts().size(),
                List.of(),
                List.of(),
                "",
                "",
                "NOT_RUN",
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                "family suite was not executed",
                blockers);
        }

        static FamilyResult missingFormation(String familyId) {
            return new FamilyResult(
                familyId,
                FamilyRole.FORMATION,
                FamilyStatus.INCOMPLETE_SUITE,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                "",
                "",
                "NOT_RUN",
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                "formation family suite was not supplied",
                List.of("formation family suite missing"));
        }

        public boolean accepted() {
            return status == FamilyStatus.ACCEPTED;
        }

        String canonicalMaterial() {
            return familyId + '|'
                + role.name() + '|'
                + status.name() + '|'
                + configuredPositiveHoldouts + '|'
                + executedPositiveHoldouts + '|'
                + skippedPositiveHoldouts + '|'
                + configuredNegativeHoldouts + '|'
                + executedNegativeHoldouts + '|'
                + skippedNegativeHoldouts + '|'
                + failedPositiveHoldouts + '|'
                + failedNegativeHoldouts + '|'
                + dynamicRuleId + '|'
                + provenanceHash + '|'
                + counterexampleStatus + '|'
                + counterexampleAttemptedSources + '|'
                + inferredAssumptions + '|'
                + counterexampleAssignments + '|'
                + counterexampleLeftValue + '|'
                + counterexampleRightValue + '|'
                + counterexampleExplanation + '|'
                + blockers;
        }

        private static List<String> accountingBlockers(
            FamilySuite suite,
            EvaluationReport report
        ) {
            List<String> blockers = new ArrayList<>();
            if (report.configuredPositiveHoldouts()
                    != suite.positiveHoldouts().size()
                    || report.configuredNegativeHoldouts()
                    != suite.negativeHoldouts().size()) {
                blockers.add("configured holdout counts differ from the family suite");
            }
            if (report.executedPositiveHoldouts()
                    + report.skippedPositiveHoldouts()
                    != report.configuredPositiveHoldouts()
                    || report.executedNegativeHoldouts()
                    + report.skippedNegativeHoldouts()
                    != report.configuredNegativeHoldouts()) {
                blockers.add("executed/skipped holdout accounting is incomplete");
            }
            if (report.executedPositiveHoldouts() != report.positiveResults().size()
                    || report.executedNegativeHoldouts()
                    != report.negativeResults().size()) {
                blockers.add("executed holdout counts differ from result lists");
            }
            return List.copyOf(blockers);
        }

        private static FamilyStatus mapStatus(EvaluationStatus status) {
            return switch (status) {
                case ACCEPTED_FOR_PROOF -> FamilyStatus.ACCEPTED;
                case REJECTED -> FamilyStatus.REJECTED;
                case INCONCLUSIVE -> FamilyStatus.INCONCLUSIVE;
                case COMPILATION_REJECTED -> FamilyStatus.COMPILATION_REJECTED;
            };
        }
    }

    public record TransferReport(
        String schema,
        String hypothesisId,
        String sourceClusterId,
        String formationHash,
        boolean targetProvided,
        List<String> trainingFamilies,
        List<String> heldOutFamilies,
        TransferStatus status,
        List<FamilyResult> familyResults,
        List<String> blockers,
        String noveltyStatus,
        String proofStatus,
        String ablationStatus,
        String interestingnessStatus,
        String contentHash
    ) {
        public TransferReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported bridge-transfer schema");
            }
            requireText(hypothesisId, "hypothesisId");
            requireText(sourceClusterId, "sourceClusterId");
            requireSha256(formationHash, "formationHash");
            if (targetProvided) {
                throw new IllegalArgumentException(
                    "bridge transfer evidence must remain target-free");
            }
            trainingFamilies = sortedDistinct(trainingFamilies);
            heldOutFamilies = sortedDistinct(heldOutFamilies);
            Objects.requireNonNull(status, "status");
            familyResults = familyResults == null
                ? List.of()
                : familyResults.stream()
                    .sorted(Comparator.comparing(FamilyResult::familyId)
                        .thenComparing(result -> result.role().name()))
                    .toList();
            blockers = sortedDistinct(blockers);
            requireNotEvaluated(noveltyStatus, "noveltyStatus");
            requireNotEvaluated(proofStatus, "proofStatus");
            requireNotEvaluated(ablationStatus, "ablationStatus");
            requireNotEvaluated(interestingnessStatus, "interestingnessStatus");
            requireSha256(contentHash, "contentHash");
        }

        public boolean accepted() {
            return status == TransferStatus.ACCEPTED_CROSS_FAMILY_TRANSFER;
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("hypothesisId", hypothesisId)
                .property("sourceClusterId", sourceClusterId)
                .property("formationHash", formationHash)
                .property("targetProvided", targetProvided)
                .stringArray("trainingFamilies", trainingFamilies)
                .stringArray("heldOutFamilies", heldOutFamilies)
                .property("status", status.name())
                .property("noveltyStatus", noveltyStatus)
                .property("proofStatus", proofStatus)
                .property("ablationStatus", ablationStatus)
                .property("interestingnessStatus", interestingnessStatus)
                .stringArray("blockers", blockers)
                .array("familyResults", array -> familyResults.forEach(result ->
                    array.objectValue(object -> writeFamilyResult(object, result))))
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

        private static void writeFamilyResult(JsonWriter json, FamilyResult result) {
            json.property("familyId", result.familyId())
                .property("role", result.role().name())
                .property("status", result.status().name())
                .property("configuredPositiveHoldouts", result.configuredPositiveHoldouts())
                .property("executedPositiveHoldouts", result.executedPositiveHoldouts())
                .property("skippedPositiveHoldouts", result.skippedPositiveHoldouts())
                .property("configuredNegativeHoldouts", result.configuredNegativeHoldouts())
                .property("executedNegativeHoldouts", result.executedNegativeHoldouts())
                .property("skippedNegativeHoldouts", result.skippedNegativeHoldouts())
                .stringArray("failedPositiveHoldouts", result.failedPositiveHoldouts())
                .stringArray("failedNegativeHoldouts", result.failedNegativeHoldouts())
                .property("dynamicRuleId", result.dynamicRuleId())
                .property("provenanceHash", result.provenanceHash())
                .property("counterexampleStatus", result.counterexampleStatus())
                .stringArray("counterexampleAttemptedSources",
                    result.counterexampleAttemptedSources())
                .stringArray("inferredAssumptions", result.inferredAssumptions())
                .stringArray("counterexampleAssignments",
                    result.counterexampleAssignments())
                .property("counterexampleLeftValue", result.counterexampleLeftValue())
                .property("counterexampleRightValue", result.counterexampleRightValue())
                .property("counterexampleExplanation", result.counterexampleExplanation())
                .stringArray("blockers", result.blockers());
        }
    }

    private static List<String> sortedDistinct(List<String> values) {
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

    private static void requireNotEvaluated(String value, String name) {
        if (!"NOT_EVALUATED".equals(value)) {
            throw new IllegalArgumentException(name + " must be NOT_EVALUATED");
        }
    }
}