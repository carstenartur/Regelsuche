package de.regelsuche.docs;

import de.regelsuche.docs.HiddenRuleHoldoutPartition.SplitAudit;
import de.regelsuche.docs.HiddenRulePilotEvaluator.CandidateRelation;
import de.regelsuche.docs.HiddenRulePilotEvaluator.Evaluation;
import de.regelsuche.docs.HiddenRulePilotEvaluator.HiddenReference;
import de.regelsuche.docs.HiddenRulePilotRunner.AblationEvidence;
import de.regelsuche.docs.HiddenRulePilotRunner.CandidateValidationEvidence;
import de.regelsuche.docs.HiddenRulePilotRunner.CounterexampleEvidence;
import de.regelsuche.docs.HiddenRulePilotRunner.NegativeHoldoutResult;
import de.regelsuche.docs.HiddenRulePilotRunner.PositiveHoldoutResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Runs the post-hoc hidden-rule benchmark and emits deterministic raw evidence. */
public final class HiddenRulePilotCampaign {
    public static final String SCHEMA = "regelsuche.hidden-rule-benchmark/v2";
    public static final String RUNTIME_SCHEMA = "regelsuche.hidden-rule-benchmark-runtime/v1";

    private final HiddenRulePilotRunner runner = new HiddenRulePilotRunner();
    private final HiddenRulePilotEvaluator evaluator = new HiddenRulePilotEvaluator();
    private final HiddenRuleHoldoutPartition partition = new HiddenRuleHoldoutPartition();

    public PilotReport run(List<PilotCase> cases) {
        Objects.requireNonNull(cases, "cases");
        List<CaseReport> reports = cases.stream()
            .sorted(Comparator.comparing(pilotCase -> pilotCase.task().opaqueCaseId()))
            .map(this::runCase)
            .toList();
        return new PilotReport(SCHEMA, reports);
    }

    public Path write(Path output, PilotReport report) {
        return writeText(output, report.toJson());
    }

    public Path writeRuntime(Path output, PilotReport report) {
        return writeText(output, report.runtimeJson());
    }

    private static Path writeText(Path output, String content) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(content, "content");
        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, content, StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private CaseReport runCase(PilotCase pilotCase) {
        SplitAudit split = partition.audit(pilotCase.task());
        long started = System.nanoTime();
        RuntimeResult runtime = runner.run(pilotCase.task());
        long elapsedNanos = System.nanoTime() - started;
        Evaluation evaluation = evaluator.evaluate(
            pilotCase.task(), runtime, pilotCase.reference());
        LinkedHashSet<String> blockers = new LinkedHashSet<>(evaluation.blockers());
        if (!split.passed()) {
            blockers.add("train/holdout split collision");
        }
        boolean holdoutsComplete = runtime.holdouts().positives().size()
                == pilotCase.task().positiveHoldouts().size()
            && runtime.holdouts().negatives().size()
                == pilotCase.task().negativeHoldouts().size();
        if (!holdoutsComplete) {
            blockers.add("holdout evaluation incomplete");
        }
        boolean accepted = evaluation.pilotAccepted() && split.passed() && holdoutsComplete;
        return new CaseReport(
            pilotCase.task().opaqueCaseId(),
            evaluation.family(),
            runtime,
            split,
            evaluation,
            elapsedNanos,
            accepted,
            List.copyOf(blockers));
    }

    public record PilotCase(RuntimeTask task, HiddenReference reference) {
        public PilotCase {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(reference, "reference");
        }
    }

    /** elapsedNanos is serialized only into the explicitly non-canonical runtime report. */
    public record CaseReport(
        String opaqueCaseId,
        String family,
        RuntimeResult runtime,
        SplitAudit split,
        Evaluation evaluation,
        long elapsedNanos,
        boolean accepted,
        List<String> blockers
    ) {
        public CaseReport {
            blockers = List.copyOf(blockers);
        }

        public boolean holdoutsComplete() {
            return runtime.holdouts().positives().size() == split.positives().size()
                && runtime.holdouts().negatives().size() == split.negatives().size();
        }

        public boolean holdoutsPassed() {
            return holdoutsComplete() && runtime.holdouts().allPassed();
        }
    }

    public record PilotReport(String schema, List<CaseReport> cases) {
        public PilotReport {
            cases = List.copyOf(cases);
        }

        public int acceptedCases() {
            return (int) cases.stream().filter(CaseReport::accepted).count();
        }

        public int frozenCandidates() {
            return (int) cases.stream().filter(caseReport -> caseReport.runtime().frozen()).count();
        }

        public int materialAblations() {
            return (int) cases.stream()
                .filter(caseReport -> caseReport.evaluation().materialAblation())
                .count();
        }

        public int familyCount() {
            return (int) cases.stream().map(CaseReport::family).distinct().count();
        }

        public int rediscoveredCases() {
            return (int) cases.stream()
                .map(caseReport -> caseReport.evaluation().candidateRelation())
                .filter(relation -> relation != CandidateRelation.NONE
                    && relation != CandidateRelation.DIFFERENT)
                .count();
        }

        /** Number of adversarial negative holdouts configured in the audited benchmark. */
        public int negativeHoldouts() {
            return cases.stream().mapToInt(caseReport -> caseReport.split().negatives().size()).sum();
        }

        /** Number of negative holdouts actually executed after a candidate was compiled. */
        public int evaluatedNegativeHoldouts() {
            return cases.stream()
                .mapToInt(caseReport -> caseReport.runtime().holdouts().negatives().size())
                .sum();
        }

        public int skippedNegativeHoldouts() {
            return negativeHoldouts() - evaluatedNegativeHoldouts();
        }

        public int falsePositiveHoldouts() {
            return (int) cases.stream()
                .flatMap(caseReport -> caseReport.runtime().holdouts().negatives().stream())
                .filter(result -> !result.noApplication())
                .count();
        }

        public int generatedValidationExamples() {
            return cases.stream().mapToInt(caseReport ->
                caseReport.runtime().validationEvidence().generatedValidationExamples()).sum();
        }

        public int counterexampleSearches() {
            return cases.stream().mapToInt(caseReport ->
                caseReport.runtime().validationEvidence().counterexampleSearches().size()).sum();
        }

        public Map<String, Integer> failureTaxonomy() {
            Map<String, Integer> failures = new TreeMap<>();
            cases.stream().flatMap(caseReport -> caseReport.blockers().stream())
                .forEach(blocker -> failures.merge(blocker, 1, Integer::sum));
            return Collections.unmodifiableMap(failures);
        }

        public String toJson() {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .object("summary", summary -> summary
                    .property("cases", cases.size())
                    .property("families", familyCount())
                    .property("frozenCandidates", frozenCandidates())
                    .property("materialAblations", materialAblations())
                    .property("acceptedCases", acceptedCases())
                    .property("rediscoveredCases", rediscoveredCases())
                    .property("rediscoveryRatePermille", permille(rediscoveredCases(), cases.size()))
                    .property("negativeHoldouts", negativeHoldouts())
                    .property("evaluatedNegativeHoldouts", evaluatedNegativeHoldouts())
                    .property("skippedNegativeHoldouts", skippedNegativeHoldouts())
                    .property("falsePositiveHoldouts", falsePositiveHoldouts())
                    .property("falsePositiveRatePermille",
                        permille(falsePositiveHoldouts(), evaluatedNegativeHoldouts()))
                    .property("generatedValidationExamples", generatedValidationExamples())
                    .property("counterexampleSearches", counterexampleSearches()))
                .object("failureTaxonomy", taxonomy ->
                    failureTaxonomy().forEach(taxonomy::property))
                .array("cases", array -> cases.forEach(caseReport ->
                    array.objectValue(object -> writeCase(object, caseReport))))
                .endObject();
            return json.toString();
        }

        /** Non-canonical wall-clock telemetry; never used for hashes or byte-stability checks. */
        public String runtimeJson() {
            long totalElapsedNanos = cases.stream().mapToLong(CaseReport::elapsedNanos).sum();
            return new JsonWriter().beginObject()
                .property("schema", RUNTIME_SCHEMA)
                .property("cases", cases.size())
                .property("totalElapsedNanos", totalElapsedNanos)
                .property("generatedValidationExamples", generatedValidationExamples())
                .property("counterexampleSearches", counterexampleSearches())
                .array("caseRuntimes", array -> cases.forEach(caseReport ->
                    array.objectValue(object -> object
                        .property("opaqueCaseId", caseReport.opaqueCaseId())
                        .property("elapsedNanos", caseReport.elapsedNanos())
                        .property("exploredStates",
                            caseReport.runtime().searchMetrics().exploredStates())
                        .property("generatedValidationExamples", caseReport.runtime()
                            .validationEvidence().generatedValidationExamples())
                        .property("counterexampleSearches", caseReport.runtime()
                            .validationEvidence().counterexampleSearches().size()))))
                .endObject().toString();
        }

        private static int permille(int numerator, int denominator) {
            return denominator == 0 ? 0 : numerator * 1000 / denominator;
        }

        private static void writeCase(JsonWriter json, CaseReport report) {
            RuntimeResult runtime = report.runtime();
            Evaluation evaluation = report.evaluation();
            json.property("opaqueCaseId", report.opaqueCaseId())
                .property("family", report.family())
                .property("runtimeStatus", runtime.status().name())
                .property("searchStatus", runtime.searchStatus().name())
                .property("candidateRelation", evaluation.candidateRelation().name())
                .property("candidateFrozen", runtime.frozen())
                .property("validationPassed", evaluation.validationPassed())
                .property("splitPassed", report.split().passed())
                .property("holdoutsComplete", report.holdoutsComplete())
                .property("holdoutsPassed", report.holdoutsPassed())
                .property("materialAblation", evaluation.materialAblation())
                .property("accepted", report.accepted())
                .property("pathLength", Math.max(0, runtime.path().size() - 1))
                .stringArray("primitiveRuleIds", runtime.primitiveRuleIds())
                .stringArray("assumptions", runtime.assumptions())
                .stringArray("stageEvidence", runtime.stageEvidence())
                .stringArray("blockers", report.blockers())
                .object("metrics", metrics -> writeMetrics(metrics, runtime.searchMetrics()))
                .object("split", split -> writeSplit(split, report.split()))
                .object("candidate", candidate -> writeCandidate(candidate, runtime))
                .object("validation", validation -> writeValidation(
                    validation, runtime.validationEvidence()))
                .object("holdouts", holdouts -> writeHoldouts(holdouts, runtime))
                .array("leakageViolations", leakage -> evaluation.leakageViolations().forEach(
                    violation -> leakage.objectValue(object -> object
                        .property("location", violation.location())
                        .property("fingerprint", violation.tokenFingerprint()))));
        }

        private static void writeMetrics(JsonWriter json, GoalMetrics metrics) {
            json.property("exploredStates", metrics.exploredStates())
                .property("expandedStates", metrics.expandedStates())
                .property("generatedTransformations", metrics.generatedTransformations())
                .property("enqueuedStates", metrics.enqueuedStates())
                .property("duplicatePrunes", metrics.duplicatePrunes())
                .property("transpositionPrunes", metrics.transpositionPrunes())
                .property("depthPrunes", metrics.depthPrunes())
                .property("candidateBudgetPrunes", metrics.candidateBudgetPrunes())
                .property("identityCacheHits", metrics.identityCacheHits())
                .property("identityCacheMisses", metrics.identityCacheMisses())
                .property("internedValues", metrics.internedValues());
        }

        private static void writeSplit(JsonWriter json, SplitAudit split) {
            json.object("training", training -> training
                    .property("valueFingerprint", split.training().exactValue())
                    .property("alphaFingerprint", split.training().alphaShape()))
                .array("positives", positives -> split.positives().forEach(holdout ->
                    positives.objectValue(object -> object
                        .property("id", holdout.id())
                        .property("valueFingerprint", holdout.fingerprint().exactValue())
                        .property("alphaFingerprint", holdout.fingerprint().alphaShape()))))
                .array("negatives", negatives -> split.negatives().forEach(holdout ->
                    negatives.objectValue(object -> object
                        .property("id", holdout.id())
                        .property("valueFingerprint", holdout.fingerprint().exactValue())
                        .property("alphaFingerprint", holdout.fingerprint().alphaShape()))))
                .array("collisions", collisions -> split.collisions().forEach(collision ->
                    collisions.objectValue(object -> object
                        .property("kind", collision.kind())
                        .property("holdoutId", collision.holdoutId())
                        .property("fingerprint", collision.fingerprint()))));
        }

        private static void writeCandidate(JsonWriter json, RuntimeResult runtime) {
            if (runtime.candidate() == null) {
                json.property("present", false);
                return;
            }
            json.property("present", true)
                .property("leftPattern", runtime.candidate().leftPattern())
                .property("rightPattern", runtime.candidate().rightPattern())
                .property("confidence", runtime.candidate().confidence())
                .property("dynamicRuleId", runtime.candidate().dynamicRuleId())
                .property("provenanceHash", runtime.candidate().provenanceHash())
                .stringArray("assumptions", runtime.candidate().assumptions());
        }

        private static void writeValidation(
            JsonWriter json,
            CandidateValidationEvidence evidence
        ) {
            json.property("proofStatus", evidence.proofStatus())
                .property("generatedValidationExamples", evidence.generatedValidationExamples())
                .property("failedValidationExamples", evidence.failedValidationExamples())
                .property("passed", evidence.passed())
                .array("counterexampleSearches", searches ->
                    evidence.counterexampleSearches().forEach(search ->
                        searches.objectValue(object -> writeCounterexample(object, search))));
        }

        private static void writeCounterexample(
            JsonWriter json,
            CounterexampleEvidence evidence
        ) {
            json.property("status", evidence.status())
                .property("counterexamplePresent", evidence.counterexamplePresent())
                .stringArray("attemptedSources", evidence.attemptedSources())
                .property("explanation", evidence.explanation());
        }

        private static void writeHoldouts(JsonWriter json, RuntimeResult runtime) {
            json.array("positives", positives -> runtime.holdouts().positives().forEach(result ->
                    positives.objectValue(object -> writePositive(object, result))))
                .array("negatives", negatives -> runtime.holdouts().negatives().forEach(result ->
                    negatives.objectValue(object -> writeNegative(object, result))));
        }

        private static void writePositive(JsonWriter json, PositiveHoldoutResult result) {
            json.property("id", result.id())
                .property("directApplicationEquivalent", result.directApplicationEquivalent())
                .property("baselineReached", result.baselineReached())
                .property("candidateReached", result.candidateReached())
                .object("ablation", ablation -> writeAblation(ablation, result.ablation()));
        }

        private static void writeAblation(JsonWriter json, AblationEvidence evidence) {
            json.property("baselineReached", evidence.baselineReached())
                .property("candidateReached", evidence.candidateReached())
                .property("baselineExploredStates", evidence.baselineExploredStates())
                .property("candidateExploredStates", evidence.candidateExploredStates())
                .property("baselineDepth", evidence.baselineDepth())
                .property("candidateDepth", evidence.candidateDepth())
                .property("stateReduction", evidence.stateReduction())
                .property("materialBenefit", evidence.materialBenefit());
        }

        private static void writeNegative(JsonWriter json, NegativeHoldoutResult result) {
            json.property("id", result.id())
                .property("noApplication", result.noApplication())
                .property("candidateCount", result.candidateCount());
        }
    }
}
