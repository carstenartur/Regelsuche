package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class HornerTransferStudyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Long> DEADLINES = List.of(16_384L, 32_768L, 65_536L, 131_072L, 262_144L);
    private static final long PRIMARY = 131_072;
    private static final String PROTOCOL = "sha256:f2c2f07b6709b2bab033721259a39f9d2b3a8bb289dbcb4bfe245f83e3208dda";
    private static final String RECEIPT = "sha256:6863b403ed13292dd4aefde8603ba83b1f7ff221ee5dab091a7f019108947930";
    private static final String SNAPSHOT = "sha256:9e56f3640b85520b8b4090f64aa96c14bb703d3d218ab6fd60c2cdb47e74e223";
    private static final EvolutionGenome INVENTORY = TraceStrategyTransferExample.inventory();

    private enum Arm { BASE, PRIOR, REGEN, DROP }
    private record Frozen(String snapshot, long acquisition) {}
    private record Setup(CheckedSchemaTaskSolver solver, long acquisition, long load, long preparation) {
        long total() { return Math.addExact(acquisition, Math.addExact(load, preparation)); }
    }
    private record Step(String rule, String source, String target, String learnedEvidence, long verification) {}
    private record Row(int index, int distributed, String source, String output, long score, String outcome, boolean withinQueryBudget,
        boolean solved, long query, long verificationWithinQuery, long replay, long audit, long completedAt, int learnedSteps, String pathHash) {}
    private record Checkpoint(long budget, int completed, int solved) {}
    private record Summary(long acquisition, long load, long preparation, List<Checkpoint> checkpoints, int totalSolved, long fullCost) {
        int primarySolved() { return checkpoints.stream().filter(c -> c.budget() == PRIMARY).findFirst().orElseThrow().solved(); }
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

    private static Row clockRow(boolean solved, long completedAt) {
        return new Row(0, 0, "", "", 0, "", solved, solved, 0, 0, 0, 0, completedAt, 0, "");
    }

    @Test
    @EnabledIfSystemProperty(named = "horner.phase", matches = "final")
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
        var inputs = HornerTransferCases.panel(seed);
        HornerTransferCases.verifySplit(inputs);
        Path out = Files.createDirectory(Path.of(System.getProperty("horner.output")));
        var summaries = new EnumMap<Arm, Summary>(Arm.class);
        var elapsed = new EnumMap<Arm, Long>(Arm.class);
        Map<String, List<Step>> paths = new TreeMap<>();
        for (Arm arm : order) {
            long start = System.nanoTime();
            Setup setup = prepare(arm, frozen);
            var rows = run(arm, setup, inputs, paths);
            elapsed.put(arm, System.nanoTime() - start);
            writeRows(out.resolve(arm.name() + ".jsonl"), rows);
            summaries.put(arm, summarize(setup, rows));
        }
        int regen = summaries.get(Arm.REGEN).primarySolved();
        var report = Map.of("freeze", seed, "protocol", PROTOCOL, "model", SNAPSHOT, "primaryBudget", PRIMARY,
            "passed", primaryPassed(regen, summaries.get(Arm.BASE).primarySolved(), summaries.get(Arm.DROP).primarySolved()),
            "improvedOnPriorAtPrimary", regen > summaries.get(Arm.PRIOR).primarySolved(), "arms", summaries);
        writeJson(out.resolve("summary.json"), new TreeMap<>(report));
        writeJson(out.resolve("paths.json"), paths);
        writeJson(out.resolve("elapsed-nanos.json"), elapsed);
    }

    private static boolean primaryPassed(int learned, int base, int drop) { return learned > base && learned > drop; }

    private static Frozen readFrozenInputs(Path research) throws IOException {
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

    private static String readChecked(Path path, String digest) throws IOException {
        String contents = Files.readString(path);
        if (!SchematicProofPlan.hash(contents).equals(digest)) throw new IllegalStateException("freeze mismatch in " + path);
        return contents;
    }

    private static Setup prepare(Arm arm, Frozen frozen) {
        var inventory = TypedLearnedMoveInventory.primitives(INVENTORY);
        if (arm == Arm.BASE) {
            var base = inventory.newTaskSolver();
            return new Setup(base, 0, 0, base.preparationWork());
        }
        var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(frozen.snapshot()).model(), INVENTORY.contentHash());
        var solver = arm == Arm.PRIOR ? inventory.newTaskSolverWithSelection(model, true)
            : inventory.newTaskSolverWithRegeneratedWitness(model);
        return new Setup(solver, frozen.acquisition(), model.loadWork(), solver.preparationWork());
    }

    private static List<Row> run(Arm arm, Setup setup, List<HornerTransferCases.Input> inputs,
            Map<String, List<Step>> paths) throws IOException {
        var rows = new ArrayList<Row>();
        long clock = setup.total();
        for (var input : inputs) {
            var task = input.task();
            var result = arm == Arm.DROP ? drop(setup.solver(), task) : setup.solver().solve(task).evidence();
            if (!result.accountingComplete()) throw new IllegalStateException("incomplete solver accounting on " + input.index());
            String output = ExpressionFormatter.format(result.incumbent().expression());
            long[] audit = {0};
            ExactPolynomialAnalysis.withProductWork(n -> audit[0] = Math.addExact(audit[0], n)).requireEquivalent(input.source(), output);
            clock = Math.addExact(clock, Math.addExact(result.totalWork(), audit[0]));
            var path = result.witness().stream().map(HornerTransferStudyTest::step).toList();
            String hash = SchematicProofPlan.hash(JSON.writeValueAsString(path));
            var earlier = paths.putIfAbsent(hash, path);
            if (earlier != null && !earlier.equals(path)) throw new IllegalStateException("witness hash collision");
            boolean solved = result.withinBudget() && result.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED
                && result.outputScore() <= HornerTransferCases.MAX_NODES;
            int learned = Math.toIntExact(path.stream().filter(step -> step.learnedEvidence() != null).count());
            rows.add(new Row(input.index(), input.distributed(), input.source(), output, result.outputScore(),
                result.search().outcome().name(), result.withinBudget(), solved, result.queryWork(),
                result.search().metrics().verificationWork(), result.replayWork(), audit[0], clock, learned, hash));
        }
        return rows;
    }

    private static Step step(TypedMoveSearch.WitnessStep step) {
        if (!step.verification().accepted()) throw new IllegalStateException("rejected witness step");
        String evidence = step.move().sourceKind() == SearchMove.SourceKind.LEARNED
            ? ((TransformationProvenance.ExactTheoryStep) step.move().provenance()).evidence().binding().evidenceHash() : null;
        return new Step(step.move().ruleId(), ExpressionFormatter.format(step.source().expression()),
            ExpressionFormatter.format(step.target().expression()), evidence, step.verification().work());
    }

    // These solvers use an unchanged native node-count objective. Filter only after paid routing.
    private static TypedSourceOnlySearch.Result drop(CheckedSchemaTaskSolver solver, CheckedSchemaTaskSolver.Task task) {
        return CompiledAstReplayCodec.withExpressionCache(256, 2_000_000, () -> {
            var p = solver.problem(task);
            var providers = p.providers().stream().filter(provider -> provider.descriptor().sourceKind() != SearchMove.SourceKind.LEARNED).toList();
            var ablated = new TypedMoveSearch.Problem(p.source(), p.context(), providers, p.policy(), p.verifier(),
                p.stateScore(), p.mode(), p.scheduling(), p.budget(), p.stateValue());
            return new TypedSourceOnlySearch().searchUntil(ablated, task.goal().objective(),
                task.goal().maximumOutputScore(), SearchContinuationContract.PATH_SENSITIVE);
        });
    }

    private static Summary summarize(Setup setup, List<Row> rows) {
        var checkpoints = DEADLINES.stream().map(budget -> checkpoint(rows, budget)).toList();
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

    private static void writeRows(Path path, List<Row> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
            for (var row : rows) {
                writer.write(JSON.writeValueAsString(row));
                writer.newLine();
            }
        }
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
