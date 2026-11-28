package de.regelsuche.evolution;

import static de.regelsuche.evolution.HornerTransferStudyTest.JSON;
import static de.regelsuche.evolution.HornerTransferStudyTest.writeJson;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Supplemental comparison of paid primitive ordering with a free greedy and a branching control. */
@EnabledIfSystemProperty(named = "ordering.output", matches = ".+")
class PrimitiveOrderingStudyTest {
    enum Arm { ORDERED, DROP_ORDER, GREEDY, SUBTREE }
    private static final String PRIOR = "27bb13a8c861578912ef968ab73b2d06cb95fce7";
    private static final EvolutionGenome INVENTORY = TraceStrategyTransferExample.inventory();
    private record Corpus(List<CheckedSchemaTaskSolver.Task> tasks, long readWork) {}

    private static Corpus corpus(Path research) throws Exception {
        var tasks = new ArrayList<>(PairedTacticTraining.lessons());
        long read = 0;
        for (var task : tasks) read += 2L + ExpressionFormatter.formatStructure(task.source()).length();
        for (String family : List.of("A","B")) {
            var path = research.resolve("prospective-tactic-transfer/results").resolve(family).resolve("BASE.jsonl.gz");
            try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)),
                    StandardCharsets.UTF_8))) {
                for (int i = 0; i < 3; i++) {
                    String line = reader.readLine();
                    if (line == null) throw new IllegalStateException("incomplete training prefix");
                    read += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    var row = JSON.readTree(line);
                    assertEquals(i,row.required("index").asInt());
                    tasks.add(new CheckedSchemaTaskSolver.Task(
                        new ExpressionParser().parseExactTerm(row.required("source").asText()).expression(), List.of(),
                        CheckedSchemaTaskSolver.Goal.atMostNodes(row.required("target").asInt()),
                        new MoveSearch.Budget(32,32,200_000,512,8192)));
                }
            }
        }
        return new Corpus(List.copyOf(tasks), read);
    }

    private static List<RetainedTacticDevelopmentTest.Panel> panels() throws Exception {
        String official = System.getProperty("ordering.official");
        if (official == null) {
            var mixed = RetainedTacticDevelopmentTest.panels().getFirst();
            var panels = new ArrayList<>(List.of(mixed));
            for (String f : List.of("C","D")) panels.add(new RetainedTacticDevelopmentTest.Panel(f,
                RetainedTacticStudyTest.DEADLINE, AdmissionTransferCases.panel(f,PRIOR)));
            return panels;
        }
        var dir = Path.of(official,"E");
        var summary = JSON.readTree(dir.resolve("summary.json").toFile());
        assertEquals("E",summary.required("family").asText());
        assertEquals(RetainedTacticStudyTest.DEADLINE,summary.required("deadline").asLong());
        assertEquals(System.getProperty("local.freeze"),summary.required("freeze").asText());
        var inputs = new ArrayList<PairedTacticCases.Input>();
        try (var lines = Files.newBufferedReader(dir.resolve("SHARED.jsonl"))) {
            String line;
            while ((line = lines.readLine()) != null) {
                var row = JSON.readValue(line, PairedTacticStudyTest.Row.class);
                assertEquals(inputs.size(),row.index());
                inputs.add(new PairedTacticCases.Input(PairedTacticCases.Family.A,row.index(),
                    new PairedTacticCases.Stratum(row.m(),row.orientation(),row.cap()),row.source(),row.target()));
            }
        }
        assertEquals(256,inputs.size());
        return List.of(new RetainedTacticDevelopmentTest.Panel("E",RetainedTacticStudyTest.DEADLINE,List.copyOf(inputs)));
    }

    @Test void comparePrimitiveOrdering() throws Exception {
        var output = Files.createDirectory(Path.of(System.getProperty("ordering.output")));
        var corpus = corpus(Path.of(System.getProperty("ordering.research")));
        var fit = LearnedPrimitiveOrdering.fit(INVENTORY,corpus.tasks());
        var ordered = LearnedPrimitiveOrdering.load(INVENTORY,fit.model());
        var greedy = LearnedPrimitiveOrdering.untrained(INVENTORY);
        var inventory = TypedLearnedMoveInventory.primitives(INVENTORY);
        var baseline = inventory.newTaskSolver();
        var setup = new PairedTacticStudyTest.Setup(corpus.readWork() + fit.acquisitionWork(),0,
            ordered.loadWork(),fit.formationWork());
        var free = new PairedTacticStudyTest.Setup(0,0,0,0);
        var training = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < fit.lessons().size(); i++) {
            var lesson = fit.lessons().get(i);
            var task = corpus.tasks().get(i);
            training.add(new TreeMap<>(Map.of("source",ExpressionFormatter.formatStructure(task.source()),
                "target",task.goal().maximumOutputScore(), "solved",lesson.solved(),"work",lesson.work(),"rules",lesson.rules())));
        }
        writeJson(output.resolve("training.json"),new TreeMap<>(Map.of("inventoryHash",fit.model().inventoryHash(),
            "inventory",inventory.primitiveProviders().stream().map(p -> p.descriptor().id()).toList(),
            "orders",new TreeMap<>(fit.model().orders()),"training",training,"readWork",corpus.readWork(),"setup",setup)));
        int rotation = Integer.parseInt(System.getProperty("ordering.rotation","0"));
        assertTrue(rotation == 0 || rotation == 1);
        var arms = List.of(Arm.values());
        for (var panel : panels()) {
            var dir = Files.createDirectory(output.resolve(panel.name()));
            var paths = new TreeMap<String,List<PairedTacticStudyTest.Step>>();
            var summaries = new EnumMap<Arm,PairedTacticStudyTest.Summary>(Arm.class);
            for (Arm arm : rotation == 0 ? arms : arms.reversed()) {
                var ledger = arm == Arm.ORDERED || arm == Arm.DROP_ORDER ? setup : free;
                var engine = new PairedTacticStudyTest.Engine(task -> new PairedTacticStudyTest.Sample(0,switch (arm) {
                    case ORDERED -> ordered.solve(task);
                    case DROP_ORDER, GREEDY -> greedy.solve(task);
                    case SUBTREE -> baseline.solveWithSubtreeNodeCounts(task).evidence();
                }),ledger);
                long clock = ledger.total();
                int solved = 0, timely = 0;
                try (var writer = Files.newBufferedWriter(dir.resolve(arm.name() + ".jsonl"))) {
                    for (var input : panel.inputs()) {
                        var row = PairedTacticStudyTest.run(engine,input,clock,paths);
                        assertTrue(row.learnedSchemas().isEmpty());
                        clock = row.completedAt();
                        if (row.solved()) {
                            solved++;
                            if (clock <= panel.deadline()) timely++;
                        }
                        writer.write(JSON.writeValueAsString(row));
                        writer.newLine();
                    }
                }
                summaries.put(arm,new PairedTacticStudyTest.Summary(ledger,timely,solved,clock));
            }
            writeJson(dir.resolve("paths.json"),paths);
            writeJson(dir.resolve("summary.json"),new TreeMap<>(Map.of("family",panel.name(),"deadline",panel.deadline(),
                "arms",summaries)));
        }
    }
}
