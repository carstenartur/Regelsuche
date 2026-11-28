package de.regelsuche.evolution;

import static de.regelsuche.evolution.HornerTransferStudyTest.writeJson;
import static de.regelsuche.evolution.PairedTacticStudyTest.writeLines;

import de.regelsuche.evolution.PairedTacticStudyTest.Engine;
import de.regelsuche.evolution.PairedTacticStudyTest.Row;
import de.regelsuche.evolution.PairedTacticStudyTest.Sample;
import de.regelsuche.evolution.PairedTacticStudyTest.Setup;
import de.regelsuche.evolution.PairedTacticStudyTest.Step;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Match all arms against the same separately audited end-to-end tasks and costs. */
@EnabledIfSystemProperty(named = "local.phase", matches = "development|final")
class LocalTacticStudyTest {
    enum Arm { SHARED, PRIOR, LOCAL_OLD, LOCAL, DROP }
    private static final EvolutionGenome GENOME = TraceStrategyTransferExample.inventory();
    private static final String PRIOR_FREEZE = "27bb13a8c861578912ef968ab73b2d06cb95fce7";
    private record Removal(int index, String schema, Row row) {}

    @Test void comparePaidLocalSearch() throws IOException {
        Path research = Path.of(System.getProperty("local.research"));
        HornerTransferStudyTest.readChecked(research.resolve("prospective-local-tactics/protocol.md"),
            "sha256:ca9732802d94cbf8f9b650e5e554e13e166e82b185dbb71aa31676b85755b4ee");
        Path output = Files.createDirectory(Path.of(System.getProperty("local.output")));
        var previous = RetainedTacticStudyTest.materials(research);
        var frozen = research.resolve("prospective-retained-tactics/training/union.json");
        if (!Files.readString(frozen).equals(previous.union().model().toCanonicalJson()))
            throw new IllegalStateException("retained library differs from its acquisition history");
        int rotation = Integer.parseInt(System.getProperty("local.rotation"));
        if (rotation < 0 || rotation > 1) throw new IllegalArgumentException("expected rotation 0 or 1");
        if ("development".equals(System.getProperty("local.phase"))) {
            for (String panel : List.of("C","D")) {
                compare(output, panel, PRIOR_FREEZE, AdmissionTransferCases.panel(panel,PRIOR_FREEZE),previous,rotation);
            }
        } else {
            String seed = System.getProperty("local.freeze");
            if (seed == null || !seed.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("expected frozen commit SHA");
            var cases = LocalTransferCases.panel(seed).stream().map(c -> new PairedTacticCases.Input(
                PairedTacticCases.Family.A,c.index(),new PairedTacticCases.Stratum(c.stages(),c.variant(),c.cap()),
                c.source(),c.target())).toList();
            compare(output,"E",seed,cases,previous,rotation);
        }
    }

    private static Engine engine(Arm arm, RetainedTacticStudyTest.Materials materials, Set<String> excluded) {
        if (arm == Arm.PRIOR)
            return RetainedTacticStudyTest.engine(RetainedTacticStudyTest.Arm.RETAINED,materials,Set.of());
        if (arm == Arm.SHARED) {
            var solver = TypedLearnedMoveInventory.primitives(GENOME).newTaskSolver();
            return new Engine(task -> new Sample(0,solver.solveWithSharedNodeCounts(task).evidence(),false),
                new Setup(0,0,0,solver.preparationWork()));
        }
        var library = arm == Arm.LOCAL_OLD ? materials.old() : materials.union();
        var solver = LocalTacticSearch.prepare(GENOME,library.model());
        var disabled = arm == Arm.DROP ? library.model().schemas().stream()
            .map(CheckedLearnedSchemaModel.Schema::id).collect(Collectors.toUnmodifiableSet()) : excluded;
        return new Engine(task -> {
            var result = solver.solve(task,disabled);
            return new Sample(result.selectionWork(),result.evidence(),result.admitted());
        }, new Setup(library.acquisition(),0,library.load(),
            Math.addExact(solver.preparationWork(),arm == Arm.LOCAL_OLD ? 0 : materials.composition())));
    }

    private static void compare(Path output, String panel, String seed, List<PairedTacticCases.Input> inputs,
            RetainedTacticStudyTest.Materials m, int rotation) throws IOException {
        Path dir = Files.createDirectory(output.resolve(panel));
        var paths = new TreeMap<String,List<Step>>();
        var rows = new EnumMap<Arm,List<Row>>(Arm.class);
        var summaries = new EnumMap<Arm,PairedTacticStudyTest.Summary>(Arm.class);
        var admissions = new EnumMap<Arm,List<Boolean>>(Arm.class);
        var order = List.of(Arm.values());
        for (Arm arm : rotation == 0 ? order : order.reversed()) {
            var solver = engine(arm,m,Set.of());
            var decisions = new ArrayList<Boolean>();
            var monitored = new Engine(task -> {
                var sample = solver.query().solve(task);
                decisions.add(Boolean.TRUE.equals(sample.admitted()));
                return sample;
            },solver.setup());
            long clock = solver.setup().total();
            int solved = 0, timely = 0;
            var records = new ArrayList<Row>();
            for (var input : inputs) {
                var row = PairedTacticStudyTest.run(monitored,input,clock,paths);
                clock = row.completedAt();
                if (row.solved()) {
                    solved++;
                    if (clock <= RetainedTacticStudyTest.DEADLINE) timely++;
                }
                records.add(row);
            }
            rows.put(arm,records);
            admissions.put(arm,decisions);
            summaries.put(arm,new PairedTacticStudyTest.Summary(solver.setup(),timely,solved,clock));
            writeLines(dir.resolve(arm.name()+".jsonl"),records);
        }
        if (!summaries.get(Arm.LOCAL).setup().equals(summaries.get(Arm.DROP).setup()))
            throw new IllegalStateException("removing proposals changed cold setup");
        var removals = new ArrayList<Removal>();
        var knockouts = new HashMap<String,Engine>();
        for (var input : inputs) {
            int i = input.index();
            var candidate = rows.get(Arm.LOCAL).get(i);
            if (!candidate.solved() || order.stream().filter(arm -> arm != Arm.LOCAL)
                    .anyMatch(arm -> rows.get(arm).get(i).solved())) continue;
            for (String id : candidate.learnedSchemas()) {
                var solver = knockouts.computeIfAbsent(id,k -> engine(Arm.LOCAL,m,Set.of(k)));
                if (!solver.setup().equals(summaries.get(Arm.LOCAL).setup())) throw new IllegalStateException("removal changed setup");
                var row = PairedTacticStudyTest.run(solver,input,solver.setup().total(),paths);
                if (row.learnedSchemas().contains(id)) throw new IllegalStateException("used disabled theorem");
                removals.add(new Removal(i,id,row));
            }
        }
        writeJson(dir.resolve("admissions.json"),admissions);
        writeJson(dir.resolve("paths.json"),paths);
        writeLines(dir.resolve("removals.jsonl"),removals);
        writeJson(dir.resolve("summary.json"),new TreeMap<>(Map.of("family",panel,"freeze",seed,
            "deadline",RetainedTacticStudyTest.DEADLINE,"arms",summaries)));
    }
}
