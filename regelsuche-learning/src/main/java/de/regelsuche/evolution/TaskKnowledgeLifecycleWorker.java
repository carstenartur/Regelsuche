package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Source-only lifecycle adapter over public APIs, without mathematical answer templates. */
public final class TaskKnowledgeLifecycleWorker implements AutoCloseable {
    private static final Set<String> PROFILES = Set.of("PRIMITIVE", "LEGACY_SESSION", "LEGACY_RELOAD", "PREPARED_REUSE", "MEASURED_IMPROVEMENT");
    private static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(32, 32, 200_000, 512, 200_000);
    private CumulativeTaskKnowledge knowledge;
    private TaskKnowledgeSession session;
    private String profile, seedId;
    private String promotionWorkload = "SUPPLIED_LEGACY";
    private Path snapshot, directory;
    private List<TaskKnowledgeSelfImprover.Batch> improvements = List.of();
    private long futureQueries;
    private boolean consumer, improved, poisoned, closed;

    public Map<String, Object> handle(String line) {
        try {
            if (poisoned || closed) throw new IllegalStateException("unavailable session");
            var request = CheckedSchemaSupport.read(line);
            return switch (CheckedSchemaSupport.text(request, "op")) {
                case "initialize" -> initialize(request);
                case "acquire" -> acquire(request);
                case "restore" -> restore(request);
                case "load" -> load(request);
                case "run" -> run(request);
                default -> throw new IllegalArgumentException("unknown lifecycle operation");
            };
        } catch (Exception failure) {
            poisoned = true;
            return Map.of("status", "ERROR", "error", failure.toString(), "accountingComplete", false);
        }
    }

    private void configure(JsonNode request) {
        if (profile != null) throw new IllegalStateException("already configured");
        profile = CheckedSchemaSupport.text(request, "profile");
        if (!PROFILES.contains(profile)) throw new IllegalArgumentException("native lifecycle profile required");
    }
    private Map<String, Object> initialize(JsonNode request) {
        CheckedSchemaSupport.fields(request, "op", "profile"); configure(request);
        if (!profile.equals("PRIMITIVE")) throw new IllegalArgumentException("only primitive profile skips acquisition");
        knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        seedId = knowledge.id(); consumer = true;
        return common("INITIALIZED");
    }
    private Map<String, Object> acquire(JsonNode request) throws Exception {
        CheckedSchemaSupport.fields(request, "op", "profile", "seedBlocks", "directory"); configure(request);
        if (profile.equals("PRIMITIVE")) throw new IllegalArgumentException("primitive profile cannot acquire unused knowledge");
        knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var steps = new ArrayList<Map<String, Object>>();
        for (var block : array(request.get("seedBlocks"))) {
            CheckedSchemaSupport.fields(block, "lessons", "validation");
            String parent = knowledge.id();
            var update = knowledge.learnBatch(tasks(block.get("lessons")), tasks(block.get("validation")), 1_000_000);
            knowledge = update.next();
            steps.add(Map.of("parentId", parent, "generationId", knowledge.id(), "promoted", update.promoted(),
                "decision", update.reason().name(), "work", update.work(), "lessonGenerations",
                update.training().stream().map(CumulativeTaskKnowledge.Execution::generationId).toList()));
        }
        snapshot = knowledge.save(Path.of(CheckedSchemaSupport.text(request, "directory")));
        seedId = knowledge.id();
        var result = common("SAVED"); result.put("steps", steps); return result;
    }
    private Map<String, Object> restore(JsonNode request) {
        if (request.has("promotionWorkload")) {
            CheckedSchemaSupport.fields(request, "op", "profile", "snapshot", "directory", "improvementBatches", "futureQueries", "promotionWorkload");
            promotionWorkload = CheckedSchemaSupport.text(request, "promotionWorkload");
            if (!promotionWorkload.equals(MixedTaskKnowledgeWorkload.REVISION))
                throw new IllegalArgumentException("unknown promotion workload");
        } else CheckedSchemaSupport.fields(request, "op", "profile", "snapshot", "directory", "improvementBatches", "futureQueries");
        configure(request);
        if (profile.equals("PRIMITIVE")) throw new IllegalArgumentException("primitive profile does not restore");
        snapshot = Path.of(CheckedSchemaSupport.text(request, "snapshot"));
        directory = Path.of(CheckedSchemaSupport.text(request, "directory"));
        futureQueries = request.path("futureQueries").longValue();
        if (!request.path("futureQueries").isIntegralNumber() || futureQueries < 1 || futureQueries > 1_000_000)
            throw new IllegalArgumentException("bounded future horizon required");
        var batches = new ArrayList<TaskKnowledgeSelfImprover.Batch>();
        for (var block : array(request.get("improvementBatches"))) {
            CheckedSchemaSupport.fields(block, "lessons", "validation", "promotion");
            var supplied = tasks(block.get("promotion"));
            if (!promotionWorkload.equals("SUPPLIED_LEGACY") && !supplied.isEmpty())
                throw new IllegalArgumentException("named promotion workload requires an empty supplied promotion list");
            batches.add(new TaskKnowledgeSelfImprover.Batch(tasks(block.get("lessons")), tasks(block.get("validation")),
                promotionWorkload.equals("SUPPLIED_LEGACY") ? supplied : MixedTaskKnowledgeWorkload.promotionTasks()));
        }
        improvements = List.copyOf(batches);
        if (profile.equals("PREPARED_REUSE") || profile.equals("MEASURED_IMPROVEMENT"))
            session = new TaskKnowledgeSession(TraceStrategyTransferExample.inventory(), 4, 4_000_000);
        consumer = true;
        return common("CONFIGURED");
    }
    private Map<String, Object> load(JsonNode request) throws Exception {
        CheckedSchemaSupport.fields(request, "op"); requireConsumer();
        boolean reused = knowledge != null;
        long preparation = 0, lookup = 0;
        if (session != null) {
            var loaded = session.prepare(snapshot);
            knowledge = loaded.knowledge(); reused = loaded.reused(); preparation = loaded.preparationWork(); lookup = loaded.lookupWork();
        } else if (!profile.equals("PRIMITIVE") && (knowledge == null || profile.equals("LEGACY_RELOAD"))) {
            knowledge = CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(), snapshot);
            reused = false; preparation = knowledge.sessionSetupWork();
        }
        if (knowledge.constructionProfile() != TaskConstructionProfile.NONE) throw new IllegalArgumentException("changed native capabilities");
        if (seedId == null) seedId = knowledge.id();
        TaskKnowledgeSelfImprover.Result improvement = null;
        if (profile.equals("MEASURED_IMPROVEMENT") && !improved) {
            var policy = new TaskKnowledgeSelfImprover.Policy(5_000_000, 120_000_000_000L, 120_000_000_000L, 2, 3, futureQueries);
            improvement = new TaskKnowledgeSelfImprover().improve(knowledge, improvements, policy,
                promotionWorkload.equals("SUPPLIED_LEGACY") ? TaskKnowledgeSelfImprover.CostScope.LEGACY_ATTEMPT
                    : TaskKnowledgeSelfImprover.CostScope.IMPROVEMENT_RUN);
            knowledge = improvement.active();
            snapshot = knowledge.save(directory);
            lookup = Math.addExact(lookup, session.retainVerified(knowledge).lookupWork());
            improved = true;
        }
        var result = common("LOADED"); result.put("cacheHit", reused); result.put("preparationWork", preparation); result.put("lookupWork", lookup);
        result.put("improvement", improvement == null ? null : Map.of("activeId", improvement.active().id(),
            "attempts", improvement.attempts(), "knownWork", improvement.knownWork(), "paid", improvement.paid(),
            "accountingComplete", improvement.accountingComplete(), "budgetExhausted", improvement.budgetExhausted()));
        return result;
    }
    private Map<String, Object> run(JsonNode request) {
        CheckedSchemaSupport.fields(request, "op", "task"); requireConsumer();
        if (knowledge == null) throw new IllegalStateException("first strict load is required");
        var task = task(request.get("task"));
        var execution = knowledge.solve(task, profile.equals("PRIMITIVE")
            ? CumulativeTaskKnowledge.Strategy.BASELINE : CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        var result = common("RESULT"); result.put("task", CheckedSchemaSupport.JSON.convertValue(request.get("task"), Map.class));
        result.put("solved", execution.solved()); result.put("nativeApiWork", execution.totalWork());
        result.put("withinBudget", execution.totalWork() <= BUDGET.totalWork()
            && execution.solution().map(s -> s.evidence().withinBudget()).orElse(true));
        result.put("normalizedInput", ExpressionFormatter.format(task.source()));
        result.put("outputScore", execution.solution().map(s -> s.evidence().outputScore()).orElse(Long.MAX_VALUE));
        execution.solution().ifPresent(solution -> {
            var evidence = solution.evidence();
            result.put("output", ExpressionFormatter.format(evidence.incumbent().expression()));
            result.put("witness", evidence.witness().stream().map(edge -> Map.of(
                "source", ExpressionFormatter.format(edge.source().expression()), "target", ExpressionFormatter.format(edge.target().expression()),
                "move", edge.move(), "verification", edge.verification())).toList());
        });
        return result;
    }
    private Map<String, Object> common(String status) {
        var result = new TreeMap<String, Object>();
        result.put("status", status); result.put("profile", profile); result.put("seedId", seedId);
        result.put("generationId", knowledge == null ? "" : knowledge.id());
        result.put("snapshot", snapshot == null ? "" : snapshot.toString());
        result.put("schemaCount", knowledge == null ? 0 : knowledge.schemaCount());
        result.put("cache", session == null ? null : session.statistics());
        result.put("promotionWorkload", promotionWorkload);
        result.put("improvementCostScope", promotionWorkload.equals("SUPPLIED_LEGACY")
            ? TaskKnowledgeSelfImprover.CostScope.LEGACY_ATTEMPT.name() : TaskKnowledgeSelfImprover.CostScope.IMPROVEMENT_RUN.name());
        result.put("promotionCases", promotionWorkload.equals(MixedTaskKnowledgeWorkload.REVISION)
            ? MixedTaskKnowledgeWorkload.promotion() : List.of());
        result.put("nativeBudget", List.of(32, 32, 200_000, 512, 200_000));
        return result;
    }
    private void requireConsumer() { if (!consumer) throw new IllegalStateException("fresh query consumer required"); }
    private static List<JsonNode> array(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 32) throw new IllegalArgumentException("bounded array required");
        var result = new ArrayList<JsonNode>(); node.forEach(result::add); return List.copyOf(result);
    }
    private static List<CheckedSchemaTaskSolver.Task> tasks(JsonNode node) { return array(node).stream().map(TaskKnowledgeLifecycleWorker::task).toList(); }
    private static CheckedSchemaTaskSolver.Task task(JsonNode node) {
        CheckedSchemaSupport.fields(node, "source", "maximumNodes");
        String source = CheckedSchemaSupport.text(node, "source");
        if (source.length() > 2048 || !node.path("maximumNodes").isIntegralNumber()
                || !node.path("maximumNodes").canConvertToInt()) throw new IllegalArgumentException("bounded exact task required");
        int goal = node.path("maximumNodes").intValue();
        if (goal < 1 || goal > 256) throw new IllegalArgumentException("node goal bound");
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
            List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(goal), BUDGET);
    }
    @Override public void close() { if (session != null) session.close(); knowledge = null; closed = true; }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("JSONL input required");
        System.out.println("{\"status\":\"READY\",\"java\":\"" + Runtime.version() + "\"}");
        try (var worker = new TaskKnowledgeLifecycleWorker(); var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                System.out.println(LearnedSchedulingArtifacts.json(worker.handle(line))); System.out.flush();
            }
        }
    }
}
