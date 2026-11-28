package de.regelsuche.evolution;

import static de.regelsuche.evolution.HornerTransferStudyTest.JSON;
import static de.regelsuche.evolution.HornerTransferStudyTest.writeJson;
import static de.regelsuche.evolution.PairedTacticStudyTest.writeLines;

import de.regelsuche.evolution.PairedTacticStudyTest.Engine;
import de.regelsuche.evolution.PairedTacticStudyTest.Row;
import de.regelsuche.evolution.PairedTacticStudyTest.Sample;
import de.regelsuche.evolution.PairedTacticStudyTest.Setup;
import de.regelsuche.evolution.PairedTacticStudyTest.Step;
import de.regelsuche.parse.ExpressionFormatter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Cold-start portfolio experiment. Every paid arm emits successful and failed proof paths. */
@EnabledIfSystemProperty(named = "retained.phase", matches = "train|final")
class RetainedTacticStudyTest {
    enum Arm { BASE, AUTO_OLD, CHECKED_OLD, PREVIOUS_POLICY, FRESH_ONLY, OLD_ONLY, PRIOR_UNION, RETAINED_FORCED, RETAINED, DROP_ALL }
    static final long DEADLINE = 524_288;
    static final String STUDY = "prospective-retained-tactics";
    static final String PROTOCOL = "sha256:65d509412987427192fb04af5b2af726eaad9888f5c29451a894aed432ea4ed4";
    private static final EvolutionGenome INVENTORY = TraceStrategyTransferExample.inventory();
    record Library(CheckedLearnedSchemaModel model, long acquisition, long load, CostAwareTacticRouter.Fit fit) {}
    record Materials(PairedTacticStudyTest.Materials previous, Library old, Library fresh, Library priorUnion, Library union,
            RetainedTraceAcquisition.Result acquired, long priorComposition, long composition,
            List<CheckedSchemaTaskSolver.Task> calibration, long calibrationSourceWork) {}
    record Summary(Setup setup, int byDeadline, int totalSolved, long fullCost) {}
    record Removal(int index, String disabled, Row row) {}

    static Materials materials(Path research) throws IOException {
        var prior = PairedTacticStudyTest.readMaterials(research, research.resolve("prospective-tactic-transfer/training"));
        var old = prior.earlier().load();
        var fresh = prior.fresh().load();
        var priorUnion = old.retainWith(fresh);
        var acquired = RetainedTraceAcquisition.acquire(research);
        var retained = acquired.model().schemas().isEmpty() ? priorUnion : priorUnion.model().retainWith(acquired.model());
        long composition = retained == priorUnion ? priorUnion.work() : Math.addExact(priorUnion.work(),retained.work());
        long bothAcquisitions = Math.addExact(prior.earlier().acquisition(), prior.fresh().acquisition());
        long bothLoads = Math.addExact(old.loadWork(),fresh.loadWork());

        var calibration = new ArrayList<CheckedSchemaTaskSolver.Task>();
        var previousTasks = PairedTacticTraining.calibration();
        long sourceWork = 0;
        for (int i = 0; i < previousTasks.size(); i += 4) {
            var task = previousTasks.get(i);
            calibration.add(task);
            sourceWork += ExpressionFormatter.formatStructure(task.source()).length() + 1L;
        }
        var acquiredCalibration = RetainedTraceAcquisition.calibration(research);
        calibration.addAll(acquiredCalibration.tasks());
        sourceWork = Math.addExact(sourceWork,acquiredCalibration.work());
        var block = HornerTransferCases.mixedBlocks(IntervalTransferCases.MIXED_SEED,4,1);
        for (var input : block) sourceWork += input.source().length() + 1L;
        for (var input : block.subList(0,4)) calibration.add(input.task());

        return new Materials(prior,
            fitted(old,prior.earlier().acquisition(),old.loadWork(),calibration),
            fitted(fresh,prior.fresh().acquisition(),fresh.loadWork(),calibration),
            fitted(priorUnion.model(),bothAcquisitions,bothLoads,calibration),
            fitted(retained.model(),Math.addExact(bothAcquisitions,acquired.acquisition()),Math.addExact(bothLoads,acquired.load()),calibration),
            acquired,priorUnion.work(),composition,List.copyOf(calibration),sourceWork);
    }

    private static Library fitted(CheckedLearnedSchemaModel model, long acquisition, long load, List<CheckedSchemaTaskSolver.Task> tasks) {
        return new Library(model, acquisition, load, CostAwareTacticRouter.fitSparse(INVENTORY,model,tasks));
    }

    static Engine engine(Arm arm, Materials materials, Set<String> disabled) {
        if (arm == Arm.BASE || arm == Arm.AUTO_OLD || arm == Arm.PREVIOUS_POLICY) {
            var previous = switch (arm) {
                case BASE -> PairedTacticStudyTest.Arm.BASE;
                case AUTO_OLD -> PairedTacticStudyTest.Arm.AUTO_OLD;
                default -> PairedTacticStudyTest.Arm.POLICY;
            };
            return PairedTacticStudyTest.engine(previous,materials.previous(),Set.of());
        }
        var library = switch (arm) {
            case OLD_ONLY, CHECKED_OLD -> materials.old();
            case FRESH_ONLY -> materials.fresh();
            case PRIOR_UNION -> materials.priorUnion();
            default -> materials.union();
        };
        long composition = library == materials.union() ? materials.composition() : library == materials.priorUnion() ? materials.priorComposition() : 0;
        if (arm == Arm.RETAINED_FORCED) {
            var solver = TypedLearnedMoveInventory.primitives(INVENTORY).newTaskSolverWithSharedContext(library.model());
            return new Engine(t -> new Sample(0,solver.solveWithAdmission(t,Set.of()).evidence()),
                new Setup(library.acquisition(),0,library.load(),Math.addExact(composition,solver.preparationWork())));
        }
        if (arm == Arm.CHECKED_OLD) {
            var solver = TypedLearnedMoveInventory.primitives(INVENTORY).newTaskSolverWithDomainReceipts(library.model(),true);
            return new Engine(t -> new Sample(0,solver.solve(t).evidence()),new Setup(library.acquisition(),0,library.load(),solver.preparationWork()));
        }
        var excluded = arm == Arm.DROP_ALL
            ? library.model().schemas().stream().map(s -> s.id()).collect(Collectors.toUnmodifiableSet()) : disabled;
        var solver = CostAwareTacticRouter.prepare(INVENTORY,library.model(),library.fit().admission(),excluded);
        return new Engine(t -> {
            var result = solver.solve(t);
            return new Sample(result.selectionWork(),result.evidence(),result.admitted());
        },new Setup(library.acquisition(),Math.addExact(library.fit().work(),materials.calibrationSourceWork()),library.load(),
            Math.addExact(composition,solver.preparationWork())));
    }

    private static Map<String,Object> receipt(Library library) {
        return new TreeMap<>(Map.of("model",library.model().identity(),"acquisition",library.acquisition(),"load",library.load(),
            "schemas",library.model().schemas().stream().map(s -> s.id()).sorted().toList(),"calibration",library.fit().work(),
            "admission",library.fit().admission().fingerprint(),"acceptedShapes",library.fit().admission().acceptedShapes(),
            "trials",library.fit().trials(),"sparseTrials",library.fit().sparseTrials(),
            "selectedSchemas",library.fit().admission().selectedSchemas()));
    }

    private static Map<String,Object> calibrationTask(CheckedSchemaTaskSolver.Task task) {
        return new TreeMap<>(Map.of("source",ExpressionFormatter.formatStructure(task.source()),"target",task.goal().maximumOutputScore(),
            "depth",task.budget().maxSearchDepth(),"cap",task.budget().totalWork()));
    }

    @Test void retainedKnowledgeStudy() throws IOException {
        var research = Path.of(System.getProperty("retained.research"));
        var study = research.resolve(STUDY);
        HornerTransferStudyTest.readChecked(study.resolve("protocol.md"),PROTOCOL);
        var out = Files.createDirectory(Path.of(System.getProperty("retained.output")));
        var m = materials(research);
        var receipt = new TreeMap<String,Object>();
        receipt.put("old",receipt(m.old()));
        receipt.put("fresh",receipt(m.fresh()));
        receipt.put("priorUnion",receipt(m.priorUnion()));
        receipt.put("union",receipt(m.union()));
        receipt.put("priorComposition",m.priorComposition());
        receipt.put("composition",m.composition());
        receipt.put("acquiredAcquisition",m.acquired().acquisition());
        receipt.put("acquiredLoad",m.acquired().load());
        receipt.put("calibrationSources",m.calibration().stream().map(RetainedTacticStudyTest::calibrationTask).toList());
        receipt.put("calibrationSourceWork",m.calibrationSourceWork());
        var models = Map.of("acquired.json",m.acquired().model(),"prior-union.json",m.priorUnion().model(),"union.json",m.union().model());
        var reports = Map.<String,Object>of("receipt.json",receipt,"trace-acquisition.json",m.acquired().receipt());
        if ("train".equals(System.getProperty("retained.phase"))) {
            for (var e : models.entrySet())
                Files.writeString(out.resolve(e.getKey()),e.getValue().toCanonicalJson(),StandardOpenOption.CREATE_NEW);
            for (var e : reports.entrySet()) writeJson(out.resolve(e.getKey()),e.getValue());
            return;
        }
        var frozen = study.resolve("training");
        for (var e : models.entrySet())
            if (!Files.readString(frozen.resolve(e.getKey())).equals(e.getValue().toCanonicalJson()))
                throw new IllegalStateException("frozen model changed: " + e.getKey());
        for (var e : reports.entrySet())
            if (!JSON.readTree(frozen.resolve(e.getKey()).toFile()).equals(JSON.readTree(JSON.writeValueAsBytes(e.getValue()))))
                throw new IllegalStateException("frozen receipt changed: " + e.getKey());
        var seed = System.getProperty("retained.freeze");
        int rotation = Integer.parseInt(System.getProperty("retained.rotation"));
        if (rotation != 0 && rotation != 1) throw new IllegalArgumentException("expected rotation 0 or 1");
        for (String family : List.of("C","D"))
            evaluate(out.resolve(family),family,seed,AdmissionTransferCases.panel(family,seed),m,rotation);
    }

    private static void evaluate(Path dir, String family, String seed, List<PairedTacticCases.Input> cases, Materials materials, int rotation) throws IOException {
        Files.createDirectory(dir);
        var rows = new EnumMap<Arm,List<Row>>(Arm.class);
        var summaries = new EnumMap<Arm,Summary>(Arm.class);
        var elapsed = new EnumMap<Arm,Long>(Arm.class);
        var admissions = new EnumMap<Arm,List<Boolean>>(Arm.class);
        var paths = new TreeMap<String,List<Step>>();
        var order = List.of(Arm.values());
        for (var arm : rotation == 0 ? order : order.reversed()) {
            long start = System.nanoTime();
            var solver = engine(arm,materials,Set.of());
            var decisions = new ArrayList<Boolean>();
            var observed = new Engine(t -> {
                var sample = solver.query().solve(t);
                if (sample.admitted() != null) decisions.add(sample.admitted());
                return sample;
            },solver.setup());
            long clock = solver.setup().total();
            int solved = 0;
            int credited = 0;
            var records = new ArrayList<Row>();
            for (var input : cases) {
                var row = PairedTacticStudyTest.run(observed,input,clock,paths);
                clock = row.completedAt();
                if (row.solved()) {
                    solved++;
                    if (clock <= DEADLINE) credited++;
                }
                records.add(row);
            }
            elapsed.put(arm,System.nanoTime()-start);
            if (!decisions.isEmpty()) admissions.put(arm,List.copyOf(decisions));
            summaries.put(arm,new Summary(solver.setup(),credited,solved,clock));
            rows.put(arm,records);
            writeLines(dir.resolve(arm+".jsonl"),records);
        }
        var ablations = new ArrayList<Removal>();
        var cache = new HashMap<String,Engine>();
        for (var input : cases) {
            int i = input.index();
            var candidate = rows.get(Arm.RETAINED).get(i);
            if (!candidate.solved() || Stream.of(Arm.BASE,Arm.AUTO_OLD,Arm.CHECKED_OLD,Arm.PREVIOUS_POLICY,Arm.DROP_ALL)
                    .anyMatch(a -> rows.get(a).get(i).solved())) continue;
            for (String id : candidate.learnedSchemas()) {
                var solver = cache.computeIfAbsent(id,k -> engine(Arm.RETAINED,materials,Set.of(k)));
                if (!solver.setup().equals(summaries.get(Arm.RETAINED).setup())) throw new IllegalStateException("changed removal cost");
                var row = PairedTacticStudyTest.run(solver,input,solver.setup().total(),paths);
                if (row.learnedSchemas().contains(id)) throw new IllegalStateException("removed tactic used");
                ablations.add(new Removal(i,id,row));
            }
        }
        writeLines(dir.resolve("removals.jsonl"),ablations);
        writeJson(dir.resolve("paths.json"),paths);
        writeJson(dir.resolve("admissions.json"),admissions);
        writeJson(dir.resolve("elapsed.json"),elapsed);
        writeJson(dir.resolve("summary.json"),new TreeMap<>(Map.of("family",family,"freeze",seed,"deadline",DEADLINE,"arms",summaries)));
    }
}
