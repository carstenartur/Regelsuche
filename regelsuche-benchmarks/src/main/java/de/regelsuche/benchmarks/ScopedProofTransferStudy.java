package de.regelsuche.benchmarks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.benchmarks.ScopedProofPartitions.Case;
import de.regelsuche.benchmarks.ScopedProofPartitions.Split;
import de.regelsuche.benchmarks.ScopedProofTrials.Arm;
import de.regelsuche.benchmarks.ScopedProofTrials.Row;
import de.regelsuche.solver.ir.ExistentialIntroduction.Statement;
import de.regelsuche.solver.ir.SolverIr;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Separate TRAIN freeze and independently invocable evaluation commands for the public protocol. */
public final class ScopedProofTransferStudy {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String PROTOCOL = "abf2aba070a70f1ac48ab066d134195c9182af9c";
    record Observation(String id, String binder, JsonNode source, JsonNode recipient, List<Row> arms) { }
    record Plan(String schema, String protocolCommit, String trainingHash, Map<String,List<String>> witnessPriorities,
                boolean inductionBaseFirst, int trainingProposals, int trainingCheckerCalls) { }
    record Totals(int cases, int reached, int proposals, int checkerCalls, int planUses, int chargedTrainingProposals,
                  int chargedTrainingCheckerCalls) { }

    private ScopedProofTransferStudy() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("train")) {
            train(Path.of(args[1]));
            System.out.println(SolverIr.sha256(Files.readString(Path.of(args[1], "plan.json"))));
        } else if (args.length == 3) {
            evaluate(args[0].toUpperCase(Locale.ROOT), Path.of(args[1]), args[2]);
        } else {
            throw new IllegalArgumentException("train DIRECTORY | validation|final DIRECTORY TRUSTED_PLAN_HASH");
        }
    }

    static void train(Path dir) throws Exception {
        var training = ScopedProofPartitions.generate(Split.TRAIN);
        var observations = new ArrayList<Observation>();
        var scores = new TreeMap<String, Map<String,Integer>>();
        int baseFirstWins = 0, sortedWins = 0, proposals = 0, checkerCalls = 0;
        for (var c : training) {
            var row = ScopedProofTrials.run(c, observations.size(), Arm.EXHAUSTIVE, ScopedProofTrials.GRAMMAR, false);
            observations.add(observation(c, List.of(row)));
            proposals += row.trials().size();
            checkerCalls += row.checkerCalls();
            if (c.witness()) {
                String shape = ScopedProofTrials.shape(new Statement(c.source(), c.binder())).key();
                var byRule = scores.computeIfAbsent(shape, ignored -> new TreeMap<>());
                for (var trial : row.trials()) if (trial.accepted()) byRule.merge(trial.proposal(), 1, Integer::sum);
            } else {
                if (row.trials().getFirst().accepted()) sortedWins++;
                String selected = ScopedProofTrials.firstBase(c.source());
                if (row.trials().stream().anyMatch(t -> t.accepted() && t.proposal().equals(selected))) baseFirstWins++;
            }
        }
        var priorities = new TreeMap<String,List<String>>();
        scores.forEach((shape, hits) -> priorities.put(shape, ScopedProofTrials.GRAMMAR.stream()
            .sorted(Comparator.<String>comparingInt(rule -> hits.getOrDefault(rule, 0)).reversed()).toList()));
        String evidence = encode(Map.of("split", "TRAIN", "cases", observations, "hits", scores,
            "baseFirstHits", baseFirstWins, "sortedFirstHits", sortedWins, "protocolCommit", PROTOCOL));
        persist(dir, "train.json", evidence);
        persist(dir, "plan.json", encode(new Plan("scoped-proof-plan-priorities/v1", PROTOCOL, SolverIr.sha256(evidence),
            priorities, baseFirstWins > sortedWins, proposals, checkerCalls)));
    }

    static void evaluate(String name, Path dir, String expectedHash) throws Exception {
        var split = Split.valueOf(name);
        if (split == Split.TRAIN) throw new IllegalArgumentException("use the training command for TRAIN");
        String frozen = Files.readString(dir.resolve("plan.json"));
        if (!SolverIr.sha256(frozen).equals(expectedHash)) throw new IllegalArgumentException("frozen plan hash differs");
        Plan plan = JSON.readValue(frozen, Plan.class);
        if (!plan.protocolCommit().equals(PROTOCOL) || !plan.trainingHash().equals(SolverIr.sha256(Files.readString(dir.resolve("train.json")))))
            throw new IllegalArgumentException("training evidence or protocol differs");
        var observations = new ArrayList<Observation>();
        for (Case c : ScopedProofPartitions.generate(split)) {
            String shape = c.witness() ? ScopedProofTrials.shape(new Statement(c.source(), c.binder())).key() : "";
            var order = plan.witnessPriorities().getOrDefault(shape, ScopedProofTrials.GRAMMAR);
            var arms = new ArrayList<Row>();
            for (Arm arm : Arm.values()) arms.add(ScopedProofTrials.run(c, observations.size(), arm, order, plan.inductionBaseFirst()));
            observations.add(observation(c, arms));
        }
        var totals = new TreeMap<String,Totals>();
        for (Arm arm : Arm.values()) totals.put(arm.name(), totals(observations, arm, plan));
        persist(dir, name.toLowerCase(Locale.ROOT) + ".json", encode(Map.of("split", name, "cases", observations,
            "frozenPlanHash", expectedHash, "protocolCommit", PROTOCOL, "work", totals)));
    }

    private static Totals totals(List<Observation> observations, Arm arm, Plan plan) {
        var rows = observations.stream().flatMap(o -> o.arms().stream()).filter(r -> r.arm().equals(arm.name())).toList();
        return new Totals(rows.size(), (int) rows.stream().filter(r -> r.status().equals("FOUND")).count(),
            rows.stream().mapToInt(r -> r.trials().size()).sum(), rows.stream().mapToInt(Row::checkerCalls).sum(),
            (int) rows.stream().filter(Row::selectedPlanApplied).count(), arm == Arm.LEARNED ? plan.trainingProposals() : 0,
            arm == Arm.LEARNED ? plan.trainingCheckerCalls() : 0);
    }

    private static Observation observation(Case c, List<Row> rows) throws Exception {
        return new Observation(c.id(), c.binder(), JSON.readTree(c.source().toCanonicalJson()),
            JSON.readTree(c.receiver().toCanonicalJson()), List.copyOf(rows));
    }

    private static String encode(Object object) throws Exception {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(object) + "\n";
    }

    private static void persist(Path dir, String name, String text) throws Exception {
        Files.createDirectories(dir);
        Path output = dir.resolve(name);
        if (Files.exists(output)) {
            if (!Files.readString(output).equals(text)) throw new IllegalStateException("existing study evidence differs: " + name);
            return;
        }
        Path staged = Files.createTempFile(dir, name, ".tmp");
        try {
            Files.writeString(staged, text);
            Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
