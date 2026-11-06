package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** JSONL boundary around paid public recurrence knowledge APIs; queries never update knowledge. */
public final class RecurrenceKnowledgeComparisonWorker {
    private static final String REVISION = "regelsuche.recurrence-knowledge-worker/v1";
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> PROFILES = Set.of("BASELINE", "EXACT_FROZEN", "FAMILY");
    private enum State { NEW, PRODUCER, CONSUMER, SAVED, ERROR }
    private State state = State.NEW;
    private String profile;
    private CumulativeTaskKnowledge knowledge;

    /** Malformed requests and technical failures poison this process's session. Paid rejections do not. */
    public Map<String, Object> handle(String line) throws IOException {
        try {
            if (state == State.ERROR) throw new IllegalStateException("session is poisoned");
            if (line == null || line.length() > 4_000_000) throw new IllegalArgumentException("request size limit");
            var request = JSON.readTree(line);
            if (request == null || !request.isObject()) throw new IllegalArgumentException("object request required");
            return dispatch(request, text(request, "command"));
        } catch (IOException | RuntimeException failure) {
            state = State.ERROR;
            return error(failure);
        }
    }

    private Map<String, Object> dispatch(JsonNode request, String command) throws IOException {
        return switch (command) {
            case "initialize" -> initialize(request);
            case "learn" -> learn(request);
            case "generalize" -> generalize(request);
            case "save" -> save(request);
            case "restore" -> restore(request);
            case "run" -> run(request);
            default -> throw new IllegalArgumentException("unknown command");
        };
    }

    private Map<String, Object> initialize(JsonNode request) {
        fields(request, Set.of("command", "profile"));
        requireState(State.NEW);
        profile = requestedProfile(request);
        knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        state = profile.equals("BASELINE") ? State.CONSUMER : State.PRODUCER;
        return common("INITIALIZED");
    }

    private Map<String, Object> learn(JsonNode request) {
        fields(request, Set.of("command", "training", "validation", "budget"));
        requireState(State.PRODUCER);
        var training = tasks(request.get("training"));
        var validation = tasks(request.get("validation"));
        var update = knowledge.learnRecurrences(training, validation, new Budget(integer(request, "budget", 1, Integer.MAX_VALUE)));
        return updated("LEARNED", update);
    }

    private Map<String, Object> generalize(JsonNode request) {
        fields(request, Set.of("command", "validation", "budget"));
        requireState(State.PRODUCER);
        if (!profile.equals("FAMILY")) throw new IllegalStateException("only FAMILY generalizes");
        var update = knowledge.generalizeRecurrences(tasks(request.get("validation")),
            new Budget(integer(request, "budget", 1, Integer.MAX_VALUE)));
        return updated("GENERALIZED", update);
    }

    private Map<String, Object> updated(String status, RecurrenceKnowledge.Update update) {
        knowledge = update.next();
        var response = common(status);
        response.put("reason", update.reason().name());
        response.put("promoted", update.promoted());
        response.put("work", costs(update.work()));
        response.put("training", update.training().stream().map(RecurrenceKnowledgeComparisonWorker::receipt).toList());
        return response;
    }

    private Map<String, Object> save(JsonNode request) throws IOException {
        fields(request, Set.of("command", "directory"));
        requireState(State.PRODUCER);
        Path path = knowledge.save(Path.of(text(request, "directory")).toAbsolutePath().normalize());
        var response = snapshot("SAVED", path);
        state = State.SAVED;
        return response;
    }

    private Map<String, Object> restore(JsonNode request) throws IOException {
        fields(request, Set.of("command", "profile", "snapshot"));
        requireState(State.NEW);
        profile = requestedProfile(request);
        if (profile.equals("BASELINE")) throw new IllegalArgumentException("baseline must initialize empty knowledge");
        var path = Path.of(text(request, "snapshot")).toAbsolutePath().normalize();
        knowledge = CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(), path);
        if (profile.equals("EXACT_FROZEN") && knowledge.recurrenceFamilyCount() != 0)
            throw new IllegalArgumentException("EXACT_FROZEN cannot restore a family");
        var response = snapshot("RESTORED", path);
        state = State.CONSUMER;
        return response;
    }

    private Map<String, Object> snapshot(String status, Path path) throws IOException {
        String hash = hash(Files.readAllBytes(path));
        if (!hash.equals(knowledge.id())) throw new IOException("snapshot bytes changed during operation");
        var response = common(status);
        response.put("snapshot", path.toString());
        response.put("snapshotHash", hash);
        return response;
    }

    private Map<String, Object> run(JsonNode request) {
        fields(request, Set.of("command", "id", "task"));
        requireState(State.CONSUMER);
        String id = text(request, "id");
        var task = task(request.get("task"));
        var strategy = profile.equals("BASELINE") ? CumulativeTaskKnowledge.Strategy.BASELINE : CumulativeTaskKnowledge.Strategy.AUTOMATIC;
        var execution = knowledge.solve(task, strategy);
        var response = common("RESULT");
        response.put("id", id);
        response.put("task", JSON.convertValue(request.get("task"), Map.class));
        response.putAll(receipt(execution));
        response.put("law", execution.law().map(law -> Map.of("polynomial", law.proposal().polynomial(),
            "evolution", law.proposal().evolution().name(), "initialValue", law.initialValue().toString())).orElse(null));
        return response;
    }

    private TreeMap<String, Object> common(String status) {
        var response = new TreeMap<String, Object>();
        response.put("revision", REVISION);
        response.put("status", status);
        response.put("profile", profile);
        response.put("generationId", knowledge.id());
        response.put("acquisitionWork", knowledge.acquisitionWork());
        response.put("acquisitionCosts", costs(knowledge.acquisitionCosts()));
        response.put("sessionSetupWork", knowledge.sessionSetupWork());
        response.put("recurrenceCount", knowledge.recurrenceCount());
        response.put("recurrenceFamilyCount", knowledge.recurrenceFamilyCount());
        return response;
    }

    private static Map<String, Object> costs(CumulativeTaskKnowledge.Costs work) {
        return Map.of("lessons", work.lessons(), "formation", work.formation(), "preparation", work.preparation(),
            "validation", work.validation(), "selection", work.selection(), "restoration", work.restoration(),
            "splitChecking", work.splitChecking(), "total", work.total());
    }

    private static Map<String, Object> receipt(RecurrenceKnowledge.Execution execution) {
        return Map.of("outcome", execution.outcome().name(), "solved", execution.solved(),
            "reused", execution.reused(), "totalWork", execution.totalWork());
    }

    private static Map<String, Object> error(Exception failure) {
        var response = new TreeMap<String, Object>();
        response.put("revision", REVISION);
        response.put("status", "ERROR");
        response.put("error", failure.toString());
        response.put("accountingComplete", false);
        if (failure instanceof RecurrenceKnowledge.LearningFailure learning) {
            response.put("knownWork", costs(learning.knownWork()));
            response.put("completedLessons", learning.completedLessons().stream().map(RecurrenceKnowledgeComparisonWorker::receipt).toList());
        }
        return response;
    }

    private static List<Request> tasks(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 32)
            throw new IllegalArgumentException("one to 32 tasks required");
        var result = new ArrayList<Request>();
        node.forEach(value -> result.add(task(value)));
        return List.copyOf(result);
    }

    private static Request task(JsonNode node) {
        fields(node, Set.of("linear", "offset", "initial", "degree", "workBudget"));
        var rows = node.get("linear");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 3) throw new IllegalArgumentException("one to three matrix rows required");
        var linear = new ArrayList<List<Rational>>();
        rows.forEach(row -> linear.add(vector(row, rows.size())));
        var transition = new Transition(new ExactMatrix(linear), new ExactVector(vector(node.get("offset"), rows.size())));
        return new Request(transition, vector(node.get("initial"), rows.size()), integer(node, "degree", 1, 2),
            new Budget(integer(node, "workBudget", 0, Integer.MAX_VALUE)));
    }

    private static List<Rational> vector(JsonNode node, int size) {
        if (node == null || !node.isArray() || node.size() != size) throw new IllegalArgumentException("coordinate count mismatch");
        var result = new ArrayList<Rational>();
        node.forEach(value -> result.add(rational(value)));
        return List.copyOf(result);
    }

    private static Rational rational(JsonNode node) {
        if (!node.isTextual()) throw new IllegalArgumentException("canonical rational string required");
        String value = node.textValue();
        if (value.length() > 80 || !value.matches("-?(0|[1-9][0-9]*)(/[1-9][0-9]*)?"))
            throw new IllegalArgumentException("bounded canonical rational required");
        String[] parts = value.split("/");
        var rational = new Rational(new BigInteger(parts[0]), parts.length == 1 ? BigInteger.ONE : new BigInteger(parts[1]));
        if (rational.numerator().bitLength() > 128 || rational.denominator().bitLength() > 128 || !rational.toString().equals(value))
            throw new IllegalArgumentException("bounded canonical rational required");
        return rational;
    }

    private void requireState(State expected) {
        if (state != expected) throw new IllegalStateException("command requires " + expected + "; actual " + state);
    }

    private static String requestedProfile(JsonNode request) {
        String result = text(request, "profile");
        if (!PROFILES.contains(result)) throw new IllegalArgumentException("unknown profile");
        return result;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("object required");
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("unexpected fields");
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 4096)
            throw new IllegalArgumentException("bounded nonempty text required: " + field);
        return value.textValue();
    }

    private static int integer(JsonNode node, String field, int minimum, int maximum) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum || value.intValue() > maximum)
            throw new IllegalArgumentException("bounded integer required: " + field);
        return value.intValue();
    }

    private static String hash(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    public static void main(String[] args) throws IOException {
        var worker = new RecurrenceKnowledgeComparisonWorker();
        System.out.println(JSON.writeValueAsString(Map.of("status", "READY", "java", Runtime.version().toString())));
        System.out.flush();
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                System.out.println(JSON.writeValueAsString(worker.handle(line)));
                System.out.flush();
            }
        }
    }
}
