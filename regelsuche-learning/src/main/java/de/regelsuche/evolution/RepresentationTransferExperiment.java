package de.regelsuche.evolution;

import de.regelsuche.evolution.RepresentationStrategyLearner.Application;
import de.regelsuche.evolution.RepresentationStrategyLearner.Policy;
import de.regelsuche.evolution.RepresentationStrategyLearner.Profile;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Public development study; this is not a sealed final holdout or preregistration. */
public final class RepresentationTransferExperiment {
    public static final int BUDGET = 20_000;
    public record Case(String id, String family, List<String> equations, boolean negativeControl) {
        public Case { equations = List.copyOf(equations); }
    }
    public record Row(Case task, Profile profile, Application application) { }
    public record Summary(Profile profile, long applicationWork, int solved, int regressions) { }
    public record Study(Policy policy, List<Row> rows, List<Summary> summaries) {
        public Study { rows = List.copyOf(rows); summaries = List.copyOf(summaries); }
    }

    public static List<List<String>> training() {
        return List.of(
            List.of("a+b=3", "a-b=1"),
            List.of("a+b+c=6", "a-b=0", "b-c=-1"),
            List.of("a+b=5", "a-b=1", "c+d=9", "c-d=1"),
            List.of("a+b=7", "a-b=3", "c+d=11", "c-d=3", "e+f=15", "e-f=3", "g+h=19", "g-h=3"),
            List.of("a+b=7", "a-b=3", "c+d=11", "c-d=3", "e+f=15", "e-f=3"),
            List.of("a+b=2", "b+c=3", "c+d=4", "a+2*d=1"));
    }
    public static List<Case> evaluation() {
        List<Case> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int n = 21 + i;
            List<String> coefficients = new ArrayList<>();
            List<String> recurrences = new ArrayList<>();
            List<String> redundant = new ArrayList<>();
            List<String> free = new ArrayList<>();
            for (int block = 0; block < 4; block++) {
                String x = "x" + block, y = "y" + block, z = "z" + block;
                int rhs = n + block * 7;
                coefficients.add((block + 2) + "*" + x + "+" + y + "=" + rhs);
                coefficients.add(x + "-" + y + "=" + (block + 2));
                recurrences.add(x + "+" + y + "+" + z + "=" + rhs);
                recurrences.add(y + "-2*" + x + "=1");
                recurrences.add(z + "-2*" + y + "=1");
                redundant.add(x + "+" + y + "=" + rhs);
                redundant.add("2*" + x + "+2*" + y + "=" + 2 * rhs);
                redundant.add(x + "-" + y + "=4");
                free.add((block + 2) + "*" + x + "+" + y + "=" + rhs);
            }
            tasks.add(new Case("coeff-" + i, "coefficient_matching", coefficients, false));
            tasks.add(new Case("recurrence-" + i, "recurrence_blocks", recurrences, false));
            tasks.add(new Case("redundant-" + i, "redundant_constraints", redundant, false));
            tasks.add(new Case("free-" + i, "free_parameters", free, false));
            tasks.add(new Case("connected-" + i, "connected_controls", List.of(
                "x+y=" + n, "y+z=7", "z+w=8", "x+2*w=6"), false));
        }
        tasks.add(new Case("nonlinear", "negative_controls", List.of("x*y=2"), true));
        tasks.add(new Case("function", "negative_controls", List.of("sin(x)=0"), true));
        return List.copyOf(tasks);
    }

    public Study run() {
        var learner = new RepresentationStrategyLearner();
        Policy policy = learner.fit(training(), BUDGET); // Freeze before any evaluation outcome exists.
        List<Row> rows = new ArrayList<>();
        for (Case task : evaluation()) {
            if (!task.negativeControl && policy.trainingIdentities().contains(
                    RepresentationStrategyLearner.identity(task.equations))) {
                throw new IllegalStateException("Training/evaluation overlap: " + task.id);
            }
            for (Profile profile : Profile.values()) rows.add(new Row(task, profile,
                learner.apply(policy, task.equations, profile, BUDGET)));
        }
        List<Summary> summaries = new ArrayList<>();
        Set<String> baseline = rows.stream().filter(row -> row.profile == Profile.DIRECT
            && row.application.audit().verified()).map(row -> row.task.id).collect(java.util.stream.Collectors.toSet());
        for (Profile profile : Profile.values()) {
            List<Row> selected = rows.stream().filter(row -> row.profile == profile).toList();
            summaries.add(new Summary(profile, selected.stream().mapToLong(row -> row.application.totalWork()).sum(),
                (int) selected.stream().filter(row -> row.application.audit().verified()).count(),
                (int) selected.stream().filter(row -> baseline.contains(row.task.id) && !row.application.audit().verified()).count()));
        }
        return new Study(policy, rows, summaries);
    }

    public static String protocolJson() {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", "regelsuche.representation-transfer-protocol/v1")
            .property("purpose", "PUBLIC_DEVELOPMENT_STUDY")
            .property("constructionBudget", BUDGET)
            .property("auditBudget", LinearRepresentationJson.AUDIT_BUDGET)
            .property("learningScope", "Finite independent-block/minimum-variable branch; fixed solve/compose skeleton")
            .property("workScope", "Mechanical solver, preparation, composition, policy selection, fit and audit units; excludes parsing, hashes, split canonicalization and rational bit complexity")
            .property("splitExclusion", "Sorted row-wise exact alpha-polynomial identities; conservative, not full system equivalence")
            .property("sealedHoldout", false).property("externallyPreregistered", false)
            .property("randomSeed", 730)
            .array("thresholdGrammar", w -> RepresentationStrategyLearner.THRESHOLDS.forEach(w::value))
            .stringArray("profiles", java.util.Arrays.stream(Profile.values()).map(Enum::name).toList())
            .array("training", w -> training().forEach(source -> w.arrayValue(a -> source.forEach(a::value))))
            .array("evaluation", w -> evaluation().forEach(task -> w.objectValue(a -> writeCase(a, task))));
        return writer.endObject().toString() + "\n";
    }
    public static String summaryJson(Study study) {
        JsonWriter writer = new JsonWriter().beginObject();
        writeSummary(writer, study);
        return writer.endObject().toString() + "\n";
    }
    private static void writeSummary(JsonWriter writer, Study study) {
        writer.property("schema", "regelsuche.representation-transfer-summary/v1")
            .property("minimumVariables", study.policy.minimumVariables())
            .property("learningWork", study.policy.learningWork())
            .property("fitWork", study.policy.fitWork())
            .property("applicationCases", evaluation().size())
            .array("profiles", w -> study.summaries.forEach(summary -> w.objectValue(s -> s
                .property("profile", summary.profile.name()).property("applicationWork", summary.applicationWork)
                .property("includingLearningWork", summary.applicationWork + (summary.profile == Profile.LEARNED ? study.policy.learningWork() : 0))
                .property("verifiedSolutions", summary.solved).property("regressions", summary.regressions))));
    }
    public static String toJson(Study study) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", "regelsuche.representation-transfer-study/v1")
            .property("protocolHash", LinearRepresentationJson.hash(protocolJson()))
            .property("policyHash", policyHash(study.policy))
            .object("summary", w -> writeSummary(w, study))
            .object("diagnosis", w -> RepresentationLearningDiagnosis.write(w, RepresentationLearningDiagnosis.analyze(study)))
            .object("policy", w -> writePolicy(w, study.policy))
            .array("training", w -> study.policy.observations().forEach(observation -> w.objectValue(o -> o
                .property("trainingIndex", observation.trainingIndex())
                .object("artifact", a -> LinearRepresentationJson.writeArtifact(a, observation.result(), observation.audit())))))
            .array("rows", w -> study.rows.forEach(row -> w.objectValue(r -> {
                r.object("task", t -> writeCase(t, row.task)).property("profile", row.profile.name())
                    .property("selectionWork", row.application.selectionWork()).property("totalWork", row.application.totalWork())
                    .object("artifact", a -> LinearRepresentationJson.writeArtifact(a, row.application.result(), row.application.audit()));
            })));
        return writer.endObject().toString() + "\n";
    }
    private static void writeCase(JsonWriter writer, Case task) {
        writer.property("id", task.id).property("family", task.family).stringArray("equations", task.equations)
            .property("negativeControl", task.negativeControl);
    }
    private static void writePolicy(JsonWriter writer, Policy policy) {
        writer.property("schema", "regelsuche.representation-policy/v1")
            .property("minimumVariables", policy.minimumVariables()).property("fitWork", policy.fitWork())
            .property("learningWork", policy.learningWork())
            .stringArray("trainingIdentities", policy.trainingIdentities().stream().sorted().toList());
    }
    public static String policyHash(Policy policy) {
        JsonWriter writer = new JsonWriter().beginObject();
        writePolicy(writer, policy);
        return LinearRepresentationJson.hash(writer.endObject().toString());
    }
    public static String manifestJson(Study study) {
        return new JsonWriter().beginObject().property("schema", "regelsuche.representation-transfer-manifest/v1")
            .property("protocolHash", LinearRepresentationJson.hash(protocolJson()))
            .property("policyHash", policyHash(study.policy))
            .property("studyHash", LinearRepresentationJson.hash(toJson(study)))
            .property("diagnosisHash", LinearRepresentationJson.hash(RepresentationLearningDiagnosis.toJson(study)))
            .property("summaryHash", LinearRepresentationJson.hash(summaryJson(study))).endObject().toString() + "\n";
    }
    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Expected output directory");
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        // Retain the fixed development protocol before fitting or evaluating.
        Files.writeString(directory.resolve("representation-transfer-protocol.json"), protocolJson());
        Study study = new RepresentationTransferExperiment().run();
        Files.writeString(directory.resolve("representation-transfer-study.json"), toJson(study));
        Files.writeString(directory.resolve("representation-transfer-summary.json"), summaryJson(study));
        Files.writeString(directory.resolve("representation-transfer-diagnosis.json"), RepresentationLearningDiagnosis.toJson(study));
        Files.writeString(directory.resolve("representation-transfer-manifest.json"), manifestJson(study));
    }
}
