package de.regelsuche.evolution;

import static de.regelsuche.ast.BinaryOperator.*;

import de.regelsuche.ast.*;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.inventory.HistoryMovePolicy;
import de.regelsuche.inventory.RuleHistoryMemory;
import de.regelsuche.inventory.TypedPolicySelection;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.symbol.SymbolId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Public development diagnostics; profile selection sees TRAIN only, never these evaluation outcomes. */
public final class TypedLearningWorkStudy {
    public static final String REVISION = "regelsuche.typed-learning-work-development/v1";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final List<Long> WORK_BUDGETS = List.of(50L, 150L, 500L, 2000L);
    private static final long TRAIN_BUDGET = 10_000;
    private enum Selection { INVENTORY, DEFAULT, TRAIN_SELECTED }
    public enum Configuration {
        PRIMITIVE_INVENTORY(false, Selection.INVENTORY), PRIMITIVE_DEFAULT(false, Selection.DEFAULT),
        PRIMITIVE_SELECTED(false, Selection.TRAIN_SELECTED), LEARNED_INVENTORY(true, Selection.INVENTORY),
        LEARNED_DEFAULT(true, Selection.DEFAULT), LEARNED_SELECTED(true, Selection.TRAIN_SELECTED);
        private final boolean learned;
        private final Selection selection;
        Configuration(boolean learned, Selection selection) { this.learned = learned; this.selection = selection; }
    }
    private record Case(String id, Expr source, Expr goal) {}
    public record Row(String caseId, Configuration configuration, long budget, TypedMoveSearch.Result result) {}
    public record TrainingRun(String id, String source, String goal, TypedMoveSearch.Result result) {}
    public record Training(TypedPolicySelection.FrozenPolicy policy, List<TrainingRun> historyRuns, long memoryWork) {
        public Training { historyRuns = List.copyOf(historyRuns); }
        public long historySearchWork() {
            return historyRuns.stream().mapToLong(run -> run.result().metrics().totalWork()).reduce(0, Math::addExact);
        }
    }
    public record Report(String protocol, TraceRewriteStrategyLearner.FrozenStrategy formation, long formationWork,
            Training primitive, Training learned, List<Row> rows) {
        public Report { rows = List.copyOf(rows); }
        public String modelJson() { return TypedLearningWorkStudy.modelJson(formation, formationWork, primitive, learned); }
        public String summaryJson() {
            return LearnedSchedulingArtifacts.json(Map.of("schema", REVISION,
                "claim", "PUBLIC_DEVELOPMENT_ONLY;NOT_CPU_OR_GENERAL_SUPERIORITY",
                "protocolHash", SchematicProofPlan.hash(protocol), "modelHash", SchematicProofPlan.hash(modelJson()),
                "formationWork", formationWork, "training", trainingSummary(primitive, learned),
                "rows", rows.stream().map(TypedLearningWorkStudy::rowSummary).toList()));
        }
    }
    private TypedLearningWorkStudy() {}

    public static Report run() {
        String protocol = protocol();
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var inventory = formation.typedMoves();
        var primitive = train(formation, inventory, false);
        var learned = train(formation, inventory, true);
        long formationWork = inventory.formationWork();
        String frozen = modelJson(formation, formationWork, primitive, learned);
        var rows = new ArrayList<Row>();
        for (var example : cases()) for (long budget : WORK_BUDGETS) for (var configuration : Configuration.values()) {
            var training = configuration.learned ? learned : primitive;
            var policy = configuration.selection == Selection.DEFAULT
                ? HistoryMovePolicy.typed(training.policy().history(), HistoryMovePolicy.Weights.DEFAULT)
                : MovePriorityPolicy.INVENTORY_ORDER;
            var problem = problem(inventory, configuration.learned, example, MoveContext.Phase.FROZEN_EVALUATION, budget, policy);
            var result = configuration.selection == Selection.TRAIN_SELECTED
                ? training.policy().evaluate(problem) : new TypedMoveSearch().search(problem);
            if (result.reached() && result.metrics().totalWork() > budget) throw new IllegalStateException("over-budget success");
            rows.add(new Row(example.id(), configuration, budget, result));
        }
        if (!frozen.equals(modelJson(formation, formationWork, primitive, learned))) {
            throw new IllegalStateException("evaluation changed the frozen model");
        }
        return new Report(protocol, formation, formationWork, primitive, learned, rows);
    }

    private static Training train(TraceRewriteStrategyLearner.FrozenStrategy formation,
            TypedLearnedMoveInventory inventory, boolean learned) {
        var history = new RuleHistoryMemory();
        var runs = new ArrayList<TrainingRun>();
        var tasks = new ArrayList<TypedPolicySelection.TrainingTask>();
        for (var observation : formation.observations()) {
            var example = new Case(observation.input().id(), parseInput(observation.input().expression()),
                parseInput(observation.search().bestState().expression()));
            var problem = problem(inventory, learned, example, MoveContext.Phase.TRAIN, TRAIN_BUDGET,
                MovePriorityPolicy.INVENTORY_ORDER);
            var result = new TypedMoveSearch().search(problem);
            history.observe(result, MoveContext.Phase.TRAIN, Map.of());
            runs.add(new TrainingRun(example.id(), CODEC.encodeExpression(example.source()), CODEC.encodeExpression(example.goal()), result));
            tasks.add(new TypedPolicySelection.TrainingTask(example.id(), problem));
        }
        return new Training(new TypedPolicySelection().train(history.freeze(), tasks, profiles()), runs, history.measuredWork());
    }

    private static List<TypedPolicySelection.Profile> profiles() {
        return List.of(TypedPolicySelection.Profile.inventoryOrder("inventory"),
            new TypedPolicySelection.Profile("default", HistoryMovePolicy.Weights.DEFAULT),
            new TypedPolicySelection.Profile("goal", new HistoryMovePolicy.Weights(0, 0, 0, 20, 0, 0, 0, 0)));
    }

    private static TypedMoveSearch.Problem problem(TypedLearnedMoveInventory inventory, boolean learned,
            Case example, MoveContext.Phase phase, long work, MovePriorityPolicy policy) {
        return new TypedMoveSearch.Problem(example.source(), new TypedMoveSearch.Context(example.goal(), List.of(), phase),
            learned ? inventory.providers() : inventory.primitiveProviders(), policy, inventory.verifier(), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(12, 12, 0, 256, work));
    }

    /** Parsing happens only at the existing learner's text-input boundary, never inside typed dispatch. */
    private static Expr parseInput(String input) {
        return new ExpressionParser().parse(new InputRequest(InputType.TERM, input)).terms().getFirst();
    }

    private static List<Case> cases() {
        var examples = new ArrayList<Case>();
        var ids = List.of("one-site", "two-sites", "three-sites");
        for (int count = 1; count <= 3; count++) {
            var sources = new ArrayList<Expr>(); var goals = new ArrayList<Expr>();
            for (int i = 0; i < count; i++) {
                Expr a = base(i), b = variable(2 * i + 1);
                sources.add(add(mul(add(a, b), new BinaryExpr(a, SUB, b)), mul(b, b)));
                goals.add(square(a));
            }
            examples.add(new Case(ids.get(count - 1), new FunctionExpr("context", sources), new FunctionExpr("context", goals)));
        }
        Expr a = base(10), b = variable(21);
        examples.add(new Case("near-miss", add(mul(add(a, b), new BinaryExpr(a, SUB, b)), mul(b, add(b, new NumberExpr(1)))), square(a)));
        examples.add(new Case("simple-zero", add(base(20), new NumberExpr(0)), base(20)));
        return List.copyOf(examples);
    }

    private static Expr base(int index) { return new FunctionExpr("f", List.of(variable(2 * index), NumberExpr.exact("1/3"))); }
    private static Expr variable(int index) { return VariableExpr.scoped(new SymbolId(new UUID(0, 901), index + 1)); }
    private static Expr add(Expr a, Expr b) { return new BinaryExpr(a, ADD, b); }
    private static Expr mul(Expr a, Expr b) { return new BinaryExpr(a, MUL, b); }
    private static Expr square(Expr a) { return new BinaryExpr(a, POW, new NumberExpr(2)); }

    private static String protocol() {
        return LearnedSchedulingArtifacts.json(Map.of("schema", REVISION,
            "information", "SHARED_EXPLICIT_EVALUATION_GOALS;TRAIN_TARGETS_FROM_TARGET_FREE_LEARNER_ENDPOINTS",
            "split", "PUBLIC_DEVELOPMENT_AFTER_EXPLORATION;NOT_INDEPENDENT_FAMILY_HOLDOUT",
            "inventory", TraceStrategyTransferExample.inventory().toCanonicalJson(),
            "formation", Map.of("inputs", TraceStrategyTransferExample.trainingInputs(), "limits", TraceStrategyTransferExample.limits()),
            "profiles", profiles(), "configurations", List.of(Configuration.values()),
            "budgets", Map.of("evaluation", WORK_BUDGETS, "train", TRAIN_BUDGET, "primitiveDepth", 12, "searchDepth", 12, "states", 256),
            "evaluation", cases().stream().map(example -> Map.of("id", example.id(), "source", CODEC.encodeExpression(example.source()),
                "goal", CODEC.encodeExpression(example.goal()))).toList()));
    }

    private static String modelJson(TraceRewriteStrategyLearner.FrozenStrategy formation, long formationWork,
            Training primitive, Training learned) {
        return LearnedSchedulingArtifacts.json(Map.of("formation", formation.toCanonicalJson(), "formationWork", formationWork,
            "primitivePolicy", primitive.policy().toCanonicalJson(), "learnedPolicy", learned.policy().toCanonicalJson(),
            "training", trainingSummary(primitive, learned),
            "workScope", "CHARGED_MECHANICS_AND_REPLAY;EXCLUDES_COMPILATION_SERIALIZATION_AND_COMPLETE_CPU_BIT_COST"));
    }

    private static Map<String, Object> trainingSummary(Training primitive, Training learned) {
        return Map.of("primitive", trainingSummary(primitive), "learned", trainingSummary(learned));
    }
    private static Map<String, Object> trainingSummary(Training training) {
        return Map.of("selected", training.policy().selected().id(), "historySearchWork", training.historySearchWork(),
            "historyMemoryWork", training.memoryWork(), "profileSelectionWork", training.policy().trainingWork());
    }

    private static Map<String, Object> rowSummary(Row row) {
        var metrics = row.result().metrics();
        var data = new TreeMap<String, Object>();
        data.put("case", row.caseId()); data.put("configuration", row.configuration()); data.put("budget", row.budget());
        data.put("outcome", row.result().outcome()); data.put("totalWork", metrics.totalWork());
        data.put("primitiveWork", metrics.primitiveWork()); data.put("searchWork", metrics.searchWork());
        data.put("verificationWork", metrics.verificationWork()); data.put("exploredStates", metrics.exploredStates());
        data.put("primitiveDepth", metrics.firstHitPrimitiveDepth());
        data.put("learnedWitness", row.result().witness().stream().anyMatch(step -> step.move().sourceKind() == SearchMove.SourceKind.LEARNED));
        return data;
    }

    public static Path write(Report report, Path output) throws IOException {
        var files = new TreeMap<String, String>();
        files.put("protocol.json", report.protocol()); files.put("model.json", report.modelJson()); files.put("summary.json", report.summaryJson());
        writeTraining(files, "primitive", report.primitive()); writeTraining(files, "learned", report.learned());
        for (var row : report.rows()) files.put("run-" + row.caseId() + "-" + row.configuration() + "-" + row.budget() + ".json",
            LearnedSchedulingArtifacts.resultJson(row.result().encodedResult()));
        return TraceStrategyTransferExample.writeArtifacts(SchematicProofPlan.hash(report.summaryJson()), files, output, REVISION);
    }
    private static void writeTraining(Map<String, String> files, String inventory, Training training) {
        for (var run : training.historyRuns()) files.put("train-" + inventory + "-" + run.id() + ".json", LearnedSchedulingArtifacts.json(
            Map.of("source", run.source(), "goal", run.goal(), "search", LearnedSchedulingArtifacts.resultJson(run.result().encodedResult()))));
    }

    public static void main(String[] args) throws IOException {
        var report = run();
        System.out.println(write(report, Path.of(args.length == 0 ? "build/reports/typed-learning-work" : args[0])));
        System.out.println(report.summaryJson());
    }
}
