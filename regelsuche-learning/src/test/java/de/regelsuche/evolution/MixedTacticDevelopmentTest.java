package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.TransformationProvenance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Development-only crossing of selection and verification on the previously published mixed stream. */
@EnabledIfSystemProperty(named = "mixed.output", matches = ".+")
class MixedTacticDevelopmentTest {
    private static final String SEED = "efb9439d38f8f0fa4e20ce5ffbedc34fa5248519";
    private static final long DEADLINE = 524_288;
    private enum Arm {
        ORIGINAL(false, false), CHECKER(false, true), POLICY(true, false), BOTH(true, true);
        final boolean policy;
        final boolean checker;
        Arm(boolean policy, boolean checker) { this.policy = policy; this.checker = checker; }
    }
    private static final List<List<Arm>> ORDERS = List.of(
        List.of(Arm.ORIGINAL, Arm.CHECKER, Arm.BOTH, Arm.POLICY),
        List.of(Arm.CHECKER, Arm.POLICY, Arm.ORIGINAL, Arm.BOTH),
        List.of(Arm.POLICY, Arm.BOTH, Arm.CHECKER, Arm.ORIGINAL),
        List.of(Arm.BOTH, Arm.ORIGINAL, Arm.POLICY, Arm.CHECKER));

    @Test
    void compareOnPublishedMixedStream() throws Exception {
        Path research = Path.of(System.getProperty("mixed.research"));
        Path out = Files.createDirectory(Path.of(System.getProperty("mixed.output")));
        int rotation = Integer.parseInt(System.getProperty("mixed.rotation"));
        var frozen = HornerTransferStudyTest.readFrozenInputs(research);
        var inputs = HornerTransferCases.mixedPanel(SEED);
        var times = new TreeMap<String, Long>();
        var facts = new TreeMap<String, Object>();
        var results = new TreeMap<Arm, List<HornerTransferStudyTest.Row>>();
        long largestSetup = 0;
        for (boolean warmup : List.of(true, false)) {
            for (Arm arm : ORDERS.get(rotation)) {
                long start = System.nanoTime();
                var setup = prepare(arm, frozen);
                long prepared = System.nanoTime();
                var paths = new TreeMap<String, List<HornerTransferStudyTest.Step>>();
                var rows = HornerTransferStudyTest.run(HornerTransferStudyTest.Arm.FAST, setup,
                    warmup ? inputs.subList(0, 128) : inputs, paths, ExpressionFormatter::format);
                long end = System.nanoTime();
                if (warmup) continue;
                times.put(arm + "_setup", prepared - start);
                times.put(arm + "_total", end - start);
                var summary = HornerTransferStudyTest.summarize(setup, rows, List.of(DEADLINE));
                String digest = ledgerHash(rows);
                if (arm == Arm.ORIGINAL) {
                    var archive = research.resolve("prospective-interval-transfer/development/FAST.jsonl.gz");
                    try (var input = new GZIPInputStream(Files.newInputStream(archive))) {
                        assertEquals(hex(sha256().digest(input.readAllBytes())), digest);
                    }
                }
                facts.put(arm.name(), Map.of("summary", summary, "ledgerSha256", digest,
                    "query", rows.stream().mapToLong(HornerTransferStudyTest.Row::query).sum(),
                    "onlineVerification", rows.stream().mapToLong(HornerTransferStudyTest.Row::verificationWithinQuery).sum(),
                    "terminalReplay", rows.stream().mapToLong(HornerTransferStudyTest.Row::replay).sum(),
                    "polynomialAudit", rows.stream().mapToLong(HornerTransferStudyTest.Row::audit).sum(),
                    "learnedSteps", rows.stream().mapToLong(HornerTransferStudyTest.Row::learnedSteps).sum()));
                results.put(arm, rows);
                largestSetup = Math.max(largestSetup, setup.total());
            }
        }
        var equal = new TreeMap<String, Long>();
        for (var entry : results.entrySet()) {
            var rows = entry.getValue();
            var first = rows.getFirst();
            long actualSetup = first.completedAt() - first.query() - first.replay() - first.audit();
            long extra = largestSetup - actualSetup;
            equal.put(entry.getKey().name(), rows.stream().filter(r -> r.solved() && r.completedAt() + extra <= DEADLINE).count());
        }
        facts.put("commonSetup", largestSetup);
        facts.put("completedWithCommonSetup", equal);
        HornerTransferStudyTest.writeJson(out.resolve("mixed.json"), facts);
        HornerTransferStudyTest.writeJson(out.resolve("mixed-nanos.json"), times);
        compareSameLearnedSteps(frozen, inputs, out, rotation % 2 != 0);
    }

    private static HornerTransferStudyTest.Setup prepare(Arm arm, HornerTransferStudyTest.Frozen frozen) {
        var genome = TraceStrategyTransferExample.inventory();
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(frozen.snapshot()).model(), genome.contentHash());
        long acquisition = frozen.acquisition();
        CheckedSchemaTaskSolver solver;
        if (arm.policy) {
            var fit = LearnedTacticPolicy.fit(genome, model, PairedTacticTraining.calibration());
            acquisition = Math.addExact(acquisition, fit.work());
            solver = inventory.newTaskSolverWithTacticPolicy(model, fit.policy(), arm.checker, false);
        } else {
            solver = inventory.newTaskSolverWithDomainReceipts(model, arm.checker);
        }
        return new HornerTransferStudyTest.Setup(solver, acquisition, model.loadWork(), solver.preparationWork());
    }

    private record Witness(TypedMoveSearch.WitnessStep step, TypedMoveSearch.Context context, int depth) {}

    private static void compareSameLearnedSteps(HornerTransferStudyTest.Frozen frozen, List<HornerTransferCases.Input> inputs,
            Path out, boolean reverse) throws IOException {
        var genome = TraceStrategyTransferExample.inventory();
        var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(frozen.snapshot()).model(), genome.contentHash());
        var solver = TypedLearnedMoveInventory.primitives(genome).newTaskSolverWithDomainReceipts(model);
        var samples = new ArrayList<Witness>();
        for (var input : inputs) {
            var task = input.task();
            var context = solver.scopedTask(task).problem().context();
            for (var step : solver.solve(task).evidence().witness()) {
                if (step.move().sourceKind() != SearchMove.SourceKind.LEARNED) continue;
                var evidence = (TransformationProvenance.ExactTheoryStep) step.move().provenance();
                var path = HornerTransferStudyTest.JSON.readTree(evidence.evidence().binding().canonicalEvidenceJson()).required("path");
                assertTrue(path.isArray());
                samples.add(new Witness(step, context, path.size()));
            }
        }
        assertEquals(1536, samples.size());
        var checkers = List.of(model.verifier(true), model.verifierWithSharedContext());
        var work = new TreeMap<String, Map<Integer, List<Long>>>();
        var timings = new TreeMap<String, List<Long>>();
        for (int round = -1; round < 3; round++) {
            for (int arm : reverse ? List.of(1, 0) : List.of(0, 1)) {
                var verifiedWork = new long[samples.size()];
                long start = System.nanoTime();
                for (int j = 0; j < samples.size(); j++) {
                    var sample = samples.get(j);
                    var step = sample.step();
                    var actual = CompiledAstReplayCodec.withExpressionCache(256, 2_000_000,
                        () -> checkers.get(arm).verify(step.source(), step.move(), sample.context()));
                    assertTrue(actual.accepted());
                    assertEquals(step.verification().receipts(), actual.receipts());
                    assertEquals(step.verification().reason(), actual.reason());
                    verifiedWork[j] = actual.work();
                }
                long elapsed = System.nanoTime() - start;
                if (round < 0) continue;
                String name = arm == 0 ? "old" : "new";
                timings.computeIfAbsent(name, ignored -> new ArrayList<>()).add(elapsed);
                var counts = new TreeMap<Integer, long[]>();
                for (int j = 0; j < samples.size(); j++) {
                    var bucket = counts.computeIfAbsent(samples.get(j).depth(), ignored -> new long[2]);
                    bucket[0]++;
                    bucket[1] += verifiedWork[j];
                }
                var byDepth = new TreeMap<Integer, List<Long>>();
                counts.forEach((depth, values) -> byDepth.put(depth, List.of(values[0], values[1])));
                var previous = work.put(name, byDepth);
                if (previous != null) assertEquals(previous, byDepth);
            }
        }
        HornerTransferStudyTest.writeJson(out.resolve("replay.json"), work);
        HornerTransferStudyTest.writeJson(out.resolve("replay-nanos.json"), timings);
    }

    private static String ledgerHash(List<HornerTransferStudyTest.Row> rows) throws IOException {
        var hash = sha256();
        for (var row : rows) {
            hash.update(HornerTransferStudyTest.JSON.writeValueAsBytes(row));
            hash.update((byte) '\n');
        }
        return hex(hash.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
