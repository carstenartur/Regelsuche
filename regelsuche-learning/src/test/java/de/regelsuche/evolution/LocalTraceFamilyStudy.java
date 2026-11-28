package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** Prospective public development comparison. Training and final evaluation are separate processes. */
public final class LocalTraceFamilyStudy {
    private static final String PROTOCOL = "f070e56c9c7537010a963aea90d91979d1334f44";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final EvolutionGenome INVENTORY = TraceStrategyTransferExample.inventory();
    enum Arm { BASE, ORDER, LOCAL, DROP, WHOLE, HAND }

    record Trace(String source, boolean solved, long work, List<String> selected) { }
    record Gate(String source, long baseline, long previous, long candidate, Long routed) { }
    record Formation(String decision, String snapshotHash, int schemas, CumulativeTaskKnowledge.Costs costs,
            List<Trace> train, List<Gate> gate, List<CheckedLearnedSchemaModel.Attempt> attempts) { }
    record Freeze(String protocol, String inventory, Map<String, Integer> priorities, long orderTrainingWork,
            long tallyWork, Formation whole, Formation local) { }
    record Step(String rule, String kind, String source, String target, long verification) { }
    record Row(String family, int n, String source, int maximumNodes, String outcome, boolean solved,
            boolean accountingComplete, String output, long outputScore, long queryWork, long verification,
            long finalReplay, long externalAudit, List<Step> steps) { }
    record Observation(Arm arm, long acquisition, long restoration, int schemas, long wallNanos,
            long threadCpuNanos, List<Row> rows) { }
    record Replicate(int rotation, String freezeHash, List<Observation> arms) { }

    private LocalTraceFamilyStudy() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("train")) {
            train(Path.of(args[1]));
        } else if (args.length == 5 && args[0].equals("final")) {
            evaluate(Path.of(args[1]), args[2], Integer.parseInt(args[3]), Path.of(args[4]));
        } else {
            throw new IllegalArgumentException("train DIR | final DIR SHA256 ROTATION OUTPUT.json");
        }
    }

    static void train(Path dir) throws IOException {
        Files.createDirectories(dir);
        var train = LocalTraceFamilyCases.train().stream().map(LocalTraceFamilyCases.Case::task).toList();
        var gate = LocalTraceFamilyCases.gate().stream().map(LocalTraceFamilyCases.Case::task).toList();
        var base = CumulativeTaskKnowledge.empty(INVENTORY);
        var whole = base.learnBatch(train, gate, 1_000_000);
        var local = base.learnLocalBatch(train, gate, 1_000_000);
        var oldPaths = whole.training().stream().map(LocalTraceFamilyStudy::trace).toList();
        var paths = local.training().stream().map(LocalTraceFamilyStudy::trace).toList();
        if (!paths.equals(oldPaths) || paths.size() != train.size()) {
            throw new IllegalStateException("expected identical primitive TRAIN paths in both formation arms");
        }
        var order = new TreeMap<String, Integer>();
        long tally = 0;
        for (Trace path : paths) {
            if (path.solved()) for (String rule : path.selected()) {
                order.merge(rule, 1, Math::addExact);
                tally++;
            }
        }
        var frozen = new Freeze(PROTOCOL, INVENTORY.contentHash(), order,
            paths.stream().mapToLong(Trace::work).sum(), tally,
            formation(dir, "whole.json", whole), formation(dir, "local.json", local));
        String evidence = writeJson(dir.resolve("freeze.json"), frozen);
        System.out.println("Freeze " + SchematicProofPlan.hash(evidence));
    }

    private static Trace trace(CumulativeTaskKnowledge.Execution run) {
        var solution = run.solution().orElseThrow();
        return new Trace(ExpressionFormatter.format(run.task().source()), run.solved(), run.totalWork(),
            solution.evidence().witness().stream().map(s -> s.move().ruleId()).toList());
    }

    private static Formation formation(Path dir, String file, CumulativeTaskKnowledge.Update update) throws IOException {
        String snapshot = update.next().toCanonicalJson();
        Files.writeString(dir.resolve(file), snapshot, StandardOpenOption.CREATE_NEW);
        return new Formation(update.reason().name(), SchematicProofPlan.hash(snapshot), update.next().schemaCount(),
            update.work(), update.training().stream().map(LocalTraceFamilyStudy::trace).toList(),
            update.validation().stream().map(v -> new Gate(ExpressionFormatter.format(v.task().source()),
                v.baseline().totalWork(), v.previous().totalWork(), v.candidate().totalWork(),
                v.automatic().map(CumulativeTaskKnowledge.Execution::totalWork).orElse(null))).toList(), update.attempts());
    }

    private static void evaluate(Path dir, String digest, int rotation, Path output) throws IOException {
        String original = Files.readString(dir.resolve("freeze.json"));
        if (!SchematicProofPlan.hash(original).equals(digest) || rotation < 0 || rotation > 2) {
            throw new IllegalArgumentException("wrong freeze digest or rotation");
        }
        Freeze freeze = JSON.readValue(original, Freeze.class);
        if (!PROTOCOL.equals(freeze.protocol()) || !INVENTORY.contentHash().equals(freeze.inventory())) {
            throw new IllegalArgumentException("wrong experiment or primitive inventory");
        }
        var results = new ArrayList<Observation>();
        var arms = List.of(Arm.values());
        for (int index = 0; index < arms.size(); index++) {
            Arm arm = arms.get((index + rotation * 2) % arms.size());
            results.add(evaluateArm(arm, dir, freeze));
        }
        writeJson(output, new Replicate(rotation, digest, results));
    }

    private static Observation evaluateArm(Arm arm, Path dir, Freeze freeze) throws IOException {
        var timer = ManagementFactory.getThreadMXBean();
        if (!timer.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("current thread CPU clock unavailable");
        long wall = System.nanoTime(), cpu = timer.getCurrentThreadCpuTime();
        var primitives = TypedLearnedMoveInventory.primitives(INVENTORY);
        var solver = primitives.newTaskSolver();
        long acquisition = 0, setup = 0;
        int schemas = 0;
        if (arm == Arm.ORDER) acquisition = freeze.orderTrainingWork() + freeze.tallyWork();
        if (arm == Arm.HAND) solver = primitives.newTaskSolverWithProfile(TaskConstructionProfile.POLYNOMIAL_DIRECT_V1);
        if (arm == Arm.LOCAL || arm == Arm.DROP || arm == Arm.WHOLE) {
            boolean whole = arm == Arm.WHOLE;
            var formation = whole ? freeze.whole() : freeze.local();
            String snapshot = Files.readString(dir.resolve(whole ? "whole.json" : "local.json"));
            if (!formation.snapshotHash().equals(SchematicProofPlan.hash(snapshot))) {
                throw new IllegalArgumentException("changed frozen knowledge");
            }
            var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(snapshot).model(), INVENTORY.contentHash());
            schemas = model.schemas().size();
            if (schemas != formation.schemas()) throw new IllegalArgumentException("changed schema count");
            if (schemas > 0) solver = primitives.newTaskSolver(model);
            setup = model.loadWork();
            acquisition = formation.costs().total() + freeze.tallyWork();
        }
        setup = Math.addExact(setup, solver.preparationWork());
        var rows = new ArrayList<Row>();
        for (var input : LocalTraceFamilyCases.finalCases()) {
            var task = input.task();
            var result = arm == Arm.BASE || arm == Arm.HAND ? solver.solve(task).evidence()
                : ordered(solver.problem(task), task, freeze.priorities(), arm == Arm.DROP);
            rows.add(row(input, result));
        }
        return new Observation(arm, acquisition, setup, schemas, System.nanoTime() - wall,
            timer.getCurrentThreadCpuTime() - cpu, List.copyOf(rows));
    }

    private static TypedSourceOnlySearch.Result ordered(TypedMoveSearch.Problem ordinary, CheckedSchemaTaskSolver.Task task,
            Map<String, Integer> priorities, boolean dropLearned) {
        var providers = ordinary.providers().stream()
            .filter(p -> !dropLearned || p.descriptor().sourceKind() != SearchMove.SourceKind.LEARNED).toList();
        int primitiveProviders = (int) providers.stream().filter(p -> p.descriptor().sourceKind() == SearchMove.SourceKind.PRIMITIVE).count();
        TypedMoveSearch.TypedPolicy ranked = new TypedMoveSearch.TypedPolicy() {
            @Override public double score(SearchMove move, MoveState state, MoveContext context) { return 0; }
            @Override public Stage stage(MoveProvider.Descriptor p, MoveState state, MoveContext context) {
                return ordinary.policy().stage(p, state, context);
            }
            @Override public double providerScore(MoveProvider.Descriptor p, MoveState state, MoveContext context) {
                return p.sourceKind() == SearchMove.SourceKind.PRIMITIVE ? priorities.getOrDefault(p.id(), 0) : 0;
            }
            @Override public long contextWork(MoveState state, MoveContext context) {
                // Each picker scores each descriptor once. One extra frequency lookup per primitive.
                return primitiveProviders;
            }
        };
        var problem = new TypedMoveSearch.Problem(ordinary.source(), ordinary.context(), providers, ranked,
            ordinary.verifier(), ordinary.stateScore(), ordinary.mode(), ordinary.scheduling(), ordinary.budget(), ordinary.stateValue());
        return CompiledAstReplayCodec.withExpressionCache(256, 2_000_000,
            () -> new TypedSourceOnlySearch().searchUntil(problem, task.goal().objective(), task.goal().maximumOutputScore(),
                SearchContinuationContract.PATH_SENSITIVE));
    }

    private static Row row(LocalTraceFamilyCases.Case input, TypedSourceOnlySearch.Result result) {
        String output = ExpressionFormatter.format(result.incumbent().expression());
        long[] audit = {0};
        var polynomial = new ExactPolynomialAnalysis(units -> audit[0] = Math.addExact(audit[0], units));
        polynomial.requireEquivalent(input.source(), output);
        var steps = new ArrayList<Step>();
        for (var step : result.witness()) {
            if (!step.verification().accepted()) throw new IllegalStateException("unverified selected move");
            steps.add(new Step(step.move().ruleId(), step.move().sourceKind().name(),
                ExpressionFormatter.format(step.source().expression()), ExpressionFormatter.format(step.target().expression()),
                step.verification().work()));
        }
        boolean solved = result.withinBudget() && result.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED
            && result.outputScore() <= input.nodes();
        return new Row(input.family(), input.n(), input.source(), input.nodes(), result.search().outcome().name(), solved,
            result.accountingComplete(), output, result.outputScore(), result.totalWork(),
            result.search().metrics().verificationWork(), result.replayWork(), audit[0], List.copyOf(steps));
    }

    private static String writeJson(Path path, Object value) throws IOException {
        String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
        Files.writeString(path, text, StandardOpenOption.CREATE_NEW);
        return text;
    }
}
