package de.regelsuche.evolution;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Executable real-search contracts, also invoked from JUnit. */
final class CumulativeTaskKnowledgeContract {
    private static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(32, 32, 200_000, 512, 200_000);
    private static CheckedSchemaTaskSolver.Task task(String source, int nodes) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
            List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), BUDGET);
    }
    static String nested(String base, int depth, String prefix) {
        if (depth == 0) return base;
        String inner = nested(base, depth - 1, prefix), b = prefix + depth;
        return "((" + inner + "+" + b + ")*(" + inner + "-" + b + ")+" + b + "*" + b + ")";
    }
    private static List<CheckedSchemaTaskSolver.Task> lessons(int depth) {
        return List.of(task(nested("a", depth, "b") + "+3", 2 * depth + 3),
            task("5*(" + nested("c", depth, "d") + ")", 2 * depth + 3));
    }
    private static List<CheckedSchemaTaskSolver.Task> validation(int depth) {
        return List.of(task(nested("p*q", depth, "v"), 2 * depth + 3),
            task(nested("z", depth, "w") + "+7", 2 * depth + 3), task("x", 1),
            task("(x+y)*(x-y)+y*(y+1)", 1));
    }
    static CumulativeTaskKnowledge frozenFirst;
    static CumulativeTaskKnowledge generations() {
        var current = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var all = new ArrayList<CumulativeTaskKnowledge>(); all.add(current);
        for (int depth = 1; depth <= 3; depth++) {
            String parent = current.id();
            var checks = new ArrayList<CheckedSchemaTaskSolver.Task>();
            for (int level = 1; level <= depth; level++) checks.addAll(validation(level));
            var update = current.learnBatch(lessons(depth), checks);
            require(current.id().equals(parent), "batch cannot mutate its parent");
            require(update.training().stream().allMatch(r -> r.generationId().equals(parent)), "same-generation feedback blocked");
            require(update.promoted(), "expected useful generation " + depth + ": " + update.reason());
            if (depth > 1) require(update.training().stream().anyMatch(r -> r.solution().orElseThrow().learnedApplications() > 0),
                "later lessons must actually use earlier knowledge");
            current = update.next(); all.add(current);
            if (depth == 1) frozenFirst = current;
            require(current.generation() == depth && current.parentId().equals(parent), "versioned parent chain");
            require(current.schemaCount() > all.get(depth - 1).schemaCount(), "new generations preserve earlier schemata");
            require(current.acquisitionWork() > all.get(depth - 1).acquisitionWork(), "paid acquisition accumulates");
            var unseen = task(nested("r*s", depth, "t") + "+11", 2 * depth + 5);
            var previous = all.get(depth - 1).solve(unseen, CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST);
            var next = current.solve(unseen, CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST);
            require(previous.solved() && next.solved(), "both generations solve the declared transfer task");
            require(next.totalWork() < previous.totalWork(), "later knowledge must save real query work, not just depth");
            System.out.println("GENERATION " + depth + " previous=" + previous.totalWork() + " current=" + next.totalWork()
                + " schemas=" + current.schemaCount() + " acquisition=" + current.acquisitionWork());
        }
        for (var knowledge : all) {
            long total = 0;
            for (int depth = 1; depth <= 3; depth++) {
                var query = task(nested("r+s", depth, "t"), 2 * depth + 3);
                var execution = knowledge.solve(query, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                require(execution.solved(), "every state solves the fixed transfer stream");
                total += execution.totalWork();
            }
            total += knowledge.solve(task("(x+y)*(x-y)+y*(y+1)", 1), CumulativeTaskKnowledge.Strategy.AUTOMATIC).totalWork();
            System.out.println("FIXED_STREAM generation=" + knowledge.generation() + " work=" + total);
        }
        return current;
    }
    static void persistenceAndStrategies(CumulativeTaskKnowledge state, Path directory) throws Exception {
        var stored = state.save(directory);
        require(stored.equals(state.save(directory)), "idempotent immutable snapshot");
        var restored = CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(), stored);
        require(restored.id().equals(state.id()), "restore preserves generation identity");
        require(restored.sessionSetupWork() > 0, "restore and preparation are not free");
        var request = task(nested("r*s", 3, "t"), 9);
        var before = state.solve(request, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        var after = restored.solve(request, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(before.solved() && after.solved() && before.totalWork() == after.totalWork(), "restored solver and routing agree");
        var miss = restored.solve(task("(x+y)*(x-y)+y*(y+1)", 1), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(miss.selectedStrategy() == CumulativeTaskKnowledge.Strategy.BASELINE, "negative validation selects the cheap baseline");
        var fresh = restored.solve(task("q+29", 1), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        require(fresh.selectedStrategy() == CumulativeTaskKnowledge.Strategy.BASELINE, "unknown profile uses baseline, not an expensive guess");
        String json = Files.readString(stored);
        expect(IllegalArgumentException.class, () -> CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(), json.replace("parentId", "unknownField")));
        var tiny = new CheckedSchemaTaskSolver.Task(request.source(), request.assumptions(), request.goal(), new MoveSearch.Budget(1, 1, 1, 1, 1));
        require(!restored.solve(tiny, CumulativeTaskKnowledge.Strategy.AUTOMATIC).solved(), "selection cannot hide budget excess");
        require(restored.solve(tiny, CumulativeTaskKnowledge.Strategy.AUTOMATIC).totalWork() > 0, "failed selection remains paid");
    }
    static void failedLearningAndSplitIsolation(CumulativeTaskKnowledge state) {
        var request = task("x+0", 1);
        expect(IllegalArgumentException.class, () -> state.learnBatch(List.of(request, task("y+0", 1)), List.of(task("z+0", 1))));
        var rejected = state.learnBatch(List.of(task("x+13", 1), task("y+17", 1)), List.of(task("z+19", 1)));
        require(!rejected.promoted(), "failed lessons do not create knowledge");
        require(rejected.next().schemaCount() == state.schemaCount(), "rejection preserves known schemata");
        require(rejected.next().acquisitionWork() > state.acquisitionWork(), "rejection cannot refund attempted learning");
        require(rejected.next().lastDecision() == CumulativeTaskKnowledge.Decision.NO_NEW_SCHEMA, "rejection decision retained in snapshot");
        require(!rejected.next().lastAttempts().isEmpty(), "negative formation attempts retained in snapshot");
        require(rejected.training().stream().noneMatch(CumulativeTaskKnowledge.Execution::solved), "unsolved lessons retained");
    }
    static void failuresRetainPaidPrefixes(CumulativeTaskKnowledge state) {
        var goal = new CheckedSchemaTaskSolver.Goal(ignored -> { throw new IllegalStateException("test objective failure"); }, 1);
        var failing = new CheckedSchemaTaskSolver.Task(task("y+17", 1).source(), List.of(), goal, BUDGET);
        String id = state.id();
        try { state.learnBatch(List.of(task(nested("a", 1, "b") + "+3", 5), failing), List.of(task("z+19", 1))); }
        catch (CumulativeTaskKnowledge.BatchFailure failure) {
            require(!failure.accountingComplete() && failure.knownWork().lessons() > 0, "unknown failed work is not a free success");
            require(failure.completedLessons().size() == 1 && state.id().equals(id), "paid prefix and old knowledge survive");
            return;
        }
        throw new AssertionError("expected retained batch failure");
    }
    static void corruptedModelsNeverGainAuthority(CumulativeTaskKnowledge state) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(state.toCanonicalJson());
        var model = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(snapshot.path("model").textValue());
        ((com.fasterxml.jackson.databind.node.ObjectNode) model.path("schemas").get(0)).set("target", json.readTree("[\"N\",\"0\"]"));
        snapshot.put("model", model.toString());
        expect(IllegalArgumentException.class, () -> CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(), snapshot.toString()));
        String duplicate = state.toCanonicalJson().replace("{\"revision\":", "{\"generation\":1,\"revision\":");
        expect(IllegalArgumentException.class, () -> CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(), duplicate));
    }
    static void acquisitionBudgetAndUnhelpfulCandidatesRemainPaid() {
        var state = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var limited = state.learnBatch(lessons(1), validation(1), 1);
        require(!limited.promoted() && limited.reason() == CumulativeTaskKnowledge.Decision.ACQUISITION_LIMIT,
            "learning has its own explicitly bounded budget");
        require(limited.next().schemaCount() == 0 && limited.next().acquisitionWork() > 0,
            "atomic overrun paid without activating knowledge");
        var unhelpful = state.learnBatch(lessons(1), List.of(task("z+29", 1)));
        require(!unhelpful.promoted() && unhelpful.reason() == CumulativeTaskKnowledge.Decision.NO_VALIDATED_BENEFIT,
            "a proved schema alone does not justify activation");
        require(unhelpful.next().schemaCount() == 0 && unhelpful.work().formation() > 0 && unhelpful.work().validation() > 0,
            "unsuccessful acquisition and validation remain paid");
    }
    static void snapshotFilesAreNeverSilentlyReplaced(CumulativeTaskKnowledge state) throws Exception {
        var directory = Files.createTempDirectory("knowledge-conflict-");
        try {
            var file = state.save(directory); Files.writeString(file, "tampered");
            expect(java.io.IOException.class, () -> state.save(directory));
            expect(java.io.IOException.class, () -> CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(), file));
            require(Files.readString(file).equals("tampered"), "conflicting file must not be overwritten");
            Files.delete(file);
        } finally { Files.delete(directory); }
    }
    static void measuredLifecycle(CumulativeTaskKnowledge state, Path directory) throws Exception {
        var restored = CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(), state.save(directory));
        var baseline = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        long baseWork = 0, frozenWork = frozenFirst.acquisitionWork();
        long learnedWork = restored.acquisitionWork() + restored.sessionSetupWork();
        long start = System.nanoTime();
        for (int round = 0; round < 128; round++) {
            String u = "u" + round, v = "v" + round;
            String base = switch (round % 4) { case 0 -> u + "+" + v; case 1 -> u + "-" + v;
                case 2 -> u + "*2"; default -> u + "/2"; };
            var stream = new ArrayList<CheckedSchemaTaskSolver.Task>();
            for (int depth = 1; depth <= 3; depth++) stream.add(task(nested(base, depth, "t"), 2 * depth + 3));
            stream.add(task("(x+y)*(x-y)+y*(y+1)", 1));
            for (var request : stream) {
                // The baseline does not pay for unused routing. No arm carries previous query results.
                var b = baseline.solve(request, CumulativeTaskKnowledge.Strategy.BASELINE);
                var f = frozenFirst.solve(request, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                var l = restored.solve(request, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                require(b.solved() == f.solved() && b.solved() == l.solved(), "same task success throughout the stream");
                baseWork += b.totalWork(); frozenWork += f.totalWork(); learnedWork += l.totalWork();
            }
        }
        require(learnedWork < baseWork && learnedWork < frozenWork, "this declared development stream amortizes actual acquisition and restore receipts");
        System.out.println("LIFECYCLE tasks=512 baseline=" + baseWork + " frozen=" + frozenWork + " cumulative=" + learnedWork
            + " acquisition=" + state.acquisitionWork() + " freshRestore=" + restored.sessionSetupWork()
            + " diagnosticAllArmsMillis=" + (System.nanoTime() - start) / 1_000_000);
    }
    private static void expect(Class<? extends Throwable> type, Throwing action) {
        try { action.run(); } catch (Throwable error) { if (type.isInstance(error)) return; throw new AssertionError(error); }
        throw new AssertionError("expected " + type.getSimpleName());
    }
    @FunctionalInterface interface Throwing { void run() throws Exception; }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--restore")) {
            var restored = CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(), Path.of(args[1]));
            var result = restored.solve(task(nested("u*v", 3, "s"), 9), CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            require(result.solved() && result.solution().orElseThrow().learnedApplications() > 0, "fresh JVM uses learned knowledge without training");
            System.out.println("FRESH_RESTORE generation=" + restored.generation() + " work=" + result.totalWork()); return;
        }
        var state = generations();
        var directory = args.length == 1 ? Path.of(args[0]) : Files.createTempDirectory("cumulative-knowledge-");
        persistenceAndStrategies(state, directory); failedLearningAndSplitIsolation(state);
        failuresRetainPaidPrefixes(state); corruptedModelsNeverGainAuthority(state);
        acquisitionBudgetAndUnhelpfulCandidatesRemainPaid(); snapshotFilesAreNeverSilentlyReplaced(state);
        measuredLifecycle(state, directory);
        System.out.println("SNAPSHOT=" + state.save(directory));
        System.out.println("PASS cumulative task knowledge contracts");
    }
}
