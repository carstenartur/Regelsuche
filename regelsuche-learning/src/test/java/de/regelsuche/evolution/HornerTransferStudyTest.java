package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.Expr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchContinuationContract;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.TransformationProvenance;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class HornerTransferStudyTest {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Long> DEADLINES = List.of(16_384L, 32_768L, 65_536L, 131_072L, 262_144L);
    private static final long PRIMARY = 131_072;
    private static final long LONG_PRIMARY = 524_288;
    private static final String FIRST_STUDY_SEED = "05dda9fb837d53f6d37e3925744b009c3b09672d";
    private static final String LONG_PROTOCOL = "sha256:2f6d5c3be42d8f291f3facac132cd749c9b5b246a257af32aafad52840c81702";
    private static final String PROTOCOL = "sha256:f2c2f07b6709b2bab033721259a39f9d2b3a8bb289dbcb4bfe245f83e3208dda";
    private static final String RECEIPT = "sha256:6863b403ed13292dd4aefde8603ba83b1f7ff221ee5dab091a7f019108947930";
    static final String SNAPSHOT = "sha256:9e56f3640b85520b8b4090f64aa96c14bb703d3d218ab6fd60c2cdb47e74e223";
    private static final EvolutionGenome INVENTORY = TraceStrategyTransferExample.inventory();

    enum Arm { BASE, PRIOR, REGEN, DROP, FAST, FAST_DROP }
    private static final List<Arm> HISTORICAL_ARMS = List.of(Arm.BASE, Arm.PRIOR, Arm.REGEN, Arm.DROP);
    record Frozen(String snapshot, long acquisition) {}
    record Setup(CheckedSchemaTaskSolver solver, long acquisition, long load, long preparation) {
        long total() { return Math.addExact(acquisition, Math.addExact(load, preparation)); }
    }
    record Step(String rule, String source, String target, String learnedEvidence, long verification) {}
    record Row(int index, int distributed, String source, String output, long score, String outcome, boolean withinQueryBudget,
        boolean solved, long query, long verificationWithinQuery, long replay, long audit, long completedAt, int learnedSteps, String pathHash) {}
    record Checkpoint(long budget, int completed, int solved) {}
    record Summary(long acquisition, long load, long preparation, List<Checkpoint> checkpoints, int totalSolved, long fullCost) {
        int solvedAt(long budget) { return checkpoints.stream().filter(c -> c.budget() == budget).findFirst().orElseThrow().solved(); }
    }

    @Test
    void deadlinesChargeSetupAndFailuresAndCountOnlyFinishedTasks() {
        var rows = List.of(clockRow(false, 17_000), clockRow(true, 20_000), clockRow(true, PRIMARY + 1));
        var first = checkpoint(rows, 16_384);
        assertEquals(0, first.completed());
        assertEquals(0, first.solved());
        assertEquals(new Checkpoint(17_000, 1, 0), checkpoint(rows, 17_000));
        assertEquals(new Checkpoint(20_000, 2, 1), checkpoint(rows, 20_000));
        assertEquals(1, checkpoint(rows, PRIMARY).solved());
        assertTrue(primaryPassed(2, 1, 0));
        assertFalse(primaryPassed(2, 2, 0));
        assertFalse(primaryPassed(2, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> HornerTransferCases.panel("invalid SHA"));
    }

    @Test
    void pairedResultsCountByTopologyAndRespectTheSharedClock() {
        var inputs = List.of(new HornerTransferCases.Input(0, 0, HornerTransferCases.Topology.H, "x"),
            new HornerTransferCases.Input(1, 0, HornerTransferCases.Topology.D, "y"));
        var rows = new EnumMap<Arm, List<Row>>(Arm.class);
        for (Arm arm : HISTORICAL_ARMS) rows.put(arm, List.of(clockRow(true, 1), clockRow(false, 2)));
        rows.put(Arm.REGEN, List.of(clockRow(false, 1), clockRow(true, 2)));
        var results = topologyResults(inputs, rows, 2);
        assertTrue(results.contains(new TopologyResult(HornerTransferCases.Topology.H, Arm.BASE, 1, 1, 0, 1, 0, 1)));
        assertTrue(results.contains(new TopologyResult(HornerTransferCases.Topology.D, Arm.BASE, 1, 0, 0, 0, 1, 0)));
        assertTrue(topologyResults(inputs, rows, 1).contains(
            new TopologyResult(HornerTransferCases.Topology.D, Arm.REGEN, 1, 1, 0, 0, 0, 0)));
    }

    private static Row clockRow(boolean solved, long completedAt) {
        return new Row(0, 0, "", "", 0, "", solved, solved, 0, 0, 0, 0, completedAt, 0, "");
    }

    @Test
    @EnabledIfSystemProperty(named = "horner.phase", matches = "final|amortization")
    void evaluateFrozenStream() throws IOException {
        Path research = Path.of(System.getProperty("horner.research"));
        String seed = System.getProperty("horner.freeze");
        int rotation = Integer.parseInt(System.getProperty("horner.rotation"));
        Frozen frozen = readFrozenInputs(research);
        var order = switch (rotation) {
            case 0 -> List.of(Arm.BASE, Arm.PRIOR, Arm.REGEN, Arm.DROP);
            case 1 -> List.of(Arm.DROP, Arm.REGEN, Arm.PRIOR, Arm.BASE);
            default -> throw new IllegalArgumentException("rotation must be 0 or 1");
        };
        boolean longStream = System.getProperty("horner.phase").equals("amortization");
        long primary = longStream ? LONG_PRIMARY : PRIMARY;
        String protocol = longStream ? LONG_PROTOCOL : PROTOCOL;
        if (longStream) readChecked(research.resolve("prospective-amortization/protocol.md"), protocol);
        var inputs = longStream ? HornerTransferCases.mixedPanel(seed) : HornerTransferCases.panel(seed);
        var split = longStream ? Stream.concat(HornerTransferCases.panel(FIRST_STUDY_SEED).stream(), inputs.stream()).toList() : inputs;
        HornerTransferCases.verifySplit(split);
        var deadlines = longStream ? List.of(primary) : DEADLINES;
        var allRows = new EnumMap<Arm, List<Row>>(Arm.class);
        Path out = Files.createDirectory(Path.of(System.getProperty("horner.output")));
        var summaries = new EnumMap<Arm, Summary>(Arm.class);
        var elapsed = new EnumMap<Arm, Long>(Arm.class);
        Map<String, List<Step>> paths = new TreeMap<>();
        for (Arm arm : order) {
            long start = System.nanoTime();
            Setup setup = prepare(arm, frozen);
            var rows = run(arm, setup, inputs, paths, ExpressionFormatter::format);
            elapsed.put(arm, System.nanoTime() - start);
            writeRows(out.resolve(arm.name() + ".jsonl"), rows);
            summaries.put(arm, summarize(setup, rows, deadlines));
            if (longStream) allRows.put(arm, rows);
        }
        if (longStream) writeJson(out.resolve("topologies.json"), topologyResults(inputs, allRows, primary));
        int regen = summaries.get(Arm.REGEN).solvedAt(primary);
        var report = Map.of("freeze", seed, "protocol", protocol, "model", SNAPSHOT, "primaryBudget", primary,
            "passed", primaryPassed(regen, summaries.get(Arm.BASE).solvedAt(primary), summaries.get(Arm.DROP).solvedAt(primary)),
            "improvedOnPriorAtPrimary", regen > summaries.get(Arm.PRIOR).solvedAt(primary), "arms", summaries);
        writeJson(out.resolve("summary.json"), new TreeMap<>(report));
        writeJson(out.resolve("paths.json"), paths);
        writeJson(out.resolve("elapsed-nanos.json"), elapsed);
    }

    private static boolean primaryPassed(int learned, int base, int drop) { return learned > base && learned > drop; }

    static Frozen readFrozenInputs(Path research) throws IOException {
        readChecked(research.resolve("prospective-horner-transfer/protocol.md"), PROTOCOL);
        var previous = research.resolve("prospective-distributive-coverage");
        var receipt = JSON.readTree(readChecked(previous.resolve("freeze.json"), RECEIPT));
        if (!INVENTORY.contentHash().equals(receipt.required("inventory").textValue())
                || !SNAPSHOT.equals(receipt.required("modelSnapshot").textValue())) {
            throw new IllegalStateException("receipt does not bind the model and inventory");
        }
        long acquisition = JSON.treeToValue(receipt.required("acquisition"), CumulativeTaskKnowledge.Costs.class).total();
        return new Frozen(readChecked(previous.resolve("snapshot.json"), SNAPSHOT), acquisition);
    }

    static String readChecked(Path path, String digest) throws IOException {
        String contents = Files.readString(path);
        if (!SchematicProofPlan.hash(contents).equals(digest)) throw new IllegalStateException("freeze mismatch in " + path);
        return contents;
    }

    static Setup prepare(Arm arm, Frozen frozen) {
        var inventory = TypedLearnedMoveInventory.primitives(INVENTORY);
        if (arm == Arm.BASE) {
            var base = inventory.newTaskSolver();
            return new Setup(base, 0, 0, base.preparationWork());
        }
        var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(frozen.snapshot()).model(), INVENTORY.contentHash());
        var solver = switch (arm) {
            case PRIOR -> inventory.newTaskSolverWithSelection(model, true);
            case REGEN, DROP -> inventory.newTaskSolverWithRegeneratedWitness(model);
            case FAST, FAST_DROP -> inventory.newTaskSolverWithDomainReceipts(model);
            case BASE -> throw new IllegalStateException("primitive arm with model");
        };
        return new Setup(solver, frozen.acquisition(), model.loadWork(), solver.preparationWork());
    }

    static List<Row> run(Arm arm, Setup setup, List<? extends TransferStudyInput> inputs,
            Map<String, List<Step>> paths, Function<Expr, String> format) throws IOException {
        var rows = new ArrayList<Row>();
        long clock = setup.total();
        for (var input : inputs) {
            var task = input.task();
            var result = switch (arm) {
                case DROP, FAST_DROP -> drop(setup.solver(), task, arm);
                default -> setup.solver().solve(task).evidence();
            };
            if (!result.accountingComplete()) throw new IllegalStateException("incomplete solver accounting on " + input.index());
            String output = format.apply(result.incumbent().expression());
            long[] audit = {0};
            ExactPolynomialAnalysis.withProductWork(n -> audit[0] = Math.addExact(audit[0], n)).requireEquivalent(input.source(), output);
            clock = Math.addExact(clock, Math.addExact(result.totalWork(), audit[0]));
            var path = result.witness().stream().map(s -> step(s, format)).toList();
            String hash = SchematicProofPlan.hash(JSON.writeValueAsString(path));
            var earlier = paths.putIfAbsent(hash, path);
            if (earlier != null && !earlier.equals(path)) throw new IllegalStateException("witness hash collision");
            boolean solved = result.withinBudget() && result.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED
                && result.outputScore() <= task.goal().maximumOutputScore();
            int learned = Math.toIntExact(path.stream().filter(step -> step.learnedEvidence() != null).count());
            rows.add(new Row(input.index(), input.variant(), input.source(), output, result.outputScore(),
                result.search().outcome().name(), result.withinBudget(), solved, result.queryWork(),
                result.search().metrics().verificationWork(), result.replayWork(), audit[0], clock, learned, hash));
        }
        return rows;
    }

    private static Step step(TypedMoveSearch.WitnessStep step, Function<Expr, String> format) {
        if (!step.verification().accepted()) throw new IllegalStateException("rejected witness step");
        String evidence = step.move().sourceKind() == SearchMove.SourceKind.LEARNED
            ? ((TransformationProvenance.ExactTheoryStep) step.move().provenance()).evidence().binding().evidenceHash() : null;
        return new Step(step.move().ruleId(), format.apply(step.source().expression()),
            format.apply(step.target().expression()), evidence, step.verification().work());
    }

    // Remove only the learned providers, preserving each arm's paid routing and objective.
    private static TypedSourceOnlySearch.Result drop(CheckedSchemaTaskSolver solver, CheckedSchemaTaskSolver.Task task, Arm arm) {
        return CompiledAstReplayCodec.withExpressionCache(256, 2_000_000, () -> {
            var scoped = arm == Arm.FAST_DROP ? solver.scopedTask(task) : null;
            var p = scoped != null ? scoped.problem() : solver.problem(task);
            var objective = scoped != null ? scoped.objective() : task.goal().objective();
            var providers = p.providers().stream().filter(provider -> provider.descriptor().sourceKind() != SearchMove.SourceKind.LEARNED).toList();
            var ablated = new TypedMoveSearch.Problem(p.source(), p.context(), providers, p.policy(), p.verifier(),
                p.stateScore(), p.mode(), p.scheduling(), p.budget(), p.stateValue());
            return new TypedSourceOnlySearch().searchUntil(ablated, objective,
                task.goal().maximumOutputScore(), SearchContinuationContract.PATH_SENSITIVE);
        });
    }

    static Summary summarize(Setup setup, List<Row> rows, List<Long> deadlines) {
        var checkpoints = deadlines.stream().map(budget -> checkpoint(rows, budget)).toList();
        return new Summary(setup.acquisition(), setup.load(), setup.preparation(), checkpoints,
            Math.toIntExact(rows.stream().filter(Row::solved).count()), rows.getLast().completedAt());
    }

    private static Checkpoint checkpoint(List<Row> rows, long budget) {
        int completed = 0, solved = 0;
        for (Row row : rows) {
            if (row.completedAt() > budget) break;
            completed++;
            if (row.solved()) solved++;
        }
        return new Checkpoint(budget, completed, solved);
    }

    private record TopologyResult(HornerTransferCases.Topology topology, Arm arm, int attempts, int fullSolved, long streamWork,
        int solvedAtDeadline, int regenWins, int regenLosses) {}

    private static List<TopologyResult> topologyResults(List<HornerTransferCases.Input> inputs, Map<Arm, List<Row>> rows, long primary) {
        var results = new ArrayList<TopologyResult>();
        for (var topology : HornerTransferCases.Topology.values()) {
            for (Arm arm : HISTORICAL_ARMS) {
                int attempts = 0, full = 0, onTime = 0, wins = 0, losses = 0;
                long work = 0;
                for (var input : inputs) {
                    if (input.topology() != topology) continue;
                    Row row = rows.get(arm).get(input.index());
                    Row candidate = rows.get(Arm.REGEN).get(input.index());
                    boolean earned = row.solved() && row.completedAt() <= primary;
                    boolean regenEarned = candidate.solved() && candidate.completedAt() <= primary;
                    attempts++;
                    if (row.solved()) full++;
                    if (earned) onTime++;
                    if (regenEarned && !earned) wins++;
                    if (earned && !regenEarned) losses++;
                    work = Math.addExact(work, Math.addExact(row.query(), Math.addExact(row.replay(), row.audit())));
                }
                results.add(new TopologyResult(topology, arm, attempts, full, work, onTime, wins, losses));
            }
        }
        return results;
    }

    static void writeRows(Path path, List<Row> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
            for (var row : rows) {
                writer.write(JSON.writeValueAsString(row));
                writer.newLine();
            }
        }
    }

    static void writeJson(Path path, Object value) throws IOException {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
