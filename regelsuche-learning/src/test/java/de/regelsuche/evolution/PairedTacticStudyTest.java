package de.regelsuche.evolution;

import static de.regelsuche.evolution.HornerTransferStudyTest.JSON;
import static de.regelsuche.evolution.HornerTransferStudyTest.writeJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import de.regelsuche.transform.TransformationProvenance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class PairedTacticStudyTest {
    private static final String PROTOCOL = "sha256:03d061e16071d3333ba44bc260b53a904d619f7e6cf81e7ddcdc7c79c81d1120";
    private static final long DEADLINE = 1_048_576;
    private static final EvolutionGenome INVENTORY = TraceStrategyTransferExample.inventory();

    enum Arm { BASE, AUTO_OLD, FAST_OLD, AUTO_FRESH, FAST_FRESH, SHARED_FRESH, POLICY, NO_TACTICS }
    record Setup(long acquisition, long calibration, long load, long preparation) {
        long total() { return Math.addExact(Math.addExact(acquisition, calibration), Math.addExact(load, preparation)); }
    }
    record Sample(long selection, TypedSourceOnlySearch.Result evidence, Boolean admitted) {
        Sample(long selection, TypedSourceOnlySearch.Result evidence) { this(selection, evidence, null); }
    }
    interface Query { Sample solve(CheckedSchemaTaskSolver.Task task); }
    record Engine(Query query, Setup setup) {}
    record Library(String snapshot, long acquisition) {
        CheckedLearnedSchemaModel load() {
            return CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(snapshot).model(), INVENTORY.contentHash());
        }
    }
    record FitReceipt(String fingerprint, long work, Object trials) {}
    record Materials(Library earlier, Library fresh, FitReceipt receipt) {}
    record Step(String rule, String source, String target, String schema, String evidence, long verification) {}
    record Row(int index, String source, int m, int orientation, int cap, int target,
        String output, long score, String outcome, boolean accountingComplete, boolean withinBudget, boolean solved,
        long selection, long query, long verificationWithinQuery, long replay, long audit, long completedAt,
        String pathHash, List<String> learnedSchemas) {}
    record Summary(Setup setup, int byDeadline, int totalSolved, long fullCost) {}
    record Knockout(int index, List<String> disabled, List<String> alsoHistorical, Row row) {}

    @Test
    void recipesKeepTheirMathematicalGrouping() {
        var stratum = new PairedTacticCases.Stratum(2, 0, 256);
        // Non-FINAL parameter range; check construction without sampling the held-out stream.
        assertEquals("((a+58001)*(1-p)+(a+58001)*p)", PairedTacticCases.blend(8000, stratum, List.of(0)));
        assertEquals("(((c+68001)+f)*((c+68001)-f)+f*f)+((b+e)*(b-e)+e*e)",
            PairedTacticCases.circle(8000, stratum, List.of(1,0)));
    }

    @Test
    @EnabledIfSystemProperty(named = "paired.phase", matches = "train|final")
    void prospectiveStudy() throws IOException {
        Path research = Path.of(System.getProperty("paired.research"));
        Path study = research.resolve("prospective-tactic-transfer");
        HornerTransferStudyTest.readChecked(study.resolve("protocol.md"), PROTOCOL);
        if ("train".equals(System.getProperty("paired.phase"))) {
            train(Files.createDirectory(Path.of(System.getProperty("paired.output"))));
            return;
        }
        Path out = Files.createDirectory(Path.of(System.getProperty("paired.output")));
        String seed = System.getProperty("paired.freeze");
        int rotation = Integer.parseInt(System.getProperty("paired.rotation"));
        if (rotation < 0 || rotation > 1) throw new IllegalArgumentException("expected rotation zero or one");
        var materials = readMaterials(research, study.resolve("training"));
        var a = PairedTacticCases.panel(PairedTacticCases.Family.A, seed);
        var b = PairedTacticCases.panel(PairedTacticCases.Family.B, seed);
        var development = Stream.of(PairedTacticTraining.lessons(), PairedTacticTraining.gates(), PairedTacticTraining.calibration())
            .flatMap(List::stream).map(task -> ExpressionFormatter.formatStructure(task.source())).toList();
        PairedTacticCases.verifySplit(Stream.concat(a.stream(), b.stream()).toList(), development);
        family(out, seed, a, materials, rotation);
        family(out, seed, b, materials, rotation);
    }

    private static Map<String, Object> taskRecord(CheckedSchemaTaskSolver.Task t) {
        return new TreeMap<>(Map.of("source", ExpressionFormatter.formatStructure(t.source()), "target", t.goal().maximumOutputScore(),
            "depth", t.budget().maxSearchDepth(), "cap", t.budget().totalWork()));
    }

    private static void train(Path out) throws IOException {
        var update = CumulativeTaskKnowledge.empty(INVENTORY)
            .learnLocalBatch(PairedTacticTraining.lessons(), PairedTacticTraining.gates(), 1_000_000);
        String snapshot = update.next().toCanonicalJson();
        Files.writeString(out.resolve("snapshot.json"), snapshot, StandardOpenOption.CREATE_NEW);
        var lessons = update.training().stream().map(exec -> {
            var e = exec.solution().orElseThrow().evidence();
            return new TreeMap<>(Map.of("task", taskRecord(exec.task()), "work", exec.totalWork(), "solved", exec.solved(),
                "output", ExpressionFormatter.formatStructure(e.incumbent().expression()), "witness", e.witness().stream().map(PairedTacticStudyTest::step).toList()));
        }).toList();
        var gates = update.validation().stream().map(v -> new TreeMap<>(Map.of("task", taskRecord(v.task()),
            "base", v.baseline().totalWork(), "previous", v.previous().totalWork(), "candidate", v.candidate().totalWork(),
            "routed", v.automatic().map(CumulativeTaskKnowledge.Execution::totalWork).orElse(0L)))).toList();
        writeJson(out.resolve("training.json"), new TreeMap<>(Map.of("protocol", PROTOCOL, "snapshot", SchematicProofPlan.hash(snapshot),
            "inventory", INVENTORY.contentHash(), "decision", update.reason(), "acquisition", update.work(),
            "training", lessons, "gate", gates, "calibration", PairedTacticTraining.calibration().stream().map(PairedTacticStudyTest::taskRecord).toList())));
        var model = new Library(snapshot, update.work().total()).load();
        var fit = LearnedTacticPolicy.fit(INVENTORY, model, PairedTacticTraining.calibration());
        writeJson(out.resolve("fit.json"), new FitReceipt(fit.policy().fingerprint(), fit.work(), fit.trials()));
    }

    static Materials readMaterials(Path research, Path training) throws IOException {
        var earlier = HornerTransferStudyTest.readFrozenInputs(research);
        var manifest = JSON.readTree(Files.readString(training.resolve("training.json")));
        if (!PROTOCOL.equals(manifest.required("protocol").asText()) || !INVENTORY.contentHash().equals(manifest.required("inventory").asText()))
            throw new IllegalStateException("foreign acquisition receipt");
        String snapshot = HornerTransferStudyTest.readChecked(training.resolve("snapshot.json"), manifest.required("snapshot").asText());
        long acquisition = JSON.treeToValue(manifest.required("acquisition"), CumulativeTaskKnowledge.Costs.class).total();
        if (acquisition != TaskKnowledgeSnapshot.decode(snapshot).costs().total()) throw new IllegalStateException("acquisition mismatch");
        return new Materials(new Library(earlier.snapshot(), earlier.acquisition()), new Library(snapshot, acquisition),
            JSON.readValue(Files.readString(training.resolve("fit.json")), FitReceipt.class));
    }

    static Engine engine(Arm arm, Materials materials, Set<String> disabled) {
        var inventory = TypedLearnedMoveInventory.primitives(INVENTORY);
        if (arm == Arm.BASE) {
            var solver = inventory.newTaskSolver();
            return new Engine(t -> new Sample(0, solver.solve(t).evidence()), new Setup(0,0,0,solver.preparationWork()));
        }
        var library = arm == Arm.AUTO_OLD || arm == Arm.FAST_OLD ? materials.earlier() : materials.fresh();
        if (arm == Arm.AUTO_OLD || arm == Arm.AUTO_FRESH) {
            var automatic = CumulativeTaskKnowledge.restore(INVENTORY, library.snapshot(),
                CumulativeTaskKnowledge.MatcherPreparation.DOMAIN_RECEIPTS_WITH_ROUTING);
            return new Engine(task -> {
                var result = automatic.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                return new Sample(result.selectionWork(), result.solution().orElseThrow().evidence());
            }, new Setup(library.acquisition(), 0, automatic.sessionSetupWork(), 0));
        }
        var model = library.load();
        long calibration = 0;
        CheckedSchemaTaskSolver solver;
        if (arm == Arm.POLICY || arm == Arm.NO_TACTICS) {
            var fit = LearnedTacticPolicy.fit(INVENTORY, model, PairedTacticTraining.calibration());
            if (!fit.policy().fingerprint().equals(materials.receipt().fingerprint()) || fit.work() != materials.receipt().work())
                throw new IllegalStateException("policy differs from training receipt");
            calibration = fit.work();
            solver = arm == Arm.NO_TACTICS ? inventory.newTaskSolverWithTacticPolicy(model, fit.policy(), true, true)
                : inventory.newTaskSolverWithTacticPolicy(model, fit.policy(), true, disabled);
        } else {
            solver = arm == Arm.SHARED_FRESH ? inventory.newTaskSolverWithDomainReceipts(model, true)
                : inventory.newTaskSolverWithDomainReceipts(model);
        }
        return new Engine(task -> new Sample(0, solver.solve(task).evidence()),
            new Setup(library.acquisition(), calibration, model.loadWork(), solver.preparationWork()));
    }

    private static void family(Path out, String seed, List<PairedTacticCases.Input> inputs, Materials materials,
            int rotation) throws IOException {
        var family = inputs.getFirst().family();
        Path dir = Files.createDirectory(out.resolve(family.name()));
        var ledger = new EnumMap<Arm,List<Row>>(Arm.class);
        var summaries = new EnumMap<Arm,Summary>(Arm.class);
        var elapsed = new EnumMap<Arm,Long>(Arm.class);
        var paths = new TreeMap<String,List<Step>>();
        var order = List.of(Arm.values());
        for (Arm arm : rotation == 0 ? order : order.reversed()) {
            long start = System.nanoTime();
            var engine = engine(arm, materials, Set.of());
            long clock = engine.setup().total();
            int total = 0, byDeadline = 0;
            var rows = new ArrayList<Row>();
            for (var input : inputs) {
                var row = run(engine, input, clock, paths);
                clock = row.completedAt();
                if (row.solved()) {
                    total++;
                    if (clock <= DEADLINE) byDeadline++;
                }
                rows.add(row);
            }
            elapsed.put(arm, System.nanoTime() - start);
            ledger.put(arm, rows);
            writeLines(dir.resolve(arm + ".jsonl"), rows);
            summaries.put(arm, new Summary(engine.setup(), byDeadline, total, clock));
        }
        var newIds = new HashSet<String>();
        materials.fresh().load().schemas().forEach(s -> newIds.add(s.id()));
        var historicalIds = new HashSet<String>();
        materials.earlier().load().schemas().forEach(s -> historicalIds.add(s.id()));
        var knockouts = new ArrayList<Knockout>();
        var engines = new HashMap<List<String>,Engine>();
        for (var input : inputs) {
            int i = input.index();
            var candidate = ledger.get(Arm.POLICY).get(i);
            if (!candidate.solved() || Stream.of(Arm.BASE, Arm.FAST_OLD, Arm.AUTO_OLD, Arm.NO_TACTICS)
                    .anyMatch(a -> ledger.get(a).get(i).solved())) continue;
            var used = candidate.learnedSchemas().stream().filter(newIds::contains).toList();
            var subsets = new ArrayList<List<String>>();
            for (String id : used) subsets.add(List.of(id));
            if (used.size() > 1) subsets.add(used);
            for (var disabled : subsets) {
                var engine = engines.computeIfAbsent(disabled, ids -> engine(Arm.POLICY, materials, Set.copyOf(ids)));
                if (!engine.setup().equals(summaries.get(Arm.POLICY).setup())) throw new IllegalStateException("different knockout setup");
                var row = run(engine, input, engine.setup().total(), paths);
                if (row.learnedSchemas().stream().anyMatch(disabled::contains)) throw new IllegalStateException("ablated schema applied");
                knockouts.add(new Knockout(i, disabled, disabled.stream().filter(historicalIds::contains).toList(), row));
            }
        }
        writeLines(dir.resolve("knockouts.jsonl"), knockouts);
        writeJson(dir.resolve("paths.json"), paths);
        writeJson(dir.resolve("elapsed-nanos.json"), elapsed);
        writeJson(dir.resolve("summary.json"), new TreeMap<>(Map.of("family",family,"freeze",seed,"protocol",PROTOCOL,
            "primaryBudget",DEADLINE,"arms",summaries)));
    }

    private static Step step(TypedMoveSearch.WitnessStep step) {
        if (!step.verification().accepted()) throw new IllegalStateException("invalid step");
        var binding = step.move().sourceKind() == SearchMove.SourceKind.LEARNED
            ? ((TransformationProvenance.ExactTheoryStep) step.move().provenance()).evidence().binding() : null;
        return new Step(step.move().ruleId(), ExpressionFormatter.formatStructure(step.source().expression()),
            ExpressionFormatter.formatStructure(step.target().expression()), binding == null ? null : binding.theoryStepId(),
            binding == null ? null : binding.evidenceHash(), step.verification().work());
    }

    static Row run(Engine engine, PairedTacticCases.Input input, long clock, Map<String,List<Step>> paths) throws IOException {
        var sample = engine.query().solve(input.task());
        var result = sample.evidence();
        if (!result.accountingComplete()) throw new IllegalStateException("incomplete accounting");
        String output = ExpressionFormatter.formatStructure(result.incumbent().expression());
        long[] audit = {0};
        ExactPolynomialAnalysis.withProductWork(n -> audit[0] = Math.addExact(audit[0], n)).requireEquivalent(input.source(), output);
        var steps = result.witness().stream().map(PairedTacticStudyTest::step).toList();
        String hash = SchematicProofPlan.hash(JSON.writeValueAsString(steps));
        var old = paths.putIfAbsent(hash, steps);
        if (old != null && !old.equals(steps)) throw new IllegalStateException("inconsistent witness dictionary");
        long work = Math.addExact(sample.selection(), result.totalWork());
        boolean within = result.withinBudget() && work <= input.stratum().cap();
        boolean solved = within && result.search().outcome() == MoveSearch.Outcome.QUALITY_REACHED && result.outputScore() <= input.target();
        return new Row(input.index(), input.source(), input.stratum().m(), input.stratum().orientation(), input.stratum().cap(), input.target(),
            output, result.outputScore(), result.search().outcome().name(), true, within, solved, sample.selection(), result.queryWork(),
            result.search().metrics().verificationWork(), result.replayWork(), audit[0], Math.addExact(clock, Math.addExact(work, audit[0])),
            hash, steps.stream().map(Step::schema).filter(java.util.Objects::nonNull).distinct().sorted().toList());
    }

    static void writeLines(Path path, List<?> records) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
            for (var record : records) {
                writer.write(JSON.writeValueAsString(record));
                writer.newLine();
            }
        }
    }
}
