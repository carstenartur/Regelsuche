package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.TransformationProvenance;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class DistributiveCoverageStudyTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final EvolutionGenome INVENTORY = TraceStrategyTransferExample.inventory();
    private static final String PROTOCOL_HASH = "sha256:8a36e7ce282f64de7406f3a58462b58345344b8091dec8399a0a7f9696cbb8ad";
    enum Arm { BASE, LEARNED, DROP }
    private record Gate(String source, long baseline, long candidate, long routed, boolean baselineSolved, boolean candidateSolved) {}
    private record Trace(String source, boolean solved, String output, long work, List<String> primitiveRules) {}
    private record Freeze(String protocol, String inventory, String modelSnapshot, String decision, int schemas,
            CumulativeTaskKnowledge.Costs acquisition, List<Trace> training, List<Gate> gate) {}
    private record Step(String rule, String schema, String source, String target, long verification) {}
    private record Row(Arm arm, String family, int n, int budget, int goal, String outcome, boolean solved,
            boolean withinBudget, boolean complete, String output, long score, long work, long verification,
            long replay, long audit, int learnedSteps, String path) {}
    private record Timing(Arm arm, long acquisition, long loadAndPrepare, long elapsedNanos, long threadCpuNanos) {}

    @Test
    void malformedFreezeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> checkHash("altered", "sha256:000"));
    }

    @Test
    @EnabledIfSystemProperty(named = "distributive.phase", matches = "train|final")
    void runProtocol() throws Exception {
        Path dir = Path.of(System.getProperty("distributive.directory"));
        Files.createDirectories(dir);
        if (System.getProperty("distributive.phase").equals("train")) train(dir);
        else evaluate(dir, System.getProperty("distributive.digest"),
            Integer.parseInt(System.getProperty("distributive.rotation")), Path.of(System.getProperty("distributive.output")));
    }

    private static void train(Path dir) throws IOException {
        var start = CumulativeTaskKnowledge.empty(INVENTORY);
        var lessons = DistributiveCoverageCases.training().stream().map(c -> c.task(DistributiveCoverageCases.REFERENCE)).toList();
        var gates = DistributiveCoverageCases.gate().stream().map(c -> c.task(DistributiveCoverageCases.REFERENCE)).toList();
        var update = start.learnLocalBatch(lessons, gates, 1_000_000);
        String snapshot = update.next().toCanonicalJson();
        Files.writeString(dir.resolve("snapshot.json"), snapshot, StandardOpenOption.CREATE_NEW);
        var traces = update.training().stream().map(execution -> {
            var result = execution.solution().orElseThrow().evidence();
            return new Trace(ExpressionFormatter.format(execution.task().source()), execution.solved(),
                ExpressionFormatter.format(result.incumbent().expression()), execution.totalWork(),
                result.witness().stream().map(step -> step.move().ruleId()).toList());
        }).toList();
        var trials = update.validation().stream().map(g -> new Gate(ExpressionFormatter.format(g.task().source()),
            g.baseline().totalWork(), g.candidate().totalWork(), g.automatic().map(CumulativeTaskKnowledge.Execution::totalWork).orElse(-1L),
            g.baseline().solved(), g.candidate().solved())).toList();
        var freeze = new Freeze(PROTOCOL_HASH, INVENTORY.contentHash(), SchematicProofPlan.hash(snapshot),
            update.reason().name(), update.next().schemaCount(), update.work(), traces, trials);
        write(dir.resolve("freeze.json"), freeze);
        System.out.println("Distributive freeze " + SchematicProofPlan.hash(Files.readString(dir.resolve("freeze.json"))));
    }

    private static void checkHash(String data, String expected) {
        if (!SchematicProofPlan.hash(data).equals(expected)) throw new IllegalArgumentException("incorrect frozen inputs");
    }

    private static void evaluate(Path dir, String digest, int rotation, Path out) throws IOException {
        String record = Files.readString(dir.resolve("freeze.json"));
        checkHash(record, digest);
        var freeze = JSON.readValue(record, Freeze.class);
        if (!freeze.protocol().equals(PROTOCOL_HASH) || !freeze.inventory().equals(INVENTORY.contentHash()))
            throw new IllegalArgumentException("wrong protocol or inventory");
        String snapshot = Files.readString(dir.resolve("snapshot.json"));
        checkHash(snapshot, freeze.modelSnapshot());
        var arms = switch (rotation) {
            case 0 -> List.of(Arm.BASE, Arm.LEARNED, Arm.DROP);
            case 1 -> List.of(Arm.DROP, Arm.LEARNED, Arm.BASE);
            default -> throw new IllegalArgumentException("rotation");
        };
        Files.createDirectory(out);
        write(out.resolve("split.json"), alphaOverlaps());
        var paths = new TreeMap<String,List<Step>>();
        var times = new ArrayList<Timing>();
        try (var rows = Files.newBufferedWriter(out.resolve("rows.jsonl"), StandardOpenOption.CREATE_NEW)) {
            for (var arm : arms) times.add(measure(arm, freeze, snapshot, paths, rows));
        }
        write(out.resolve("times.json"), times);
        write(out.resolve("paths.json"), paths);
    }

    private static Timing measure(Arm arm, Freeze freeze, String snapshot, Map<String,List<Step>> paths,
            BufferedWriter rows) throws IOException {
        var timer = ManagementFactory.getThreadMXBean();
        if (!timer.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("missing thread CPU counter");
        long cpu = timer.getCurrentThreadCpuTime(), wall = System.nanoTime();
        var inventory = TypedLearnedMoveInventory.primitives(INVENTORY);
        var solver = inventory.newTaskSolver();
        long setup = 0, acquisition = 0;
        if (arm != Arm.BASE) {
            var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(snapshot).model(), INVENTORY.contentHash());
            if (model.schemas().size() != freeze.schemas()) throw new IllegalStateException("bad model");
            if (!model.schemas().isEmpty()) solver = inventory.newTaskSolverWithSelection(model, true);
            setup = Math.addExact(model.loadWork(), solver.preparationWork());
            acquisition = freeze.acquisition().total();
        }
        for (var input : DistributiveCoverageCases.panel()) {
            for (int budget : DistributiveCoverageCases.WORK) {
                var task = input.task(budget);
                var result = arm == Arm.DROP && freeze.schemas() > 0 ? drop(solver, task) : solver.solve(task).evidence();
                rows.write(JSON.writeValueAsString(row(arm, input, result, budget, paths)));
                rows.newLine();
            }
        }
        return new Timing(arm, acquisition, setup, System.nanoTime()-wall, timer.getCurrentThreadCpuTime()-cpu);
    }

    private static TypedSourceOnlySearch.Result drop(CheckedSchemaTaskSolver solver, CheckedSchemaTaskSolver.Task task) {
        return CompiledAstReplayCodec.withExpressionCache(256, 2_000_000, () -> {
            var p = solver.problem(task);
            var providers = p.providers().stream().filter(provider -> provider.descriptor().sourceKind() != SearchMove.SourceKind.LEARNED).toList();
            var dropped = new TypedMoveSearch.Problem(p.source(), p.context(), providers, p.policy(), p.verifier(),
                p.stateScore(), p.mode(), p.scheduling(), p.budget(), p.stateValue());
            return new TypedSourceOnlySearch().searchUntil(dropped, task.goal().objective(), task.goal().maximumOutputScore(),
                SearchContinuationContract.PATH_SENSITIVE);
        });
    }

    private static Row row(Arm arm, DistributiveCoverageCases.Case input, TypedSourceOnlySearch.Result result,
            int budget, Map<String,List<Step>> paths) throws IOException {
        long[] audit = {0};
        String output = ExpressionFormatter.format(result.incumbent().expression());
        new ExactPolynomialAnalysis(n -> audit[0] = Math.addExact(audit[0], n)).requireEquivalent(input.source(), output);
        var steps = result.witness().stream().map(s -> {
            if (!s.verification().accepted()) throw new IllegalStateException("unverified witness");
            String schema = s.move().sourceKind() == SearchMove.SourceKind.LEARNED
                ? ((TransformationProvenance.ExactTheoryStep) s.move().provenance()).evidence().binding().theoryStepId() : null;
            return new Step(s.move().ruleId(), schema, ExpressionFormatter.format(s.source().expression()),
                ExpressionFormatter.format(s.target().expression()), s.verification().work());
        }).toList();
        String hash = SchematicProofPlan.hash(JSON.writeValueAsString(steps));
        var previous = paths.putIfAbsent(hash, steps);
        if (previous != null && !previous.equals(steps)) throw new IllegalStateException("ambiguous witness");
        boolean solved = result.withinBudget() && result.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED
            && result.outputScore() <= input.nodes();
        return new Row(arm, input.family(), input.n(), budget, input.nodes(), result.search().outcome().name(), solved,
            result.withinBudget(), result.accountingComplete(), output, result.outputScore(), result.totalWork(),
            result.search().metrics().verificationWork(), result.replayWork(), audit[0],
            Math.toIntExact(steps.stream().filter(s -> s.schema() != null).count()), hash);
    }

    private static Map<String,List<String>> alphaOverlaps() {
        var checker = new ExactPolynomialAnalysis();
        var groups = Map.of("TRAIN", DistributiveCoverageCases.training(), "GATE", DistributiveCoverageCases.gate(),
            "FINAL", DistributiveCoverageCases.panel());
        var identities = new TreeMap<String,List<String>>();
        for (var group : groups.entrySet()) for (var c : group.getValue())
            identities.computeIfAbsent(checker.alphaIdentity(c.source()), ignored -> new ArrayList<>())
                .add(group.getKey()+"/"+c.family()+"/"+c.n());
        identities.values().removeIf(v -> v.size() < 2);
        identities.values().forEach(Collections::sort);
        return identities;
    }

    private static void write(Path file, Object value) throws IOException {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n", StandardOpenOption.CREATE_NEW);
    }
}
