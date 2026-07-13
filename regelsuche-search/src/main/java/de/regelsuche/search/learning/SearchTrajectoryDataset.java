package de.regelsuche.search.learning;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic, leakage-audited collection of post-labelled search trajectories. */
public final class SearchTrajectoryDataset {
    public static final String SCHEMA = "regelsuche.search-trajectory-dataset/v2";

    private static final Comparator<SearchTrajectoryRun> RUN_ORDER = Comparator
        .comparing((SearchTrajectoryRun run) -> run.context().split().ordinal())
        .thenComparing(run -> run.context().family())
        .thenComparing(run -> run.context().runId());

    private final List<SearchTrajectoryRun> runs;
    private final List<TrajectorySplitPlanner.LeakageViolation> leakageViolations;

    public SearchTrajectoryDataset(List<SearchTrajectoryRun> runs) {
        this(runs, new TrajectorySplitPlanner().leakageViolations(runs));
    }

    public SearchTrajectoryDataset(
        List<SearchTrajectoryRun> runs,
        List<TrajectorySplitPlanner.LeakageViolation> leakageViolations
    ) {
        Objects.requireNonNull(runs, "runs");
        Objects.requireNonNull(leakageViolations, "leakageViolations");
        this.runs = runs.stream().sorted(RUN_ORDER).toList();
        this.leakageViolations = leakageViolations.stream()
            .sorted(Comparator
                .comparing(TrajectorySplitPlanner.LeakageViolation::kind)
                .thenComparing(TrajectorySplitPlanner.LeakageViolation::fingerprint))
            .toList();
    }

    public static SearchTrajectoryDataset fromPlan(TrajectorySplitPlanner.SplitPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new SearchTrajectoryDataset(plan.runs(), plan.leakageViolations());
    }

    public List<SearchTrajectoryRun> runs() {
        return runs;
    }

    public List<TrajectorySplitPlanner.LeakageViolation> leakageViolations() {
        return leakageViolations;
    }

    public boolean leakageFree() {
        return leakageViolations.isEmpty();
    }

    /** One compact JSON object per deterministic SearchEvent, ordered by split/run/sequence. */
    public String toJsonLines() {
        requireLeakageFree();
        StringBuilder result = new StringBuilder();
        for (SearchTrajectoryRun run : runs) {
            for (SearchTrajectoryRecord record : run.records().stream()
                    .sorted(Comparator.comparingLong(SearchTrajectoryRecord::sequence))
                    .toList()) {
                result.append(toJson(record)).append('\n');
            }
        }
        return result.toString();
    }

    /** Compact run-level TSV for quick comparison and shell tooling. */
    public String toTabularSummary() {
        StringBuilder tsv = new StringBuilder(
            "split\trunId\tfamily\tsuccess\tstatus\trecords\tdecisions\tselectedDecisions"
                + "\ttaskValueClass\ttaskAlphaClass\n");
        for (SearchTrajectoryRun run : runs) {
            tsv.append(run.context().split()).append('\t')
                .append(cell(run.context().runId())).append('\t')
                .append(cell(run.context().family())).append('\t')
                .append(run.success()).append('\t')
                .append(run.terminalStatus()).append('\t')
                .append(run.records().size()).append('\t')
                .append(run.decisionCount()).append('\t')
                .append(run.selectedDecisionCount()).append('\t')
                .append(cell(run.taskValueFingerprint())).append('\t')
                .append(cell(run.taskAlphaFingerprint())).append('\n');
        }
        return tsv.toString();
    }

    public DatasetSummary summary() {
        int records = 0;
        int decisions = 0;
        int selectedDecisions = 0;
        int missingTargets = 0;
        int missingDecisionParents = 0;
        int missingDecisionRules = 0;
        int unavailableDescriptors = 0;
        int unparseableExpressions = 0;
        Set<String> families = new LinkedHashSet<>();
        Set<String> taskValueClasses = new LinkedHashSet<>();
        Set<String> taskAlphaClasses = new LinkedHashSet<>();
        Map<DatasetSplit, MutableSplitBalance> splitBalances = new LinkedHashMap<>();
        for (DatasetSplit split : DatasetSplit.values()) {
            splitBalances.put(split, new MutableSplitBalance());
        }

        for (SearchTrajectoryRun run : runs) {
            families.add(run.context().family());
            taskValueClasses.add(run.taskValueFingerprint());
            taskAlphaClasses.add(run.taskAlphaFingerprint());
            MutableSplitBalance split = splitBalances.get(run.context().split());
            split.runs++;
            if (run.success()) {
                split.successfulRuns++;
            }
            for (SearchTrajectoryRecord record : run.records()) {
                records++;
                split.records++;
                if (record.decision()) {
                    decisions++;
                    split.decisions++;
                    if (record.selectedPath()) {
                        selectedDecisions++;
                        split.selectedDecisions++;
                    }
                    if (record.parent() == null) {
                        missingDecisionParents++;
                    }
                    if (record.ruleId().isBlank()) {
                        missingDecisionRules++;
                    }
                    if (!record.transformationDescriptor().available()) {
                        unavailableDescriptors++;
                    }
                }
                if (record.target() == null) {
                    missingTargets++;
                }
                if (!record.expression().parseable()) {
                    unparseableExpressions++;
                }
            }
        }

        Map<DatasetSplit, SplitBalance> immutableBalances = new LinkedHashMap<>();
        splitBalances.forEach((split, value) -> immutableBalances.put(split, value.freeze()));
        int successfulRuns = (int) runs.stream().filter(SearchTrajectoryRun::success).count();
        return new DatasetSummary(
            runs.size(),
            records,
            decisions,
            selectedDecisions,
            successfulRuns,
            runs.size() - successfulRuns,
            families.size(),
            taskValueClasses.size(),
            taskAlphaClasses.size(),
            missingTargets,
            missingDecisionParents,
            missingDecisionRules,
            unavailableDescriptors,
            unparseableExpressions,
            leakageViolations.size(),
            Collections.unmodifiableMap(immutableBalances));
    }

    public Path writeJsonLines(Path output) {
        return write(output, toJsonLines());
    }

    public Path writeTabularSummary(Path output) {
        return write(output, toTabularSummary());
    }

    public String summaryJson() {
        DatasetSummary summary = summary();
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("runs", summary.runs())
            .property("records", summary.records())
            .property("decisions", summary.decisions())
            .property("selectedDecisions", summary.selectedDecisions())
            .property("successfulRuns", summary.successfulRuns())
            .property("failedRuns", summary.failedRuns())
            .property("families", summary.families())
            .property("taskValueClasses", summary.taskValueClasses())
            .property("taskAlphaClasses", summary.taskAlphaClasses())
            .property("missingTargets", summary.missingTargets())
            .property("missingDecisionParents", summary.missingDecisionParents())
            .property("missingDecisionRules", summary.missingDecisionRules())
            .property("unavailableDescriptors", summary.unavailableDescriptors())
            .property("unparseableExpressions", summary.unparseableExpressions())
            .property("leakageViolations", summary.leakageViolations())
            .array("splits", array -> summary.splitBalances().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> array.objectValue(object -> object
                    .property("split", entry.getKey().name())
                    .property("runs", entry.getValue().runs())
                    .property("records", entry.getValue().records())
                    .property("decisions", entry.getValue().decisions())
                    .property("selectedDecisions", entry.getValue().selectedDecisions())
                    .property("successfulRuns", entry.getValue().successfulRuns()))))
            .array("leakage", array -> leakageViolations.forEach(violation ->
                array.objectValue(object -> object
                    .property("kind", violation.kind())
                    .property("fingerprint", violation.fingerprint())
                    .stringArray("splits", violation.splits().stream().map(Enum::name).toList())
                    .stringArray("runIds", violation.runIds()))))
            .endObject();
        return json.toString();
    }

    private void requireLeakageFree() {
        if (!leakageFree()) {
            throw new IllegalStateException(
                "search trajectory export blocked by split leakage: " + leakageViolations);
        }
    }

    private static String toJson(SearchTrajectoryRecord record) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", record.schema())
            .property("producerVersion", record.producerVersion())
            .property("runId", record.runId())
            .property("family", record.family())
            .property("split", record.split().name())
            .property("ruleInventoryHash", record.ruleInventoryHash())
            .property("sequence", record.sequence())
            .property("eventType", record.eventType().name())
            .object("expression", value -> writeFingerprint(value, record.expression()));
        if (record.parent() == null) {
            json.nullProperty("parent");
        } else {
            json.object("parent", value -> writeFingerprint(value, record.parent()));
        }
        if (record.target() == null) {
            json.nullProperty("target");
        } else {
            json.object("target", value -> writeFingerprint(value, record.target()));
        }
        ExpressionFeatures features = record.features();
        json.object("features", value -> value
                .property("nodeCount", features.nodeCount())
                .property("maxDepth", features.maxDepth())
                .property("variableOccurrences", features.variableOccurrences())
                .property("distinctVariables", features.distinctVariables())
                .property("numericLiterals", features.numericLiterals())
                .property("additions", features.additions())
                .property("subtractions", features.subtractions())
                .property("multiplications", features.multiplications())
                .property("divisions", features.divisions())
                .property("powers", features.powers())
                .property("functions", features.functions())
                .property("parseable", features.parseable()));
        if (record.transformationDescriptor() == null) {
            json.nullProperty("transformationDescriptor");
        } else {
            json.object("transformationDescriptor", value ->
                writeDescriptor(value, record.transformationDescriptor()));
        }
        json.property("depth", record.depth())
            .property("score", record.score())
            .property("parentScore", record.parentScore())
            .property("frontierSize", record.frontierSize())
            .property("visitedCount", record.visitedCount())
            .property("generatedCount", record.generatedCount())
            .property("ruleId", record.ruleId())
            .property("rewriteKind", record.rewriteKind() == null ? "" : record.rewriteKind().name())
            .stringArray("applicableRuleIds", record.applicableRuleIds())
            .stringArray("assumptions", record.assumptions())
            .property("pruningReason", record.pruningReason())
            .property("eventualSuccess", record.eventualSuccess())
            .property("selectedPath", record.selectedPath())
            .property("terminalStatus", record.terminalStatus().name())
            .endObject();
        return json.toString();
    }

    private static void writeDescriptor(
        JsonWriter json,
        TransformationDescriptor descriptor
    ) {
        TransformationDescriptor.AstDelta delta = descriptor.astDelta();
        json.property("schema", TransformationDescriptor.SCHEMA)
            .property("rewriteKind", descriptor.rewriteKind().name())
            .property("equivalencePreserving", descriptor.equivalencePreserving())
            .property("mayIncreaseComplexity", descriptor.mayIncreaseComplexity())
            .property("estimatedCostDelta", descriptor.estimatedCostDelta())
            .object("astDelta", value -> value
                .property("nodeCount", delta.nodeCount())
                .property("maxDepth", delta.maxDepth())
                .property("variableOccurrences", delta.variableOccurrences())
                .property("distinctVariables", delta.distinctVariables())
                .property("numericLiterals", delta.numericLiterals())
                .property("additions", delta.additions())
                .property("subtractions", delta.subtractions())
                .property("multiplications", delta.multiplications())
                .property("divisions", delta.divisions())
                .property("powers", delta.powers())
                .property("functions", delta.functions())
                .property("parseable", delta.parseable()))
            .object("parentRoot", value -> writeRoot(value, descriptor.parentRoot()))
            .object("childRoot", value -> writeRoot(value, descriptor.childRoot()))
            .property("assumptionCount", descriptor.assumptionCount())
            .object("assumptionClassCounts", value ->
                descriptor.assumptionClassCounts().forEach((kind, count) ->
                    value.property(kind.name(), count)))
            .property("targeted", descriptor.targeted())
            .property("targetDistanceBefore", descriptor.targetDistanceBefore())
            .property("targetDistanceAfter", descriptor.targetDistanceAfter())
            .property("targetDistanceDelta", descriptor.targetDistanceDelta())
            .property("available", descriptor.available())
            .property("predictiveFingerprint", descriptor.predictiveFingerprint())
            .object("featureVector", value -> descriptor.featureVector().forEach(value::property));
    }

    private static void writeRoot(
        JsonWriter json,
        TransformationDescriptor.RootSignature root
    ) {
        json.property("kind", root.kind().name())
            .property("arity", root.arity());
    }

    private static void writeFingerprint(JsonWriter json, ExpressionFingerprint fingerprint) {
        json.property("valueHash", fingerprint.valueHash())
            .property("alphaShapeHash", fingerprint.alphaShapeHash())
            .property("parseable", fingerprint.parseable());
    }

    private static Path write(Path output, String content) {
        Objects.requireNonNull(output, "output");
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

    private static String cell(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ');
    }

    public record DatasetSummary(
        int runs,
        int records,
        int decisions,
        int selectedDecisions,
        int successfulRuns,
        int failedRuns,
        int families,
        int taskValueClasses,
        int taskAlphaClasses,
        int missingTargets,
        int missingDecisionParents,
        int missingDecisionRules,
        int unavailableDescriptors,
        int unparseableExpressions,
        int leakageViolations,
        Map<DatasetSplit, SplitBalance> splitBalances
    ) {
        public DatasetSummary {
            splitBalances = Collections.unmodifiableMap(new LinkedHashMap<>(splitBalances));
        }
    }

    public record SplitBalance(
        int runs,
        int records,
        int decisions,
        int selectedDecisions,
        int successfulRuns
    ) {
    }

    private static final class MutableSplitBalance {
        private int runs;
        private int records;
        private int decisions;
        private int selectedDecisions;
        private int successfulRuns;

        private SplitBalance freeze() {
            return new SplitBalance(runs, records, decisions, selectedDecisions, successfulRuns);
        }
    }
}
