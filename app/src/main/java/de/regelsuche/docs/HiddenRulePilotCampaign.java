package de.regelsuche.docs;

import de.regelsuche.docs.HiddenRuleHoldoutPartition.SplitAudit;
import de.regelsuche.docs.HiddenRulePilotEvaluator.Evaluation;
import de.regelsuche.docs.HiddenRulePilotEvaluator.HiddenReference;
import de.regelsuche.docs.HiddenRulePilotRunner.AblationEvidence;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Runs the post-hoc hidden-rule pilot and emits deterministic raw evidence. */
public final class HiddenRulePilotCampaign {
    public static final String SCHEMA = "regelsuche.hidden-rule-pilot/v1";

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
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(report, "report");
        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, report.toJson(), StandardCharsets.UTF_8);
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
        boolean accepted = evaluation.pilotAccepted() && split.passed();
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

        public String toJson() {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .object("summary", summary -> summary
                    .property("cases", cases.size())
                    .property("families", familyCount())
                    .property("frozenCandidates", frozenCandidates())
                    .property("materialAblations", materialAblations())
                    .property("acceptedCases", acceptedCases()))
                .array("cases", array -> cases.forEach(caseReport ->
                    array.objectValue(object -> writeCase(object, caseReport))))
                .endObject();
            return json.toString();
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
                .property("splitPassed", report.split().passed())
                .property("holdoutsPassed", runtime.holdouts().allPassed())
                .property("materialAblation", evaluation.materialAblation())
                .property("accepted", report.accepted())
                .property("elapsedNanos", report.elapsedNanos())
                .property("pathLength", Math.max(0, runtime.path().size() - 1))
                .stringArray("primitiveRuleIds", runtime.primitiveRuleIds())
                .stringArray("assumptions", runtime.assumptions())
                .stringArray("stageEvidence", runtime.stageEvidence())
                .stringArray("blockers", report.blockers())
                .object("metrics", metrics -> writeMetrics(metrics, runtime.searchMetrics()))
                .object("split", split -> writeSplit(split, report.split()))
                .object("candidate", candidate -> writeCandidate(candidate, runtime))
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
