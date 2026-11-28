package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/** Acquire from fixed chronological prefixes of earlier public primitive streams.
 * All proof paths are solved afresh. Only the input and node bound are read from old rows. */
final class RetainedTraceAcquisition {
    private static final EvolutionGenome INVENTORY = TraceStrategyTransferExample.inventory();
    record Result(CheckedLearnedSchemaModel model, long acquisition, long load, Map<String,Object> receipt) {}
    record Calibration(List<CheckedSchemaTaskSolver.Task> tasks, long work) {}
    private record Prefix(List<JsonNode> rows, long work) {}
    private record Batch(CheckedLearnedSchemaModel model, long acquisition, Map<String,Object> receipt) {}

    private RetainedTraceAcquisition() {}

    static Result acquire(Path research) throws IOException {
        Batch first = learn(research, "A");
        Batch second = learn(research, "B");
        var joined = first.model().retainWith(second.model());
        long acquisition = Math.addExact(Math.addExact(first.acquisition(),second.acquisition()),joined.work());
        long load = Math.addExact(first.model().loadWork(),second.model().loadWork());
        return new Result(joined.model(), acquisition, load, new TreeMap<>(Map.of(
            "batches",List.of(first.receipt(),second.receipt()), "joinWork",joined.work(),
            "acquisition",acquisition, "load",load)));
    }

    static Calibration calibration(Path research) throws IOException {
        var tasks = new ArrayList<CheckedSchemaTaskSolver.Task>();
        long work = 0;
        for (String stream : List.of("A","B")) {
            var prefix = read(research,stream,20);
            work = Math.addExact(work,prefix.work());
            for (var row : prefix.rows().subList(16,20)) tasks.add(task(row,32,row.required("cap").asInt()));
        }
        return new Calibration(List.copyOf(tasks),work);
    }

    private static Prefix read(Path research, String stream, int count) throws IOException {
        var file = research.resolve("prospective-tactic-transfer/results").resolve(stream).resolve("BASE.jsonl.gz");
        var rows = new ArrayList<JsonNode>();
        long work = 0;
        try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                StandardCharsets.UTF_8))) {
            for (int i=0;i<count;i++) {
                String line=reader.readLine();
                if (line==null) throw new IOException("incomplete public trace prefix: " + file);
                work = Math.addExact(work,1L+line.getBytes(StandardCharsets.UTF_8).length);
                var row=HornerTransferStudyTest.JSON.readTree(line);
                if (row.required("index").asInt()!=i) throw new IOException("unordered public trace prefix: " + file);
                rows.add(row);
            }
        }
        return new Prefix(List.copyOf(rows),work);
    }

    private static CheckedSchemaTaskSolver.Task task(JsonNode row, int depth, long budget) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(row.required("source").asText()).expression(),
            List.of(),CheckedSchemaTaskSolver.Goal.atMostNodes(row.required("target").asInt()),
            new MoveSearch.Budget(32,depth,200_000,512,budget));
    }

    private static Batch learn(Path research, String stream) throws IOException {
        var prefix = read(research,stream,6);
        var primitive = TypedLearnedMoveInventory.primitives(INVENTORY).newTaskSolver();
        var lessons = new ArrayList<CheckedSchemaTaskSolver.Task>();
        var gates = new ArrayList<CheckedSchemaTaskSolver.Task>();
        var references = new ArrayList<Map<String,Object>>();
        long referenceWork = 0;
        for (int i=0;i<6;i++) {
            var row = prefix.rows().get(i);
            var ordinary = task(row,32,8192);
            if (i<3) {
                lessons.add(ordinary);
            } else {
                var solved = primitive.solve(ordinary);
                referenceWork = Math.addExact(referenceWork,solved.evidence().totalWork());
                references.add(proof(solved.evidence(),solved.solved(),solved.evidence().totalWork()));
                int depth = solved.solved() ? Math.max(1,solved.evidence().witness().size()-1) : 32;
                gates.add(task(row,depth,8192));
            }
        }
        var update = CumulativeTaskKnowledge.empty(INVENTORY).learnLocalBatch(lessons,gates,1_000_000);
        var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(update.next().toCanonicalJson()).model(),
            INVENTORY.contentHash());
        var support = new TreeMap<String,List<String>>();
        for (var schema : model.schemas()) support.put(schema.id(),schema.supportingObservationIds());
        var training = new ArrayList<Map<String,Object>>();
        for (int i=0;i<update.training().size();i++) {
            var e=update.training().get(i);
            var row=taskReceipt(prefix.rows().get(i));
            row.putAll(proof(e.solution().orElseThrow().evidence(),e.solved(),e.totalWork()));
            training.add(row);
        }
        var validation = new ArrayList<Map<String,Object>>();
        for (int i=0;i<update.validation().size();i++) {
            var v=update.validation().get(i);
            var row=taskReceipt(prefix.rows().get(i+3));
            row.put("depth",gates.get(i).budget().maxSearchDepth());
            row.put("reference",references.get(i));
            row.put("base",v.baseline().totalWork());
            row.put("previous",v.previous().totalWork());
            row.put("candidate",v.candidate().totalWork());
            row.put("routed",v.automatic().map(CumulativeTaskKnowledge.Execution::totalWork).orElse(0L));
            validation.add(row);
        }
        var receipt = new TreeMap<String,Object>();
        receipt.put("stream",stream);
        receipt.put("acquisition",update.work());
        receipt.put("readWork",prefix.work());
        receipt.put("referenceSetup",primitive.preparationWork());
        receipt.put("referenceWork",referenceWork);
        receipt.put("load",model.loadWork());
        receipt.put("decision",update.reason().name());
        receipt.put("training",training);
        receipt.put("gates",validation);
        receipt.put("support",support);
        receipt.put("modelHash",SchematicProofPlan.hash(model.toCanonicalJson()));
        long cost = Math.addExact(Math.addExact(prefix.work(),primitive.preparationWork()),
            Math.addExact(referenceWork,update.work().total()));
        return new Batch(model,cost,receipt);
    }

    private static Map<String,Object> taskReceipt(JsonNode input) {
        var row=new TreeMap<String,Object>();
        row.put("source",input.required("source").asText());
        row.put("target",input.required("target").asInt());
        row.put("index",input.required("index").asInt());
        return row;
    }

    private static Map<String,Object> proof(TypedSourceOnlySearch.Result e, boolean solved, long work) {
        var witness=e.witness().stream().map(step -> {
            var row=new TreeMap<String,Object>();
            row.put("rule",step.move().ruleId());
            row.put("source",ExpressionFormatter.formatStructure(step.source().expression()));
            row.put("target",ExpressionFormatter.formatStructure(step.target().expression()));
            row.put("schema",null);
            row.put("evidence",null);
            row.put("verification",step.verification().work());
            return row;
        }).toList();
        return new TreeMap<>(Map.of("work",work,"solved",solved,"witness",witness,
            "output",ExpressionFormatter.formatStructure(e.incumbent().expression())));
    }
}
