package de.regelsuche.benchmark;

import static de.regelsuche.benchmark.CandidateIndependentReusableMacroBatchMain.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayTrace;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.*;
import de.regelsuche.benchmark.OpenMacroPathInventory.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Open-target two-step discovery on the four macro TRAIN starts from the public #383 corpus. */
public final class TrackCOpenMacroComparison {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIRECTORY = "track-c-open-macros";
    private static final String PLAN =
            "sha256:b5780d13f2278b3eb91b94e4166f312f2ecb71acadda96aba49087cb7d0c7694";
    private static final String COMMIT = "1fa021ac7f17bcf7886f761b0cd2c37bce9fd26f";

    private enum Arm {
        PRODUCTION_SCORE,
        ENUMERATE_PRIMITIVE_PATHS,
        SEEDED_RANDOM_PRIMITIVE_PATHS
    }

    private record Frozen(
            int index,
            Arm arm,
            String seed,
            ObjectNode evidence,
            String hash,
            FormationResult result) {}

    private TrackCOpenMacroComparison() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("expected frozen corpus directory and output");
        Path data = Path.of(args[0]);
        Path planFile = data.resolve(DIRECTORY + "/comparison-plan.json");
        String planHash =
                "sha256:"
                        + HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(Files.readAllBytes(planFile)));
        if (!PLAN.equals(planHash)) throw new IllegalArgumentException("changed comparison plan");
        var plan = readObject(planFile);
        var corpus =
                frozenFile(data, "case-corpus", plan.required("caseCorpusContentHash").textValue());
        frozenFile(
                data,
                "corpus-freeze-receipt",
                plan.required("freezeReceiptContentHash").textValue());
        var mapping =
                operationRules(
                        frozenFile(
                                data,
                                "macro-primitives",
                                plan.required("primitiveProfileContentHash").textValue()));
        var cases = macroCases(corpus);
        var starts = trainStarts(cases);
        var inventory = new OpenMacroPathInventory(mapping);
        var adapter = new CandidateIndependentReusableMacroAdapter(mapping);
        var universes = new ArrayList<Inventory>();
        for (var start : starts) universes.add(inventory.generate(start));
        var campaigns = new ArrayList<Frozen>();
        String challenge = plan.required("challengeId").textValue();
        for (int index = 1; index <= plan.required("campaigns").intValue(); index++) {
            String seed =
                    semanticHash(
                            JSON.valueToTree(
                                    Map.of(
                                            "campaignId",
                                            challenge
                                                    + "-campaign-"
                                                    + String.format(
                                                            java.util.Locale.ROOT, "%02d", index),
                                            "challengeId",
                                            challenge,
                                            "index",
                                            index)));
            for (Arm arm : Arm.values()) campaigns.add(form(index, arm, seed, universes, adapter));
        }
        // Evaluation targets, objectives and task budgets first enter here, after all candidate
        // sets are bound.
        var tasks = evaluationTasks(cases);
        var report = JSON.createObjectNode();
        report.put("schema", "regelsuche.track-c-open-macro-comparison/v1");
        report.put("planCommit", COMMIT);
        report.put("planByteHash", PLAN);
        report.put("corpusHash", corpus.required("contentHash").textValue());
        report.set("pathUniverses", JSON.valueToTree(universes));
        var runs = report.putArray("campaigns");
        for (Frozen run : campaigns) {
            var row = runs.addObject();
            row.put("index", run.index());
            row.put("arm", run.arm().name());
            row.put("seed", run.seed());
            row.set("formation", run.evidence());
            row.put("formationHash", run.hash());
            var evals = row.putArray("evaluations");
            for (var task : tasks) {
                var baseline = adapter.baseline(task.task());
                var result = adapter.evaluate(task.task(), run.result(), baseline);
                if (result.outcome() == UtilityOutcome.CANDIDATE_NOT_FORMED) {
                    result =
                            new PairedEvaluation(
                                    result.taskId(),
                                    result.outcome(),
                                    baseline.run(),
                                    result.macroEnabled(),
                                    result.correctnessRegression(),
                                    result.detail());
                }
                evals.add(evaluation(task, result));
            }
        }
        Files.writeString(Path.of(args[1]), canonicalJson(report) + "\n");
    }

    static List<Start> trainStarts(List<ObjectNode> cases) {
        var starts = new ArrayList<Start>();
        for (var item : cases) {
            if (!"TRAIN".equals(item.required("split").textValue())) continue;
            for (var trace : item.required("formationInput").required("replayTraces")) {
                var assumptions = new ArrayList<String>();
                for (var assumption : trace.required("assumptions"))
                    assumptions.add(assumption.textValue());
                starts.add(new Start(trace.required("source").textValue(), assumptions));
            }
        }
        if (starts.size() != 4)
            throw new IllegalArgumentException("expected four public TRAIN starts");
        return List.copyOf(starts);
    }

    private static ObjectNode frozenFile(Path data, String name, String expected) throws Exception {
        var doc = readObject(data.resolve(name + ".json"));
        requireContentHash(doc, name);
        if (!expected.equals(doc.required("contentHash").textValue()))
            throw new IllegalArgumentException(name);
        return doc;
    }

    private static Frozen form(
            int index,
            Arm arm,
            String seed,
            List<Inventory> universe,
            CandidateIndependentReusableMacroAdapter adapter) {
        var random = new Random(Long.parseUnsignedLong(seed.substring(7, 23), 16));
        var groups = new LinkedHashMap<List<String>, List<ReplayTrace>>();
        var evidence = JSON.createObjectNode();
        var decisions = evidence.putArray("selections");
        for (int i = 0; i < universe.size(); i++) {
            var input = universe.get(i);
            var row = decisions.addObject();
            row.put("startIndex", i);
            if (input.choices().isEmpty()) {
                row.putNull("chosenKey");
                continue;
            }
            var item = select(arm, input.choices(), random);
            row.put("chosenKey", item.key());
            var trace =
                    new ReplayTrace(
                            "open-path-" + (i + 1),
                            input.start().source(),
                            item.endpoints().getLast(),
                            item.operations(),
                            input.start().assumptions());
            groups.computeIfAbsent(item.operations(), ignored -> new ArrayList<>()).add(trace);
        }
        var accepted = new LinkedHashMap<String, MacroCandidate>();
        var replayRows = evidence.putArray("groups");
        for (var entry : groups.entrySet()) {
            var result = adapter.form(entry.getValue());
            var group = replayRows.addObject();
            group.set("operations", JSON.valueToTree(entry.getKey()));
            group.set("formation", formation(result));
            if (result.status() == FormationStatus.SELECTED) {
                for (var macro : result.macros()) accepted.putIfAbsent(macro.macroId(), macro);
            }
        }
        var result =
                new FormationResult(
                        accepted.isEmpty()
                                ? FormationStatus.GENERALIZATION_REJECTED
                                : FormationStatus.SELECTED,
                        List.copyOf(accepted.values()),
                        List.of(),
                        "Frozen union of selected open-target groups");
        evidence.set("accepted", formation(result));
        return new Frozen(index, arm, seed, evidence, semanticHash(evidence), result);
    }

    private static PathChoice select(Arm arm, List<PathChoice> choices, Random random) {
        return switch (arm) {
            case PRODUCTION_SCORE ->
                    choices.stream()
                            .min(
                                    Comparator.comparingInt(PathChoice::endpointScore)
                                            .thenComparing(PathChoice::key))
                            .orElseThrow();
            case ENUMERATE_PRIMITIVE_PATHS -> choices.getFirst();
            case SEEDED_RANDOM_PRIMITIVE_PATHS -> choices.get(random.nextInt(choices.size()));
        };
    }
}
