package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.search.moves.MoveSearch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Thin experiment adapter; all mathematics and learning belong to existing public APIs. */
public final class HistoricalComparisonWorker {
    private static final MoveSearch.Budget STRUCTURAL = new MoveSearch.Budget(24, 12, 1_000_000, 128, 2_000_000);
    private CumulativeTaskKnowledge knowledge;
    private String profile, preludeHash;
    private boolean consumer, poisoned;

    public Map<String, Object> handle(String line) {
        try {
            if (poisoned) throw new IllegalStateException("poisoned session");
            var request = CheckedSchemaSupport.read(line);
            return switch (CheckedSchemaSupport.text(request, "op")) {
                case "initialize" -> initialize(request);
                case "restore" -> restore(request);
                case "run" -> run(request);
                default -> throw new IllegalArgumentException("unknown operation");
            };
        } catch (Exception failure) {
            poisoned = true;
            return Map.of("status", "ERROR", "error", failure.toString(), "accountingComplete", false);
        }
    }

    private void configure(JsonNode request) {
        if (knowledge != null) throw new IllegalStateException("already configured");
        profile = CheckedSchemaSupport.text(request, "profile");
        if (!Set.of("NATIVE_BASE", "NATIVE_LEARNED").contains(profile)) throw new IllegalArgumentException("native profile required");
        var prelude = request.get("prelude");
        CheckedSchemaSupport.fields(prelude, "expressionBlocks", "recurrenceBlocks", "recurrenceFamilyValidation");
        preludeHash = SchematicProofPlan.hash(LearnedSchedulingArtifacts.json(CheckedSchemaSupport.JSON.convertValue(prelude, Map.class)) + "\n");
    }

    private Map<String, Object> initialize(JsonNode request) throws Exception {
        CheckedSchemaSupport.fields(request, "op", "profile", "prelude", "directory");
        configure(request);
        knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), TaskConstructionProfile.STRUCTURAL_V1);
        var operations = new ArrayList<Map<String, Object>>();
        if (profile.equals("NATIVE_BASE")) {
            consumer = true;
            return setup("INITIALIZED", operations);
        }
        var prelude = request.get("prelude");
        for (var block : array(prelude.get("expressionBlocks"))) {
            CheckedSchemaSupport.fields(block, "lessons", "validation");
            var update = knowledge.learnBatch(expressions(block.get("lessons")), expressions(block.get("validation")), 5_000_000);
            knowledge = update.next();
            operations.add(Map.of("stage", "EXPRESSION", "promoted", update.promoted(), "reason", update.reason().name(), "work", update.work().total()));
        }
        for (var block : array(prelude.get("recurrenceBlocks"))) {
            CheckedSchemaSupport.fields(block, "lessons", "validation");
            var update = knowledge.learnRecurrences(recurrences(block.get("lessons")), recurrences(block.get("validation")), new Budget(500_000));
            knowledge = update.next();
            operations.add(Map.of("stage", "RECURRENCE", "promoted", update.promoted(), "reason", update.reason().name(), "work", update.work().total()));
        }
        if (!array(prelude.get("recurrenceFamilyValidation")).isEmpty()) {
            var update = knowledge.generalizeRecurrences(recurrences(prelude.get("recurrenceFamilyValidation")), new Budget(500_000));
            knowledge = update.next();
            operations.add(Map.of("stage", "FAMILY", "promoted", update.promoted(), "reason", update.reason().name(), "work", update.work().total()));
        }
        Path snapshot = knowledge.save(Path.of(CheckedSchemaSupport.text(request, "directory")));
        var result = setup("SAVED", operations);
        result.put("snapshot", snapshot.toString());
        result.put("snapshotHash", SchematicProofPlan.hash(Files.readString(snapshot)));
        return result;
    }

    private Map<String, Object> restore(JsonNode request) throws Exception {
        CheckedSchemaSupport.fields(request, "op", "profile", "prelude", "snapshot");
        configure(request);
        if (!profile.equals("NATIVE_LEARNED")) throw new IllegalArgumentException("only learned knowledge restores");
        Path snapshot = Path.of(CheckedSchemaSupport.text(request, "snapshot"));
        knowledge = CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(), snapshot);
        if (knowledge.constructionProfile() != TaskConstructionProfile.STRUCTURAL_V1) throw new IllegalArgumentException("changed native capabilities");
        consumer = true;
        var result = setup("RESTORED", List.of());
        result.put("snapshotHash", SchematicProofPlan.hash(Files.readString(snapshot)));
        return result;
    }

    private Map<String, Object> setup(String status, List<Map<String, Object>> operations) {
        var result = common(status);
        result.put("operations", operations);
        result.put("structuralBudget", List.of(24, 12, 1_000_000, 128, 2_000_000));
        return result;
    }

    private Map<String, Object> common(String status) {
        var result = new TreeMap<String, Object>();
        result.put("status", status); result.put("profile", profile); result.put("preludeHash", preludeHash);
        result.put("generationId", knowledge.id());
        result.put("acquisitionApiWork", knowledge.acquisitionWork());
        result.put("restorationApiWork", knowledge.sessionSetupWork());
        result.put("schemaCount", knowledge.schemaCount()); result.put("recurrenceCount", knowledge.recurrenceCount());
        result.put("recurrenceFamilyCount", knowledge.recurrenceFamilyCount());
        return result;
    }

    private Map<String, Object> run(JsonNode request) {
        CheckedSchemaSupport.fields(request, "op", "task");
        if (!consumer) throw new IllegalStateException("a configured query consumer is required");
        var task = request.get("task");
        var result = common("RESULT");
        result.put("task", CheckedSchemaSupport.JSON.convertValue(task, Map.class));
        var strategy = profile.equals("NATIVE_BASE") ? CumulativeTaskKnowledge.Strategy.BASELINE : CumulativeTaskKnowledge.Strategy.AUTOMATIC;
        if (CheckedSchemaSupport.text(task, "kind").equals("INVARIANT")) {
            CheckedSchemaSupport.fields(task, "kind", "recurrence");
            var execution = knowledge.solve(recurrence(task.get("recurrence")), strategy);
            result.put("solved", execution.solved()); result.put("reused", execution.reused());
            result.put("nativeApiWork", execution.totalWork()); result.put("outcome", execution.outcome().name());
            result.put("withinBudget", execution.totalWork() <= task.path("recurrence").path("workBudget").asInt());
            result.put("law", execution.law().map(law -> Map.of("polynomial", law.proposal().polynomial(),
                "evolution", law.proposal().evolution().name(), "initialValue", law.initialValue().toString())).orElse(null));
        } else {
            var expression = expression(task);
            var execution = knowledge.solve(expression, strategy);
            result.put("solved", execution.solved()); result.put("nativeApiWork", execution.totalWork());
            result.put("withinBudget", execution.totalWork() <= STRUCTURAL.totalWork()
                && execution.solution().map(s -> s.evidence().withinBudget()).orElse(false));
            result.put("outcome", execution.solved() ? "VERIFIED" : "INCONCLUSIVE");
            result.put("reused", execution.solution().map(s -> s.learnedApplications() > 0).orElse(false));
            result.put("normalizedInput", ExpressionFormatter.format(expression.source()));
            execution.solution().ifPresent(solution -> {
                var evidence = solution.evidence();
                result.put("output", ExpressionFormatter.format(evidence.incumbent().expression()));
                result.put("witness", evidence.witness().stream().map(edge -> Map.of(
                    "source", ExpressionFormatter.format(edge.source().expression()),
                    "target", ExpressionFormatter.format(edge.target().expression()),
                    "move", edge.move(), "verification", edge.verification())).toList());
            });
        }
        return result;
    }

    private static CheckedSchemaTaskSolver.Task expression(JsonNode task) {
        CheckedSchemaSupport.fields(task, "kind", "source");
        var objective = switch (CheckedSchemaSupport.text(task, "kind")) {
            case "FACTOR" -> StructuralTaskObjective.FACTORIZATION;
            case "TWO_SQUARES" -> StructuralTaskObjective.SUM_OF_TWO_SQUARES;
            default -> throw new IllegalArgumentException("structural goal required");
        };
        String source = CheckedSchemaSupport.text(task, "source");
        if (source.length() > 2048) throw new IllegalArgumentException("source length");
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(), List.of(), objective.goal(), STRUCTURAL);
    }

    private static List<JsonNode> array(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > 32) throw new IllegalArgumentException("bounded task array required");
        var result = new ArrayList<JsonNode>(); value.forEach(result::add); return List.copyOf(result);
    }
    private static List<CheckedSchemaTaskSolver.Task> expressions(JsonNode value) { return array(value).stream().map(HistoricalComparisonWorker::expression).toList(); }
    private static List<Request> recurrences(JsonNode value) { return array(value).stream().map(HistoricalComparisonWorker::recurrence).toList(); }
    private static Request recurrence(JsonNode task) {
        CheckedSchemaSupport.fields(task, "linear", "offset", "initial", "degree", "workBudget");
        var rows = array(task.get("linear"));
        if (rows.isEmpty() || rows.size() > 3) throw new IllegalArgumentException("transition dimension");
        var matrix = new ArrayList<List<Rational>>(); rows.forEach(row -> matrix.add(vector(row, rows.size())));
        int degree = integer(task.get("degree")), budget = integer(task.get("workBudget"));
        if (degree < 1 || degree > 2 || budget < 1 || budget > 100_000) throw new IllegalArgumentException("recurrence bound");
        return new Request(new Transition(new ExactMatrix(matrix), new ExactVector(vector(task.get("offset"), rows.size()))),
            vector(task.get("initial"), rows.size()), degree, new Budget(budget));
    }
    private static int integer(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("integer required");
        return value.intValue();
    }
    private static List<Rational> vector(JsonNode value, int dimension) {
        var coordinates = array(value);
        if (coordinates.size() != dimension) throw new IllegalArgumentException("coordinate dimension");
        return coordinates.stream().map(coordinate -> {
            if (!coordinate.isTextual() || coordinate.textValue().length() > 80
                    || !coordinate.textValue().matches("-?(0|[1-9][0-9]*)(/[1-9][0-9]*)?")) throw new IllegalArgumentException("rational required");
            String[] parts = coordinate.textValue().split("/");
            var rational = new Rational(new BigInteger(parts[0]), parts.length == 1 ? BigInteger.ONE : new BigInteger(parts[1]));
            if (!rational.toString().equals(coordinate.textValue())) throw new IllegalArgumentException("noncanonical rational");
            return rational;
        }).toList();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("JSONL stdin only");
        System.out.println("{\"status\":\"READY\",\"java\":\"" + Runtime.version() + "\"}");
        var worker = new HistoricalComparisonWorker();
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                System.out.println(LearnedSchedulingArtifacts.json(worker.handle(line)));
                System.out.flush();
            }
        }
    }
}
